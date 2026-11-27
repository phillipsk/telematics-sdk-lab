package com.telematicslab.demo.sdk.api

enum class TripState(val label: String, val isWakeful: Boolean) {
    IDLE("IDLE", false),
    VERIFYING("VERIFYING", true),
    RECORDING("RECORDING", true),
    STOP_PENDING("STOP PENDING", true),
    FINALIZING("FINALIZING", true)
}

enum class FilterEngineMode(val label: String, val description: String) {
    STANDARD_LOW_PASS("Standard Low-Pass", "Single-pole exponential IIR filter"),
    ADAPTIVE_DUAL_POLE("Adaptive Dual-Pole", "Cascaded two-stage road-chatter suppressor")
}

enum class AutoStopMode(val label: String, val hysteresisSec: Int) {
    MANUAL_ONLY("Manual Only", 0),
    SMART_5M("Smart 5m", 300),
    QUICK_90S("Quick 90s", 90)
}

enum class EventType(val displayName: String, val shortLabel: String) {
    HARD_BRAKE("Hard Brake", "Brake"),
    RAPID_ACCEL("Rapid Accel", "Accel"),
    HARSH_CORNER_LEFT("Harsh Left Turn", "Left"),
    HARSH_CORNER_RIGHT("Harsh Right Turn", "Right"),
    SPEEDING("Speeding", "Speed"),
    DISTRACTION_SCREEN("Screen-On Distraction", "Screen"),
    DISTRACTION_HANDHELD("Handheld Phone Use", "Handheld"),
    POTENTIAL_IMPACT("Potential Impact", "Impact")
}

enum class ImpactVerdict(val label: String) {
    POTENTIAL_IMPACT("Potential Vehicle Impact"),
    REJECTED_PHONE_DROP("Rejected (Phone Drop / No Δv)")
}

enum class ExportFormat(val extension: String, val mimeType: String) {
    JSON("json", "application/json"),
    CSV("csv", "text/csv"),
    GPX("gpx", "application/gpx+xml")
}

enum class SeatRole(val label: String) {
    DRIVER("Driver"),
    FRONT_PASSENGER("Front Passenger"),
    BACKSEAT_PASSENGER("Backseat Passenger"),
    UNASSIGNED("Role TBD")
}

enum class SyncStatus(val label: String) {
    LOCAL_ONLY("Local Only"),
    PENDING_UPLOAD("Sync Pending"),
    SYNCED("Cloud Synced"),
    FAILED("Sync Retry Needed")
}

enum class TransportMode(val label: String) {
    CAR("Car"),
    WALK("Walk"),
    BICYCLE("Bicycle")
}

data class TripPreset(
    val id: String,
    val group: String,
    val label: String,
    val mode: TransportMode,
    val seatRole: SeatRole,
    val plannedDate: String? = null
)

data class ImuSample(
    val timestampMs: Long,
    val longG: Float,
    val latG: Float,
    val vertG: Float,
    val rawLongG: Float,
    val rawLatG: Float,
    val rawVertG: Float,
    val rawPhoneX: Float,
    val rawPhoneY: Float,
    val rawPhoneZ: Float,
    val rawTotalG: Float,
    val gyroX: Float,
    val gyroY: Float,
    val gyroZ: Float,
    val speedMph: Float
)

data class FrameMappingResult(
    val phoneLinearX: Float = 0f,
    val phoneLinearY: Float = 0f,
    val phoneLinearZ: Float = 0f,
    val phoneRawX: Float = 0f,
    val phoneRawY: Float = 0f,
    val phoneRawZ: Float = 0f,
    val worldEastMs2: Float = 0f,
    val worldNorthMs2: Float = 0f,
    val worldUpMs2: Float = 0f,
    val rawLongG: Float = 0f,
    val rawLatG: Float = 0f,
    val rawVertG: Float = 0f,
    val filteredLongG: Float = 0f,
    val filteredLatG: Float = 0f,
    val filteredVertG: Float = 0f,
    val rawTotalG: Float = 1f,
    val pitchDeg: Float = 0f,
    val rollDeg: Float = 0f,
    val azimuthDeg: Float = 0f,
    val headingDeg: Float = 0f,
    val headingLocked: Boolean = false
)

data class GnssHealth(
    val satellitesVisible: Int = 0,
    val satellitesUsed: Int = 0,
    val avgCn0DbHz: Float = 0f,
    val accuracyMeters: Float = 0f,
    val provider: String = "none",
    val hasFix: Boolean = false,
    val lat: Double = 0.0,
    val lon: Double = 0.0,
    val speedMph: Float = 0f,
    val bearingDeg: Float = 0f,
    val altitudeM: Double = 0.0,
    val lastFixTimestampMs: Long = 0L
)

data class DrivingEvent(
    val id: String,
    val type: EventType,
    val timestampMs: Long,
    val elapsedTripSec: Float,
    val peakValue: Float,
    val unit: String,
    val durationMs: Long,
    val speedStartMph: Float,
    val speedEndMph: Float,
    val lat: Double,
    val lon: Double,
    val details: String,
    val waveformSnippet: List<ImuSample> = emptyList()
)

