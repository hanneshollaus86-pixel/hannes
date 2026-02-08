package com.example.hannes

import android.Manifest
import android.annotation.SuppressLint
import android.content.pm.PackageManager
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.google.android.gms.location.FusedLocationProviderClient
import com.google.android.gms.location.LocationCallback
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationResult
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import com.google.android.gms.maps.model.LatLng
import com.google.android.gms.maps.model.Tile
import com.google.android.gms.maps.model.TileProvider
import com.google.android.gms.maps.model.UrlTileProvider
import com.google.maps.android.SphericalUtil
import com.google.maps.android.compose.CameraPositionState
import com.google.maps.android.compose.GoogleMap
import com.google.maps.android.compose.MapProperties
import com.google.maps.android.compose.Marker
import com.google.maps.android.compose.Polygon
import com.google.maps.android.compose.Polyline
import com.google.maps.android.compose.TileOverlay
import com.google.maps.android.compose.rememberCameraPositionState
import com.google.maps.android.compose.rememberTileOverlayState
import java.net.MalformedURLException
import java.net.URL

private enum class MapMode(val label: String) {
    NONE("Navigation"),
    DISTANCE("Strecke"),
    AREA("Fläche"),
    NOTE("Notiz")
}

private data class Note(val position: LatLng, val text: String)

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme {
                MapScreen()
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun MapScreen() {
    val context = LocalContext.current
    val fusedLocationClient = remember {
        LocationServices.getFusedLocationProviderClient(context)
    }
    val currentLocation = remember { mutableStateOf<LatLng?>(null) }
    val hasPermission = remember { mutableStateOf(false) }
    val launcher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestMultiplePermissions(),
    ) { result ->
        hasPermission.value = result[Manifest.permission.ACCESS_FINE_LOCATION] == true ||
            result[Manifest.permission.ACCESS_COARSE_LOCATION] == true
    }

    LaunchedEffect(Unit) {
        val fine = ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.ACCESS_FINE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED
        val coarse = ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.ACCESS_COARSE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED
        if (fine || coarse) {
            hasPermission.value = true
        } else {
            launcher.launch(
                arrayOf(
                    Manifest.permission.ACCESS_FINE_LOCATION,
                    Manifest.permission.ACCESS_COARSE_LOCATION
                )
            )
        }
    }

    LocationUpdater(
        fusedLocationClient = fusedLocationClient,
        hasPermission = hasPermission,
        currentLocation = currentLocation
    )

    val cameraPositionState = rememberCameraPositionState()
    var overlayTransparency by remember { mutableStateOf(0.2f) }
    val modeState = remember { mutableStateOf(MapMode.NONE) }
    val distancePoints = remember { mutableStateListOf<LatLng>() }
    val areaPoints = remember { mutableStateListOf<LatLng>() }
    val notes = remember { mutableStateListOf<Note>() }
    val noteDraft = remember { mutableStateOf<LatLng?>(null) }
    val noteText = remember { mutableStateOf("") }

    LaunchedEffect(currentLocation.value) {
        currentLocation.value?.let {
            if (!cameraPositionState.isMoving) {
                cameraPositionState.position = cameraPositionState.position.copy(target = it, zoom = 18f)
            }
        }
    }

    Column(modifier = Modifier.fillMaxSize()) {
        MapToolbar(
            modeState = modeState,
            onClear = {
                distancePoints.clear()
                areaPoints.clear()
            }
        )

        MapLegend(
            overlayTransparency = overlayTransparency,
            onTransparencyChange = { overlayTransparency = it },
            distanceMeters = calculateDistance(distancePoints),
            areaMeters = calculateArea(areaPoints)
        )

        GoogleMap(
            modifier = Modifier
                .fillMaxSize()
                .padding(top = 8.dp),
            cameraPositionState = cameraPositionState,
            properties = MapProperties(mapType = com.google.android.gms.maps.GoogleMap.MAP_TYPE_SATELLITE),
            onMapClick = { position ->
                when (modeState.value) {
                    MapMode.DISTANCE -> distancePoints.add(position)
                    MapMode.AREA -> areaPoints.add(position)
                    MapMode.NOTE -> {
                        noteDraft.value = position
                        noteText.value = ""
                    }
                    MapMode.NONE -> Unit
                }
            }
        ) {
            val overlayState = rememberTileOverlayState()
            overlayState.transparency = overlayTransparency
            TileOverlay(
                tileProvider = osmTileProvider(),
                state = overlayState
            )

            currentLocation.value?.let { Marker(position = it, title = "Live GPS") }

            if (distancePoints.size >= 2) {
                Polyline(points = distancePoints)
            }

            if (areaPoints.size >= 3) {
                Polygon(points = areaPoints)
            }

            notes.forEach { note ->
                Marker(position = note.position, title = note.text)
            }
        }
    }

    NoteDialog(
        noteDraft = noteDraft,
        noteText = noteText,
        onSave = { position, text ->
            notes.add(Note(position, text))
        }
    )
}

