package com.example.c2dronemobile

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.graphics.Color
import com.google.android.gms.maps.model.BitmapDescriptorFactory
import com.google.android.gms.maps.model.CameraPosition
import com.google.android.gms.maps.model.LatLng
import com.google.maps.android.compose.GoogleMap
import com.google.maps.android.compose.GroundOverlay
import com.google.maps.android.compose.GroundOverlayPosition
import com.google.maps.android.compose.MapProperties
import com.google.maps.android.compose.MapType
import com.google.maps.android.compose.Marker
import com.google.maps.android.compose.Polyline
import com.google.maps.android.compose.rememberCameraPositionState
import com.google.maps.android.compose.rememberUpdatedMarkerState
import androidx.compose.ui.platform.LocalContext
import android.content.Context

private val DOYLE_PARK = LatLng(32.86447594950015, -117.2191930622458)
private val DRONE_POSITION = DOYLE_PARK  // TODO: replace with live telemetry
private const val DEFAULT_ZOOM = 18f

private val DarkGray = Color(0xFF1A1A1A)
private val MediumGray = Color(0xFF2D2D2D)
private val Yellow = Color(0xFFFFD600)
private val GreenPastel = Color(0xFF6FBF73)
private val RedPastel = Color(0xFFF6685E)
private val OrangePastel = Color(0xFFFFCD38)
private val DisabledGray = Color(0xFF2A2A2A)
private val DisabledTextGray = Color(0xFF6B6B6B)

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MapScreen()
        }
    }
}

enum class AppMode { PLANNING, FLIGHT }

data class DroneState(
    val speed: Float = 0f,        // m/s groundspeed
    val heading: Int = 0,         // degrees
    val altitude: Float = 0f,     // meters AGL
    val batteryPct: Int = 0,      // percentage
    val connected: Boolean = false
)

@Composable
fun MapScreen() {
    val cameraPositionState = rememberCameraPositionState {
        position = CameraPosition.fromLatLngZoom(DOYLE_PARK, DEFAULT_ZOOM)
    }
    val waypoints = remember { mutableStateListOf<LatLng>() }
    var mode by remember { mutableStateOf(AppMode.PLANNING) }
    var isPaused by remember { mutableStateOf(false) }

    // MAVLink connection - USB serial only (for real hardware)
    val context = LocalContext.current
    val mavConnection = remember {
        MavLinkConnection(UsbTransport(context))
    }
    val connectionState by mavConnection.connectionState.collectAsState()
    val droneState by mavConnection.droneState.collectAsState()

    val hasWaypoints = waypoints.isNotEmpty()

    Column(modifier = Modifier.fillMaxSize().background(DarkGray)) {

        GoogleMap(
            modifier = Modifier.fillMaxWidth().weight(1f),
            cameraPositionState = cameraPositionState,
            properties = MapProperties(mapType = MapType.SATELLITE),
            onMapLongClick = { latLng ->
                if (mode == AppMode.PLANNING) waypoints.add(latLng)
            }
        ) {
            GroundOverlay(
                position = GroundOverlayPosition.create(DOYLE_PARK, 20f, 20f),
                image = BitmapDescriptorFactory.fromResource(R.drawable.drone_arrow),
                bearing = 0f
            )

            waypoints.forEachIndexed { index, position ->
                Marker(
                    state = rememberUpdatedMarkerState(position = position),
                    title = "Waypoint ${index + 1}"
                )
            }

            if (hasWaypoints) {
                Polyline(
                    points = listOf(DRONE_POSITION) + waypoints,
                    color = Yellow,
                    width = 5f
                )
            }
        }

        TelemetryBar(droneState, connectionState)

        when (mode) {
            AppMode.PLANNING -> MissionPlannerBar(
                hasWaypoints = hasWaypoints,
                onUndo = { waypoints.removeLast() },
                onClear = { waypoints.clear() },
                onUpload = {
                    // TODO: Send mission to Pixhawk
                    mode = AppMode.FLIGHT
                }
            )
            AppMode.FLIGHT -> FlightControlBar(
                isPaused = isPaused,
                onPauseResume = {
                    // TODO: Send LOITER/AUTO command to Pixhawk
                    isPaused = !isPaused
                },
                onLand = { /* TODO: Send LAND command to Pixhawk */ },
onLoiter = {
                    // TODO: Send LOITER command to Pixhawk, clear mission
                    waypoints.clear()
                    mode = AppMode.PLANNING
                    isPaused = false
                }
            )
        }
    }
}

