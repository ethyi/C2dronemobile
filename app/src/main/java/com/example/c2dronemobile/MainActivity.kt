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
import com.google.maps.android.compose.CameraPositionState
import com.google.android.gms.maps.CameraUpdateFactory
import androidx.compose.ui.platform.LocalContext
import androidx.compose.runtime.LaunchedEffect

private val DOYLE_PARK = LatLng(32.86447594950015, -117.2191930622458)
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
    val heading: Int = 0,
    val altitude: Float = 0f,
    val batteryPct: Int = 0,
    val lat: Double = 0.0,
    val lon: Double = 0.0,
    val currentWaypoint: Int = 0,
    val lastReachedWaypoint: Int = 0,
    val flightMode: Int = 0
)

@Composable
fun MapScreen() {
    val cameraPositionState = rememberCameraPositionState {
        position = CameraPosition.fromLatLngZoom(DOYLE_PARK, DEFAULT_ZOOM)
    }
    val waypoints = remember { mutableStateListOf<LatLng>() }
    var mode by remember { mutableStateOf(AppMode.PLANNING) }

    // MAVLink connection - USB serial only (for real hardware)
    val context = LocalContext.current
    val mavConnection = remember {
        MavLinkConnection(context).also { it.connect() }
    }
    val connectionState by mavConnection.connectionState.collectAsState()
    val droneState by mavConnection.droneState.collectAsState()
    val missionState by mavConnection.missionState.collectAsState()

    val hasWaypoints = waypoints.isNotEmpty()

    Column(modifier = Modifier.fillMaxSize().background(DarkGray)) {

        val dronePosition = if (droneState.lat != 0.0 && droneState.lon != 0.0) {
            LatLng(droneState.lat, droneState.lon)
        } else {
            DOYLE_PARK
        }

        var mapLoaded by remember { mutableStateOf(false) }

        LaunchedEffect(dronePosition, mapLoaded) {
            if (mapLoaded) {
                cameraPositionState.centerOnLocation(dronePosition)
            }
        }

        GoogleMap(
            modifier = Modifier.fillMaxWidth().weight(1f),
            cameraPositionState = cameraPositionState,
            properties = MapProperties(mapType = MapType.SATELLITE),
            onMapLoaded = { mapLoaded = true },
            onMapLongClick = { latLng ->
                if (mode == AppMode.PLANNING) waypoints.add(latLng)
            }
        ) {
            // Scale drone size based on zoom (smaller when zoomed in)
            val zoom = cameraPositionState.position.zoom
            val droneSize = when {
                zoom > 19f -> 5f
                zoom > 17f -> 10f
                zoom > 15f -> 15f
                else -> 20f
            }
            GroundOverlay(
                position = GroundOverlayPosition.create(dronePosition, droneSize, droneSize),
                image = BitmapDescriptorFactory.fromResource(R.drawable.drone_arrow),
                bearing = droneState.heading.toFloat()
            )

            // Show all waypoints - completed ones in grey, pending in default color
            // Use lastReachedWaypoint to determine what's been completed
            waypoints.forEachIndexed { index, position ->
                val wpSeq = index + 1  // waypoint 0 in list = seq 1 in mission
                val isCompleted = droneState.lastReachedWaypoint >= wpSeq
                Marker(
                    state = rememberUpdatedMarkerState(position = position),
                    title = "Waypoint ${index + 1}",
                    alpha = if (isCompleted) 0.4f else 1f
                )
            }

            if (hasWaypoints) {
                // Completed path - grey dotted line between completed waypoints
                val completedWaypoints = waypoints.filterIndexed { index, _ ->
                    droneState.lastReachedWaypoint >= index + 1
                }
                if (completedWaypoints.isNotEmpty()) {
                    Polyline(
                        points = completedWaypoints,
                        color = Color.Gray,
                        width = 4f,
                        pattern = listOf(
                            com.google.android.gms.maps.model.Dot(),
                            com.google.android.gms.maps.model.Gap(10f)
                        )
                    )
                }

                // Remaining path - yellow solid line from drone to remaining waypoints
                // Use lastReachedWaypoint to know where to start the yellow line
                val nextWaypointIndex = droneState.lastReachedWaypoint  // index of next waypoint (0-based)
                if (nextWaypointIndex < waypoints.size) {
                    val remainingWaypoints = waypoints.drop(nextWaypointIndex)
                    Polyline(
                        points = listOf(dronePosition) + remainingWaypoints,
                        color = Yellow,
                        width = 5f
                    )
                } else if (droneState.lastReachedWaypoint == 0 && droneState.currentWaypoint == 0) {
                    // Not in AUTO yet, show full path
                    Polyline(
                        points = listOf(dronePosition) + waypoints,
                        color = Yellow,
                        width = 5f
                    )
                }
            }
        }

        TelemetryBar(droneState, connectionState)

        // Auto-transition on mission upload complete/error
        LaunchedEffect(missionState) {
            when (missionState) {
                is MissionState.Complete -> {
                    kotlinx.coroutines.delay(1000)
                    mode = AppMode.FLIGHT
                    mavConnection.resetMissionState()
                }
                is MissionState.Error -> {
                    kotlinx.coroutines.delay(1500)
                    mavConnection.resetMissionState()
                }
                else -> {}
            }
        }


        // Check if mission is complete (all waypoints reached)
        val missionComplete = hasWaypoints && droneState.lastReachedWaypoint >= waypoints.size

        val landButton = ActionButton("Land", Color(0xFF64B5F6)) { mavConnection.sendSetMode(MODE_LAND) }

        val uploadText = when (val state = missionState) {
            is MissionState.Uploading -> "${state.current}/${state.total}"
            is MissionState.Complete -> "Uploaded!"
            is MissionState.Error -> "Failed"
            else -> "Upload"
        }
        val uploadColor = when (missionState) {
            is MissionState.Complete -> Color(0xFF90EE90)
            is MissionState.Error -> RedPastel
            else -> GreenPastel
        }

        when {
            missionComplete -> ActionBar(
                title = "✓ Mission Complete",
                titleColor = GreenPastel,
                buttons = listOf(
                    landButton,
                    ActionButton("New Mission", GreenPastel) {
                        mavConnection.clearMission()
                        mavConnection.resetWaypointTracking()
                        waypoints.clear()
                        mode = AppMode.PLANNING
                    }
                )
            )
            mode == AppMode.PLANNING -> ActionBar(
                title = "Mission Planner",
                buttons = listOf(
                    landButton,
                    ActionButton("Undo", OrangePastel, hasWaypoints) { waypoints.removeLast() },
                    ActionButton("Clear", RedPastel, hasWaypoints) { waypoints.clear() },
                    ActionButton(uploadText, uploadColor, hasWaypoints) {
                        mavConnection.uploadMission(waypoints.map { Waypoint(it.latitude, it.longitude, 4f) })
                    }
                )
            )
            mode == AppMode.FLIGHT -> ActionBar(
                title = "Flight Control",
                buttons = listOf(
                    ActionButton("Pause", OrangePastel) { mavConnection.sendSetMode(MODE_LOITER) },
                    ActionButton("Resume", GreenPastel) { mavConnection.sendSetMode(MODE_AUTO) },
                    landButton,
                    ActionButton("Abort", RedPastel) {
                        mavConnection.sendSetMode(MODE_LOITER)
                        mavConnection.clearMission()
                        waypoints.clear()
                        mode = AppMode.PLANNING
                    }
                )
            )
        }
    }
}

data class ActionButton(
    val label: String,
    val color: Color,
    val enabled: Boolean = true,
    val onClick: () -> Unit
)

@Composable
fun ActionBar(
    title: String,
    titleColor: Color = Yellow,
    buttons: List<ActionButton>
) {
    Column {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(MediumGray)
                .padding(horizontal = 12.dp, vertical = 6.dp)
        ) {
            Text(title, color = titleColor, fontSize = 14.sp, fontWeight = FontWeight.Medium)
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
                    enabled = button.enabled,
                    modifier = Modifier.weight(1f).height(48.dp),
                    shape = RoundedCornerShape(12.dp),
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(4.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = button.color,
                        disabledContainerColor = DisabledGray
                    )
                ) {
                    Text(
                        button.label,
                        color = if (button.enabled) Color.Black else DisabledTextGray,
                        fontSize = 12.sp,
                        maxLines = 1
                    )
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

suspend fun CameraPositionState.centerOnLocation(location: LatLng) {
    animate(
        update = CameraUpdateFactory.newLatLng(location),
        durationMs = 300
    )
}
