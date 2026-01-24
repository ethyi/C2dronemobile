package com.example.c2dronemobile

import android.content.Context
import android.hardware.usb.UsbManager
import android.util.Log
import com.hoho.android.usbserial.driver.UsbSerialPort
import com.hoho.android.usbserial.driver.UsbSerialProber
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*

private const val TAG = "MavLink"

// ArduCopter flight modes
const val MODE_LOITER = 5
const val MODE_AUTO = 3
const val MODE_LAND = 9

enum class ConnectionState { DISCONNECTED, CONNECTING, CONNECTED }

data class Waypoint(val lat: Double, val lon: Double, val alt: Float)

sealed class MissionState {
    object Idle : MissionState()
    data class Uploading(val current: Int, val total: Int) : MissionState()
    object Complete : MissionState()
    data class Error(val msg: String) : MissionState()
}

class MavLinkConnection(private val context: Context, private val baudRate: Int = 57600) {
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var port: UsbSerialPort? = null
    private var seq = 0

    private val _connectionState = MutableStateFlow(ConnectionState.DISCONNECTED)
    val connectionState: StateFlow<ConnectionState> = _connectionState.asStateFlow()

    private val _droneState = MutableStateFlow(DroneState())
    val droneState: StateFlow<DroneState> = _droneState.asStateFlow()

    private val _missionState = MutableStateFlow<MissionState>(MissionState.Idle)
    val missionState: StateFlow<MissionState> = _missionState.asStateFlow()

    private var pendingMission: List<Waypoint> = emptyList()

    fun connect() {
        scope.launch {
            try {
                _connectionState.value = ConnectionState.CONNECTING
                val manager = context.getSystemService(Context.USB_SERVICE) as UsbManager
                val drivers = UsbSerialProber.getDefaultProber().findAllDrivers(manager)
                if (drivers.isEmpty()) throw Exception("No USB device")

                val connection = manager.openDevice(drivers[0].device)
                    ?: throw Exception("USB permission denied")

                port = drivers[0].ports[0].apply {
                    open(connection)
                    setParameters(baudRate, 8, UsbSerialPort.STOPBITS_1, UsbSerialPort.PARITY_NONE)
                }

                _connectionState.value = ConnectionState.CONNECTED
                startReading()
            } catch (e: Exception) {
                Log.e(TAG, "Connect failed: ${e.message}")
                _connectionState.value = ConnectionState.DISCONNECTED
            }
        }
    }