data class ActionButton(
    val label: String,
    val color: Color,
    val textColor: Color = Color.Black,
    val onClick: () -> Unit
)

@Composable
fun MissionPlannerBar(
    hasWaypoints: Boolean,
    onUndo: () -> Unit,
    onClear: () -> Unit,
    onUpload: () -> Unit
) {
    val buttons = listOf(
        ActionButton("Undo", OrangePastel) { onUndo() },
        ActionButton("Clear All", RedPastel) { onClear() },
        ActionButton("Upload", GreenPastel) { onUpload() }
    )

    Column {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(MediumGray)
                .padding(horizontal = 12.dp, vertical = 6.dp)
        ) {
            Text(
                text = "Mission Planner",
                color = Yellow,
                fontSize = 14.sp,
                fontWeight = FontWeight.Medium
            )
        }

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(DarkGray)
                .padding(horizontal = 12.dp, vertical = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            buttons.forEach { button ->
                Button(
                    onClick = button.onClick,
                    enabled = hasWaypoints,
                    modifier = Modifier.weight(1f).height(70.dp),
                    shape = RoundedCornerShape(12.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = button.color,
                        disabledContainerColor = DisabledGray,
                        disabledContentColor = DisabledTextGray
                    )
                ) {
                    Text(
                        button.label,
                        color = if (hasWaypoints) button.textColor else DisabledTextGray
                    )
                }
            }
        }
    }
}

@Composable
fun FlightControlBar(
    isPaused: Boolean,
    onPauseResume: () -> Unit,
    onLand: () -> Unit,
    onLoiter: () -> Unit
) {
    val buttons = listOf(
        ActionButton(if (isPaused) "Resume" else "Pause", OrangePastel) { onPauseResume() },
        ActionButton("Land", GreenPastel) { onLand() },
        ActionButton("Loiter", RedPastel) { onLoiter() }
    )

    Column {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(MediumGray)
                .padding(horizontal = 12.dp, vertical = 6.dp)
        ) {
            Text(
                text = "Flight Control",
                color = Yellow,
                fontSize = 14.sp,
                fontWeight = FontWeight.Medium
            )
        }

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(DarkGray)
                .padding(horizontal = 12.dp, vertical = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            buttons.forEach { button ->
                Button(
                    onClick = button.onClick,
                    modifier = Modifier.weight(1f).height(48.dp),
                    shape = RoundedCornerShape(12.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = button.color
                    )
                ) {
                    Text(button.label, color = Color.Black)
                }
            }
        }
    }
}

@Composable
fun TelemetryBar(state: DroneState, connectionState: ConnectionState) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(DarkGray)
            .padding(horizontal = 12.dp, vertical = 16.dp),
        horizontalArrangement = Arrangement.SpaceEvenly
    ) {
        ConnectionStatusItem(connectionState)
        TelemetryItem("SPEED", "%.1f".format(state.speed), "m/s")
        TelemetryItem("HDG", "${state.heading}", "°")
        TelemetryItem("ALT", "%.1f".format(state.altitude), "m")
        TelemetryItem("BAT", "${state.batteryPct}", "%")
    }
}

@Composable
fun ConnectionStatusItem(state: ConnectionState) {
    val (label, color) = when (state) {
        ConnectionState.CONNECTED -> "●" to GreenPastel
        ConnectionState.CONNECTING -> "◐" to OrangePastel
        ConnectionState.DISCONNECTED -> "○" to RedPastel
    }
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(text = "LINK", color = Color.Gray, fontSize = 11.sp, fontWeight = FontWeight.Medium)
        Text(text = label, color = color, fontSize = 18.sp, fontWeight = FontWeight.Bold)
    }
}

@Composable
fun TelemetryItem(label: String, value: String, unit: String) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(
            text = label,
            color = Color.Gray,
            fontSize = 11.sp,
            fontWeight = FontWeight.Medium
        )
        Row(verticalAlignment = Alignment.Bottom) {
            Text(
                text = value,
                color = Color.White,
                fontSize = 18.sp,
                fontWeight = FontWeight.Bold
            )
            Text(
                text = unit,
                color = Color.Gray,
                fontSize = 12.sp,
                modifier = Modifier.padding(start = 2.dp, bottom = 2.dp)
            )
        }
    }
}
