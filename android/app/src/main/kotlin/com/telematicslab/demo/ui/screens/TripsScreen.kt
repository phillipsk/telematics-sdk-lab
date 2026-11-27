package com.telematicslab.demo.ui.screens

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.telematicslab.demo.sdk.api.ExportFormat
import com.telematicslab.demo.sdk.api.GnssHealth
import com.telematicslab.demo.sdk.api.PermissionState
import com.telematicslab.demo.sdk.api.PowerStats
import com.telematicslab.demo.sdk.api.SdkConfig
import com.telematicslab.demo.sdk.api.SdkDiagnostics
import com.telematicslab.demo.sdk.api.SeatRole
import com.telematicslab.demo.sdk.api.SyncStatus
import com.telematicslab.demo.sdk.api.TransportMode
import com.telematicslab.demo.sdk.api.TripDeviceLabel
import com.telematicslab.demo.sdk.api.TripPreset
import com.telematicslab.demo.sdk.api.TripRecord
import com.telematicslab.demo.sdk.api.TripState
import com.telematicslab.demo.ui.components.AccelTimelineChart
import com.telematicslab.demo.ui.components.ChartLegendRow
import com.telematicslab.demo.ui.components.EventWaveformZoomChart
import com.telematicslab.demo.ui.components.GGFrictionCircle
import com.telematicslab.demo.ui.components.RouteMapCanvas
import com.telematicslab.demo.ui.components.SpeedTimelineChart
import com.telematicslab.demo.ui.components.StatPill
import com.telematicslab.demo.ui.components.TelemetryCard
import com.telematicslab.demo.ui.components.formatMinSec
import com.telematicslab.demo.ui.components.nearestTrackIndex
import com.telematicslab.demo.ui.theme.TelemetryColors
import com.telematicslab.demo.ui.theme.eventColor
import com.telematicslab.demo.ui.theme.scoreColor
import kotlinx.coroutines.launch
import kotlin.math.roundToInt
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun TripsScreen(
    trips: List<TripRecord>,
    presets: List<TripPreset>,
    tripState: TripState,
    config: SdkConfig,
    permissions: PermissionState,
    diagnostics: SdkDiagnostics,
    power: PowerStats,
    gnss: GnssHealth,
    onRequestForegroundPermissions: () -> Unit,
    onRequestBackgroundLocation: () -> Unit,
    onRequestBatteryUnrestricted: () -> Unit,
    onRefreshHealth: () -> Unit,
    onStartPreset: (TripPreset) -> Unit,
    onDeleteTrip: (String) -> Unit,
    onUpdateTripMeta: (String, String, SeatRole, TransportMode) -> Unit,
    onSyncAll: () -> Unit,
    onConfigureCloudSync: (String, String) -> Unit,
    onExportTrip: suspend (TripRecord, ExportFormat) -> Intent?,
    onRunSimulatedDrive: () -> Unit,
    onDetailVisibilityChanged: (Boolean) -> Unit = {}
) {
    var selectedTripId by rememberSaveable { mutableStateOf<String?>(null) }
    var showPresets by rememberSaveable { mutableStateOf(true) }
    var showCloudConfig by rememberSaveable { mutableStateOf(false) }
    val selectedTrip = trips.firstOrNull { it.id == selectedTripId }

    LaunchedEffect(selectedTrip != null) {
        onDetailVisibilityChanged(selectedTrip != null)
    }

    if (selectedTrip != null) {
        BackHandler { selectedTripId = null }
        TripDetailView(
            trip = selectedTrip,
            thresholdG = config.hardBrakeG,
            onBack = { selectedTripId = null },
            onDelete = {
                onDeleteTrip(selectedTrip.id)
                selectedTripId = null
            },
            onUpdateMeta = { label, role, mode -> onUpdateTripMeta(selectedTrip.id, label, role, mode) },
            onExportTrip = onExportTrip
        )
        return
    }

    val isRecording = tripState != TripState.IDLE
    val completedLabels = remember(trips) { trips.map { TripDeviceLabel.stripDeviceTag(it.tripLabel) }.toSet() }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        // One-Click Planned Trips Hub
        TelemetryCard {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { showPresets = !showPresets },
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text("One-Click Planned Trips (${presets.size})", style = MaterialTheme.typography.titleMedium)
                    Text(
                        "Tap Start on any planned outbound/return trip to tag mode, seat role, and begin recording.",
                        style = MaterialTheme.typography.labelMedium,
                        color = TelemetryColors.Slate
                    )
                }
                Text(if (showPresets) "▲" else "▼", color = TelemetryColors.Cyan)
            }

            if (showPresets) {
                Spacer(Modifier.height(8.dp))
                val grouped = presets.groupBy { it.group }
                grouped.forEach { (groupName, groupItems) ->
                    Text(
                        groupName.uppercase(Locale.ROOT),
                        style = MaterialTheme.typography.labelMedium,
                        color = TelemetryColors.Cyan,
                        modifier = Modifier.padding(top = 6.dp, bottom = 3.dp)
                    )
                    groupItems.forEach { preset ->
                        val isDone = TripDeviceLabel.stripDeviceTag(preset.label) in completedLabels
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 3.dp)
                                .clip(RoundedCornerShape(10.dp))
                                .background(TelemetryColors.SubCardDark)
                                .padding(horizontal = 10.dp, vertical = 8.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(preset.label, fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
                                Spacer(Modifier.height(4.dp))
                                FlowRow(
                                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                                    verticalArrangement = Arrangement.spacedBy(6.dp)
                                ) {
                                    BadgePill(preset.mode.label, TelemetryColors.Teal)
                                    preset.plannedDate?.let { dateStr ->
                                        BadgePill(dateStr, TelemetryColors.Amber)
                                    }
                                    if (preset.mode == TransportMode.CAR) {
                                        BadgePill(preset.seatRole.label, TelemetryColors.Slate)
                                    }
                                    if (isDone) {
                                        BadgePill("CAPTURED", TelemetryColors.Green)
                                    }
                                }
                            }
                            Spacer(Modifier.width(10.dp))
                            Button(
                                onClick = { onStartPreset(preset) },
                                enabled = !isRecording,
                                modifier = Modifier.defaultMinSize(minWidth = 76.dp)
                            ) {
                                Text("Start", fontSize = 11.sp, fontWeight = FontWeight.Bold, maxLines = 1, softWrap = false)
                            }
                        }
                    }
                }
            }
        }

        TelemetryCard {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("Recorded & Demo Trips (${trips.size})", style = MaterialTheme.typography.titleMedium)
                if (trips.isNotEmpty() && config.cloudPasscodeConfigured) {
                    OutlinedButton(onClick = onSyncAll) {
                        Text("Sync Cloud", fontSize = 11.sp)
                    }
                }
            }
            Spacer(Modifier.height(4.dp))
            Text(
                "Trips auto-archive locally in JSON, CSV & GPX and sync to your Cloud Workbench when configured.",
                style = MaterialTheme.typography.bodyMedium,
                color = TelemetryColors.Slate
            )
            if (trips.isEmpty()) {
                Spacer(Modifier.height(10.dp))
                Button(onClick = onRunSimulatedDrive) {
                    Text("Generate 34s Demo Trip Now")
                }
            }
        }

        val fmt = remember { SimpleDateFormat("MMM d, h:mm a", Locale.US) }
        trips.forEach { trip ->
            TelemetryCard(
                modifier = Modifier
                    .testTag("trip_card_${trip.id}")
                    .clickable { selectedTripId = trip.id }
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            trip.tripLabel.ifBlank { fmt.format(Date(trip.startTimeMs)) },
                            fontWeight = FontWeight.Bold,
                            fontSize = 15.sp
                        )
                        Spacer(Modifier.height(4.dp))
                        FlowRow(
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                            verticalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            TripDeviceLabel.extractDeviceTag(trip.tripLabel)?.let { tag ->
                                BadgePill(tag, deviceBadgeColor(tag))
                            }
                            BadgePill(trip.transportMode.label, TelemetryColors.Teal)
                            BadgePill(trip.seatRole.label, TelemetryColors.Cyan)
                            val syncCol = when (trip.syncStatus) {
                                SyncStatus.SYNCED -> TelemetryColors.Green
                                SyncStatus.PENDING_UPLOAD -> TelemetryColors.Amber
                                SyncStatus.FAILED -> TelemetryColors.Red
                                SyncStatus.LOCAL_ONLY -> TelemetryColors.Slate
                            }
                            BadgePill(trip.syncStatus.label, syncCol)
                            if (trip.isSimulated) BadgePill("DEMO", TelemetryColors.Purple)
                        }
                        Spacer(Modifier.height(4.dp))
                        Text(
                            "${fmt.format(Date(trip.startTimeMs))} • ${"%.2f".format(trip.distanceMiles)} mi • ${formatDuration(trip.durationSec)} • ${trip.events.size} events",
                            style = MaterialTheme.typography.bodyMedium,
                            color = TelemetryColors.Slate
                        )
                    }
                    Spacer(Modifier.width(10.dp))

                    Box(
                        modifier = Modifier
                            .size(46.dp)
                            .clip(CircleShape)
                            .background(scoreColor(trip.score.overall).copy(alpha = 0.18f))
                            .border(1.5.dp, scoreColor(trip.score.overall), CircleShape),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            "${trip.score.overall}",
                            fontWeight = FontWeight.ExtraBold,
                            fontSize = 16.sp,
                            color = scoreColor(trip.score.overall)
                        )
                    }
                }
            }
        }

        // Expandable Cloud Sync Settings
        TelemetryCard {
            val context = LocalContext.current
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { showCloudConfig = !showCloudConfig },
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text("Cloud Sync Archive Settings", style = MaterialTheme.typography.titleMedium)
                    Text(
                        if (config.cloudPasscodeConfigured) "Connected: ${config.cloudEndpointUrl}" else "Not configured yet",
                        style = MaterialTheme.typography.labelMedium,
                        color = if (config.cloudPasscodeConfigured) TelemetryColors.Green else TelemetryColors.Amber
                    )
                }
                Text(if (showCloudConfig) "▲" else "▼", color = TelemetryColors.Cyan)
            }

            if (config.cloudUnlockUrl.isNotBlank()) {
                Spacer(Modifier.height(10.dp))
                Text(
                    "One-Click Web Workbench Link",
                    style = MaterialTheme.typography.labelMedium,
                    color = TelemetryColors.Teal
                )
                Spacer(Modifier.height(4.dp))
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(8.dp))
                        .background(TelemetryColors.SubCardDark)
                        .padding(10.dp)
                ) {
                    Text(
                        config.cloudUnlockUrl,
                        fontSize = 11.sp,
                        color = TelemetryColors.Cyan
                    )
                }
                Spacer(Modifier.height(8.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Button(
                        onClick = {
                            val openIntent = Intent(Intent.ACTION_VIEW, Uri.parse(config.cloudUnlockUrl))
                            runCatching { context.startActivity(openIntent) }
                        },
                        modifier = Modifier.weight(1f)
                    ) {
                        Text("Open", fontSize = 12.sp, fontWeight = FontWeight.Bold)
                    }
                    OutlinedButton(
                        onClick = {
                            val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                            cm.setPrimaryClip(ClipData.newPlainText("Workbench URL", config.cloudUnlockUrl))
                            Toast.makeText(context, "Workbench URL copied", Toast.LENGTH_SHORT).show()
                        },
                        modifier = Modifier.weight(1f)
                    ) {
                        Text("Copy", fontSize = 12.sp)
                    }
                    OutlinedButton(
                        onClick = {
                            val shareIntent = Intent.createChooser(
                                Intent(Intent.ACTION_SEND).apply {
                                    type = "text/plain"
                                    putExtra(Intent.EXTRA_SUBJECT, "Telematics SDK Workbench")
                                    putExtra(Intent.EXTRA_TEXT, config.cloudUnlockUrl)
                                },
                                "Share Workbench Link"
                            )
                            runCatching { context.startActivity(shareIntent) }
                        },
                        modifier = Modifier.weight(1f)
                    ) {
                        Text("Share", fontSize = 12.sp)
                    }
                }
            }

            if (showCloudConfig) {
                var urlInput by remember(config.cloudEndpointUrl) { mutableStateOf(config.cloudEndpointUrl) }
                var codeInput by remember { mutableStateOf("") }
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = urlInput,
                    onValueChange = { urlInput = it },
                    label = { Text("Cloud Workbench URL") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(Modifier.height(6.dp))
                OutlinedTextField(
                    value = codeInput,
                    onValueChange = { codeInput = it },
                    label = { Text("Workbench Passcode (leave blank to keep current)") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(Modifier.height(8.dp))
                Button(onClick = { onConfigureCloudSync(urlInput, codeInput) }) {
                    Text("Save & Sync Now")
                }
            }
        }

        // Live App System Health & Permissions Card
        val fgsOk = !config.autoDetectEnabled || power.fgsUptimeSec > 0L || diagnostics.fgsStartCount > 0
        val promptsOk = !config.motionPromptsEnabled || diagnostics.motionPromptsActive
        val cloudOk = config.cloudEndpointUrl.isNotBlank() && config.cloudPasscodeConfigured
        val presetsOk = presets.isNotEmpty()
        val sensorsOk = power.imuSampleHz > 0f
        val allHealthy = permissions.allCarReady && fgsOk && promptsOk && cloudOk && presetsOk

        TelemetryCard {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text("System Health & Permissions", style = MaterialTheme.typography.titleMedium)
                    Text(
                        "Live verification of OS permissions, background capture & cloud sync.",
                        style = MaterialTheme.typography.labelMedium,
                        color = TelemetryColors.Slate
                    )
                }
                BadgePill(
                    if (allHealthy) "ALL READY" else "CHECK ITEMS",
                    if (allHealthy) TelemetryColors.Green else TelemetryColors.Amber
                )
            }

            Spacer(Modifier.height(10.dp))
            HealthCheckRow(
                label = "Fine Location",
                detail = if (permissions.fineLocationGranted) "Granted" else "Required",
                ok = permissions.fineLocationGranted,
                onFix = onRequestForegroundPermissions
            )
            HealthCheckRow(
                label = "Background Location (All Time)",
                detail = if (permissions.backgroundLocationGranted) "Allowed all the time" else "Needed for pocket/screen-off",
                ok = permissions.backgroundLocationGranted,
                onFix = onRequestBackgroundLocation
            )
            HealthCheckRow(
                label = "Notifications",
                detail = if (permissions.postNotificationsGranted) "Allowed" else "Needed for prompts",
                ok = permissions.postNotificationsGranted,
                onFix = onRequestForegroundPermissions
            )
            HealthCheckRow(
                label = "Physical Activity (Car/Bike)",
                detail = if (permissions.activityRecognitionGranted) "Granted" else "Needed for transition hints",
                ok = permissions.activityRecognitionGranted,
                onFix = onRequestForegroundPermissions
            )
            HealthCheckRow(
                label = "Battery Unrestricted (No Doze)",
                detail = if (permissions.ignoringBatteryOptimizations) "Unrestricted (${power.standbyBucketName})" else "Optimized (${power.standbyBucketName})",
                ok = permissions.ignoringBatteryOptimizations,
                onFix = onRequestBatteryUnrestricted
            )
            HealthCheckRow(
                label = "Location FGS (Auto-Detect)",
                detail = if (power.fgsUptimeSec > 0L) "Running (${power.fgsUptimeSec}s uptime)" else if (config.autoDetectEnabled) "Ready" else "Manual Mode",
                ok = fgsOk
            )
            HealthCheckRow(
                label = "Car & Bike Motion Prompts",
                detail = if (diagnostics.motionPromptsActive) "Registered (Play Services)" else if (config.motionPromptsEnabled) "Registering..." else "Paused",
                ok = promptsOk
            )
            HealthCheckRow(
                label = "Home Departure Geofence",
                detail = diagnostics.homeGeofenceStatus,
                ok = diagnostics.homeGeofenceArmed
            )
            HealthCheckRow(
                label = "Cloud Sync Archive",
                detail = if (cloudOk) "Connected" else "Missing URL or passcode",
                ok = cloudOk
            )
            HealthCheckRow(
                label = "Planned Trip Schedule",
                detail = "${presets.size} presets loaded",
                ok = presetsOk
            )
            HealthCheckRow(
                label = "Remote Feature Config",
                detail = "v${diagnostics.remoteConfigVersion} (${diagnostics.remoteConfigSource}) • >=${config.autoStartVerifyMph.toInt()} mph",
                ok = diagnostics.remoteConfigVersion > 0
            )
            HealthCheckRow(
                label = "Live Sensors & GNSS",
                detail = "${power.imuSampleHz.toInt()} Hz IMU • ${gnss.satellitesUsed}/${gnss.satellitesVisible} sats",
                ok = sensorsOk
            )

            Spacer(Modifier.height(8.dp))
            OutlinedButton(
                onClick = onRefreshHealth,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("Refresh Health Check", fontSize = 12.sp)
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun TripDetailView(
    trip: TripRecord,
    thresholdG: Float,
    onBack: () -> Unit,
    onDelete: () -> Unit,
    onUpdateMeta: (String, SeatRole, TransportMode) -> Unit,
    onExportTrip: suspend (TripRecord, ExportFormat) -> Intent?
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var expandedEventId by remember { mutableStateOf<String?>(trip.events.firstOrNull()?.id) }
    var showFsm by remember { mutableStateOf(false) }
    var labelDraft by remember(trip.id, trip.tripLabel) { mutableStateOf(trip.tripLabel) }
    var seatDraft by remember(trip.id, trip.seatRole) { mutableStateOf(trip.seatRole) }
    var modeDraft by remember(trip.id, trip.transportMode) { mutableStateOf(trip.transportMode) }

    // Synchronized inspection & zoom state
    var inspectedIndex by remember(trip.id) { mutableStateOf<Int?>(null) }
    var timelineZoom by remember(trip.id) { mutableFloatStateOf(1f) }
    var timelinePanFrac by remember(trip.id) { mutableFloatStateOf(0.5f) }
    var mapZoom by remember(trip.id) { mutableFloatStateOf(1f) }
    var mapPan by remember(trip.id) { mutableStateOf(Offset.Zero) }
    var ggZoom by remember(trip.id) { mutableFloatStateOf(1f) }
    var fullscreenChart by remember(trip.id) { mutableStateOf<String?>(null) }

    val inspectedPoint = inspectedIndex?.takeIf { it in trip.track.indices }?.let { trip.track[it] }

    fun inspectElapsedSec(elapsed: Float) {
        val idx = nearestTrackIndex(trip.track, elapsed) ?: return
        inspectedIndex = idx
        val firstT = trip.track.firstOrNull()?.elapsedSec ?: 0f
        val lastT = trip.track.lastOrNull()?.elapsedSec ?: 1f
        if (lastT > firstT) {
            timelinePanFrac = ((elapsed - firstT) / (lastT - firstT)).coerceIn(0f, 1f)
        }
    }

    fun applyTimelineZoom(z: Float) {
        timelineZoom = z.coerceIn(1f, 8f)
        inspectedPoint?.let { pt ->
            val firstT = trip.track.firstOrNull()?.elapsedSec ?: 0f
            val lastT = trip.track.lastOrNull()?.elapsedSec ?: 1f
            if (lastT > firstT) {
                timelinePanFrac = ((pt.elapsedSec - firstT) / (lastT - firstT)).coerceIn(0f, 1f)
            }
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            OutlinedButton(onClick = onBack, modifier = Modifier.testTag("trip_detail_back")) { Text("<- All Trips") }
            TextButton(onClick = onDelete) { Text("Delete", color = TelemetryColors.Red) }
        }

        TelemetryCard {
            Text("Trip Metadata, Mode & Seat Role", style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(6.dp))
            OutlinedTextField(
                value = labelDraft,
                onValueChange = { labelDraft = it },
                label = { Text("Trip Label") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )
            Spacer(Modifier.height(6.dp))
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                TransportMode.entries.forEach { mode ->
                    FilterChip(
                        selected = modeDraft == mode,
                        onClick = {
                            modeDraft = mode
                            onUpdateMeta(labelDraft, seatDraft, mode)
                        },
                        label = { Text(mode.label, fontSize = 11.sp, maxLines = 1, softWrap = false) }
                    )
                }
            }
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                listOf(
                    SeatRole.FRONT_PASSENGER to "Front Pass.",
                    SeatRole.BACKSEAT_PASSENGER to "Backseat",
                    SeatRole.DRIVER to "Driver"
                ).forEach { (role, short) ->
                    FilterChip(
                        selected = seatDraft == role,
                        onClick = {
                            seatDraft = role
                            onUpdateMeta(labelDraft, role, modeDraft)
                        },
                        label = { Text(short, fontSize = 11.sp, maxLines = 1, softWrap = false) }
                    )
                }
            }
            if (labelDraft != trip.tripLabel) {
                Spacer(Modifier.height(4.dp))
                Button(onClick = { onUpdateMeta(labelDraft, seatDraft, modeDraft) }) {
                    Text("Save Label & Sync")
                }
            }
        }

        TelemetryCard {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text("Trip Safety Score", style = MaterialTheme.typography.titleMedium)
                        TripDeviceLabel.extractDeviceTag(trip.tripLabel)?.let { tag ->
                            BadgePill(tag, deviceBadgeColor(tag))
                        }
                    }
                    Text(
                        "${"%.2f".format(trip.distanceMiles)} mi • ${formatDuration(trip.durationSec)} • Avg ${trip.avgSpeedMph.toInt()} mph",
                        style = MaterialTheme.typography.bodyMedium,
                        color = TelemetryColors.Slate
                    )
                }
                Text(
                    "${trip.score.overall}",
                    fontSize = 34.sp,
                    fontWeight = FontWeight.ExtraBold,
                    color = scoreColor(trip.score.overall)
                )
            }
            Spacer(Modifier.height(10.dp))
            ScoreBar("Braking", trip.score.braking)
            ScoreBar("Acceleration", trip.score.acceleration)
            ScoreBar("Cornering", trip.score.cornering)
            ScoreBar("Speeding", trip.score.speeding)
            ScoreBar("Distraction", trip.score.distraction)
        }

        if (trip.track.size >= 2) {
            TelemetryCard {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("Synced Chart Inspector & Zoom", style = MaterialTheme.typography.titleSmall)
                    if (inspectedIndex != null || timelineZoom > 1f || mapZoom > 1f || ggZoom > 1f) {
                        TextButton(onClick = {
                            inspectedIndex = null
                            timelineZoom = 1f
                            timelinePanFrac = 0.5f
                            mapZoom = 1f
                            mapPan = Offset.Zero
                            ggZoom = 1f
                        }) {
                            Text("Reset", fontSize = 11.sp)
                        }
                    }
                }
                Text(
                    if (inspectedPoint != null) {
                        "${formatMinSec(inspectedPoint.elapsedSec)} • ${"%.1f".format(inspectedPoint.speedMph)} mph • Long ${"%+.2fg".format(inspectedPoint.longG)} • Lat ${"%+.2fg".format(inspectedPoint.latG)} • ±${inspectedPoint.accuracyM.toInt()}m"
                    } else {
                        "Drag scrubber or tap any route/chart point to inspect all graphs in sync."
                    },
                    fontSize = 11.sp,
                    color = if (inspectedPoint != null) Color.Yellow else TelemetryColors.Slate
                )
                Slider(
                    value = (inspectedIndex ?: 0).toFloat(),
                    onValueChange = { inspectedIndex = it.roundToInt().coerceIn(0, trip.track.lastIndex) },
                    valueRange = 0f..trip.track.lastIndex.coerceAtLeast(1).toFloat(),
                    modifier = Modifier.testTag("trip_scrubber_slider")
                )
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    listOf(1f, 2f, 4f, 8f).forEach { z ->
                        FilterChip(
                            selected = kotlin.math.abs(timelineZoom - z) < 0.2f,
                            onClick = { applyTimelineZoom(z) },
                            label = { Text("${z.toInt()}x", fontSize = 11.sp, maxLines = 1, softWrap = false) },
                            modifier = Modifier.testTag("zoom_${z.toInt()}x")
                        )
                    }
                }
                if (timelineZoom > 1.01f) {
                    Slider(
                        value = timelinePanFrac,
                        onValueChange = { timelinePanFrac = it.coerceIn(0f, 1f) },
                        valueRange = 0f..1f
                    )
                }
            }
        }

        TelemetryCard {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("1. Offline Route Map (Speed + Event Pins)", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                TextButton(
                    onClick = { fullscreenChart = "map" },
                    modifier = Modifier.testTag("expand_map_btn")
                ) {
                    Text("Expand", fontSize = 11.sp)
                }
            }
            Spacer(Modifier.height(4.dp))
            ChartLegendRow(
                listOf("<20" to TelemetryColors.Cyan, "20-40" to TelemetryColors.Teal, "40-60" to TelemetryColors.Amber, "60+" to TelemetryColors.Red)
            )
            Spacer(Modifier.height(6.dp))
            RouteMapCanvas(
                track = trip.track,
                events = trip.events,
                zoomScale = mapZoom,
                panOffset = mapPan,
                inspectedPoint = inspectedPoint,
                onSelectTrackPoint = { lat, lon ->
                    val idx = trip.track.indices.minByOrNull { i ->
                        val p = trip.track[i]
                        (p.lat - lat) * (p.lat - lat) + (p.lon - lon) * (p.lon - lon)
                    }
                    if (idx != null) inspectedIndex = idx
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(210.dp)
            )
            Spacer(Modifier.height(6.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                OutlinedButton(onClick = { mapZoom = (mapZoom / 1.5f).coerceIn(1f, 8f); if (mapZoom <= 1.05f) mapPan = Offset.Zero }) { Text("-", fontSize = 12.sp) }
                OutlinedButton(onClick = { mapZoom = (mapZoom * 1.5f).coerceIn(1f, 8f) }) { Text("+", fontSize = 12.sp) }
                OutlinedButton(onClick = { mapZoom = 1f; mapPan = Offset.Zero }) { Text("Fit", fontSize = 12.sp) }
            }
        }

        TelemetryCard {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("2. Speed Profile & Event Markers", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                TextButton(
                    onClick = { fullscreenChart = "speed" },
                    modifier = Modifier.testTag("expand_speed_btn")
                ) {
                    Text("Expand", fontSize = 11.sp)
                }
            }
            Spacer(Modifier.height(6.dp))
            SpeedTimelineChart(
                track = trip.track,
                events = trip.events,
                zoomLevel = timelineZoom,
                windowCenterFrac = timelinePanFrac,
                inspectedPoint = inspectedPoint,
                onSelectElapsedSec = { inspectElapsedSec(it) },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(175.dp)
            )
        }

        TelemetryCard {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("3. Vehicle Long & Lat G Timeline", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                TextButton(onClick = { fullscreenChart = "accel" }) {
                    Text("Expand", fontSize = 11.sp)
                }
            }
            Spacer(Modifier.height(4.dp))
            ChartLegendRow(listOf("Long G" to TelemetryColors.Cyan, "Lat G" to TelemetryColors.Amber, "Threshold" to TelemetryColors.Red))
            Spacer(Modifier.height(6.dp))
            AccelTimelineChart(
                track = trip.track,
                thresholdG = thresholdG,
                zoomLevel = timelineZoom,
                windowCenterFrac = timelinePanFrac,
                inspectedPoint = inspectedPoint,
                onSelectElapsedSec = { inspectElapsedSec(it) },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(175.dp)
            )
        }

        TelemetryCard {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("4. Trip G-G Friction Circle Scatter", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                TextButton(onClick = { fullscreenChart = "gg" }) {
                    Text("Expand", fontSize = 11.sp)
                }
            }
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                listOf(1f, 2f, 3f).forEach { z ->
                    FilterChip(
                        selected = kotlin.math.abs(ggZoom - z) < 0.2f,
                        onClick = { ggZoom = z },
                        label = { Text("${z.toInt()}x", fontSize = 11.sp, maxLines = 1, softWrap = false) }
                    )
                }
            }
            Spacer(Modifier.height(6.dp))
            GGFrictionCircle(
                currentLongG = 0f,
                currentLatG = 0f,
                trailSamples = emptyList(),
                thresholdG = thresholdG,
                scatterPoints = trip.track,
                zoomScale = ggZoom,
                inspectedPoint = inspectedPoint,
                onSelectTrackIndex = { idx -> inspectedIndex = idx },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(225.dp)
            )
        }

        TelemetryCard {
            Text("5. Driving Events & 5s IMU Waveform Zoom (${trip.events.size})", style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(6.dp))
            if (trip.events.isEmpty()) {
                Text("Clean trip — zero harsh or distraction events detected.", color = TelemetryColors.Green)
            } else {
                trip.events.forEach { ev ->
                    val expanded = expandedEventId == ev.id
                    val c = eventColor(ev.type)
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 4.dp)
                            .clip(RoundedCornerShape(12.dp))
                            .background(TelemetryColors.SubCardDark)
                            .clickable {
                                expandedEventId = if (expanded) null else ev.id
                                inspectElapsedSec(ev.elapsedTripSec)
                            }
                            .padding(10.dp)
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text("${ev.type.displayName} (@${ev.elapsedTripSec.toInt()}s)", fontWeight = FontWeight.Bold, color = c, fontSize = 13.sp)
                            Text(if (expanded) "Hide Wave ▲" else "Zoom Wave ▼", style = MaterialTheme.typography.labelMedium, color = TelemetryColors.Slate)
                        }
                        Text(ev.details, style = MaterialTheme.typography.bodyMedium, color = TelemetryColors.Slate)
                        if (expanded) {
                            Spacer(Modifier.height(6.dp))
                            ChartLegendRow(listOf("Long G" to TelemetryColors.Cyan, "Lat G" to TelemetryColors.Amber))
                            Spacer(Modifier.height(4.dp))
                            EventWaveformZoomChart(
                                samples = ev.waveformSnippet,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(125.dp)
                            )
                        }
                    }
                }
            }
            if (trip.impacts.isNotEmpty()) {
                Spacer(Modifier.height(8.dp))
                Text(
                    "Stage-2 Impact Classifications (${trip.impacts.size})",
                    style = MaterialTheme.typography.labelLarge,
                    color = TelemetryColors.TextPrimary
                )
                Spacer(Modifier.height(4.dp))
                trip.impacts.forEach { imp ->
                    val col = if (imp.verdict == com.telematicslab.demo.sdk.api.ImpactVerdict.POTENTIAL_IMPACT) {
                        TelemetryColors.Red
                    } else {
                        TelemetryColors.Green
                    }
                    Text(
                        "• ${imp.verdict.label}: ${imp.reason}",
                        style = MaterialTheme.typography.bodySmall,
                        color = col
                    )
                }
            }
        }

        TelemetryCard {
            Text("Vitals, Offline Codec & Multi-Format Export", style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(8.dp))
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(IntrinsicSize.Min),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                StatPill("BATT DELTA", "-${trip.batteryDeltaPct}%", TelemetryColors.Green, Modifier.weight(1f))
                StatPill("AVG GNSS", "±${trip.avgAccuracyM.toInt()}m", TelemetryColors.Cyan, Modifier.weight(1f))
                StatPill("VERT RMS", "${"%.3f".format(trip.avgVertRmsG)}g", TelemetryColors.Purple, Modifier.weight(1f))
            }

            if (trip.rawPayloadBytes > 0) {
                Spacer(Modifier.height(8.dp))
                val savedPct = (100 - (trip.gzipPayloadBytes * 100f / trip.rawPayloadBytes)).toInt().coerceIn(0, 99)
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(IntrinsicSize.Min),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    StatPill("RAW JSON", "${"%.1f".format(trip.rawPayloadBytes / 1024f)} KB", TelemetryColors.Slate, Modifier.weight(1f))
                    StatPill("GZIP CODEC", "${"%.1f".format(trip.gzipPayloadBytes / 1024f)} KB (-$savedPct%)", TelemetryColors.Teal, Modifier.weight(1f))
                    StatPill("SYNC CHUNKS", "${trip.idempotentChunkCount} x 16KB", TelemetryColors.Cyan, Modifier.weight(1f))
                }
            }

            Spacer(Modifier.height(8.dp))
            TextButton(onClick = { showFsm = !showFsm }) {
                Text(if (showFsm) "Hide FSM Log (${trip.fsmLog.size})" else "Show FSM Transition Log (${trip.fsmLog.size})")
            }
            if (showFsm) {
                trip.fsmLog.forEach { tr ->
                    Text(
                        "${tr.from.label} -> ${tr.to.label}: ${tr.reason}",
                        style = MaterialTheme.typography.labelMedium,
                        color = TelemetryColors.Slate
                    )
                }
            }
            Spacer(Modifier.height(8.dp))
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(
                    onClick = {
                        scope.launch {
                            onExportTrip(trip, ExportFormat.CSV)?.let {
                                context.startActivity(Intent.createChooser(it, "Export Trip CSV"))
                            }
                        }
                    },
                    modifier = Modifier.weight(1f)
                ) {
                    Text("CSV", fontSize = 12.sp)
                }
                OutlinedButton(
                    onClick = {
                        scope.launch {
                            onExportTrip(trip, ExportFormat.GPX)?.let {
                                context.startActivity(Intent.createChooser(it, "Export Trip GPX"))
                            }
                        }
                    },
                    modifier = Modifier.weight(1f)
                ) {
                    Text("GPX", fontSize = 12.sp)
                }
                Button(
                    onClick = {
                        scope.launch {
                            onExportTrip(trip, ExportFormat.JSON)?.let {
                                context.startActivity(Intent.createChooser(it, "Export Trip JSON"))
                            }
                        }
                    },
                    modifier = Modifier.weight(1f)
                ) {
                    Text("JSON", fontSize = 12.sp)
                }
            }
        }
    }

    fullscreenChart?.let { kind ->
        Dialog(
            onDismissRequest = { fullscreenChart = null },
            properties = DialogProperties(usePlatformDefaultWidth = false)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .background(TelemetryColors.BgDark)
                    .padding(14.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        when (kind) {
                            "map" -> "Interactive Route Map"
                            "speed" -> "Interactive Speed Profile"
                            "accel" -> "Interactive Vehicle Long & Lat G"
                            else -> "Interactive G-G Friction Circle"
                        },
                        style = MaterialTheme.typography.titleMedium
                    )
                    Button(
                        onClick = { fullscreenChart = null },
                        modifier = Modifier.testTag("fullscreen_done_btn")
                    ) {
                        Text("Done")
                    }
                }

                inspectedPoint?.let { pt ->
                    Text(
                        "${formatMinSec(pt.elapsedSec)} • ${"%.1f".format(pt.speedMph)} mph • Long ${"%+.2fg".format(pt.longG)} • Lat ${"%+.2fg".format(pt.latG)}",
                        fontSize = 12.sp,
                        color = Color.Yellow
                    )
                }

                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f)
                        .clip(RoundedCornerShape(12.dp))
                        .background(TelemetryColors.CardDark)
                        .padding(8.dp)
                ) {
                    when (kind) {
                        "map" -> RouteMapCanvas(
                            track = trip.track,
                            events = trip.events,
                            zoomScale = mapZoom,
                            panOffset = mapPan,
                            inspectedPoint = inspectedPoint,
                            onSelectTrackPoint = { lat, lon ->
                                val idx = trip.track.indices.minByOrNull { i ->
                                    val p = trip.track[i]
                                    (p.lat - lat) * (p.lat - lat) + (p.lon - lon) * (p.lon - lon)
                                }
                                if (idx != null) inspectedIndex = idx
                            },
                            modifier = Modifier
                                .fillMaxSize()
                                .pointerInput(Unit) {
                                    detectTransformGestures { _, pan, zoom, _ ->
                                        mapZoom = (mapZoom * zoom).coerceIn(1f, 8f)
                                        mapPan = if (mapZoom <= 1.05f) Offset.Zero else mapPan + pan
                                    }
                                }
                        )
                        "speed" -> SpeedTimelineChart(
                            track = trip.track,
                            events = trip.events,
                            zoomLevel = timelineZoom,
                            windowCenterFrac = timelinePanFrac,
                            inspectedPoint = inspectedPoint,
                            onSelectElapsedSec = { inspectElapsedSec(it) },
                            modifier = Modifier
                                .fillMaxSize()
                                .pointerInput(Unit) {
                                    detectTransformGestures { _, pan, zoom, _ ->
                                        if (zoom != 1f) applyTimelineZoom(timelineZoom * zoom)
                                        if (timelineZoom > 1.05f && size.width > 0) {
                                            val deltaFrac = -pan.x / size.width.toFloat() / timelineZoom
                                            timelinePanFrac = (timelinePanFrac + deltaFrac).coerceIn(0f, 1f)
                                        }
                                    }
                                }
                        )
                        "accel" -> AccelTimelineChart(
                            track = trip.track,
                            thresholdG = thresholdG,
                            zoomLevel = timelineZoom,
                            windowCenterFrac = timelinePanFrac,
                            inspectedPoint = inspectedPoint,
                            onSelectElapsedSec = { inspectElapsedSec(it) },
                            modifier = Modifier
                                .fillMaxSize()
                                .pointerInput(Unit) {
                                    detectTransformGestures { _, pan, zoom, _ ->
                                        if (zoom != 1f) applyTimelineZoom(timelineZoom * zoom)
                                        if (timelineZoom > 1.05f && size.width > 0) {
                                            val deltaFrac = -pan.x / size.width.toFloat() / timelineZoom
                                            timelinePanFrac = (timelinePanFrac + deltaFrac).coerceIn(0f, 1f)
                                        }
                                    }
                                }
                        )
                        else -> GGFrictionCircle(
                            currentLongG = 0f,
                            currentLatG = 0f,
                            trailSamples = emptyList(),
                            thresholdG = thresholdG,
                            scatterPoints = trip.track,
                            zoomScale = ggZoom,
                            inspectedPoint = inspectedPoint,
                            onSelectTrackIndex = { idx -> inspectedIndex = idx },
                            modifier = Modifier
                                .fillMaxSize()
                                .pointerInput(Unit) {
                                    detectTransformGestures { _, _, zoom, _ ->
                                        ggZoom = (ggZoom * zoom).coerceIn(1f, 3.5f)
                                    }
                                }
                        )
                    }
                }

                if (trip.track.size >= 2) {
                    Slider(
                        value = (inspectedIndex ?: 0).toFloat(),
                        onValueChange = { inspectedIndex = it.roundToInt().coerceIn(0, trip.track.lastIndex) },
                        valueRange = 0f..trip.track.lastIndex.coerceAtLeast(1).toFloat()
                    )
                }
            }
        }
    }
}

