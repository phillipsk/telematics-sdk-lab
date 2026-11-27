package com.telematicslab.demo.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.telematicslab.demo.sdk.api.FilterEngineMode
import com.telematicslab.demo.sdk.api.ImpactVerdict
import com.telematicslab.demo.sdk.api.LiveTelemetryState
import com.telematicslab.demo.sdk.api.SdkConfig
import com.telematicslab.demo.ui.components.ChartLegendRow
import com.telematicslab.demo.ui.components.LiveWaveformCanvas
import com.telematicslab.demo.ui.components.StatPill
import com.telematicslab.demo.ui.components.TelemetryCard
import com.telematicslab.demo.ui.components.WaveformMode
import com.telematicslab.demo.ui.theme.TelemetryColors

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun SensorsLabScreen(
    state: LiveTelemetryState,
    onUpdateConfig: ((SdkConfig) -> SdkConfig) -> Unit,
    onTestPhoneDrop: () -> Unit,
    onTestCrashImpact: () -> Unit,
    onTestWakeLockGate: () -> Unit
) {
    var waveMode by rememberSaveable { mutableStateOf(WaveformMode.VEHICLE) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        // 1. Live Oscilloscope & Dual-Engine Bridge
        TelemetryCard {
            Text("Live IMU Oscilloscope & Filter Engine Bridge", style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(6.dp))
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                WaveformMode.entries.forEach { mode ->
                    FilterChip(
                        selected = waveMode == mode,
                        onClick = { waveMode = mode },
                        label = { Text(mode.label, fontSize = 11.sp, maxLines = 1, softWrap = false) }
                    )
                }
            }

            Spacer(Modifier.height(4.dp))
            val legend = when (waveMode) {
                WaveformMode.VEHICLE -> listOf("Long G" to TelemetryColors.Cyan, "Lat G" to TelemetryColors.Amber, "Vert G" to TelemetryColors.Purple)
                WaveformMode.RAW_PHONE -> listOf("Phone X" to TelemetryColors.Red, "Phone Y" to TelemetryColors.Green, "Phone Z" to TelemetryColors.Cyan)
                WaveformMode.GYRO -> listOf("Wx" to TelemetryColors.Red, "Wy" to TelemetryColors.Green, "Wz" to TelemetryColors.Amber)
                WaveformMode.FILTER_COMPARE -> listOf("Raw Long G" to TelemetryColors.Slate, "Filtered" to TelemetryColors.Cyan)
            }
            ChartLegendRow(legend)
            Spacer(Modifier.height(6.dp))
            LiveWaveformCanvas(
                samples = state.liveWaveform,
                mode = waveMode,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(175.dp)
            )
            Spacer(Modifier.height(8.dp))

            Text("Motion Filter Backend (Bridge Pattern)", style = MaterialTheme.typography.labelMedium, color = TelemetryColors.Slate)
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                FilterEngineMode.entries.forEach { engine ->
                    FilterChip(
                        selected = state.config.filterEngineMode == engine,
                        onClick = { onUpdateConfig { it.copy(filterEngineMode = engine) } },
                        label = { Text(engine.label, fontSize = 11.sp, maxLines = 1, softWrap = false) }
                    )
                }
            }
            Text(
                state.config.filterEngineMode.description + " | α = ${"%.2f".format(state.config.lowPassAlpha)}",
                style = MaterialTheme.typography.labelMedium,
                color = TelemetryColors.Teal
            )
            Slider(
                value = state.config.lowPassAlpha,
                onValueChange = { a -> onUpdateConfig { it.copy(lowPassAlpha = a) } },
                valueRange = 0.08f..0.80f
            )
        }

        // 2. Coordinate Frame Inspector
        TelemetryCard {
            Text("Coordinate Frame Rotation (Phone -> ENU -> Car)", style = MaterialTheme.typography.titleMedium)
            Text(
                "Tilt or rotate your phone: Raw Phone X/Y/Z changes, while rotated Vehicle Long/Lat/Vert stays invariant.",
                style = MaterialTheme.typography.bodyMedium,
                color = TelemetryColors.Slate
            )
            Spacer(Modifier.height(8.dp))
            Row(modifier = Modifier.fillMaxWidth().height(IntrinsicSize.Min), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                StatPill("PITCH", "${state.frame.pitchDeg.toInt()}°", TelemetryColors.Cyan, Modifier.weight(1f))
                StatPill("ROLL", "${state.frame.rollDeg.toInt()}°", TelemetryColors.Teal, Modifier.weight(1f))
                StatPill("AZIMUTH", "${state.frame.azimuthDeg.toInt()}°", TelemetryColors.Amber, Modifier.weight(1f))
            }
            Spacer(Modifier.height(8.dp))
            Row(modifier = Modifier.fillMaxWidth().height(IntrinsicSize.Min), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                StatPill("PHONE LIN X", "%+.2f".format(state.frame.phoneLinearX), TelemetryColors.Red, Modifier.weight(1f))
                StatPill("PHONE LIN Y", "%+.2f".format(state.frame.phoneLinearY), TelemetryColors.Green, Modifier.weight(1f))
                StatPill("PHONE LIN Z", "%+.2f".format(state.frame.phoneLinearZ), TelemetryColors.Cyan, Modifier.weight(1f))
            }
            Spacer(Modifier.height(8.dp))
            Row(modifier = Modifier.fillMaxWidth().height(IntrinsicSize.Min), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                StatPill("CAR LONG G", "%+.2fg".format(state.frame.filteredLongG), TelemetryColors.Cyan, Modifier.weight(1f))
                StatPill("CAR LAT G", "%+.2fg".format(state.frame.filteredLatG), TelemetryColors.Amber, Modifier.weight(1f))
                StatPill("CAR VERT G", "%+.2fg".format(state.frame.filteredVertG), TelemetryColors.Purple, Modifier.weight(1f))
            }
        }

        // 3. 2-Stage Impact vs Phone Drop + Ring Buffer
        TelemetryCard {
            Text("2-Stage Impact Classifier & Zero-Alloc Ring Buffer", style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(6.dp))
            Row(modifier = Modifier.fillMaxWidth().height(IntrinsicSize.Min), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                StatPill("RING BUFFER", "${state.power.ringBufferFill}/${state.power.ringBufferCapacity}", TelemetryColors.Teal, Modifier.weight(1f))
                StatPill("STAGE 1 TRIG", "${"%.1f".format(state.config.impactThresholdG)}g", TelemetryColors.Amber, Modifier.weight(1f))
                StatPill("GYRO MAG", "${"%.2f".format(state.gyroMagRadS)} r/s", TelemetryColors.Purple, Modifier.weight(1f))
            }
            Spacer(Modifier.height(8.dp))
            Slider(
                value = state.config.impactThresholdG,
                onValueChange = { th -> onUpdateConfig { it.copy(impactThresholdG = th) } },
                valueRange = 2.0f..4.5f
            )
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedButton(onClick = onTestPhoneDrop, modifier = Modifier.weight(1f)) {
                    Text("Test Phone Drop", fontSize = 12.sp)
                }
                Button(
                    onClick = onTestCrashImpact,
                    modifier = Modifier.weight(1f),
                    colors = ButtonDefaults.buttonColors(containerColor = TelemetryColors.Red, contentColor = TelemetryColors.OnAccent)
                ) {
                    Text("Test Crash Δv", fontSize = 12.sp, fontWeight = FontWeight.Bold)
                }
            }

            if (state.liveImpacts.isNotEmpty()) {
                Spacer(Modifier.height(8.dp))
                state.liveImpacts.take(4).forEach { imp ->
                    val accepted = imp.verdict == ImpactVerdict.POTENTIAL_IMPACT
                    val col = if (accepted) TelemetryColors.Red else TelemetryColors.Green
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 3.dp)
                            .clip(RoundedCornerShape(10.dp))
                            .background(TelemetryColors.SubCardDark)
                            .padding(10.dp)
                    ) {
                        Text(imp.verdict.label, fontWeight = FontWeight.Bold, fontSize = 12.sp, color = col)
                        Text(imp.reason, style = MaterialTheme.typography.bodyMedium, color = TelemetryColors.Slate)
                    }
                }
            }
        }

        // 4. Power, FIFO & Remote-Config WakeLock Gating
        TelemetryCard {
            Text("Power, FIFO & Remote-Config WakeLock Policy", style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(8.dp))
            Text("Hardware FIFO Batch Latency (maxReportLatencyUs)", style = MaterialTheme.typography.labelMedium, color = TelemetryColors.Slate)
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                listOf(0 to "0ms Live", 500 to "500ms", 2000 to "2000ms").forEach { (ms, label) ->
                    FilterChip(
                        selected = state.config.fifoBatchLatencyMs == ms,
                        onClick = { onUpdateConfig { it.copy(fifoBatchLatencyMs = ms) } },
                        label = { Text(label, fontSize = 11.sp, maxLines = 1, softWrap = false) }
                    )
                }
            }

            Spacer(Modifier.height(6.dp))
            val bucketShort = state.power.standbyBucketName.substringBefore("/")
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(IntrinsicSize.Min),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                StatPill(
                    "BATTERY",
                    "${state.power.batteryPct}% · ${state.power.batteryTempC.toInt()}°C",
                    TelemetryColors.Green,
                    Modifier.weight(1f).testTag("pill_battery")
                )
                StatPill(
                    "STANDBY",
                    bucketShort,
                    TelemetryColors.Cyan,
                    Modifier.weight(1f).testTag("pill_standby")
                )
                StatPill(
                    "DOZE EXEMPT",
                    if (state.power.ignoringBatteryOptimizations) "YES" else "NO",
                    TelemetryColors.Amber,
                    Modifier.weight(1f).testTag("pill_doze")
                )
            }
            Spacer(Modifier.height(8.dp))
            Row(modifier = Modifier.fillMaxWidth().height(IntrinsicSize.Min), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                StatPill("DELIVERY", "${state.power.avgBatchIntervalMs.toInt()}ms", TelemetryColors.Teal, Modifier.weight(1f))
                StatPill("FGS UPTIME", "${state.power.fgsUptimeSec}s", TelemetryColors.Cyan, Modifier.weight(1f))
                StatPill(
                    "MANUAL LOCK",
                    "${state.power.manualWakeLockHeldMs / 1000L}s",
                    if (state.power.manualWakeLockHeldMs == 0L) TelemetryColors.Green else TelemetryColors.Red,
                    Modifier.weight(1f)
                )
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text("Simulate Screen-Off (Pocket / Doze Demo)", fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
                    Text(
                        "Visualizes hardware FIFO sensor batching, CPU wakeups/min & WakeLock gating while you screen-record.",
                        style = MaterialTheme.typography.labelMedium,
                        color = TelemetryColors.Slate
                    )
                }
                Switch(
                    checked = state.config.simulatedScreenOff,
                    onCheckedChange = { off -> onUpdateConfig { it.copy(simulatedScreenOff = off) } }
                )
            }

            if (state.config.simulatedScreenOff) {
                Spacer(Modifier.height(8.dp))
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(10.dp))
                        .background(TelemetryColors.SubCardDark)
                        .padding(10.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Text(
                        "SIMULATED SCREEN-OFF ACTIVE • FGS + SENSOR HUB FIFO",
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold,
                        color = TelemetryColors.Amber
                    )
                    val batchProg = if (state.power.fifoBatchCapacity > 0) {
                        (state.power.fifoBufferedSamples.toFloat() / state.power.fifoBatchCapacity.toFloat()).coerceIn(0f, 1f)
                    } else 1f
                    Text(
                        "Sensor Hub FIFO Fill: ${state.power.fifoBufferedSamples}/${state.power.fifoBatchCapacity} samples • Batch Flushes: ${state.power.fifoBatchFlushes}",
                        style = MaterialTheme.typography.labelMedium,
                        color = TelemetryColors.Cyan
                    )
                    LinearProgressIndicator(
                        progress = { batchProg },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(6.dp)
                            .clip(RoundedCornerShape(999.dp)),
                        color = TelemetryColors.Cyan,
                        trackColor = TelemetryColors.CardDark
                    )
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        StatPill("WAKEUPS/MIN", "${state.power.estimatedCpuWakeupsPerMin}", TelemetryColors.Teal, Modifier.weight(1f))
                        StatPill(
                            "VITALS RISK",
                            if (state.config.legacyWakeLockEnabled) "AT RISK" else "SAFE",
                            if (state.config.legacyWakeLockEnabled) TelemetryColors.Red else TelemetryColors.Green,
                            Modifier.weight(1f)
                        )
                        StatPill("SCREEN", "SIM-OFF", TelemetryColors.Amber, Modifier.weight(1f))
                    }
                    OutlinedButton(
                        onClick = onTestWakeLockGate,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("Test Event WakeLock Gate (Brake 0ms vs Impact 15s)", fontSize = 11.sp)
                    }
                }
            }

            Spacer(Modifier.height(10.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text("Remote-Config Legacy WakeLock Override", fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
                    Text(
                        "Default OFF via state denylist + event-sink decorator. FGS + system location keep sensing alive without continuous WakeLocks.",
                        style = MaterialTheme.typography.labelMedium,
                        color = TelemetryColors.Slate
                    )
                }
                Switch(
                    checked = state.config.legacyWakeLockEnabled,
                    onCheckedChange = { enabled -> onUpdateConfig { it.copy(legacyWakeLockEnabled = enabled) } }
                )
            }

            if (state.diagnostics.wakeLockDecisionLog.isNotEmpty()) {
                Spacer(Modifier.height(6.dp))
                state.diagnostics.wakeLockDecisionLog.takeLast(3).forEach { dec ->
                    Text(
                        "Policy: $dec",
                        style = MaterialTheme.typography.labelMedium,
                        color = TelemetryColors.Teal
                    )
                }
            }
        }

        // 5. SDK Runtime Diagnostics (Startup, Crash Boundary, Exit Info, Quarantine)
        TelemetryCard {
            Text("SDK Runtime Architecture & Forensics", style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(8.dp))
            Row(modifier = Modifier.fillMaxWidth().height(IntrinsicSize.Min), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                StatPill("INIT TIME", "${state.diagnostics.startupInitMs} ms", TelemetryColors.Green, Modifier.weight(1f))
                StatPill("LAST EXIT", state.diagnostics.lastExitReason, TelemetryColors.Cyan, Modifier.weight(1f))
                StatPill("TRIM MEM", "${state.diagnostics.trimMemoryEvents} (${state.diagnostics.lastTrimLevel})", TelemetryColors.Amber, Modifier.weight(1f))
            }
            Spacer(Modifier.height(8.dp))
            Row(modifier = Modifier.fillMaxWidth().height(IntrinsicSize.Min), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                StatPill("CRASH GUARD", "${state.diagnostics.caughtBoundaryFaults} caught", TelemetryColors.Green, Modifier.weight(1f))
                StatPill("QUARANTINE", "${state.diagnostics.quarantinedFiles} files", TelemetryColors.Teal, Modifier.weight(1f))
                StatPill("FGS STARTS", "${state.diagnostics.fgsStartCount} (${state.diagnostics.fgsDeniedCount} err)", TelemetryColors.Cyan, Modifier.weight(1f))
            }
            Spacer(Modifier.height(6.dp))
            Text(
                "App Startup Kickoffs: ${state.diagnostics.activeInitializers.joinToString(" -> ").ifBlank { "Lazy init" }}",
                style = MaterialTheme.typography.labelMedium,
                color = TelemetryColors.Slate
            )
        }
    }
}