    private fun startReading() {
        scope.launch {
            val buffer = ByteArray(512)
            val parser = MavLinkParser { msgId, payload, len -> parseMessage(msgId, payload, len) }

            while (port != null) {
                try {
                    val n = port?.read(buffer, 100) ?: -1
                    if (n > 0) {
                        for (i in 0 until n) parser.parseByte(buffer[i])
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Read error: ${e.message}")
                    _connectionState.value = ConnectionState.DISCONNECTED
                    break
                }
            }
        }
    }

    private fun parseMessage(msgId: Int, payload: ByteArray, len: Int) {
        // Debug: log ALL message IDs
        if (msgId != 0) Log.d(TAG, "MSG id=$msgId len=$len")

        when (msgId) {
            0 -> { // HEARTBEAT
                if (len >= 9) {
                    val customMode = readInt32(payload, 0)  // ArduCopter flight mode
                    Log.v(TAG, "HEARTBEAT mode=$customMode")
                    _droneState.value = _droneState.value.copy(flightMode = customMode)
                }
            }

            33 -> { // GLOBAL_POSITION_INT
                if (len >= 28) {
                    val lat = readInt32(payload, 4) / 1e7
                    val lon = readInt32(payload, 8) / 1e7
                    val relAlt = readInt32(payload, 16) / 1000f  // offset 16 = relative_alt (above home)
                    val hdg = readUInt16(payload, 26) / 100
                    Log.d(TAG, "GLOBAL_POS: lat=$lat lon=$lon alt=$relAlt hdg=$hdg")
                    _droneState.value = _droneState.value.copy(lat = lat, lon = lon, altitude = relAlt, heading = hdg)
                }
            }

            1 -> { // SYS_STATUS
                if (len >= 31) {
                    val battery = payload[30].toInt() and 0xFF
                    if (battery <= 100) {
                        Log.d(TAG, "SYS: battery=$battery%")
                        _droneState.value = _droneState.value.copy(batteryPct = battery)
                    }
                }
            }

            40, 51 -> { // MISSION_REQUEST, MISSION_REQUEST_INT
                val reqSeq = readUInt16(payload, 0)
                Log.d(TAG, "MISSION_REQUEST seq=$reqSeq")
                sendMissionItem(reqSeq)
            }

            47 -> { // MISSION_ACK
                // Wire order: targetSystem(0), targetComponent(1), type(2)
                // MAVLink v2 truncates trailing zeros, so len=2 means type=0 (success)
                val type = if (len >= 3) payload[2].toInt() and 0xFF else 0
                Log.d(TAG, "MISSION_ACK type=$type len=$len (0=accepted)")
                if (type == 0) {
                    _missionState.value = MissionState.Complete
                    // Auto-start mission by switching to AUTO mode
                    Log.d(TAG, "Mission uploaded, switching to AUTO")
                    sendSetMode(MODE_AUTO)
                } else {
                    _missionState.value = MissionState.Error("Rejected: $type")
                }
            }

            42 -> { // MISSION_CURRENT - which waypoint drone is heading to
                val seq = readUInt16(payload, 0)
                Log.d(TAG, "MISSION_CURRENT seq=$seq len=$len")
                val prev = _droneState.value
                // If we were heading to a waypoint and now seq=0, mark mission complete
                // seq=0 means no active waypoint / mission done
                if (prev.currentWaypoint > 0 && seq == 0 && pendingMission.isNotEmpty()) {
                    Log.d(TAG, "Mission complete - marking all waypoints as reached, switching to LOITER")
                    _droneState.value = prev.copy(currentWaypoint = seq, lastReachedWaypoint = pendingMission.size)
                    sendSetMode(MODE_LOITER)  // Auto-switch to LOITER so user can control
                } else {
                    _droneState.value = prev.copy(currentWaypoint = seq)
                }
            }

            46 -> { // MISSION_ITEM_REACHED - waypoint reached
                val seq = readUInt16(payload, 0)
                Log.d(TAG, "MISSION_ITEM_REACHED seq=$seq")
                _droneState.value = _droneState.value.copy(lastReachedWaypoint = seq)
            }
        }
    }

    fun uploadMission(waypoints: List<Waypoint>) {
        if (waypoints.isEmpty()) return
        // ArduPilot reserves seq=0 for home, so waypoints start at seq=1
        pendingMission = waypoints
        _missionState.value = MissionState.Uploading(0, waypoints.size)
        sendMissionCount(waypoints.size + 1)  // +1 for home at seq=0
    }

    fun resetMissionState() {
        _missionState.value = MissionState.Idle
    }

    fun resetWaypointTracking() {
        _droneState.value = _droneState.value.copy(lastReachedWaypoint = 0, currentWaypoint = 0)
    }

    fun clearMission() {
        Log.d(TAG, "Sending MISSION_CLEAR_ALL")
        // Wire order: target_system(0), target_component(1)
        val payload = ByteArray(2)
        payload[0] = 1  // target_system
        payload[1] = 1  // target_component
        sendMavLink(45, payload)
    }

    fun sendSetMode(mode: Int) {
        Log.d(TAG, "Sending SET_MODE: $mode")
        // MAV_CMD_DO_SET_MODE via COMMAND_LONG (msg 76)
        // Wire order: param1-7 (floats), command, target_sys, target_comp, confirmation
        val payload = ByteArray(33)
        writeFloat(payload, 0, 1f)           // param1: MAV_MODE_FLAG_CUSTOM_MODE_ENABLED
        writeFloat(payload, 4, mode.toFloat()) // param2: custom_mode (ArduCopter mode)
        // param3-7 stay 0
        writeUInt16(payload, 28, 176)        // command: MAV_CMD_DO_SET_MODE
        payload[30] = 1                       // target_system
        payload[31] = 1                       // target_component
        payload[32] = 0                       // confirmation
        sendMavLink(76, payload)
    }

    private fun sendMissionCount(count: Int) {
        Log.d(TAG, "Sending MISSION_COUNT: $count")
        // Wire order: count(0-1), target_system(2), target_component(3)
        val payload = ByteArray(4)
        writeUInt16(payload, 0, count)
        payload[2] = 1  // target_system (autopilot)
        payload[3] = 1  // target_component (autopilot)
        sendMavLink(44, payload)
    }

    private fun sendMissionItem(seqNum: Int) {
        Log.d(TAG, "Sending MISSION_ITEM_INT seq=$seqNum")
        val payload = ByteArray(38)

        if (seqNum == 0) {
            // seq=0 is home position - use drone's current location
            val home = _droneState.value
            writeInt32(payload, 16, (home.lat * 1e7).toInt())
            writeInt32(payload, 20, (home.lon * 1e7).toInt())
            writeFloat(payload, 24, 0f)  // home altitude = 0
            writeUInt16(payload, 28, 0)  // seq
            writeUInt16(payload, 30, 16) // cmd: NAV_WAYPOINT
            payload[32] = 1   // target_system
            payload[33] = 1   // target_component
            payload[34] = 0   // frame: MAV_FRAME_GLOBAL (home uses absolute)
            payload[35] = 0   // current
            payload[36] = 1   // autocontinue
            payload[37] = 0   // mission_type
        } else {
            // seq=1+ are actual waypoints
            val wpIndex = seqNum - 1
            if (wpIndex >= pendingMission.size) return
            val wp = pendingMission[wpIndex]
            _missionState.value = MissionState.Uploading(seqNum, pendingMission.size)

            writeInt32(payload, 16, (wp.lat * 1e7).toInt())
            writeInt32(payload, 20, (wp.lon * 1e7).toInt())
            writeFloat(payload, 24, wp.alt)
            writeUInt16(payload, 28, seqNum)
            writeUInt16(payload, 30, 16)  // cmd: NAV_WAYPOINT
            payload[32] = 1   // target_system
            payload[33] = 1   // target_component
            payload[34] = 3   // frame: MAV_FRAME_GLOBAL_RELATIVE_ALT
            payload[35] = if (seqNum == 1) 1 else 0  // current: 1 for first real waypoint
            payload[36] = 1   // autocontinue
            payload[37] = 0   // mission_type
        }
        sendMavLink(73, payload)
    }

    private fun sendMavLink(msgId: Int, payload: ByteArray) {
        val packet = ByteArray(12 + payload.size)
        packet[0] = 0xFD.toByte()              // MAVLink 2 STX
        packet[1] = payload.size.toByte()     // payload length
        packet[2] = 0                          // incompat_flags
        packet[3] = 0                          // compat_flags
        packet[4] = (seq++ and 0xFF).toByte() // sequence
        packet[5] = 255.toByte()              // system_id (GCS)
        packet[6] = 190.toByte()              // component_id
        packet[7] = (msgId and 0xFF).toByte()
        packet[8] = ((msgId shr 8) and 0xFF).toByte()
        packet[9] = ((msgId shr 16) and 0xFF).toByte()
        payload.copyInto(packet, 10)

        val crc = crc16(packet, 1, 9 + payload.size, msgId)
        packet[10 + payload.size] = (crc and 0xFF).toByte()
        packet[11 + payload.size] = ((crc shr 8) and 0xFF).toByte()

        port?.write(packet, 1000)
    }

    private fun crc16(data: ByteArray, start: Int, len: Int, msgId: Int): Int {
        var crc = 0xFFFF
        for (i in start until start + len) {
            crc = crcAccum(crc, data[i].toInt() and 0xFF)
        }
        val crcExtra = when (msgId) {
            44 -> 221   // MISSION_COUNT
            45 -> 232   // MISSION_CLEAR_ALL
            73 -> 38    // MISSION_ITEM_INT
            76 -> 152   // COMMAND_LONG
            else -> 0
        }
        return crcAccum(crc, crcExtra)
    }

    private fun crcAccum(crc: Int, b: Int): Int {
        var tmp = b xor (crc and 0xFF)
        tmp = tmp xor ((tmp shl 4) and 0xFF)
        return ((crc shr 8) and 0xFF) xor (tmp shl 8) xor (tmp shl 3) xor ((tmp shr 4) and 0xF)
    }

    // Read helpers
    private fun readInt32(arr: ByteArray, off: Int) =
        (arr[off].toInt() and 0xFF) or
        ((arr[off+1].toInt() and 0xFF) shl 8) or
        ((arr[off+2].toInt() and 0xFF) shl 16) or
        ((arr[off+3].toInt() and 0xFF) shl 24)

    private fun readUInt16(arr: ByteArray, off: Int) =
        (arr[off].toInt() and 0xFF) or ((arr[off+1].toInt() and 0xFF) shl 8)

    // Write helpers
    private fun writeInt32(arr: ByteArray, off: Int, v: Int) {
        arr[off] = (v and 0xFF).toByte()
        arr[off+1] = ((v shr 8) and 0xFF).toByte()
        arr[off+2] = ((v shr 16) and 0xFF).toByte()
        arr[off+3] = ((v shr 24) and 0xFF).toByte()
    }

    private fun writeUInt16(arr: ByteArray, off: Int, v: Int) {
        arr[off] = (v and 0xFF).toByte()
        arr[off+1] = ((v shr 8) and 0xFF).toByte()
    }

    private fun writeFloat(arr: ByteArray, off: Int, v: Float) =
        writeInt32(arr, off, java.lang.Float.floatToIntBits(v))
}

class MavLinkParser(private val onMessage: (Int, ByteArray, Int) -> Unit) {
    private enum class State { WAITING, V1_HEADER, V2_HEADER, PAYLOAD, CRC }
    private var state = State.WAITING
    private var payloadLen = 0
    private var msgId = 0
    private val payload = ByteArray(255)
    private var idx = 0
    private var headerIdx = 0