@Composable
private fun deviceBadgeColor(tag: String): androidx.compose.ui.graphics.Color {
    val upper = tag.uppercase(Locale.ROOT)
    return when {
        upper.contains("S23") -> TelemetryColors.Purple
        upper.contains("S20") -> TelemetryColors.Cyan
        upper.contains("IPHONE") || upper.contains("IPAD") -> TelemetryColors.Teal
        else -> TelemetryColors.Teal
    }
}

@Composable
private fun BadgePill(text: String, color: androidx.compose.ui.graphics.Color) {
    Text(
        text,
        fontSize = 10.sp,
        fontWeight = FontWeight.Bold,
        color = color,
        maxLines = 1,
        softWrap = false,
        overflow = TextOverflow.Ellipsis,
        modifier = Modifier
            .clip(RoundedCornerShape(4.dp))
            .background(color.copy(alpha = 0.16f))
            .padding(horizontal = 7.dp, vertical = 3.dp)
    )
}

@Composable
private fun ScoreBar(label: String, value: Int) {
    Column(modifier = Modifier.padding(vertical = 3.dp)) {
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(label, style = MaterialTheme.typography.bodyMedium)
            Text("$value / 100", style = MaterialTheme.typography.labelMedium, color = scoreColor(value))
        }
        Spacer(Modifier.height(3.dp))
        LinearProgressIndicator(
            progress = { (value / 100f).coerceIn(0f, 1f) },
            modifier = Modifier
                .fillMaxWidth()
                .height(6.dp)
                .clip(RoundedCornerShape(999.dp)),
            color = scoreColor(value),
            trackColor = TelemetryColors.SubCardDark
        )
    }
}

@Composable
private fun HealthCheckRow(
    label: String,
    detail: String,
    ok: Boolean,
    onFix: (() -> Unit)? = null
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 3.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(TelemetryColors.SubCardDark)
            .padding(horizontal = 10.dp, vertical = 7.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(label, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = TelemetryColors.TextPrimary)
            Text(detail, style = MaterialTheme.typography.labelMedium, color = TelemetryColors.Slate)
        }
        if (!ok && onFix != null) {
            OutlinedButton(onClick = onFix) {
                Text("Fix", fontSize = 11.sp)
            }
        } else {
            BadgePill(
                if (ok) "OK" else "WAIT",
                if (ok) TelemetryColors.Green else TelemetryColors.Amber
            )
        }
    }
}
