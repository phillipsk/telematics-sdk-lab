package com.telematicslab.demo.sdk.api

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.location.Location
import android.os.Build
import android.os.PowerManager
import androidx.core.content.ContextCompat
import com.telematicslab.demo.sdk.engine.CoordinateFrameMapper
import com.telematicslab.demo.sdk.engine.DrivingEventDetector
import com.telematicslab.demo.sdk.engine.HomeDepartureDecision
import com.telematicslab.demo.sdk.engine.HomeDepartureEvaluator
import com.telematicslab.demo.sdk.engine.ImuRingBuffer
import com.telematicslab.demo.sdk.engine.SensorEngineBridge
import com.telematicslab.demo.sdk.engine.SimulatedDriveGenerator
import com.telematicslab.demo.sdk.engine.TripScorer
import com.telematicslab.demo.sdk.engine.TripStateMachine
import com.telematicslab.demo.sdk.fgs.FgsCoordinator
import com.telematicslab.demo.sdk.internal.CloudTripUploader
import com.telematicslab.demo.sdk.internal.EventSink
import com.telematicslab.demo.sdk.internal.LocalTelemetryBridgeServer
import com.telematicslab.demo.sdk.internal.RemoteConfigClient
import com.telematicslab.demo.sdk.internal.SdkSafeExecutor
import com.telematicslab.demo.sdk.internal.WakeLockGatingEventSink
import com.telematicslab.demo.sdk.internal.WakeLockPolicy
import com.telematicslab.demo.sdk.internal.WakeLockStateController
import com.telematicslab.demo.sdk.engine.HomeGeofenceConfig
import com.telematicslab.demo.sdk.motion.HomeGeofenceManager
import com.telematicslab.demo.sdk.motion.MotionPromptManager
import com.telematicslab.demo.sdk.motion.VoiceCueSpeaker
import com.telematicslab.demo.sdk.sensor.DistractionMonitor
import com.telematicslab.demo.sdk.sensor.PowerHealthMonitor
import com.telematicslab.demo.sdk.sensor.SensorPipeline
import com.telematicslab.demo.sdk.startup.MemoryDiagnosticsTracker
import com.telematicslab.demo.sdk.storage.TripPresetStore
import com.telematicslab.demo.sdk.storage.TripRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.ZoneId
import java.util.UUID
import kotlin.math.sqrt

/**
 * Thin public SDK facade consumed by the host app.
 * Internal engines, storage, FGS coordination, and DI graph stay encapsulated inside [SdkRuntimeGraph].
 */
object TelematicsSdk {

    @Volatile
    private var graph: SdkRuntimeGraph? = null

    val state: StateFlow<LiveTelemetryState>
        get() = requireGraph().state

    internal fun initializeFromStartup(
        context: Context,
        memoryTracker: MemoryDiagnosticsTracker,
        fgsCoordinator: FgsCoordinator
    ): TelematicsSdk {
        if (graph == null) {
            synchronized(this) {
                if (graph == null) {
                    graph = SdkRuntimeGraph(context.applicationContext, memoryTracker, fgsCoordinator)
                }
            }
        }
        return this
    }

    internal fun onStartupCompleted(initMs: Long, activeInitializers: List<String>) {
        graph?.onStartupCompleted(initMs, activeInitializers)
    }

    fun ensureInitialized(context: Context) {
        if (graph == null) {
            synchronized(this) {
                if (graph == null) {
                    val appCtx = context.applicationContext
                    graph = SdkRuntimeGraph(
                        appCtx,
                        MemoryDiagnosticsTracker(appCtx),
                        FgsCoordinator(appCtx)
                    )
                }
            }
        }
    }

    fun onHostForeground() = SdkSafeExecutor.runSafely("onHostForeground", Unit) {
        requireGraph().onHostForeground()
    }

    fun onHostBackground() = SdkSafeExecutor.runSafely("onHostBackground", Unit) {
        requireGraph().onHostBackground()
    }

    fun refreshPermissions() = SdkSafeExecutor.runSafely("refreshPermissions", Unit) {
        requireGraph().refreshPermissions()
    }

    fun startTrip() = SdkSafeExecutor.runSafely("startTrip", Unit) {
        requireGraph().startTripManual()
    }

    fun startTripWithPreset(preset: TripPreset) = SdkSafeExecutor.runSafely("startTripWithPreset", Unit) {
        requireGraph().startTripWithPreset(preset)
    }

    fun startTripWithMeta(label: String, mode: TransportMode, seatRole: SeatRole) =
        SdkSafeExecutor.runSafely("startTripWithMeta", Unit) {
            requireGraph().startTripWithMeta(label, mode, seatRole)
        }

    fun triggerTestMotionPrompt(mode: TransportMode) =
        SdkSafeExecutor.runSafely("triggerTestMotionPrompt", Unit) {
            requireGraph().triggerTestMotionPrompt(mode)
        }

    fun refreshRemoteConfig(userInitiated: Boolean = true) =
        SdkSafeExecutor.runSafely("refreshRemoteConfig", Unit) {
            requireGraph().refreshRemoteConfig(userInitiated)
        }

    fun triggerWakeLockBurstDemo() =
        SdkSafeExecutor.runSafely("triggerWakeLockBurstDemo", Unit) {
            requireGraph().triggerWakeLockBurstDemo()
        }

    fun reloadPresets() = SdkSafeExecutor.runSafely("reloadPresets", Unit) {
        requireGraph().reloadPresets()
    }

    fun stopTrip() = SdkSafeExecutor.runSafely("stopTrip", Unit) {
        requireGraph().stopTrip()
    }

    fun runSimulatedDrive() = SdkSafeExecutor.runSafely("runSimulatedDrive", Unit) {
        requireGraph().runSimulatedDrive()
    }

    fun updatePolicy(transform: (SdkConfig) -> SdkConfig) = SdkSafeExecutor.runSafely("updatePolicy", Unit) {
        requireGraph().updateConfig(transform)
    }

    fun triggerImpactLabTest(simulateCollision: Boolean) = SdkSafeExecutor.runSafely("triggerImpactLabTest", Unit) {
        requireGraph().triggerSyntheticImpactTest(simulateCollision)
    }

    fun deleteTrip(tripId: String) = SdkSafeExecutor.runSafely("deleteTrip", Unit) {
        requireGraph().deleteTrip(tripId)
    }

    fun configureCloudSync(endpointUrl: String, passcode: String) = SdkSafeExecutor.runSafely("configureCloudSync", Unit) {
        requireGraph().configureCloudSync(endpointUrl, passcode)
    }

    fun updateTripMeta(tripId: String, label: String, seatRole: SeatRole, mode: TransportMode) = SdkSafeExecutor.runSafely("updateTripMeta", Unit) {
        requireGraph().updateTripMeta(tripId, label, seatRole, mode)
    }

    fun syncPendingTrips() = SdkSafeExecutor.runSafely("syncPendingTrips", Unit) {
        requireGraph().syncPendingTrips()
    }

    suspend fun exportTrip(trip: TripRecord, format: ExportFormat): Intent? =
        SdkSafeExecutor.runSuspendSafely("exportTrip", null) {
            requireGraph().exportTrip(trip, format)
        }