data class ImpactAnalysis(
    val timestampMs: Long,
    val peakG: Float,
    val preSpeedMph: Float,
    val postSpeedMph: Float,
    val deltaVMph: Float,
    val peakGyroRadS: Float,
    val verdict: ImpactVerdict,
    val reason: String
)

data class FsmTransition(
    val timestampMs: Long,
    val from: TripState,
    val to: TripState,
    val reason: String
)

data class TrackPoint(
    val elapsedSec: Float,
    val timestampMs: Long,
    val lat: Double,
    val lon: Double,
    val altitudeM: Double = 0.0,
    val speedMph: Float,
    val bearingDeg: Float,
    val accuracyM: Float,
    val satellitesUsed: Int,
    val longG: Float,
    val latG: Float,
    val vertG: Float,
    val rawTotalG: Float,
    val yawRadS: Float,
    val screenOn: Boolean,
    val handheld: Boolean
)

data class TripScoreBreakdown(
    val overall: Int = 100,
    val braking: Int = 100,
    val acceleration: Int = 100,
    val cornering: Int = 100,
    val speeding: Int = 100,
    val distraction: Int = 100,
    val smoothness: Int = 100
)

data class PowerStats(
    val batteryPct: Int = 100,
    val batteryStartPct: Int = 100,
    val batteryTempC: Float = 25f,
    val isCharging: Boolean = false,
    val standbyBucketName: String = "ACTIVE",
    val ignoringBatteryOptimizations: Boolean = false,
    val powerSaveMode: Boolean = false,
    val imuSampleHz: Float = 0f,
    val avgBatchIntervalMs: Float = 0f,
    val fifoBatchLatencyMs: Int = 0,
    val legacyWakeLockEnabled: Boolean = false,
    val manualWakeLockHeldMs: Long = 0L,
    val fgsUptimeSec: Long = 0L,
    val ringBufferFill: Int = 0,
    val ringBufferCapacity: Int = 1500,
    val estimatedCpuWakeupsPerMin: Int = 3000,
    val fifoBufferedSamples: Int = 0,
    val fifoBatchCapacity: Int = 1,
    val fifoBatchFlushes: Int = 0
)

data class SdkConfig(
    val autoDetectEnabled: Boolean = true,
    val motionPromptsEnabled: Boolean = true,
    val autoStartVerifyMph: Float = 10f,
    val autoStopMode: AutoStopMode = AutoStopMode.SMART_5M,
    val autoStopNonCar: Boolean = false,
    val voiceCuesEnabled: Boolean = false,
    val simulatedScreenOff: Boolean = false,
    val remoteConfigVersion: Int = 0,
    val fifoBatchLatencyMs: Int = 0,
    val legacyWakeLockEnabled: Boolean = false,
    val filterEngineMode: FilterEngineMode = FilterEngineMode.STANDARD_LOW_PASS,
    val lowPassAlpha: Float = 0.22f,
    val hardBrakeG: Float = 0.35f,
    val rapidAccelG: Float = 0.30f,
    val harshCornerG: Float = 0.35f,
    val speedingLimitMph: Float = 70f,
    val impactThresholdG: Float = 3.5f,
    val stopHysteresisSec: Int = 300,
    val defaultSeatRole: SeatRole = SeatRole.FRONT_PASSENGER,
    val activeTransportMode: TransportMode = TransportMode.CAR,
    val activeTripLabel: String = "Outbound Trip",
    val cloudSyncEnabled: Boolean = true,
    val cloudEndpointUrl: String = "",
    val cloudPasscodeConfigured: Boolean = false,
    val cloudUnlockUrl: String = ""
)

data class SdkDiagnostics(
    val startupInitMs: Long = 0L,
    val activeInitializers: List<String> = emptyList(),
    val lastExitReason: String = "UNKNOWN",
    val trimMemoryEvents: Int = 0,
    val lastTrimLevel: String = "NONE",
    val caughtBoundaryFaults: Int = 0,
    val quarantinedFiles: Int = 0,
    val fgsStartCount: Int = 0,
    val fgsDeniedCount: Int = 0,
    val wakeLockDecisionLog: List<String> = emptyList(),
    val filterEngineMode: FilterEngineMode = FilterEngineMode.STANDARD_LOW_PASS,
    val usbBridgeActive: Boolean = false,
    val usbBridgePort: Int = 8765,
    val motionPromptsActive: Boolean = false,
    val lastMotionSignal: String = "None",
    val remoteConfigVersion: Int = 0,
    val remoteConfigSource: String = "Default",
    val isRefreshingConfig: Boolean = false,
    val homeGeofenceArmed: Boolean = false,
    val homeGeofenceStatus: String = "Not configured"
)

