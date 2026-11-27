package com.telematicslab.demo.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.telematicslab.demo.sdk.api.LiveTelemetryState
import com.telematicslab.demo.sdk.api.SyncStatus
import com.telematicslab.demo.ui.components.TelemetryCard
import com.telematicslab.demo.ui.theme.TelemetryColors

private data class GuideTopic(
    val id: String,
    val title: String,
    val subtitle: String,
    val body: String,
    val liveReadout: (LiveTelemetryState) -> String
)

@Composable
fun GuideScreen(state: LiveTelemetryState) {
    val topics = remember {
        listOf(
            GuideTopic(
                id = "rotation",
                title = "1. Coordinate Frame Transformation",
                subtitle = "Phone Frame (X,Y,Z) -> World ENU -> Vehicle (Long, Lat, Vert)",
                body = "Drivers keep phones in pockets, cupholders, or angled mounts. A production telematics SDK rotates linear acceleration via Android's Rotation Vector into East-North-Up world coordinates, then projects onto the vehicle's GPS velocity vector so Forward = Longitudinal G, Right = Lateral G, and Up = Vertical G regardless of phone tilt.",
                liveReadout = { s ->
                    "Live: Pitch ${s.frame.pitchDeg.toInt()}°, Roll ${s.frame.rollDeg.toInt()}° -> Car Long %+.2fg, Lat %+.2fg".format(
                        s.frame.filteredLongG,
                        s.frame.filteredLatG
                    )
                }
            ),
            GuideTopic(
                id = "fsm",
                title = "2. Trip Detection FSM & Stop Hysteresis",
                subtitle = "IDLE -> VERIFYING -> RECORDING <-> STOP_PENDING -> FINALIZING",
                body = "Continuous GPS drains battery and triggers Android Vitals alerts. Passive SDKs wake on low-power signals, verify sustained vehicle speed (>= 10 mph for 5s), and enter STOP_PENDING at traffic lights or drive-thrus so a short stop bridges cleanly into one trip instead of splitting into two.",
                liveReadout = { s ->
                    "Live State: ${s.tripState.label} | Auto-Detect: ${if (s.config.autoDetectEnabled) "ON" else "OFF"} | Hysteresis: ${s.config.stopHysteresisSec}s"
                }
            ),
            GuideTopic(
                id = "dsp",
                title = "3. Low-Pass Filtering & G-G Friction Circle",
                subtitle = "Separating true vehicle dynamics from pothole & engine vibration",
                body = "Raw 50 Hz accelerometer samples contain road chatter and mount resonance. Low-pass filtering plus minimum duration gates (350-450ms) suppresses single-sample bumps while preserving genuine hard braking, rapid acceleration, and cornering on the 2D G-G Friction Circle.",
                liveReadout = { s ->
                    "Live: ${s.config.filterEngineMode.label} (α=${"%.2f".format(s.config.lowPassAlpha)}) | Raw %+.2fg -> Filtered %+.2fg".format(
                        s.frame.rawLongG,
                        s.frame.filteredLongG
                    )
                }
            ),
            GuideTopic(
                id = "impact",
                title = "4. 2-Stage Crash Detection vs. Phone Drops",
                subtitle = "Zero-alloc circular ring buffer + GNSS Δv confirmation",
                body = "Dropping a phone on a car floor creates a >4g spike just like a collision, plus high gyroscope tumble. Stage 1 captures high-G triggers into a fixed circular ring buffer; Stage 2 checks pre-impact vs. post-impact speed (Δv) over the next ~2s to reject drops where vehicle speed did not suddenly drop.",
                liveReadout = { s ->
                    "Live: Ring Buffer ${s.power.ringBufferFill}/${s.power.ringBufferCapacity} samples | Trigger >= ${"%.1f".format(s.config.impactThresholdG)}g"
                }
            ),
            GuideTopic(
                id = "vitals",
                title = "5. WakeLock Gating, Hardware FIFO & Vitals",
                subtitle = "Remote-config state denylist + event-sink decorator + FIFO batching",
                body = "Holding manual partial wake locks across trips causes >2h screen-off wake-lock penalties in Google Play Android Vitals. Modern SDKs rely on Foreground Services (location type) + system location delivery + hardware FIFO batching, gating any explicit wake lock behind a remote-config state denylist (with Locale.ROOT) and an event-sink decorator for rare critical bursts.",
                liveReadout = { s ->
                    "Live: Manual Lock ${s.power.manualWakeLockHeldMs / 1000L}s | FIFO ${s.config.fifoBatchLatencyMs}ms | Bucket ${s.power.standbyBucketName}"
                }
            ),
            GuideTopic(
                id = "gnss",
                title = "6. GNSS Health & Urban Canyons",
                subtitle = "Satellites used, horizontal accuracy, and CN0 dB-Hz",
                body = "Speed and bearing derived from multipath-reflected GPS in downtown canyons can jump wildly. Checking horizontal accuracy (rejecting >65m fixes), used satellite count, and average carrier-to-noise density (CN0) prevents false auto-starts and phantom speeding events.",
                liveReadout = { s ->
                    "Live: Sats ${s.gnss.satellitesUsed}/${s.gnss.satellitesVisible} | Acc ±${s.gnss.accuracyMeters.toInt()}m | CN0 ${"%.1f".format(s.gnss.avgCn0DbHz)} dB-Hz"
                }
            ),
            GuideTopic(
                id = "distraction",
                title = "7. Screen-On & Handheld Distraction",
                subtitle = "Distinguishing dash-mounted navigation from active phone handling",
                body = "Screen-on alone doesn't prove distraction if the phone is mounted for maps. Fusing interactive screen state with rolling gyroscope RMS rotational variance (> 0.55 rad/s) while driving >= 10 mph separates steady dashboard mounts from active handheld pickup.",
                liveReadout = { s ->
                    "Live: Screen ${if (s.screenOn) "ON" else "OFF"} | Gyro RMS ${"%.2f".format(s.gyroMagRadS)} rad/s | ${if (s.isHandheld) "HANDHELD" else "STEADY"}"
                }
            ),
            GuideTopic(
                id = "startup",
                title = "8. App Startup Kickoffs & Exit Forensics",
                subtitle = "Jetpack InitializationProvider + onTrimMemory + ApplicationExitInfo",
                body = "Embedded mobile SDKs must keep cold-start overhead minimal and never assume ownership of the host Application class. Using androidx.startup Initializers splits memory diagnostics, FGS prep, and detector kickoff cleanly, while ApplicationExitInfo (API 30+) and ComponentCallbacks2 reveal prior OS kills or memory pressure.",
                liveReadout = { s ->
                    "Live: Init ${s.diagnostics.startupInitMs}ms | Last Exit ${s.diagnostics.lastExitReason} | Trim Events ${s.diagnostics.trimMemoryEvents}"
                }
            ),
            GuideTopic(
                id = "boundary",
                title = "9. Public Facade & Host Crash Boundary",
                subtitle = "Thin public API + isolated internal graph + safe executor",
                body = "An embedded mobile SDK must never crash its host app or collide with the host's DI container. All public API calls, sensor callbacks, and coroutine scopes route through a safe execution boundary that rethrows CancellationException while catching unexpected faults into telemetry.",
                liveReadout = { s ->
                    "Live: Boundary Faults Caught = ${s.diagnostics.caughtBoundaryFaults} | Bridge = ${s.diagnostics.filterEngineMode.label}"
                }
            ),
            GuideTopic(
                id = "storage",
                title = "10. Bounded Offline Store, Quarantine & Codec",
                subtitle = "50 MB cap + priority eviction + corrupt-file quarantine + Gzip chunks",
                body = "Mobile networks drop out in garages and rural highways. Trips buffer locally under a strict 50 MB cap with priority eviction (protecting impact records), quarantine corrupt files instead of crashing, and gzip-compress payloads into 16 KB idempotent (tripId, chunkSeq) upload chunks.",
                liveReadout = { s ->
                    val latest = s.savedTrips.firstOrNull()
                    if (latest != null && latest.rawPayloadBytes > 0) {
                        "Latest Trip Codec: ${"%.1f".format(latest.rawPayloadBytes / 1024f)}KB -> ${"%.1f".format(latest.gzipPayloadBytes / 1024f)}KB (${latest.idempotentChunkCount} chunks) | Quarantine: ${s.diagnostics.quarantinedFiles}"
                    } else {
                        "Quarantined Files: ${s.diagnostics.quarantinedFiles} | Run a trip to measure Gzip chunk compression"
                    }
                }
            ),
            GuideTopic(
                id = "tutorial_drive",
                title = "11. App Tutorial: Drive Tab & Active Profile",
                subtitle = "What Active Profile means + Start/Stop + Demo Drive + Live G-G Circle",
                body = "Active Profile is simply the metadata tag (Label + Transport Mode [Car/Walk/Bicycle] + Seat Role [Front Pass./Backseat/Driver]) stamped onto the trip you are currently recording or about to start manually. Use Start Trip / Stop Trip for real rides, Run 34s Demo Drive to replay a synthetic drive through the real DSP and scorings pipeline, or turn on Passive Auto-Detect to start automatically at >= 10 mph and bridge traffic stops.",
                liveReadout = { s ->
                    "Active Profile: ${s.config.activeTripLabel} | ${s.config.activeTransportMode.label} | ${s.config.defaultSeatRole.label}"
                }
            ),
            GuideTopic(
                id = "tutorial_sensors",
                title = "12. App Tutorial: Sensors Lab Tab",
                subtitle = "Oscilloscope + Filter Engine Bridge + Coordinate Inspector + Impact Lab",
                body = "Use the 4 waveform chips (Vehicle, Raw Phone, Gyro, Filter Compare) while tilting your phone to see Phone X/Y/Z shift while rotated Car Long/Lat/Vert stays locked to travel direction. Toggle Standard Low-Pass vs. Adaptive Dual-Pole with the smoothing slider, test Stage 2 Phone Drop rejection vs. Crash delta-v, and tune FIFO hardware batching (0ms vs 500ms vs 2000ms) alongside Android Vitals wake-lock stats.",
                liveReadout = { s ->
                    "Live Sensors: ${"%.0f".format(s.power.imuSampleHz)} Hz IMU | ${s.config.filterEngineMode.label} | FIFO ${s.config.fifoBatchLatencyMs}ms"
                }
            ),
            GuideTopic(
                id = "tutorial_trips",
                title = "13. App Tutorial: Trips Tab & One-Click Presets",
                subtitle = "Planned Trips Hub + Date Badges + Route Replay + Post-Trip Edits",
                body = "Expand One-Click Planned Trips at the top of the Trips tab to see your scheduled trips (with date badges and green CAPTURED pills once recorded). Tap Start on any row to stamp its Label, Mode, and Seat Role and begin recording immediately on the Drive tab. Tap any saved trip below it to inspect its GPS breadcrumb path, vertical bounce RMS, zoomed event waveforms, share JSON/CSV/GPX files, or edit metadata afterward.",
                liveReadout = { s ->
                    val synced = s.savedTrips.count { it.syncStatus == SyncStatus.SYNCED }
                    "Planned Presets: ${s.tripPresets.size} | Saved Trips: ${s.savedTrips.size} ($synced Cloud Synced)"
                }
            ),
            GuideTopic(
                id = "tutorial_prompts",
                title = "14. App Tutorial: Car & Bike Motion Prompts",
                subtitle = "Low-power Play Services transitions + Date cross-referencing",
                body = "When Vehicle & Bike Motion Prompts is ON, Google Play Services listens in hardware for IN_VEHICLE and ON_BICYCLE transitions (ignoring indoor walking). When you board a car or bicycle while idle, it checks today's local calendar date (YYYY-MM-DD) against your uncaptured planned trips: offering Record Planned (or Extra Trip if it's an unscheduled outing, or Snooze 30m). Tapping a notification opens the Drive tab and starts recording immediately.",
                liveReadout = { s ->
                    "Motion Prompts: ${if (s.config.motionPromptsEnabled) "ENABLED" else "PAUSED"} (Reg: ${if (s.diagnostics.motionPromptsActive) "YES" else "WAIT"}) | Last: ${s.diagnostics.lastMotionSignal}"
                }
            ),
            GuideTopic(
                id = "tutorial_workbench",
                title = "15. Live Demo: Cloud Sync & Web Workbench",
                subtitle = "Phone -> Cloud backend -> Web Replay + USB ADB live telemetry",
                body = "Every completed trip automatically uploads over HTTPS as JSON (with server-generated CSV and GPX downloads) so every real road, walk, and bike trip is visible in the Web Workbench's Replay tab for live map scrubbing, score breakdown, and dual-mode presentation. Over USB with adb forward tcp:8765 tcp:8765, switching the Web Workbench to Phone USB Live streams real-time 20 Hz IMU/GNSS frames directly to your browser.",
                liveReadout = { s ->
                    val endpointSet = s.config.cloudEndpointUrl.isNotBlank() && s.config.cloudPasscodeConfigured
                    "Cloud Archive: ${if (endpointSet) "CONFIGURED" else "NOT SET"} | USB Bridge: ${if (s.diagnostics.usbBridgeActive) "PORT ${s.diagnostics.usbBridgePort}" else "OFF"}"
                }
            )
        )
    }

    var expandedId by rememberSaveable { mutableStateOf<String?>("rotation") }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        TelemetryCard {
            Text("Telematics & Mobile SDK Fundamentals", style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(4.dp))
            Text(
                "Tap any topic below to inspect how signal processing, trip FSMs, and production Android SDK architecture patterns run live on your device.",
                style = MaterialTheme.typography.bodyMedium,
                color = TelemetryColors.Slate
            )
        }

        topics.forEach { topic ->
            val expanded = expandedId == topic.id
            TelemetryCard(
                modifier = Modifier.clickable { expandedId = if (expanded) null else topic.id }
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(topic.title, fontWeight = FontWeight.Bold, fontSize = 15.sp, color = TelemetryColors.Cyan)
                        Text(topic.subtitle, style = MaterialTheme.typography.labelMedium, color = TelemetryColors.Slate)
                    }
                    Text(if (expanded) "▲" else "▼", color = TelemetryColors.Cyan, fontSize = 13.sp)
                }

                Spacer(Modifier.height(6.dp))
                Text(
                    topic.liveReadout(state),
                    style = MaterialTheme.typography.labelMedium,
                    color = TelemetryColors.Teal
                )

                if (expanded) {
                    Spacer(Modifier.height(8.dp))
                    Text(topic.body, style = MaterialTheme.typography.bodyMedium, color = TelemetryColors.TextPrimary)
                }
            }
        }
    }
}