    fun clearStatusMessage() = SdkSafeExecutor.runSafely("clearStatusMessage", Unit) {
        requireGraph().clearStatusMessage()
    }

    internal fun notifyFgsStarted() = graph?.fgsCoordinator?.onServiceStarted()
    internal fun notifyFgsStopped() = graph?.fgsCoordinator?.onServiceStopped()
    internal fun notifyFgsDenied() = graph?.fgsCoordinator?.onServiceDenied()
    internal fun currentTripStateOrIdle(): TripState = graph?.currentTripState() ?: TripState.IDLE
    internal fun ensurePassiveSensorsRunning(startFgs: Boolean) = graph?.ensurePassiveSensorsRunning(startFgs)
    internal fun onHomeGeofenceExited(nowMs: Long) = graph?.onHomeGeofenceExited(nowMs)
    internal fun onHomeGeofenceEntered() = graph?.onHomeGeofenceEntered()
    internal fun onVehicleActivityTransition(entered: Boolean, nowMs: Long) = graph?.onVehicleActivityTransition(entered, nowMs)

    private fun requireGraph(): SdkRuntimeGraph =
        checkNotNull(graph) { "TelematicsSdk not initialized; ensureInitializationProvider or call ensureInitialized()" }
}

/**
 * Isolated internal service graph inside the SDK — zero external DI container leakage into host apps.
 */
