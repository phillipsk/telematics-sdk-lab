package com.telematicslab.demo.ui.screens

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
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
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.telematicslab.demo.sdk.api.AutoStopMode
import com.telematicslab.demo.sdk.api.LiveTelemetryState
import com.telematicslab.demo.sdk.api.SdkConfig
import com.telematicslab.demo.sdk.api.SeatRole
import com.telematicslab.demo.sdk.api.TransportMode
import com.telematicslab.demo.sdk.api.TripState
import com.telematicslab.demo.ui.components.ChartLegendRow
import com.telematicslab.demo.ui.components.GGFrictionCircle
import com.telematicslab.demo.ui.components.StatPill
import com.telematicslab.demo.ui.components.TelemetryCard
import com.telematicslab.demo.ui.theme.TelemetryColors
import com.telematicslab.demo.ui.theme.eventColor
import com.telematicslab.demo.ui.theme.scoreColor

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun DriveScreen(
    state: LiveTelemetryState,
    onRequestForegroundPermissions: () -> Unit,
    onRequestBackgroundLocation: () -> Unit,
    onRequestBatteryUnrestricted: () -> Unit,
    onUpdateConfig: ((SdkConfig) -> SdkConfig) -> Unit,
    onStartTrip: () -> Unit,
    onStopTrip: () -> Unit,
    onToggleAutoDetect: (Boolean) -> Unit,
    onToggleMotionPrompts: (Boolean) -> Unit,
    onRefreshRemoteConfig: () -> Unit,
    onRunSimulatedDrive: () -> Unit
) {
    val isRecording = state.tripState == TripState.RECORDING ||
        state.tripState == TripState.VERIFYING ||
        state.tripState == TripState.STOP_PENDING

    PullToRefreshBox(
        isRefreshing = state.diagnostics.isRefreshingConfig,
        onRefresh = onRefreshRemoteConfig,
        modifier = Modifier.fillMaxSize()
    ) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        if (!state.permissions.allCarReady) {
            TelemetryCard {
                Text(
                    "Permissions & Battery Setup",
                    style = MaterialTheme.typography.titleMedium,
                    color = TelemetryColors.Amber
                )
                Spacer(Modifier.height(6.dp))
                ReadinessRow(
                    label = "Fine Location + Notifications",
                    ready = state.permissions.fineLocationGranted && state.permissions.postNotificationsGranted,
                    actionLabel = "Grant",
                    onAction = onRequestForegroundPermissions
                )
                ReadinessRow(
                    label = "Physical Activity (Car/Bike Detection)",
                    ready = state.permissions.activityRecognitionGranted,
                    actionLabel = "Grant",
                    onAction = onRequestForegroundPermissions
                )
                ReadinessRow(
                    label = "Background Location (All Time)",
                    ready = state.permissions.backgroundLocationGranted,
                    actionLabel = "Allow",
                    onAction = onRequestBackgroundLocation
                )
                ReadinessRow(
                    label = "Battery Unrestricted (No Doze Kill)",
                    ready = state.permissions.ignoringBatteryOptimizations,
                    actionLabel = "Allow",
                    onAction = onRequestBatteryUnrestricted
                )
            }
        }

        TelemetryCard {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column {
                    Text("SPEED", style = MaterialTheme.typography.labelMedium, color = TelemetryColors.Slate)
                    Row(verticalAlignment = Alignment.Bottom) {
                        Text(
                            "${state.gnss.speedMph.toInt()}",
                            fontSize = 52.sp,
                            fontWeight = FontWeight.ExtraBold,
                            color = TelemetryColors.TextPrimary
                        )
                        Spacer(Modifier.width(6.dp))
                        Text(
                            "MPH",
                            fontSize = 15.sp,
                            fontWeight = FontWeight.Bold,
                            color = TelemetryColors.Cyan,
                            modifier = Modifier.padding(bottom = 10.dp)
                        )
                    }
                }

                Column(horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    val badgeColor = when (state.tripState) {
                        TripState.RECORDING -> TelemetryColors.Green
                        TripState.VERIFYING, TripState.STOP_PENDING -> TelemetryColors.Amber
                        TripState.FINALIZING -> TelemetryColors.Cyan
                        TripState.IDLE -> TelemetryColors.Slate
                    }
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(999.dp))
                            .background(badgeColor.copy(alpha = 0.16f))
                            .border(1.dp, badgeColor, RoundedCornerShape(999.dp))
                            .padding(horizontal = 12.dp, vertical = 5.dp)
                    ) {
                        val text = if (state.tripState == TripState.STOP_PENDING) {
                            "STOP PENDING (${state.stopPendingRemainingSec}s)"
                        } else if (state.isSimulatingTrip) {
                            "SIMULATING DRIVE"
                        } else {
                            state.tripState.label
                        }
                        Text(text, fontSize = 12.sp, fontWeight = FontWeight.Bold, color = badgeColor)
                    }

                    Text(
                        "Score: ${state.liveScore.overall}",
                        fontSize = 15.sp,
                        fontWeight = FontWeight.Bold,
                        color = scoreColor(state.liveScore.overall)
                    )
                }
            }

            Spacer(Modifier.height(10.dp))
            Row(modifier = Modifier.fillMaxWidth().height(IntrinsicSize.Min), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                StatPill("DISTANCE", "${"%.2f".format(state.tripDistanceMiles)} mi", TelemetryColors.Cyan, Modifier.weight(1f))
                StatPill("DURATION", formatDuration(state.tripElapsedSec), TelemetryColors.Teal, Modifier.weight(1f))
                StatPill("MAX SPD", "${state.maxSpeedMph.toInt()} mph", TelemetryColors.Amber, Modifier.weight(1f))
            }

            Spacer(Modifier.height(10.dp))
            Text(
                "Active Profile: ${state.config.activeTripLabel} (${state.config.activeTransportMode.label})",
                style = MaterialTheme.typography.labelMedium,
                color = TelemetryColors.Teal
            )
            Spacer(Modifier.height(4.dp))
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                TransportMode.entries.forEach { mode ->
                    FilterChip(
                        selected = state.config.activeTransportMode == mode,
                        onClick = { onUpdateConfig { it.copy(activeTransportMode = mode) } },
                        label = { Text(mode.label, fontSize = 11.sp, maxLines = 1, softWrap = false) }
                    )
                }
            }

            Text("Seat Role (editable before or after trip)", style = MaterialTheme.typography.labelMedium, color = TelemetryColors.Slate)
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
                        selected = state.config.defaultSeatRole == role,
                        onClick = { onUpdateConfig { it.copy(defaultSeatRole = role) } },
                        label = { Text(short, fontSize = 11.sp, maxLines = 1, softWrap = false) }
                    )
                }
            }

            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                listOf("Outbound Drive", "Return Drive", "Test Drive").forEach { lbl ->
                    FilterChip(
                        selected = state.config.activeTripLabel == lbl,
                        onClick = { onUpdateConfig { it.copy(activeTripLabel = lbl) } },
                        label = { Text(lbl, fontSize = 10.sp, maxLines = 1, softWrap = false) }
                    )
                }
            }

            Spacer(Modifier.height(8.dp))
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Button(
                    onClick = if (isRecording) onStopTrip else onStartTrip,
                    enabled = !state.isSimulatingTrip,
                    modifier = Modifier.weight(1f).height(48.dp).testTag("drive_start_btn"),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = if (isRecording) TelemetryColors.Red else TelemetryColors.Green,
                        contentColor = TelemetryColors.OnAccent
                    )
                ) {
                    Text(if (isRecording) "Stop Trip" else "Start Trip", fontWeight = FontWeight.Bold, maxLines = 1, softWrap = false)
                }
                OutlinedButton(
                    onClick = onRunSimulatedDrive,
                    enabled = !state.isSimulatingTrip && !isRecording,
                    modifier = Modifier.weight(1f).height(48.dp).testTag("drive_demo_btn")
                ) {
                    Text("34s Demo", fontWeight = FontWeight.SemiBold, maxLines = 1, softWrap = false)
                }
            }

            Spacer(Modifier.height(8.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text("Passive Auto-Detect (FGS + FSM)", fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
                    Text(
                        if (state.config.autoStopMode == AutoStopMode.MANUAL_ONLY) {
                            "Starts >= ${state.config.autoStartVerifyMph.toInt()} mph • Manual Stop Only • Config v${state.diagnostics.remoteConfigVersion}"
                        } else {
                            "Starts >= ${state.config.autoStartVerifyMph.toInt()} mph • Auto-stops after ${state.config.stopHysteresisSec}s • Config v${state.diagnostics.remoteConfigVersion}"
                        },
                        style = MaterialTheme.typography.labelMedium,
                        color = TelemetryColors.Slate
                    )
                }
                Switch(
                    checked = state.config.autoDetectEnabled,
                    onCheckedChange = onToggleAutoDetect
                )
            }

            Spacer(Modifier.height(6.dp))
            Text("Auto-Stop After Parked", fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                AutoStopMode.entries.forEach { mode ->
                    FilterChip(
                        selected = state.config.autoStopMode == mode,
                        onClick = {
                            onUpdateConfig {
                                it.copy(
                                    autoStopMode = mode,
                                    stopHysteresisSec = if (mode == AutoStopMode.MANUAL_ONLY) 0 else mode.hysteresisSec
                                )
                            }
                        },
                        label = { Text(mode.label, fontSize = 11.sp, maxLines = 1, softWrap = false) }
                    )
                }
            }

            Spacer(Modifier.height(6.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text("Voice Cues (Start/Stop Only)", fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
                    Text(
                        "Hands-free spoken status on trip start & stop — zero mid-drive alerts",
                        style = MaterialTheme.typography.labelMedium,
                        color = TelemetryColors.Slate
                    )
                }
                Switch(
                    checked = state.config.voiceCuesEnabled,
                    onCheckedChange = { enabled -> onUpdateConfig { it.copy(voiceCuesEnabled = enabled) } }
                )
            }

            Spacer(Modifier.height(8.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text("Vehicle & Bike Motion Prompts", fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
                    Text(
                        if (state.diagnostics.motionPromptsActive) {
                            "Active (Car / Bike) • Last: ${state.diagnostics.lastMotionSignal}"
                        } else {
                            "Senses Car / Bike via Play Services & cross-checks planned dates"
                        },
                        style = MaterialTheme.typography.labelMedium,
                        color = TelemetryColors.Slate
                    )
                }
                Switch(
                    checked = state.config.motionPromptsEnabled,
                    onCheckedChange = onToggleMotionPrompts
                )
            }
        }

        TelemetryCard {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("Live Vehicle G-G Friction Circle", style = MaterialTheme.typography.titleMedium)
                Text(
                    if (state.frame.headingLocked) "GPS Heading ${state.frame.headingDeg.toInt()}°" else "Compass ${state.frame.azimuthDeg.toInt()}°",
                    style = MaterialTheme.typography.labelMedium,
                    color = if (state.frame.headingLocked) TelemetryColors.Green else TelemetryColors.Amber
                )
            }
            Spacer(Modifier.height(6.dp))
            GGFrictionCircle(
                currentLongG = state.frame.filteredLongG,
                currentLatG = state.frame.filteredLatG,
                trailSamples = state.liveWaveform,
                thresholdG = state.config.hardBrakeG,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(230.dp)
            )
            Spacer(Modifier.height(6.dp))
            Row(modifier = Modifier.fillMaxWidth().height(IntrinsicSize.Min), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                StatPill("LONG G", "%+.2fg".format(state.frame.filteredLongG), TelemetryColors.Cyan, Modifier.weight(1f))
                StatPill("LAT G", "%+.2fg".format(state.frame.filteredLatG), TelemetryColors.Amber, Modifier.weight(1f))
                StatPill("VERT G", "%+.2fg".format(state.frame.filteredVertG), TelemetryColors.Purple, Modifier.weight(1f))
                StatPill("TOTAL", "%.2fg".format(state.frame.rawTotalG), TelemetryColors.Teal, Modifier.weight(1f))
            }
        }

        TelemetryCard {
            Text("GNSS Lock & Distraction State", style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(8.dp))
            Row(modifier = Modifier.fillMaxWidth().height(IntrinsicSize.Min), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                StatPill(
                    "SATELLITES",
                    "${state.gnss.satellitesUsed}/${state.gnss.satellitesVisible}",
                    if (state.gnss.satellitesUsed >= 4) TelemetryColors.Green else TelemetryColors.Amber,
                    Modifier.weight(1f)
                )
                StatPill(
                    "ACCURACY",
                    if (state.gnss.hasFix) "±${state.gnss.accuracyMeters.toInt()}m" else "No Fix",
                    if (state.gnss.hasFix && state.gnss.accuracyMeters < 15f) TelemetryColors.Green else TelemetryColors.Amber,
                    Modifier.weight(1f)
                )
                StatPill(
                    "AVG CN0",
                    if (state.gnss.avgCn0DbHz > 0f) "${"%.1f".format(state.gnss.avgCn0DbHz)} dB" else "--",
                    TelemetryColors.Cyan,
                    Modifier.weight(1f)
                )
            }
            Spacer(Modifier.height(8.dp))
            Row(modifier = Modifier.fillMaxWidth().height(IntrinsicSize.Min), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                StatPill(
                    "MOUNT STATE",
                    if (state.isHandheld) "Handheld" else "Mounted",
                    if (state.isHandheld) TelemetryColors.Purple else TelemetryColors.Green,
                    Modifier.weight(1f)
                )
                StatPill(
                    "SCREEN",
                    if (state.screenOn) "Interactive" else "Off",
                    if (state.screenOn) TelemetryColors.Amber else TelemetryColors.Green,
                    Modifier.weight(1f)
                )
                StatPill(
                    "IMU RATE",
                    "${state.power.imuSampleHz.toInt()} Hz",
                    TelemetryColors.Teal,
                    Modifier.weight(1f)
                )
            }
        }

        TelemetryCard {
            Text("Live Detected Events (${state.liveEvents.size})", style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(4.dp))
            ChartLegendRow(listOf("Brake" to TelemetryColors.Red, "Accel" to TelemetryColors.Amber, "Turn" to TelemetryColors.Cyan))
            Spacer(Modifier.height(8.dp))
            if (state.liveEvents.isEmpty()) {
                Text(
                    "No harsh events yet. Start a trip or run the 34s Demo Drive to see real-time detection.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = TelemetryColors.Slate
                )
            } else {
                state.liveEvents.take(8).forEach { ev ->
                    val c = eventColor(ev.type)
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 4.dp)
                            .clip(RoundedCornerShape(10.dp))
                            .background(TelemetryColors.SubCardDark)
                            .padding(10.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Box(
                            modifier = Modifier
                                .size(10.dp)
                                .clip(CircleShape)
                                .background(c)
                        )
                        Spacer(Modifier.width(10.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(ev.type.displayName, fontWeight = FontWeight.Bold, fontSize = 13.sp, color = c)
                            Text(ev.details, style = MaterialTheme.typography.bodyMedium, color = TelemetryColors.Slate)
                        }
                        Text(
                            "@${ev.elapsedTripSec.toInt()}s",
                            style = MaterialTheme.typography.labelMedium,
                            color = TelemetryColors.Slate
                        )
                    }
                }
            }
        }

        if (state.config.cloudUnlockUrl.isNotBlank()) {
            val context = LocalContext.current
            TelemetryCard {
                Text("Web Workbench (One-Click Link)", style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.height(4.dp))
                Text(
                    "Open or share your live cloud dashboard with one-click passcode unlock:",
                    style = MaterialTheme.typography.labelMedium,
                    color = TelemetryColors.Slate
                )
                Spacer(Modifier.height(6.dp))
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(8.dp))
                        .background(TelemetryColors.SubCardDark)
                        .padding(10.dp)
                ) {
                    Text(
                        state.config.cloudUnlockUrl,
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
                            val openIntent = Intent(Intent.ACTION_VIEW, Uri.parse(state.config.cloudUnlockUrl))
                            runCatching { context.startActivity(openIntent) }
                        },
                        modifier = Modifier.weight(1f)
                    ) {
                        Text("Open", fontSize = 12.sp, fontWeight = FontWeight.Bold)
                    }
                    OutlinedButton(
                        onClick = {
                            val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                            cm.setPrimaryClip(ClipData.newPlainText("Workbench URL", state.config.cloudUnlockUrl))
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
                                    putExtra(Intent.EXTRA_TEXT, state.config.cloudUnlockUrl)
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
        }
    }
    }
}

fun formatDuration(sec: Int): String {
    val m = sec / 60
    val s = sec % 60
    return "%02d:%02d".format(m, s)
}

@Composable
private fun ReadinessRow(
    label: String,
    ready: Boolean,
    actionLabel: String,
    onAction: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 3.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            if (ready) "OK  $label" else "FIX $label",
            fontSize = 12.sp,
            fontWeight = FontWeight.SemiBold,
            color = if (ready) TelemetryColors.Green else TelemetryColors.TextPrimary,
            modifier = Modifier.weight(1f)
        )
        if (!ready) {
            OutlinedButton(onClick = onAction) {
                Text(actionLabel, fontSize = 11.sp)
            }
        }
    }
}