    fun parseByte(b: Byte) {
        val ub = b.toInt() and 0xFF
        when (state) {
            State.WAITING -> when (ub) {
                0xFE -> { state = State.V1_HEADER; headerIdx = 0 }  // MAVLink v1
                0xFD -> { state = State.V2_HEADER; headerIdx = 0 }  // MAVLink v2
            }
            State.V1_HEADER -> {  // v1: len, seq, sys, comp, msgId (5 bytes after STX)
                when (headerIdx) {
                    0 -> payloadLen = ub
                    4 -> { msgId = ub; idx = 0; state = if (payloadLen > 0) State.PAYLOAD else State.CRC }
                }
                headerIdx++
            }
            State.V2_HEADER -> {  // v2: len, incompat, compat, seq, sys, comp, msgId(3 bytes) (9 bytes after STX)
                when (headerIdx) {
                    0 -> payloadLen = ub
                    6 -> msgId = ub
                    7 -> msgId = msgId or (ub shl 8)
                    8 -> { msgId = msgId or (ub shl 16); idx = 0; state = if (payloadLen > 0) State.PAYLOAD else State.CRC }
                }
                headerIdx++
            }
            State.PAYLOAD -> { payload[idx++] = b; if (idx >= payloadLen) { state = State.CRC; idx = 0 } }
            State.CRC -> { if (++idx >= 2) { onMessage(msgId, payload, payloadLen); state = State.WAITING } }
        }
    }
}