internal class SdkRuntimeGraph(
    private val context: Context,
    private val memoryTracker: MemoryDiagnosticsTracker,
    val fgsCoordinator: FgsCoordinator
) {

    private val exceptionHandler = CoroutineExceptionHandler { _, throwable ->
        SdkSafeExecutor.recordFault("CoroutineScope")
        _state.update { it.copy(statusMessage = "SDK fault boundary caught: ${throwable.message}") }
    }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default + exceptionHandler)

    private val ringBuffer = ImuRingBuffer(capacity = 1500)
    private val engineBridge = SensorEngineBridge()
    private val frameMapper = CoordinateFrameMapper(engineBridge)
    private val tripFsm = TripStateMachine()
    private val eventDetector = DrivingEventDetector(ringBuffer)
    private val distractionMonitor = DistractionMonitor(context)
    private val powerMonitor = PowerHealthMonitor(context)
    private val repository = TripRepository(context)
    private val presetStore = TripPresetStore(context)
    private val motionPromptManager = MotionPromptManager(context)
    private val homeGeofenceManager = HomeGeofenceManager(context)
    private val homeDepartureEvaluator = HomeDepartureEvaluator()
    private val voiceCueSpeaker = VoiceCueSpeaker(context)
    private val cloudUploader = CloudTripUploader(context)
    private val remoteConfigClient = RemoteConfigClient(context)
    private val policyPrefs = context.getSharedPreferences("sdk_policy_prefs", Context.MODE_PRIVATE)
    private val wakeLockController = WakeLockStateController()
    private val wakeLockPolicyFlow = MutableStateFlow(WakeLockPolicy())

    private val sensorPipeline = SensorPipeline(
        context = context,
        onImuSample = ::handleImuSample,
        onGnssUpdate = ::handleGnssUpdate
    )

    private val _state = MutableStateFlow(LiveTelemetryState())
    val state: StateFlow<LiveTelemetryState> = _state.asStateFlow()

    private val bridgeServer = LocalTelemetryBridgeServer(context, _state, scope)

    private var uiRefreshJob: Job? = null
    private var simJob: Job? = null
    private var startupInitMs: Long = 0L
    private var activeInitializers: List<String> = emptyList()

    private val tripLock = Any()
    private var currentTripId: String = ""
    private var tripStartTimeMs: Long = 0L
    private var tripStartBatteryPct: Int = 100
    private var tripDistanceMiles: Float = 0f
    private var maxSpeedMph: Float = 0f
    private var lastTrackLat: Double? = null
    private var lastTrackLon: Double? = null
    private var lastTrackSampleMs: Long = 0L
    private var lastCheckpointMs: Long = 0L
    private var vertGSqSum: Double = 0.0
    private var vertGSampleCount: Long = 0L
    private val tripTrack = ArrayList<TrackPoint>()
    private val tripEvents = ArrayList<DrivingEvent>()
    private val tripImpacts = ArrayList<ImpactAnalysis>()

    private val eventSink: EventSink = WakeLockGatingEventSink(
        delegate = object : EventSink {
            override fun push(event: DrivingEvent) {
                synchronized(tripLock) {
                    tripEvents.add(event)
                }
            }
        },
        policyFlow = wakeLockPolicyFlow,
        requester = powerMonitor
    )

    @Volatile private var latestFrame = FrameMappingResult()
    @Volatile private var latestGnss = GnssHealth()
    @Volatile private var latestGyro = floatArrayOf(0f, 0f, 0f)
    @Volatile private var latestPressureHpa: Float? = null
    @Volatile private var latestImuHz = 0f
    @Volatile private var latestBatchIntervalMs = 20f
    @Volatile private var isSimulating = false
    @Volatile private var isUiForeground = false
    @Volatile private var remoteConfigSource = "Default"
    @Volatile private var isRefreshingRemoteConfig = false
    @Volatile private var fifoBufferedSamples = 0
    @Volatile private var fifoBatchFlushes = 0

    init {
        refreshPermissions()
        motionPromptManager.syncRegistration()
        homeGeofenceManager.syncRegistration()
        val savedStopMode = runCatching {
            AutoStopMode.valueOf(policyPrefs.getString("auto_stop_mode", AutoStopMode.SMART_5M.name) ?: AutoStopMode.SMART_5M.name)
        }.getOrDefault(AutoStopMode.SMART_5M)
        val initialBase = _state.value.config.copy(
            autoDetectEnabled = policyPrefs.getBoolean("auto_detect_enabled", true),
            motionPromptsEnabled = motionPromptManager.isEnabled(),
            autoStopMode = savedStopMode,
            stopHysteresisSec = savedStopMode.hysteresisSec,
            voiceCuesEnabled = policyPrefs.getBoolean("voice_cues_enabled", false),
            cloudEndpointUrl = cloudUploader.getEndpointUrl(),
            cloudPasscodeConfigured = cloudUploader.hasPasscode(),
            cloudUnlockUrl = cloudUploader.getUnlockUrl()
        )
        val cachedPair = remoteConfigClient.loadCachedConfig(initialBase)
        val initialCfg = cachedPair?.first ?: initialBase
        if (cachedPair != null) remoteConfigSource = "Cached"
        _state.update { it.copy(config = initialCfg) }
        wakeLockPolicyFlow.update { it.copy(legacyContinuousLockEnabled = initialCfg.legacyWakeLockEnabled) }
        sensorPipeline.updateBatchLatency(initialCfg.fifoBatchLatencyMs)
        scope.launch {
            SdkSafeExecutor.runSuspendSafely("recoverCheckpoint", Unit) {
                repository.recoverCheckpointIfAny()
                val trips = repository.loadAllTrips()
                val presets = presetStore.loadPresets()
                _state.update { it.copy(savedTrips = trips, tripPresets = presets) }
            }
        }
        refreshRemoteConfig(userInitiated = false)
    }

    fun onStartupCompleted(initMs: Long, initializers: List<String>) {
        startupInitMs = initMs
        activeInitializers = initializers
        emitUiStateNow()
    }

    fun onHostForeground() {
        isUiForeground = true
        refreshPermissions()
        distractionMonitor.start()
        bridgeServer.start()
        if (!isSimulating) {
            sensorPipeline.startSensors(_state.value.config.fifoBatchLatencyMs)
            sensorPipeline.startLocation()
            if (_state.value.config.autoDetectEnabled && _state.value.permissions.fineLocationGranted) {
                fgsCoordinator.startRecordingService()
            }
        }
        startUiTicker()
        syncPendingTrips()
    }

    fun onHostBackground() {
        isUiForeground = false
        val currentState = tripFsm.state
        val autoDetect = _state.value.config.autoDetectEnabled
        if (currentState == TripState.IDLE && !autoDetect && !isSimulating) {
            sensorPipeline.stopAll()
            distractionMonitor.stop()
            bridgeServer.stop()
            stopUiTicker()
        }
    }

    fun ensurePassiveSensorsRunning(startFgs: Boolean) {
        val cfg = _state.value.config
        if (!cfg.autoDetectEnabled || isSimulating) return
        val hasLoc = ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED
        if (!hasLoc) return

        sensorPipeline.startSensors(cfg.fifoBatchLatencyMs)
        sensorPipeline.startLocation()
        distractionMonitor.start()
        startUiTicker()
        if (startFgs && !fgsCoordinator.active) {
            fgsCoordinator.startRecordingService()
        }
    }

    fun refreshPermissions() {
        val fine = ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED
        val coarse = ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED
        val bg = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_BACKGROUND_LOCATION) ==
                PackageManager.PERMISSION_GRANTED
        } else {
            true
        }
        val notif = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) ==
                PackageManager.PERMISSION_GRANTED
        } else {
            true
        }
        val activityRec = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ContextCompat.checkSelfPermission(context, Manifest.permission.ACTIVITY_RECOGNITION) ==
                PackageManager.PERMISSION_GRANTED
        } else {
            true
        }
        val pm = context.getSystemService(Context.POWER_SERVICE) as PowerManager
        val battUnrestricted = pm.isIgnoringBatteryOptimizations(context.packageName)

        _state.update {
            it.copy(permissions = PermissionState(fine, coarse, bg, notif, activityRec, battUnrestricted))
        }
        motionPromptManager.syncRegistration()
        homeGeofenceManager.syncRegistration()
        if ((fine || coarse) && isUiForeground && !isSimulating) {
            sensorPipeline.startLocation()
        }
    }

    fun onHomeGeofenceExited(nowMs: Long) {
        homeDepartureEvaluator.onGeofenceExit(nowMs)
        ensurePassiveSensorsRunning(startFgs = true)
        emitUiStateNow()
    }

    fun onHomeGeofenceEntered() {
        homeDepartureEvaluator.onGeofenceEnter()
        emitUiStateNow()
    }

    fun onVehicleActivityTransition(entered: Boolean, nowMs: Long) {
        if (entered) {
            homeDepartureEvaluator.onVehicleActivityEnter(nowMs)
        } else {
            homeDepartureEvaluator.onVehicleActivityExit()
        }
    }

    fun startTripManual() {
        cancelSimulationIfRunning()
        val now = System.currentTimeMillis()
        synchronized(tripLock) {
            if (!tripFsm.manualStart(now)) return
            beginTripSession(now)
        }
        sensorPipeline.startSensors(_state.value.config.fifoBatchLatencyMs)
        sensorPipeline.startLocation()
        distractionMonitor.start()
        fgsCoordinator.startRecordingService()
        startUiTicker()
        emitUiStateNow()
        voiceCueSpeaker.speak("Telematics recording started", _state.value.config.voiceCuesEnabled)
    }

    fun stopTrip() {
        val now = System.currentTimeMillis()
        val recordToSave: TripRecord?
        synchronized(tripLock) {
            if (!tripFsm.manualStop(now)) return
            recordToSave = finalizeCurrentTripLocked(now, isSimulated = false)
            tripFsm.markFinalized(now)
        }
        powerMonitor.releaseLegacyWakeLock(now)
        if (!_state.value.config.autoDetectEnabled) {
            fgsCoordinator.stopRecordingService()
        }
        recordToSave?.let { rec ->
            scope.launch {
                val updated = repository.saveTrip(rec)
                _state.update {
                    it.copy(
                        savedTrips = updated,
                        statusMessage = "Saved trip (${"%.2f".format(rec.distanceMiles)} mi, score ${rec.score.overall})"
                    )
                }
                val synced = uploadSingleTrip(rec.id)
                motionPromptManager.notifyTripCompleted(
                    label = rec.tripLabel,
                    distanceMiles = rec.distanceMiles,
                    durationSec = rec.durationSec,
                    score = rec.score.overall,
                    eventsCount = rec.events.size,
                    syncedToCloud = synced
                )
                voiceCueSpeaker.speak("Trip saved", _state.value.config.voiceCuesEnabled)
            }
        }
        emitUiStateNow()
    }

    fun updateConfig(transform: (SdkConfig) -> SdkConfig) {
        val updated = transform(_state.value.config)
        _state.update { it.copy(config = updated) }
        wakeLockPolicyFlow.update {
            it.copy(legacyContinuousLockEnabled = updated.legacyWakeLockEnabled)
        }
        sensorPipeline.updateBatchLatency(updated.fifoBatchLatencyMs)
        if (!updated.legacyWakeLockEnabled) {
            powerMonitor.releaseLegacyWakeLock()
        }
        if (updated.autoDetectEnabled) {
            sensorPipeline.startSensors(updated.fifoBatchLatencyMs)
            sensorPipeline.startLocation()
            distractionMonitor.start()
            fgsCoordinator.startRecordingService()
        } else if (tripFsm.state == TripState.IDLE) {
            fgsCoordinator.stopRecordingService()
        }
        policyPrefs.edit()
            .putBoolean("auto_detect_enabled", updated.autoDetectEnabled)
            .putString("auto_stop_mode", updated.autoStopMode.name)
            .putBoolean("voice_cues_enabled", updated.voiceCuesEnabled)
            .apply()
        motionPromptManager.setEnabled(updated.motionPromptsEnabled)
        motionPromptManager.syncRegistration()
        if (updated.fifoBatchLatencyMs != _state.value.config.fifoBatchLatencyMs || updated.fifoBatchLatencyMs == 0) {
            fifoBufferedSamples = 0
        }
        emitUiStateNow()
    }

    fun refreshRemoteConfig(userInitiated: Boolean) {
        if (isRefreshingRemoteConfig) return
        val endpoint = cloudUploader.getEndpointUrl()
        val passcode = cloudUploader.getPasscode()
        if (endpoint.isBlank() || passcode.isBlank()) {
            if (userInitiated) {
                _state.update { it.copy(statusMessage = "Configure Cloud Sync before fetching remote config") }
            }
            return
        }

        isRefreshingRemoteConfig = true
        emitUiStateNow()
        scope.launch(Dispatchers.IO) {
            try {
                val base = _state.value.config
                val fetched = remoteConfigClient.fetchRemoteConfig(endpoint, passcode, base)
                if (fetched != null) {
                    val (merged, version) = fetched
                    remoteConfigSource = "Cloud"
                    policyPrefs.edit()
                        .putBoolean("auto_detect_enabled", merged.autoDetectEnabled)
                        .putString("auto_stop_mode", merged.autoStopMode.name)
                        .apply()
                    motionPromptManager.setEnabled(merged.motionPromptsEnabled)
                    motionPromptManager.syncRegistration()
                    wakeLockPolicyFlow.update {
                        it.copy(legacyContinuousLockEnabled = merged.legacyWakeLockEnabled)
                    }
                    sensorPipeline.updateBatchLatency(merged.fifoBatchLatencyMs)
                    if (!merged.legacyWakeLockEnabled) {
                        powerMonitor.releaseLegacyWakeLock()
                    }
                    if (merged.autoDetectEnabled && isUiForeground && _state.value.permissions.fineLocationGranted && !isSimulating) {
                        fgsCoordinator.startRecordingService()
                    } else if (!merged.autoDetectEnabled && tripFsm.state == TripState.IDLE) {
                        fgsCoordinator.stopRecordingService()
                    }
                    _state.update {
                        it.copy(
                            config = merged,
                            statusMessage = if (userInitiated) "Remote config synced (v$version)" else it.statusMessage
                        )
                    }
                } else if (userInitiated) {
                    _state.update { it.copy(statusMessage = "Remote config offline (using $remoteConfigSource)") }
                }
            } finally {
                isRefreshingRemoteConfig = false
                emitUiStateNow()
            }
        }
    }

    fun triggerWakeLockBurstDemo() {
        val gateSink = WakeLockGatingEventSink(
            delegate = object : EventSink {
                override fun push(event: DrivingEvent) = Unit
            },
            policyFlow = wakeLockPolicyFlow,
            requester = powerMonitor
        )
        val now = System.currentTimeMillis()
        val brakeSample = DrivingEvent(
            id = "demo-brk",
            type = EventType.HARD_BRAKE,
            timestampMs = now,
            elapsedTripSec = 0f,
            peakValue = 0.41f,
            unit = "g",
            durationMs = 450L,
            speedStartMph = 35f,
            speedEndMph = 20f,
            lat = latestGnss.lat,
            lon = latestGnss.lon,
            details = "Routine brake"
        )
        val impactSample = brakeSample.copy(id = "demo-imp", type = EventType.POTENTIAL_IMPACT, peakValue = 4.6f, details = "Stage-2 critical burst")
        gateSink.push(brakeSample)
        wakeLockController.recordEventGateDecision("HARD_BRAKE -> gated (0ms lock via allowlist)")
        gateSink.push(impactSample)
        wakeLockController.recordEventGateDecision("POTENTIAL_IMPACT -> granted bounded 15s burst lock")
        _state.update {
            it.copy(statusMessage = "WakeLock Gate Demo: Hard Brake=0ms, Impact=15s bounded burst")
        }
        emitUiStateNow()
    }

    fun triggerSyntheticImpactTest(isRealCrash: Boolean) {
        val now = System.currentTimeMillis()
        val gnss = latestGnss
        val output = if (isRealCrash) {
            eventDetector.evaluateSyntheticImpact(
                nowMs = now,
                peakG = 4.6f,
                peakGyroRadS = 2.4f,
                preSpeedMph = 38f,
                postSpeedMph = 0f,
                lat = gnss.lat,
                lon = gnss.lon
            )
        } else {
            eventDetector.evaluateSyntheticImpact(
                nowMs = now,
                peakG = 4.5f,
                peakGyroRadS = 8.9f,
                preSpeedMph = gnss.speedMph,
                postSpeedMph = gnss.speedMph,
                lat = gnss.lat,
                lon = gnss.lon
            )
        }

        output.newEvents.forEach { eventSink.push(it) }
        synchronized(tripLock) {
            tripImpacts.addAll(output.newImpacts)
        }
        val verdictText = output.newImpacts.firstOrNull()?.verdict?.label ?: "Tested"
        _state.update { it.copy(statusMessage = "Stage 2 Impact Lab: $verdictText") }
        emitUiStateNow()
    }

    fun runSimulatedDrive() {
        cancelSimulationIfRunning()
        isSimulating = true
        sensorPipeline.stopLocation()

        val startMs = System.currentTimeMillis()
        val simConfig = SimulatedDriveGenerator.defaultConfigForDemo(_state.value.config)
        _state.update { it.copy(config = simConfig, isSimulatingTrip = true, statusMessage = "Running 34s simulated drive...") }

        synchronized(tripLock) {
            tripFsm.manualStart(startMs)
            beginTripSession(startMs)
        }
        startUiTicker()

        simJob = scope.launch {
            try {
                val ticks = SimulatedDriveGenerator.buildScriptedTicks(startMs, hz = 25)
                for (tick in ticks) {
                    val tickMs = startMs + (tick.elapsedSec * 1000L).toLong()
                    handleGnssUpdateInternal(tick.gnss, tickMs, isSim = true)
                    handleImuSampleInternal(
                        timestampMs = tickMs,
                        phoneLinear = tick.phoneLinear,
                        phoneRaw = tick.phoneRaw,
                        rotMatrix9 = tick.rotMatrix9,
                        orientationRad = tick.orientationRad,
                        gyro = tick.gyro,
                        pressureHpa = 1013.2f,
                        imuHz = 25f,
                        avgBatchIntervalMs = 40f,
                        overrideScreenOn = tick.screenOn,
                        overrideHandheld = tick.handheld
                    )
                    delay(40L)
                }

                val endMs = startMs + 34_000L
                val saved: TripRecord?
                synchronized(tripLock) {
                    tripFsm.manualStop(endMs)
                    saved = finalizeCurrentTripLocked(endMs, isSimulated = true)
                    tripFsm.markFinalized(endMs)
                }
                if (saved != null) {
                    val trips = repository.saveTrip(saved)
                    _state.update {
                        it.copy(
                            savedTrips = trips,
                            isSimulatingTrip = false,
                            statusMessage = "Demo trip saved! Inspect 5 graphs in Trips tab (Score ${saved.score.overall})."
                        )
                    }
                    uploadSingleTrip(saved.id)
                }
            } catch (ce: CancellationException) {
                throw ce
            } finally {
                isSimulating = false
                _state.update { it.copy(isSimulatingTrip = false) }
                if (isUiForeground) {
                    sensorPipeline.startSensors(_state.value.config.fifoBatchLatencyMs)
                    sensorPipeline.startLocation()
                }
                emitUiStateNow()
            }
        }
    }

    fun deleteTrip(tripId: String) {
        scope.launch {
            val updated = repository.deleteTrip(tripId)
            _state.update { it.copy(savedTrips = updated) }
            cloudUploader.deleteCloudTrip(tripId)
        }
    }

    fun configureCloudSync(endpointUrl: String, passcode: String) {
        cloudUploader.configure(endpointUrl, passcode)
        _state.update {
            it.copy(
                config = it.config.copy(
                    cloudEndpointUrl = cloudUploader.getEndpointUrl(),
                    cloudPasscodeConfigured = cloudUploader.hasPasscode(),
                    cloudUnlockUrl = cloudUploader.getUnlockUrl()
                ),
                statusMessage = "Cloud Sync settings updated"
            )
        }
        syncPendingTrips()
    }

    fun reloadPresets() {
        scope.launch {
            val presets = presetStore.loadPresets()
            _state.update { it.copy(tripPresets = presets) }
        }
    }

    fun startTripWithPreset(preset: TripPreset) {
        startTripWithMeta(preset.label, preset.mode, preset.seatRole)
    }

    fun startTripWithMeta(label: String, mode: TransportMode, seatRole: SeatRole) {
        _state.update {
            it.copy(
                config = it.config.copy(
                    activeTripLabel = label,
                    activeTransportMode = mode,
                    defaultSeatRole = seatRole
                )
            )
        }
        startTripManual()
    }

    fun currentTripState(): TripState = tripFsm.state

    fun triggerTestMotionPrompt(mode: TransportMode) {
        scope.launch(Dispatchers.IO) {
            val suggestion = motionPromptManager.handleDetectedMotion(mode, bypassCooldown = true)
            val msg = if (suggestion != null) {
                "Posted ${mode.label} prompt -> ${suggestion.primaryLabel}"
            } else {
                "Motion prompt suppressed (stop active trip or enable prompts first)"
            }
            _state.update { it.copy(statusMessage = msg) }
            emitUiStateNow()
        }
    }

    fun updateTripMeta(tripId: String, label: String, seatRole: SeatRole, mode: TransportMode) {
        scope.launch {
            val updated = repository.updateTripMetadata(tripId, label, seatRole, mode)
            _state.update { it.copy(savedTrips = updated, statusMessage = "Updated trip metadata") }
            uploadSingleTrip(tripId)
        }
    }

    fun syncPendingTrips() {
        if (!_state.value.config.cloudSyncEnabled) return
        if (cloudUploader.getEndpointUrl().isBlank() || !cloudUploader.hasPasscode()) return
        scope.launch {
            val pending = _state.value.savedTrips.filter {
                it.syncStatus == SyncStatus.PENDING_UPLOAD || it.syncStatus == SyncStatus.FAILED || it.syncStatus == SyncStatus.LOCAL_ONLY
            }
            for (t in pending) {
                uploadSingleTrip(t.id)
            }

            // Bidirectional pull: fetch missing remote trips & refresh metadata changes (e.g. [S20] labels)
            val localById = repository.loadAllTrips().associateBy { it.id }
            val summaries = cloudUploader.listCloudTripSummaries()
            var importedCount = 0
            var refreshedMetaCount = 0
            for (summary in summaries) {
                val existing = localById[summary.id]
                if (existing == null) {
                    val raw = cloudUploader.downloadTripJson(summary.id) ?: continue
                    if (repository.importCloudTripJson(raw) != null) {
                        importedCount++
                    }
                } else if (existing.syncStatus == SyncStatus.SYNCED) {
                    val nextRole = summary.seatRole?.let { runCatching { SeatRole.valueOf(it) }.getOrNull() }
                    val nextMode = summary.transportMode?.let { runCatching { TransportMode.valueOf(it) }.getOrNull() }
                    val nextLabel = summary.tripLabel ?: existing.tripLabel
                    if (repository.applyCloudMetadata(existing.id, nextLabel, nextRole, nextMode)) {
                        refreshedMetaCount++
                    }
                }
            }
            if (importedCount > 0 || refreshedMetaCount > 0) {
                val refreshed = repository.loadAllTrips()
                _state.update {
                    it.copy(
                        savedTrips = refreshed,
                        statusMessage = "Synced $importedCount new, $refreshedMetaCount updated from Cloud"
                    )
                }
            }
        }
    }

    private suspend fun uploadSingleTrip(tripId: String): Boolean {
        if (!_state.value.config.cloudSyncEnabled) return false
        if (cloudUploader.getEndpointUrl().isBlank() || !cloudUploader.hasPasscode()) return false
        val raw = repository.readRawTripJson(tripId) ?: return false
        val ok = cloudUploader.uploadTrip(tripId, raw)
        val nextStatus = if (ok) SyncStatus.SYNCED else SyncStatus.FAILED
        val updated = repository.updateTripSyncStatus(tripId, nextStatus)
        _state.update { it.copy(savedTrips = updated) }
        return ok
    }

    suspend fun exportTrip(trip: TripRecord, format: ExportFormat): Intent? {
        val (_, uri) = repository.exportTrip(trip, format)
        return Intent(Intent.ACTION_SEND).apply {
            type = format.mimeType
            putExtra(Intent.EXTRA_STREAM, uri)
            putExtra(Intent.EXTRA_SUBJECT, "Telematics Lab Trip ${trip.id}")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
    }

    fun clearStatusMessage() {
        _state.update { it.copy(statusMessage = null) }
    }

    private fun cancelSimulationIfRunning() {
        simJob?.cancel()
        simJob = null
        isSimulating = false
        _state.update { it.copy(isSimulatingTrip = false) }
    }

    private fun handleImuSample(
        timestampMs: Long,
        phoneLinear: FloatArray,
        phoneRaw: FloatArray,
        rotMatrix9: FloatArray,
        orientationRad: FloatArray,
        gyro: FloatArray,
        pressureHpa: Float?,
        imuHz: Float,
        avgBatchIntervalMs: Float
    ) {
        if (isSimulating) return
        SdkSafeExecutor.runSafely("handleImuSample", Unit) {
            val handheld = distractionMonitor.updateGyroSample(gyro[0], gyro[1], gyro[2])
            handleImuSampleInternal(
                timestampMs = timestampMs,
                phoneLinear = phoneLinear,
                phoneRaw = phoneRaw,
                rotMatrix9 = rotMatrix9,
                orientationRad = orientationRad,
                gyro = gyro,
                pressureHpa = pressureHpa,
                imuHz = imuHz,
                avgBatchIntervalMs = avgBatchIntervalMs,
                overrideScreenOn = distractionMonitor.isScreenOn,
                overrideHandheld = handheld
            )
        }
    }

    private fun handleImuSampleInternal(
        timestampMs: Long,
        phoneLinear: FloatArray,
        phoneRaw: FloatArray,
        rotMatrix9: FloatArray,
        orientationRad: FloatArray,
        gyro: FloatArray,
        pressureHpa: Float?,
        imuHz: Float,
        avgBatchIntervalMs: Float,
        overrideScreenOn: Boolean,
        overrideHandheld: Boolean
    ) {
        val cfg = _state.value.config
        val gnss = latestGnss
        val speedMps = gnss.speedMph * 0.44704f

        val mapped = frameMapper.mapWithRotationMatrix(
            linearAccelPhone = phoneLinear,
            rawAccelPhone = phoneRaw,
            rotMatrix9 = rotMatrix9,
            orientationRad = orientationRad,
            gpsBearingDeg = if (gnss.hasFix) gnss.bearingDeg else null,
            gpsSpeedMps = speedMps,
            alpha = cfg.lowPassAlpha,
            engineMode = cfg.filterEngineMode
        )

        latestFrame = mapped
        latestGyro = gyro
        latestPressureHpa = pressureHpa
        latestImuHz = imuHz
        latestBatchIntervalMs = avgBatchIntervalMs

        if (cfg.fifoBatchLatencyMs > 0) {
            val batchCap = (cfg.fifoBatchLatencyMs / 20).coerceAtLeast(1)
            val nextFill = fifoBufferedSamples + 1
            if (nextFill >= batchCap) {
                fifoBufferedSamples = 0
                fifoBatchFlushes++
            } else {
                fifoBufferedSamples = nextFill
            }
        } else {
            fifoBufferedSamples = 0
            fifoBatchFlushes++
        }

        ringBuffer.push(
            timestampMs = timestampMs,
            longG = mapped.filteredLongG,
            latG = mapped.filteredLatG,
            vertG = mapped.filteredVertG,
            rawLongG = mapped.rawLongG,
            rawLatG = mapped.rawLatG,
            rawVertG = mapped.rawVertG,
            rawPhoneX = phoneRaw[0] / CoordinateFrameMapper.GRAVITY_MS2,
            rawPhoneY = phoneRaw[1] / CoordinateFrameMapper.GRAVITY_MS2,
            rawPhoneZ = phoneRaw[2] / CoordinateFrameMapper.GRAVITY_MS2,
            rawTotalG = mapped.rawTotalG,
            gyroX = gyro[0],
            gyroY = gyro[1],
            gyroZ = gyro[2],
            speedMph = gnss.speedMph
        )

        val currentTripState = tripFsm.state
        val effectiveWakeState = if (cfg.simulatedScreenOff && currentTripState == TripState.IDLE) {
            TripState.RECORDING
        } else {
            currentTripState
        }
        val decision = wakeLockController.evaluateState(effectiveWakeState, wakeLockPolicyFlow.value)
        powerMonitor.updateLegacyWakeLockGate(decision.shouldHoldContinuousLock, timestampMs)

        val effectiveScreenOn = if (cfg.simulatedScreenOff) false else overrideScreenOn
        val isActiveTrip = currentTripState == TripState.RECORDING || currentTripState == TripState.STOP_PENDING
        if (isActiveTrip) {
            val detected = eventDetector.processSample(
                nowMs = timestampMs,
                longG = mapped.filteredLongG,
                latG = mapped.filteredLatG,
                rawTotalG = mapped.rawTotalG,
                gyroX = gyro[0],
                gyroY = gyro[1],
                gyroZ = gyro[2],
                speedMph = gnss.speedMph,
                lat = gnss.lat,
                lon = gnss.lon,
                screenOn = effectiveScreenOn,
                handheld = overrideHandheld,
                config = cfg
            )

            detected.newEvents.forEach { eventSink.push(it) }

            synchronized(tripLock) {
                if (detected.newImpacts.isNotEmpty()) tripImpacts.addAll(detected.newImpacts)
                if (gnss.speedMph > maxSpeedMph) maxSpeedMph = gnss.speedMph
                vertGSqSum += (mapped.filteredVertG * mapped.filteredVertG).toDouble()
                vertGSampleCount++

                appendTrackPointIfDueLocked(
                    nowMs = timestampMs,
                    gnss = gnss,
                    frame = mapped,
                    yawRadS = gyro[2],
                    screenOn = overrideScreenOn,
                    handheld = overrideHandheld
                )

                if (!isSimulating && timestampMs - lastCheckpointMs >= 15_000L) {
                    lastCheckpointMs = timestampMs
                    val snapshot = buildSnapshotRecordLocked(timestampMs, isSimulated = false)
                    scope.launch { repository.saveCheckpoint(snapshot) }
                }
            }
        }
    }

    private fun handleGnssUpdate(gnss: GnssHealth) {
        if (isSimulating) return
        SdkSafeExecutor.runSafely("handleGnssUpdate", Unit) {
            handleGnssUpdateInternal(gnss, System.currentTimeMillis(), isSim = false)
        }
    }

    private fun handleGnssUpdateInternal(gnss: GnssHealth, nowMs: Long, isSim: Boolean) {
        latestGnss = gnss
        val cfg = _state.value.config
        var finalizedRecord: TripRecord? = null

        synchronized(tripLock) {
            if (!isSim) {
                val homeCfg = homeGeofenceManager.loadConfig()
                val homeDecision = if (gnss.hasFix) {
                    homeDepartureEvaluator.evaluateLocation(
                        nowMs = nowMs,
                        lat = gnss.lat,
                        lon = gnss.lon,
                        speedMph = gnss.speedMph,
                        accuracyM = gnss.accuracyMeters,
                        config = homeCfg,
                        isIdle = tripFsm.state == TripState.IDLE
                    )
                } else {
                    HomeDepartureDecision.NONE
                }

                val allowAutoStop = cfg.autoStopMode != AutoStopMode.MANUAL_ONLY &&
                    (cfg.autoStopNonCar || cfg.activeTransportMode == TransportMode.CAR)
                val effectiveHystSec = if (cfg.autoStopMode == AutoStopMode.MANUAL_ONLY) 0 else cfg.stopHysteresisSec.coerceAtLeast(30)
                val effectiveAutoDetect = cfg.autoDetectEnabled &&
                    homeDecision != HomeDepartureDecision.SUPPRESS_EARLY_AUTO_START

                val transitionedTo: TripState? = if (
                    cfg.autoDetectEnabled &&
                    homeDecision == HomeDepartureDecision.TRIGGER_AUTO_RECORD &&
                    tripFsm.manualStart(nowMs)
                ) {
                    TripState.RECORDING
                } else {
                    tripFsm.onLocationUpdate(
                        nowMs = nowMs,
                        speedMph = gnss.speedMph,
                        accuracyM = gnss.accuracyMeters,
                        autoDetectEnabled = effectiveAutoDetect,
                        stopHysteresisMs = effectiveHystSec * 1000L,
                        autoStartVerifyMph = cfg.autoStartVerifyMph,
                        allowAutoStop = allowAutoStop
                    )
                }

                when (transitionedTo) {
                    TripState.RECORDING -> {
                        if (tripStartTimeMs == 0L) {
                            val todayIso = LocalDate.now(ZoneId.systemDefault()).toString()
                            val currentSnap = _state.value
                            val completedLabels = currentSnap.savedTrips.map { TripDeviceLabel.stripDeviceTag(it.tripLabel) }.toSet()
                            val inferredMode = motionPromptManager.recentDetectedMode() ?: TransportMode.CAR
                            val matchedPreset = if (inferredMode == TransportMode.BICYCLE) {
                                currentSnap.tripPresets.firstOrNull {
                                    it.mode == TransportMode.BICYCLE && it.plannedDate == todayIso && TripDeviceLabel.stripDeviceTag(it.label) !in completedLabels
                                } ?: currentSnap.tripPresets.firstOrNull {
                                    it.mode == TransportMode.BICYCLE && TripDeviceLabel.stripDeviceTag(it.label) !in completedLabels
                                }
                            } else {
                                currentSnap.tripPresets.firstOrNull {
                                    it.mode == TransportMode.CAR && it.plannedDate == todayIso && TripDeviceLabel.stripDeviceTag(it.label) !in completedLabels
                                }
                            }
                            if (matchedPreset != null) {
                                _state.update {
                                    it.copy(
                                        config = it.config.copy(
                                            activeTripLabel = matchedPreset.label,
                                            activeTransportMode = matchedPreset.mode,
                                            defaultSeatRole = matchedPreset.seatRole
                                        )
                                    )
                                }
                            } else if (inferredMode == TransportMode.BICYCLE) {
                                _state.update {
                                    it.copy(
                                        config = it.config.copy(
                                            activeTripLabel = "Extra Bike Ride",
                                            activeTransportMode = TransportMode.BICYCLE,
                                            defaultSeatRole = SeatRole.DRIVER
                                        )
                                    )
                                }
                            }
                            beginTripSession(nowMs)
                            fgsCoordinator.startRecordingService()
                            val activeCfg = _state.value.config
                            motionPromptManager.notifyAutoRecordingStarted(
                                label = activeCfg.activeTripLabel,
                                mode = activeCfg.activeTransportMode,
                                seatRole = activeCfg.defaultSeatRole
                            )
                            voiceCueSpeaker.speak("Telematics recording started", activeCfg.voiceCuesEnabled)
                        }
                    }
                    TripState.FINALIZING -> {
                        finalizedRecord = finalizeCurrentTripLocked(nowMs, isSimulated = false)
                        tripFsm.markFinalized(nowMs)
                    }
                    else -> Unit
                }
            }

            if ((tripFsm.state == TripState.RECORDING || tripFsm.state == TripState.STOP_PENDING) && gnss.hasFix) {
                val prevLat = lastTrackLat
                val prevLon = lastTrackLon
                val isLowSpeedMode = cfg.activeTransportMode != TransportMode.CAR
                val maxAcc = if (isLowSpeedMode) 30f else 55f
                val maxJumpM = if (isLowSpeedMode) 150f else 500f
                val minStepM = if (isLowSpeedMode && gnss.speedMph < 0.7f) 2.5f else 0.7f

                if (prevLat != null && prevLon != null && gnss.accuracyMeters <= maxAcc) {
                    val results = FloatArray(1)
                    Location.distanceBetween(prevLat, prevLon, gnss.lat, gnss.lon, results)
                    val meters = results[0]
                    if (meters in minStepM..maxJumpM) {
                        tripDistanceMiles += meters / 1609.344f
                    }
                }
                lastTrackLat = gnss.lat
                lastTrackLon = gnss.lon

                // Guarantee 1 Hz GPS trackpoint capture even if IMU callbacks throttle screen-off
                if (!isSim) {
                    appendTrackPointIfDueLocked(
                        nowMs = nowMs,
                        gnss = gnss,
                        frame = latestFrame,
                        yawRadS = latestGyro[2],
                        screenOn = distractionMonitor.isScreenOn,
                        handheld = distractionMonitor.isHandheld
                    )
                }
            }
        }

        finalizedRecord?.let { rec ->
            powerMonitor.releaseLegacyWakeLock(nowMs)
            scope.launch {
                val updated = repository.saveTrip(rec)
                _state.update {
                    it.copy(
                        savedTrips = updated,
                        statusMessage = "Auto-stopped & saved trip (${"%.2f".format(rec.distanceMiles)} mi)"
                    )
                }
                val synced = uploadSingleTrip(rec.id)
                motionPromptManager.notifyTripCompleted(
                    label = rec.tripLabel,
                    distanceMiles = rec.distanceMiles,
                    durationSec = rec.durationSec,
                    score = rec.score.overall,
                    eventsCount = rec.events.size,
                    syncedToCloud = synced
                )
                voiceCueSpeaker.speak("Trip saved", _state.value.config.voiceCuesEnabled)
            }
        }
    }

    private fun appendTrackPointIfDueLocked(
        nowMs: Long,
        gnss: GnssHealth,
        frame: FrameMappingResult,
        yawRadS: Float,
        screenOn: Boolean,
        handheld: Boolean
    ) {
        if (!gnss.hasFix || nowMs - lastTrackSampleMs < 900L) return
        lastTrackSampleMs = nowMs
        val elapsedSec = ((nowMs - tripStartTimeMs).coerceAtLeast(0L)) / 1000f
        tripTrack.add(
            TrackPoint(
                elapsedSec = elapsedSec,
                timestampMs = nowMs,
                lat = gnss.lat,
                lon = gnss.lon,
                altitudeM = gnss.altitudeM,
                speedMph = gnss.speedMph,
                bearingDeg = gnss.bearingDeg,
                accuracyM = gnss.accuracyMeters,
                satellitesUsed = gnss.satellitesUsed,
                longG = frame.filteredLongG,
                latG = frame.filteredLatG,
                vertG = frame.filteredVertG,
                rawTotalG = frame.rawTotalG,
                yawRadS = yawRadS,
                screenOn = screenOn,
                handheld = handheld
            )
        )
    }

    private fun beginTripSession(nowMs: Long) {
        motionPromptManager.cancelPromptNotification()
        homeDepartureEvaluator.onTripStarted()
        currentTripId = UUID.randomUUID().toString().take(8)
        tripStartTimeMs = nowMs
        tripDistanceMiles = 0f
        maxSpeedMph = latestGnss.speedMph
        lastTrackLat = if (latestGnss.hasFix) latestGnss.lat else null
        lastTrackLon = if (latestGnss.hasFix) latestGnss.lon else null
        lastTrackSampleMs = 0L
        lastCheckpointMs = nowMs
        vertGSqSum = 0.0
        vertGSampleCount = 0L
        tripTrack.clear()
        tripEvents.clear()
        tripImpacts.clear()
        frameMapper.reset()
        eventDetector.reset(nowMs)
        powerMonitor.markTripStarted()
        tripStartBatteryPct = powerMonitor.snapshot(0f, 0f, 0, false, 0L, 0, 1500).batteryPct
    }

    private fun finalizeCurrentTripLocked(nowMs: Long, isSimulated: Boolean): TripRecord {
        val record = buildSnapshotRecordLocked(nowMs, isSimulated)
        tripStartTimeMs = 0L
        lastTrackLat = null
        lastTrackLon = null
        return record
    }

    private fun buildSnapshotRecordLocked(nowMs: Long, isSimulated: Boolean): TripRecord {
        val durationSec = (((nowMs - tripStartTimeMs).coerceAtLeast(1000L)) / 1000L).toInt()
        val score = TripScorer.score(tripEvents, tripDistanceMiles)
        val avgSpeed = if (tripTrack.isNotEmpty()) {
            tripTrack.map { it.speedMph }.average().toFloat()
        } else {
            (tripDistanceMiles / (durationSec / 3600f).coerceAtLeast(0.001f))
        }
        val avgAcc = if (tripTrack.isNotEmpty()) tripTrack.map { it.accuracyM }.average().toFloat() else latestGnss.accuracyMeters
        val currentBatt = powerMonitor.snapshot(0f, 0f, 0, false, 0L, 0, 1500).batteryPct
        val vertRms = if (vertGSampleCount > 0L) sqrt(vertGSqSum / vertGSampleCount).toFloat() else 0f

        val deviceTag = TripDeviceLabel.detectAndroidDeviceTag(Build.MODEL)
        return TripRecord(
            id = currentTripId.ifBlank { UUID.randomUUID().toString().take(8) },
            tripLabel = TripDeviceLabel.ensureDeviceTag(_state.value.config.activeTripLabel, deviceTag),
            seatRole = _state.value.config.defaultSeatRole,
            transportMode = _state.value.config.activeTransportMode,
            syncStatus = if (_state.value.config.cloudSyncEnabled && cloudUploader.hasPasscode()) {
                SyncStatus.PENDING_UPLOAD
            } else {
                SyncStatus.LOCAL_ONLY
            },
            startTimeMs = tripStartTimeMs,
            endTimeMs = nowMs,
            durationSec = durationSec,
            distanceMiles = tripDistanceMiles,
            maxSpeedMph = maxSpeedMph,
            avgSpeedMph = avgSpeed,
            avgVertRmsG = vertRms,
            score = score,
            events = tripEvents.toList(),
            impacts = tripImpacts.toList(),
            track = tripTrack.toList(),
            fsmLog = tripFsm.getTransitions(),
            batteryDeltaPct = (tripStartBatteryPct - currentBatt).coerceAtLeast(0),
            avgAccuracyM = avgAcc,
            manualWakeLockMs = powerMonitor.currentHeldWakeLockMs(nowMs),
            isSimulated = isSimulated
        )
    }

    private fun startUiTicker() {
        if (uiRefreshJob?.isActive == true) return
        uiRefreshJob = scope.launch {
            var notifTick = 0
            while (true) {
                emitUiStateNow()
                notifTick++
                if (fgsCoordinator.active && notifTick % 10 == 0) {
                    val s = _state.value
                    fgsCoordinator.updateNotification(
                        s.tripState,
                        s.gnss.speedMph,
                        s.tripDistanceMiles,
                        s.liveEvents.size
                    )
                }
                delay(66L)
            }
        }
    }

    private fun stopUiTicker() {
        uiRefreshJob?.cancel()
        uiRefreshJob = null
    }

    private fun emitUiStateNow() {
        val now = System.currentTimeMillis()
        val cfg = _state.value.config
        val stateNow = tripFsm.state
        val effectiveHystSec = if (cfg.autoStopMode == AutoStopMode.MANUAL_ONLY) 0 else cfg.stopHysteresisSec.coerceAtLeast(30)
        val stopRem = tripFsm.stopPendingRemainingSec(now, effectiveHystSec * 1000L)
        val fsmLog = tripFsm.getTransitions()

        val elapsedSec: Int
        val distMi: Float
        val maxSpd: Float
        val eventsSnap: List<DrivingEvent>
        val impactsSnap: List<ImpactAnalysis>
        val trackSnap: List<TrackPoint>
        val scoreSnap: TripScoreBreakdown

        synchronized(tripLock) {
            elapsedSec = if (tripStartTimeMs > 0L && stateNow != TripState.IDLE) {
                ((now - tripStartTimeMs).coerceAtLeast(0L) / 1000L).toInt()
            } else 0
            distMi = tripDistanceMiles
            maxSpd = maxSpeedMph
            eventsSnap = tripEvents.toList()
            impactsSnap = tripImpacts.toList()
            trackSnap = tripTrack.takeLast(120)
            scoreSnap = if (stateNow != TripState.IDLE) TripScorer.score(eventsSnap, distMi) else TripScoreBreakdown()
        }

        val gyro = latestGyro
        val gyroMag = sqrt(gyro[0] * gyro[0] + gyro[1] * gyro[1] + gyro[2] * gyro[2])
        val batchCap = if (cfg.fifoBatchLatencyMs > 0) (cfg.fifoBatchLatencyMs / 20).coerceAtLeast(1) else 1
        val estWakeupsPerMin = if (cfg.fifoBatchLatencyMs <= 0) 3000 else (60_000 / cfg.fifoBatchLatencyMs).coerceAtLeast(1)
        val powerSnap = powerMonitor.snapshot(
            imuHz = latestImuHz,
            avgBatchIntervalMs = latestBatchIntervalMs,
            fifoBatchLatencyMs = cfg.fifoBatchLatencyMs,
            legacyWakeLockEnabled = cfg.legacyWakeLockEnabled,
            fgsUptimeSec = fgsCoordinator.uptimeSec(now),
            ringFill = ringBuffer.count(),
            ringCapacity = ringBuffer.capacity
        ).copy(
            estimatedCpuWakeupsPerMin = estWakeupsPerMin,
            fifoBufferedSamples = fifoBufferedSamples,
            fifoBatchCapacity = batchCap,
            fifoBatchFlushes = fifoBatchFlushes
        )

        val homeCfg = homeGeofenceManager.loadConfig()
        val homeArmed = homeCfg.isConfigured && HomeGeofenceManager.isRegistered
        val homeStatus = when {
            homeArmed -> "Armed (${homeCfg.radiusMeters.toInt()}m • >${homeCfg.minInVehicleSec}s & >${homeCfg.minSpeedMph.toInt()} mph)"
            homeCfg.isConfigured -> "Configured (registering...)"
            else -> "Not configured"
        }

        val diagnosticsSnap = SdkDiagnostics(
            startupInitMs = startupInitMs,
            activeInitializers = activeInitializers,
            lastExitReason = memoryTracker.lastExitReason,
            trimMemoryEvents = memoryTracker.trimEventsCount,
            lastTrimLevel = memoryTracker.lastTrimLevel,
            caughtBoundaryFaults = SdkSafeExecutor.faultCount(),
            quarantinedFiles = repository.quarantinedFilesCount,
            fgsStartCount = fgsCoordinator.startAttempts,
            fgsDeniedCount = fgsCoordinator.deniedCount,
            wakeLockDecisionLog = wakeLockController.recentDecisions(),
            filterEngineMode = cfg.filterEngineMode,
            usbBridgeActive = bridgeServer.isRunning,
            usbBridgePort = bridgeServer.port,
            motionPromptsActive = MotionPromptManager.isRegistered,
            lastMotionSignal = motionPromptManager.getLastSignal(),
            remoteConfigVersion = cfg.remoteConfigVersion,
            remoteConfigSource = remoteConfigSource,
            isRefreshingConfig = isRefreshingRemoteConfig,
            homeGeofenceArmed = homeArmed,
            homeGeofenceStatus = homeStatus
        )

        val wave = ringBuffer.snapshotLast(250)

        _state.update {
            it.copy(
                tripState = stateNow,
                stopPendingRemainingSec = stopRem,
                tripElapsedSec = elapsedSec,
                tripDistanceMiles = distMi,
                maxSpeedMph = maxSpd,
                liveScore = scoreSnap,
                frame = latestFrame,
                gnss = latestGnss,
                gyroX = gyro[0],
                gyroY = gyro[1],
                gyroZ = gyro[2],
                gyroMagRadS = gyroMag,
                pressureHpa = latestPressureHpa,
                screenOn = if (cfg.simulatedScreenOff) false else distractionMonitor.isScreenOn,
                isUnlocked = distractionMonitor.isUnlocked,
                isHandheld = distractionMonitor.isHandheld,
                liveWaveform = wave,
                liveTrack = trackSnap,
                liveEvents = eventsSnap.asReversed(),
                liveImpacts = impactsSnap.asReversed(),
                fsmLog = fsmLog.asReversed(),
                power = powerSnap,
                diagnostics = diagnosticsSnap
            )
        }
    }
}
