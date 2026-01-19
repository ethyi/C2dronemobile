package com.example.c2dronemobile

import android.content.Context
import android.hardware.usb.UsbManager
import com.hoho.android.usbserial.driver.UsbSerialPort
import com.hoho.android.usbserial.driver.UsbSerialProber
import android.util.Log
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*

private const val TAG = "MavLink"

enum class ConnectionState { DISCONNECTED, CONNECTING, CONNECTED }

interface MavLinkTransport {
    fun connect()
    fun disconnect()
    fun isConnected(): Boolean
    fun read(buffer: ByteArray): Int
}

class UsbTransport(
    private val context: Context,
    private val baudRate: Int = 57600
) : MavLinkTransport {
    private var port: UsbSerialPort? = null

    override fun connect() {
        val manager = context.getSystemService(Context.USB_SERVICE) as UsbManager
        val drivers = UsbSerialProber.getDefaultProber().findAllDrivers(manager)
        if (drivers.isEmpty()) throw IllegalStateException("No USB device found")

        val driver = drivers[0]
        val connection = manager.openDevice(driver.device)
            ?: throw IllegalStateException("USB permission denied")

        port = driver.ports[0].apply {
            open(connection)
            setParameters(baudRate, 8, UsbSerialPort.STOPBITS_1, UsbSerialPort.PARITY_NONE)
        }
    }

    override fun disconnect() {
        port?.close()
        port = null
    }

    override fun isConnected(): Boolean = port != null
    override fun read(buffer: ByteArray): Int = port?.read(buffer, 1000) ?: -1
}

class MavLinkConnection(private val transport: MavLinkTransport) {
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    private val _connectionState = MutableStateFlow(ConnectionState.DISCONNECTED)
    val connectionState: StateFlow<ConnectionState> = _connectionState.asStateFlow()

    private val _droneState = MutableStateFlow(DroneState())
    val droneState: StateFlow<DroneState> = _droneState.asStateFlow()

    fun connect() {
        scope.launch {
            try {
                Log.d(TAG, "Connecting...")
                _connectionState.value = ConnectionState.CONNECTING
                transport.connect()
                Log.d(TAG, "Connected!")
                _connectionState.value = ConnectionState.CONNECTED
                _droneState.value = _droneState.value.copy(connected = true)
                startReading()
            } catch (e: Exception) {
                Log.e(TAG, "Connection failed: ${e.message}", e)
                _connectionState.value = ConnectionState.DISCONNECTED
                _droneState.value = _droneState.value.copy(connected = false)
            }
        }
    }

    fun disconnect() {
        transport.disconnect()
        _connectionState.value = ConnectionState.DISCONNECTED
        _droneState.value = _droneState.value.copy(connected = false)
    }

    private fun startReading() {
        scope.launch {
            val buffer = ByteArray(300)
            val parser = MavLinkParser { msgId, payload, len -> parseMessage(msgId, payload, len) }

            try {
                while (transport.isConnected()) {
                    val bytesRead = transport.read(buffer)
                    if (bytesRead > 0) {
                        Log.v(TAG, "Read $bytesRead bytes")
                        for (i in 0 until bytesRead) {
                            parser.parseByte(buffer[i])
                        }
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Read error: ${e.message}", e)
                disconnect()
            }
        }
    }

    private fun parseMessage(msgId: Int, payload: ByteArray, len: Int) {
        when (msgId) {
            74 -> { // VFR_HUD
                if (len >= 20) {
                    _droneState.value = _droneState.value.copy(
                        speed = readFloat(payload, 4),      // groundspeed
                        heading = readInt16(payload, 8),
                        altitude = readFloat(payload, 12)
                    )
                }
            }
            1 -> { // SYS_STATUS
                if (len >= 31) {
                    _droneState.value = _droneState.value.copy(
                        batteryPct = payload[30].toInt() and 0xFF
                    )
                }
            }
        }
    }

    private fun readFloat(arr: ByteArray, offset: Int): Float {
        val bits = (arr[offset].toInt() and 0xFF) or
                   ((arr[offset + 1].toInt() and 0xFF) shl 8) or
                   ((arr[offset + 2].toInt() and 0xFF) shl 16) or
                   ((arr[offset + 3].toInt() and 0xFF) shl 24)
        return java.lang.Float.intBitsToFloat(bits)
    }

    private fun readInt16(arr: ByteArray, offset: Int): Int {
        val value = (arr[offset].toInt() and 0xFF) or ((arr[offset + 1].toInt() and 0xFF) shl 8)
        return if (value >= 32768) value - 65536 else value
    }
}

class MavLinkParser(private val onMessage: (Int, ByteArray, Int) -> Unit) {
    private enum class State { WAITING_STX, HEADER, PAYLOAD, CRC }

    private var state = State.WAITING_STX
    private var payloadLen = 0
    private var msgId = 0
    private val payload = ByteArray(255)
    private var index = 0
    private var headerIndex = 0

    fun parseByte(b: Byte) {
        val ub = b.toInt() and 0xFF
        when (state) {
            State.WAITING_STX -> if (ub == 0xFD) { state = State.HEADER; headerIndex = 0 }
            State.HEADER -> {
                when (headerIndex) {
                    0 -> payloadLen = ub
                    6 -> msgId = ub
                    7 -> msgId = msgId or (ub shl 8)
                    8 -> {
                        msgId = msgId or (ub shl 16)
                        index = 0
                        state = if (payloadLen > 0) State.PAYLOAD else State.CRC
                    }
                }
                headerIndex++
            }
            State.PAYLOAD -> {
                payload[index++] = b
                if (index >= payloadLen) { state = State.CRC; index = 0 }
            }
            State.CRC -> {
                if (++index >= 2) {
                    onMessage(msgId, payload, payloadLen)
                    state = State.WAITING_STX
                }
            }
        }
    }
}