data class TripRecord(
    val id: String,
    val tripLabel: String = "",
    val seatRole: SeatRole = SeatRole.UNASSIGNED,
    val transportMode: TransportMode = TransportMode.CAR,
    val syncStatus: SyncStatus = SyncStatus.LOCAL_ONLY,
    val startTimeMs: Long,
    val endTimeMs: Long,
    val durationSec: Int,
    val distanceMiles: Float,
    val maxSpeedMph: Float,
    val avgSpeedMph: Float,
    val avgVertRmsG: Float = 0f,
    val score: TripScoreBreakdown,
    val events: List<DrivingEvent>,
    val impacts: List<ImpactAnalysis>,
    val track: List<TrackPoint>,
    val fsmLog: List<FsmTransition>,
    val batteryDeltaPct: Int,
    val avgAccuracyM: Float,
    val manualWakeLockMs: Long,
    val rawPayloadBytes: Int = 0,
    val gzipPayloadBytes: Int = 0,
    val idempotentChunkCount: Int = 0,
    val isSimulated: Boolean = false
)

data class PermissionState(
    val fineLocationGranted: Boolean = false,
    val coarseLocationGranted: Boolean = false,
    val backgroundLocationGranted: Boolean = false,
    val postNotificationsGranted: Boolean = true,
    val activityRecognitionGranted: Boolean = false,
    val ignoringBatteryOptimizations: Boolean = false
) {
    val allCarReady: Boolean
        get() = fineLocationGranted &&
            backgroundLocationGranted &&
            postNotificationsGranted &&
            activityRecognitionGranted &&
            ignoringBatteryOptimizations
}

data class LiveTelemetryState(
    val tripState: TripState = TripState.IDLE,
    val stopPendingRemainingSec: Int = 0,
    val tripElapsedSec: Int = 0,
    val tripDistanceMiles: Float = 0f,
    val maxSpeedMph: Float = 0f,
    val liveScore: TripScoreBreakdown = TripScoreBreakdown(),
    val frame: FrameMappingResult = FrameMappingResult(),
    val gnss: GnssHealth = GnssHealth(),
    val gyroX: Float = 0f,
    val gyroY: Float = 0f,
    val gyroZ: Float = 0f,
    val gyroMagRadS: Float = 0f,
    val pressureHpa: Float? = null,
    val screenOn: Boolean = true,
    val isUnlocked: Boolean = true,
    val isHandheld: Boolean = false,
    val isSimulatingTrip: Boolean = false,
    val liveWaveform: List<ImuSample> = emptyList(),
    val liveTrack: List<TrackPoint> = emptyList(),
    val liveEvents: List<DrivingEvent> = emptyList(),
    val liveImpacts: List<ImpactAnalysis> = emptyList(),
    val fsmLog: List<FsmTransition> = emptyList(),
    val power: PowerStats = PowerStats(),
    val config: SdkConfig = SdkConfig(),
    val diagnostics: SdkDiagnostics = SdkDiagnostics(),
    val permissions: PermissionState = PermissionState(),
    val tripPresets: List<TripPreset> = emptyList(),
    val savedTrips: List<TripRecord> = emptyList(),
    val statusMessage: String? = null
)

object TripDeviceLabel {
    private val trailingTagRegex = Regex("""\s*\[([A-Za-z0-9 _-]{2,16})\]$""")

    fun detectAndroidDeviceTag(model: String): String {
        val up = model.trim().uppercase(java.util.Locale.ROOT)
        return when {
            up.contains("SM-G98") || up.contains("G986") || up.contains("S20") -> "S20"
            up.contains("SM-S91") || up.contains("S911") || up.contains("S23") -> "S23"
            up.contains("SM-S92") || up.contains("S24") -> "S24"
            up.contains("SM-S93") || up.contains("S25") -> "S25"
            up.contains("PIXEL") -> model.trim().take(12)
            up.isNotBlank() -> model.trim().replace(" ", "").take(12)
            else -> "Android"
        }
    }

    fun extractDeviceTag(label: String): String? {
        val trimmed = label.trim()
        if (trimmed.isEmpty()) return null
        val match = trailingTagRegex.find(trimmed) ?: return null
        val raw = match.groupValues[1].trim()
        if (raw.equals("iOS", ignoreCase = true)) return "iPhone"
        return raw.ifEmpty { null }
    }

    fun stripDeviceTag(label: String): String {
        val trimmed = label.trim()
        val match = trailingTagRegex.find(trimmed) ?: return trimmed
        return trimmed.substring(0, match.range.first).trim()
    }

    fun ensureDeviceTag(label: String, deviceTag: String): String {
        val cleanTag = deviceTag.trim()
        val base = stripDeviceTag(label)
        val existing = extractDeviceTag(label)
        if (existing != null) {
            return if (base.isEmpty()) "[$existing]" else "$base [$existing]"
        }
        if (cleanTag.isEmpty()) {
            return base.ifEmpty { "Trip" }
        }
        return if (base.isEmpty()) "Trip [$cleanTag]" else "$base [$cleanTag]"
    }
}