@Composable
private fun MapToolbar(
    modeState: MutableState<MapMode>,
    onClear: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .statusBarsPadding()
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Text(text = "GPS Live Karte", style = MaterialTheme.typography.titleLarge)
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            ModeButton(label = MapMode.NONE.label, isActive = modeState.value == MapMode.NONE) {
                modeState.value = MapMode.NONE
            }
            ModeButton(label = MapMode.DISTANCE.label, isActive = modeState.value == MapMode.DISTANCE) {
                modeState.value = MapMode.DISTANCE
            }
            ModeButton(label = MapMode.AREA.label, isActive = modeState.value == MapMode.AREA) {
                modeState.value = MapMode.AREA
            }
            ModeButton(label = MapMode.NOTE.label, isActive = modeState.value == MapMode.NOTE) {
                modeState.value = MapMode.NOTE
            }
        }
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.End
        ) {
            TextButton(onClick = onClear) {
                Text(text = "Messung zurücksetzen")
            }
        }
    }
}

@Composable
private fun MapLegend(
    overlayTransparency: Float,
    onTransparencyChange: (Float) -> Unit,
    distanceMeters: Double,
    areaMeters: Double
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        Text(text = "Transparenz Karten-Overlay")
        Slider(
            value = overlayTransparency,
            onValueChange = onTransparencyChange,
            valueRange = 0f..1f
        )
        Text(text = "Strecke: ${formatDistance(distanceMeters)}")
        Text(text = "Fläche: ${formatArea(areaMeters)}")
    }
}

@Composable
private fun ModeButton(label: String, isActive: Boolean, onClick: () -> Unit) {
    Button(onClick = onClick) {
        Text(text = if (isActive) "$label ✓" else label)
    }
}

@Composable
private fun NoteDialog(
    noteDraft: MutableState<LatLng?>,
    noteText: MutableState<String>,
    onSave: (LatLng, String) -> Unit
) {
    val position = noteDraft.value
    if (position != null) {
        AlertDialog(
            onDismissRequest = { noteDraft.value = null },
            title = { Text(text = "Notiz hinzufügen") },
            text = {
                OutlinedTextField(
                    value = noteText.value,
                    onValueChange = { noteText.value = it },
                    label = { Text(text = "Notiz") }
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        onSave(position, noteText.value.ifBlank { "Notiz" })
                        noteDraft.value = null
                    }
                ) {
                    Text(text = "Speichern")
                }
            },
            dismissButton = {
                TextButton(onClick = { noteDraft.value = null }) {
                    Text(text = "Abbrechen")
                }
            }
        )
    }
}

@SuppressLint("MissingPermission")
@Composable
private fun LocationUpdater(
    fusedLocationClient: FusedLocationProviderClient,
    hasPermission: MutableState<Boolean>,
    currentLocation: MutableState<LatLng?>
) {
    DisposableEffect(hasPermission.value) {
        if (!hasPermission.value) {
            return@DisposableEffect onDispose { }
        }

        val request = LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, 1000L)
            .setMinUpdateIntervalMillis(500L)
            .build()

        val callback = object : LocationCallback() {
            override fun onLocationResult(result: LocationResult) {
                val location = result.lastLocation ?: return
                currentLocation.value = LatLng(location.latitude, location.longitude)
            }
        }

        fusedLocationClient.requestLocationUpdates(request, callback, null)

        onDispose {
            fusedLocationClient.removeLocationUpdates(callback)
        }
    }
}

private fun calculateDistance(points: List<LatLng>): Double {
    if (points.size < 2) return 0.0
    return SphericalUtil.computeLength(points)
}

private fun calculateArea(points: List<LatLng>): Double {
    if (points.size < 3) return 0.0
    return kotlin.math.abs(SphericalUtil.computeArea(points))
}

private fun formatDistance(distanceMeters: Double): String {
    return if (distanceMeters >= 1000) {
        String.format("%.2f km", distanceMeters / 1000)
    } else {
        String.format("%.0f m", distanceMeters)
    }
}

private fun formatArea(areaMeters: Double): String {
    return if (areaMeters >= 1_000_000) {
        String.format("%.2f km²", areaMeters / 1_000_000)
    } else {
        String.format("%.0f m²", areaMeters)
    }
}

private fun osmTileProvider(): TileProvider {
    return object : UrlTileProvider(256, 256) {
        override fun getTileUrl(x: Int, y: Int, zoom: Int): URL? {
            return try {
                URL("https://tile.openstreetmap.org/$zoom/$x/$y.png")
            } catch (exception: MalformedURLException) {
                null
            }
        }

        override fun getTile(x: Int, y: Int, zoom: Int): Tile? {
            return super.getTile(x, y, zoom)
        }
    }
}
