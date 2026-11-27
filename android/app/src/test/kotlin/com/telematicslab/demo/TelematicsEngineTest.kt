package com.telematicslab.demo

import com.telematicslab.demo.sdk.api.AutoStopMode
import com.telematicslab.demo.sdk.api.DrivingEvent
import com.telematicslab.demo.sdk.api.EventType
import com.telematicslab.demo.sdk.api.FilterEngineMode
import com.telematicslab.demo.sdk.api.ImpactVerdict
import com.telematicslab.demo.sdk.api.SdkConfig
import com.telematicslab.demo.sdk.api.SeatRole
import com.telematicslab.demo.sdk.api.TransportMode
import com.telematicslab.demo.sdk.api.TripDeviceLabel
import com.telematicslab.demo.sdk.api.TripPreset
import com.telematicslab.demo.sdk.api.TripState
import com.telematicslab.demo.sdk.engine.CoordinateFrameMapper
import com.telematicslab.demo.sdk.engine.DrivingEventDetector
import com.telematicslab.demo.sdk.engine.HomeDepartureDecision
import com.telematicslab.demo.sdk.engine.HomeDepartureEvaluator
import com.telematicslab.demo.sdk.engine.HomeGeofenceConfig
import com.telematicslab.demo.sdk.engine.ImuRingBuffer
import com.telematicslab.demo.sdk.engine.LocationSpeedArbitrator
import com.telematicslab.demo.sdk.engine.SensorEngineBridge
import com.telematicslab.demo.sdk.engine.SimulatedDriveGenerator
import com.telematicslab.demo.sdk.engine.TripPromptResolver
import com.telematicslab.demo.sdk.engine.TripScorer
import com.telematicslab.demo.sdk.engine.TripStateMachine
import com.telematicslab.demo.sdk.internal.CloudTripUploader
import com.telematicslab.demo.sdk.internal.EventSink
import com.telematicslab.demo.sdk.internal.RemoteConfigClient
import com.telematicslab.demo.sdk.internal.SdkSafeExecutor
import com.telematicslab.demo.sdk.internal.TimedWakeLockRequester
import com.telematicslab.demo.sdk.internal.WakeLockGatingEventSink
import com.telematicslab.demo.sdk.internal.WakeLockPolicy
import com.telematicslab.demo.sdk.internal.WakeLockStateController
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Locale
import kotlin.math.abs

class TelematicsEngineTest {

    @Test
    fun `coordinate frame mapper produces invariant vehicle G across arbitrary phone mount tilts`() {
        val mapperA = CoordinateFrameMapper()
        val mapperB = CoordinateFrameMapper()

        val bearingDeg = 90f
        val aWorldEast = 0.4f * CoordinateFrameMapper.GRAVITY_MS2
        val aWorldNorth = 0f
        val aWorldUp = 0f

        val rotA = SimulatedDriveGenerator.buildRotationMatrix(0f, 0f, 0f)
        val pLinA = floatArrayOf(
            rotA[0] * aWorldEast + rotA[3] * aWorldNorth + rotA[6] * aWorldUp,
            rotA[1] * aWorldEast + rotA[4] * aWorldNorth + rotA[7] * aWorldUp,
            rotA[2] * aWorldEast + rotA[5] * aWorldNorth + rotA[8] * aWorldUp
        )
        val resA = mapperA.mapWithRotationMatrix(
            linearAccelPhone = pLinA,
            rawAccelPhone = pLinA,
            rotMatrix9 = rotA,
            orientationRad = floatArrayOf(0f, 0f, 0f),
            gpsBearingDeg = bearingDeg,
            gpsSpeedMps = 12f,
            alpha = 1.0f
        )

        val yawB = Math.toRadians(40.0).toFloat()
        val pitchB = Math.toRadians(55.0).toFloat()
        val rollB = Math.toRadians(25.0).toFloat()
        val rotB = SimulatedDriveGenerator.buildRotationMatrix(yawB, pitchB, rollB)
        val pLinB = floatArrayOf(
            rotB[0] * aWorldEast + rotB[3] * aWorldNorth + rotB[6] * aWorldUp,
            rotB[1] * aWorldEast + rotB[4] * aWorldNorth + rotB[7] * aWorldUp,
            rotB[2] * aWorldEast + rotB[5] * aWorldNorth + rotB[8] * aWorldUp
        )
        val resB = mapperB.mapWithRotationMatrix(
            linearAccelPhone = pLinB,
            rawAccelPhone = pLinB,
            rotMatrix9 = rotB,
            orientationRad = floatArrayOf(yawB, pitchB, rollB),
            gpsBearingDeg = bearingDeg,
            gpsSpeedMps = 12f,
            alpha = 1.0f
        )

        assertEquals(0.40f, resA.rawLongG, 0.01f)
        assertEquals(0.40f, resB.rawLongG, 0.01f)
        assertEquals(0.0f, resB.rawLatG, 0.01f)
        assertEquals(0.0f, resB.rawVertG, 0.01f)
    }

    @Test
    fun `low pass filter attenuates single-sample pothole spike`() {
        val mapper = CoordinateFrameMapper()
        val rot = SimulatedDriveGenerator.buildRotationMatrix(0f, 0f, 0f)

        mapper.mapWithRotationMatrix(floatArrayOf(0f, 0f, 0f), floatArrayOf(0f, 0f, 9.81f), rot, floatArrayOf(0f, 0f, 0f), 0f, 10f, 0.22f)
        val spiked = mapper.mapWithRotationMatrix(
            linearAccelPhone = floatArrayOf(0f, CoordinateFrameMapper.GRAVITY_MS2, 0f),
            rawAccelPhone = floatArrayOf(0f, CoordinateFrameMapper.GRAVITY_MS2, 9.81f),
            rotMatrix9 = rot,
            orientationRad = floatArrayOf(0f, 0f, 0f),
            gpsBearingDeg = 0f,
            gpsSpeedMps = 10f,
            alpha = 0.22f
        )
        assertEquals(1.0f, spiked.rawLongG, 0.02f)
        assertTrue(spiked.filteredLongG < 0.25f)
    }

    @Test
    fun `dual engine bridge switches between standard and adaptive dual pole filters`() {
        val bridge = SensorEngineBridge()
        val std = bridge.filter(FilterEngineMode.STANDARD_LOW_PASS, 0.6f, 0f, 0f, 0.25f)
        assertEquals(FilterEngineMode.STANDARD_LOW_PASS, bridge.currentMode())
        assertEquals(0.6f, std.longG, 0.001f)

        // Seed adaptive engine at 0 then apply step to 1.0g: dual pole attenuates step more smoothly than single pole
        bridge.filter(FilterEngineMode.ADAPTIVE_DUAL_POLE, 0f, 0f, 0f, 0.25f)
        val adaptStep = bridge.filter(FilterEngineMode.ADAPTIVE_DUAL_POLE, 1.0f, 0f, 0f, 0.25f)
        assertEquals(FilterEngineMode.ADAPTIVE_DUAL_POLE, bridge.currentMode())
        assertTrue(adaptStep.longG in 0.01f..0.15f)
    }

    @Test
    fun `ring buffer overwrites cleanly and extracts time windows`() {
        val ring = ImuRingBuffer(capacity = 5)
        for (i in 1..8) {
            ring.push(i * 100L, i.toFloat(), 0f, 0f, 0f, 0f, 0f, 0f, 0f, 0f, 1f, 0f, 0f, 0f, 20f)
        }
        assertEquals(5, ring.count())
        val last = ring.snapshotLast(5)
        assertEquals(listOf(400L, 500L, 600L, 700L, 800L), last.map { it.timestampMs })

        val win = ring.snapshotWindow(500L, 700L)
        assertEquals(listOf(500L, 600L, 700L), win.map { it.timestampMs })
    }

    @Test
    fun `trip state machine bridges short traffic stop and ignores noisy gps`() {
        val fsm = TripStateMachine()
        val t0 = 1_000_000L

        fsm.onLocationUpdate(t0, speedMph = 35f, accuracyM = 120f, autoDetectEnabled = true, stopHysteresisMs = 90_000L)
        assertEquals(TripState.IDLE, fsm.state)

        fsm.onLocationUpdate(t0 + 1_000L, speedMph = 18f, accuracyM = 6f, autoDetectEnabled = true, stopHysteresisMs = 90_000L)
        assertEquals(TripState.VERIFYING, fsm.state)

        fsm.onLocationUpdate(t0 + 6_500L, speedMph = 24f, accuracyM = 5f, autoDetectEnabled = true, stopHysteresisMs = 90_000L)
        assertEquals(TripState.RECORDING, fsm.state)

        fsm.onLocationUpdate(t0 + 20_000L, speedMph = 0.5f, accuracyM = 5f, autoDetectEnabled = true, stopHysteresisMs = 90_000L)
        assertEquals(TripState.STOP_PENDING, fsm.state)

        fsm.onLocationUpdate(t0 + 60_000L, speedMph = 12f, accuracyM = 5f, autoDetectEnabled = true, stopHysteresisMs = 90_000L)
        assertEquals(TripState.RECORDING, fsm.state)

        fsm.onLocationUpdate(t0 + 80_000L, speedMph = 0f, accuracyM = 5f, autoDetectEnabled = true, stopHysteresisMs = 90_000L)
        assertEquals(TripState.STOP_PENDING, fsm.state)
        fsm.onLocationUpdate(t0 + 171_000L, speedMph = 0f, accuracyM = 5f, autoDetectEnabled = true, stopHysteresisMs = 90_000L)
        assertEquals(TripState.FINALIZING, fsm.state)
    }

    @Test
    fun `event detector rejects brief bump and detects sustained hard brake`() {
        val ring = ImuRingBuffer(300)
        val detector = DrivingEventDetector(ring)
        val cfg = SdkConfig()
        val t0 = 10_000L
        detector.reset(t0)

        for (i in 0..4) {
            val g = if (i in 1..2) -0.50f else 0f
            val out = detector.processSample(t0 + i * 50L, g, 0f, 1f, 0f, 0f, 0f, 30f, 37.77, -122.42, false, false, cfg)
            assertTrue(out.newEvents.isEmpty())
        }

        var brakeDetected = false
        for (i in 0..14) {
            val g = if (i in 1..11) -0.45f else 0f
            val out = detector.processSample(t0 + 5_000L + i * 50L, g, 0f, 1f, 0f, 0f, 0f, 30f, 37.77, -122.42, false, false, cfg)
            if (out.newEvents.any { it.type == EventType.HARD_BRAKE }) {
                brakeDetected = true
            }
        }
        assertTrue(brakeDetected)
    }

    @Test
    fun `impact classifier distinguishes phone drop from vehicle collision`() {
        val ring = ImuRingBuffer(300)
        val detector = DrivingEventDetector(ring)
        detector.reset(1_000L)

        val dropOut = detector.evaluateSyntheticImpact(
            nowMs = 5_000L,
            peakG = 4.6f,
            peakGyroRadS = 9.0f,
            preSpeedMph = 30f,
            postSpeedMph = 30f,
            lat = 37.7749,
            lon = -122.4194
        )
        assertEquals(ImpactVerdict.REJECTED_PHONE_DROP, dropOut.newImpacts.first().verdict)
        assertTrue(dropOut.newEvents.isEmpty())

        val crashOut = detector.evaluateSyntheticImpact(
            nowMs = 8_000L,
            peakG = 4.8f,
            peakGyroRadS = 2.0f,
            preSpeedMph = 40f,
            postSpeedMph = 0f,
            lat = 37.7749,
            lon = -122.4194
        )
        assertEquals(ImpactVerdict.POTENTIAL_IMPACT, crashOut.newImpacts.first().verdict)
        assertEquals(EventType.POTENTIAL_IMPACT, crashOut.newEvents.first().type)
    }

    @Test
    fun `wake lock state controller suppresses wakeful recording lock safely under turkish locale`() {
        val prevLocale = Locale.getDefault()
        try {
            Locale.setDefault(Locale("tr", "TR"))
            val controller = WakeLockStateController()
            val policy = WakeLockPolicy(stateDenylist = setOf("RECORDING", "VERIFYING"))

            val recDecision = controller.evaluateState(TripState.RECORDING, policy)
            assertFalse(recDecision.shouldHoldContinuousLock)

            val verDecision = controller.evaluateState(TripState.VERIFYING, policy)
            assertFalse(verDecision.shouldHoldContinuousLock)

            val overrideDecision = controller.evaluateState(
                TripState.RECORDING,
                policy.copy(legacyContinuousLockEnabled = true)
            )
            assertTrue(overrideDecision.shouldHoldContinuousLock)
        } finally {
            Locale.setDefault(prevLocale)
        }
    }

    @Test
    fun `wake lock gating event sink decorator requests timed lock only for allowlisted events`() {
        val forwarded = mutableListOf<DrivingEvent>()
        val lockRequests = mutableListOf<Pair<Long, String>>()

        val sink = WakeLockGatingEventSink(
            delegate = object : EventSink {
                override fun push(event: DrivingEvent) {
                    forwarded.add(event)
                }
            },
            policyFlow = MutableStateFlow(
                WakeLockPolicy(
                    eventAllowlist = setOf(EventType.POTENTIAL_IMPACT),
                    eventLockDurationMs = 15_000L
                )
            ),
            requester = object : TimedWakeLockRequester {
                override fun requestTimedLock(durationMs: Long, tag: String) {
                    lockRequests.add(durationMs to tag)
                }
            }
        )

        val brakeEvent = DrivingEvent("1", EventType.HARD_BRAKE, 100L, 5f, 0.4f, "g", 400L, 30f, 20f, 0.0, 0.0, "brake")
        val impactEvent = DrivingEvent("2", EventType.POTENTIAL_IMPACT, 200L, 10f, 4.5f, "g", 1500L, 40f, 0f, 0.0, 0.0, "impact")

        sink.push(brakeEvent)
        sink.push(impactEvent)

        assertEquals(2, forwarded.size)
        assertEquals(1, lockRequests.size)
        assertEquals(15_000L, lockRequests.first().first)
        assertTrue(lockRequests.first().second.contains("POTENTIAL_IMPACT"))
    }

    @Test
    fun `sdk safe executor catches runtime exceptions into fault count and rethrows cancellation`() {
        SdkSafeExecutor.resetForTest()
        val fallback = SdkSafeExecutor.runSafely("testOp", "safe-fallback") {
            throw IllegalStateException("boom")
        }
        assertEquals("safe-fallback", fallback)
        assertEquals(1, SdkSafeExecutor.faultCount())

        var cancelled = false
        try {
            SdkSafeExecutor.runSafely("cancelOp", Unit) {
                throw CancellationException("coroutine cancelled")
            }
        } catch (_: CancellationException) {
            cancelled = true
        }
        assertTrue(cancelled)
        assertEquals(1, SdkSafeExecutor.faultCount())
    }

    @Test
    fun `simulated drive pipeline detects expected driving events and produces valid score`() {
        val startMs = 1_700_000_000_000L
        val ticks = SimulatedDriveGenerator.buildScriptedTicks(startMs, hz = 25)
        val mapper = CoordinateFrameMapper()
        val ring = ImuRingBuffer(1500)
        val detector = DrivingEventDetector(ring)
        val cfg = SimulatedDriveGenerator.defaultConfigForDemo(SdkConfig())
        detector.reset(startMs)

        val collectedEvents = mutableListOf<EventType>()
        val collectedImpacts = mutableListOf<ImpactVerdict>()

        for (tick in ticks) {
            val nowMs = startMs + (tick.elapsedSec * 1000L).toLong()
            val mapped = mapper.mapWithRotationMatrix(
                tick.phoneLinear,
                tick.phoneRaw,
                tick.rotMatrix9,
                tick.orientationRad,
                tick.gnss.bearingDeg,
                tick.gnss.speedMph * 0.44704f,
                cfg.lowPassAlpha
            )
            ring.push(
                nowMs, mapped.filteredLongG, mapped.filteredLatG, mapped.filteredVertG,
                mapped.rawLongG, mapped.rawLatG, mapped.rawVertG,
                0f, 0f, 0f, mapped.rawTotalG,
                tick.gyro[0], tick.gyro[1], tick.gyro[2], tick.gnss.speedMph
            )
            val out = detector.processSample(
                nowMs, mapped.filteredLongG, mapped.filteredLatG, mapped.rawTotalG,
                tick.gyro[0], tick.gyro[1], tick.gyro[2],
                tick.gnss.speedMph, tick.gnss.lat, tick.gnss.lon,
                tick.screenOn, tick.handheld, cfg
            )
            collectedEvents += out.newEvents.map { it.type }
            collectedImpacts += out.newImpacts.map { it.verdict }
        }

        assertTrue("Expected HARD_BRAKE in simulated drive", EventType.HARD_BRAKE in collectedEvents)
        assertTrue("Expected RAPID_ACCEL in simulated drive", EventType.RAPID_ACCEL in collectedEvents)
        assertTrue("Expected HARSH_CORNER_LEFT in simulated drive", EventType.HARSH_CORNER_LEFT in collectedEvents)
        assertTrue("Expected DISTRACTION_HANDHELD in simulated drive", EventType.DISTRACTION_HANDHELD in collectedEvents)
        assertTrue("Expected REJECTED_PHONE_DROP in simulated drive", ImpactVerdict.REJECTED_PHONE_DROP in collectedImpacts)
        assertFalse("Phone drop should NOT become POTENTIAL_IMPACT", EventType.POTENTIAL_IMPACT in collectedEvents)

        val cleanScore = TripScorer.score(emptyList(), 5.0f)
        assertEquals(100, cleanScore.overall)
        assertNotNull(ticks.lastOrNull())
    }

    @Test
    fun `coordinate frame mapper preserves last valid GNSS heading while stopped`() {
        val mapper = CoordinateFrameMapper()
        val (rotMatrix, orientationRad) = mapper.rotationMatrixFromGravity(
            floatArrayOf(0f, 0f, CoordinateFrameMapper.GRAVITY_MS2)
        )

        val moving = mapper.mapWithRotationMatrix(
            linearAccelPhone = floatArrayOf(0f, 0f, 0f),
            rawAccelPhone = floatArrayOf(0f, 0f, CoordinateFrameMapper.GRAVITY_MS2),
            rotMatrix9 = rotMatrix,
            orientationRad = orientationRad,
            gpsBearingDeg = 135f,
            gpsSpeedMps = 8f,
            alpha = 0.2f
        )
        assertTrue(moving.headingLocked)
        assertEquals(135f, moving.headingDeg, 0.01f)

        val stopped = mapper.mapWithRotationMatrix(
            linearAccelPhone = floatArrayOf(0f, 0f, 0f),
            rawAccelPhone = floatArrayOf(0f, 0f, CoordinateFrameMapper.GRAVITY_MS2),
            rotMatrix9 = rotMatrix,
            orientationRad = orientationRad,
            gpsBearingDeg = 20f,
            gpsSpeedMps = 0.1f,
            alpha = 0.2f
        )
        assertTrue(stopped.headingLocked)
        assertEquals(135f, stopped.headingDeg, 0.01f)
    }

    @Test
    fun `gravity fallback rotation matrix forms orthonormal right-handed basis`() {
        val mapper = CoordinateFrameMapper()
        val (r, _) = mapper.rotationMatrixFromGravity(floatArrayOf(2.5f, 4.1f, 8.5f))
        assertEquals(9, r.size)

        val norm0 = r[0] * r[0] + r[1] * r[1] + r[2] * r[2]
        val norm1 = r[3] * r[3] + r[4] * r[4] + r[5] * r[5]
        val norm2 = r[6] * r[6] + r[7] * r[7] + r[8] * r[8]
        assertEquals(1f, norm0, 0.001f)
        assertEquals(1f, norm1, 0.001f)
        assertEquals(1f, norm2, 0.001f)

        val dot01 = r[0] * r[3] + r[1] * r[4] + r[2] * r[5]
        val dot12 = r[3] * r[6] + r[4] * r[7] + r[5] * r[8]
        val dot02 = r[0] * r[6] + r[1] * r[7] + r[2] * r[8]
        assertTrue(abs(dot01) < 0.001f)
        assertTrue(abs(dot12) < 0.001f)
        assertTrue(abs(dot02) < 0.001f)
    }

    @Test
    fun `event detector enforces refractory cooldown across repeated harsh braking`() {
        val ring = ImuRingBuffer(600)
        val detector = DrivingEventDetector(ring)
        val cfg = SdkConfig(hardBrakeG = 0.30f)
        val t0 = 1_700_000_000_000L
        detector.reset(t0)

        fun stepBrake(atMs: Long, longG: Float): Int {
            return detector.processSample(
                nowMs = atMs,
                longG = longG,
                latG = 0f,
                rawTotalG = 1.1f,
                gyroX = 0f,
                gyroY = 0f,
                gyroZ = 0f,
                speedMph = 35f,
                lat = 37.7749,
                lon = -122.4194,
                screenOn = false,
                handheld = false,
                config = cfg
            ).newEvents.count { it.type == EventType.HARD_BRAKE }
        }

        // First 500ms harsh brake pulse (emits on release at t0 + 500ms)
        var emitted = 0
        for (dt in 0L..450L step 50L) emitted += stepBrake(t0 + dt, -0.45f)
        emitted += stepBrake(t0 + 500L, 0f)
        assertEquals(1, emitted)

        // Second 500ms harsh brake pulse 1s later (< 4s cooldown -> suppressed)
        for (dt in 1500L..1950L step 50L) emitted += stepBrake(t0 + dt, -0.45f)
        emitted += stepBrake(t0 + 2000L, 0f)
        assertEquals(1, emitted)

        // Third 500ms harsh brake pulse after > 4s cooldown -> emits second event
        for (dt in 5000L..5450L step 50L) emitted += stepBrake(t0 + dt, -0.45f)
        emitted += stepBrake(t0 + 5500L, 0f)
        assertEquals(2, emitted)
    }

    @Test
    fun `trip scorer clamps extreme penalties between 0 and 100`() {
        val manyEvents = (1..25).map { idx ->
            DrivingEvent(
                id = "ev-$idx",
                type = if (idx % 2 == 0) EventType.POTENTIAL_IMPACT else EventType.HARD_BRAKE,
                timestampMs = 1_700_000_000_000L + idx * 1000L,
                elapsedTripSec = idx.toFloat(),
                peakValue = 0.6f,
                unit = "g",
                durationMs = 500L,
                speedStartMph = 45f,
                speedEndMph = 10f,
                lat = 37.7749,
                lon = -122.4194,
                details = "Extreme event"
            )
        }
        val lowScore = TripScorer.score(manyEvents, distanceMiles = 0.25f)
        assertEquals(0, lowScore.overall)
        assertTrue(lowScore.braking in 20..100)
    }

    @Test
    fun `transport modes, seat roles, and trip presets expose expected metadata`() {
        assertEquals("Car", TransportMode.CAR.label)
        assertEquals("Walk", TransportMode.WALK.label)
        assertEquals("Bicycle", TransportMode.BICYCLE.label)

        val preset = TripPreset(
            id = "commute_out",
            group = "Daily Commute",
            label = "Home -> Office",
            mode = TransportMode.BICYCLE,
            seatRole = SeatRole.DRIVER
        )
        assertEquals(TransportMode.BICYCLE, preset.mode)
        assertEquals("Driver", preset.seatRole.label)
    }

    @Test
    fun `prompt resolver matches exact plannedDate and advances to return trip after outbound capture`() {
        val presets = listOf(
            TripPreset("p1", "Commute", "Morning Commute (Out)", TransportMode.CAR, SeatRole.FRONT_PASSENGER, plannedDate = "2026-05-10"),
            TripPreset("p2", "Commute", "Evening Return", TransportMode.CAR, SeatRole.FRONT_PASSENGER, plannedDate = "2026-05-10"),
            TripPreset("p3", "Weekend", "Saturday Errand", TransportMode.CAR, SeatRole.DRIVER, plannedDate = "2026-05-11")
        )

        val first = TripPromptResolver.resolve(
            detectedMode = TransportMode.CAR,
            todayIso = "2026-05-10",
            presets = presets,
            completedTripLabels = emptySet(),
            tripState = TripState.IDLE,
            promptsEnabled = true,
            nowMs = 1_000_000L,
            lastPromptMs = 0L,
            snoozeUntilMs = 0L
        )
        assertNotNull(first)
        assertTrue(first!!.isPlannedMatch)
        assertEquals("Morning Commute (Out)", first.primaryLabel)
        assertEquals(SeatRole.FRONT_PASSENGER, first.seatRole)

        val second = TripPromptResolver.resolve(
            detectedMode = TransportMode.CAR,
            todayIso = "2026-05-10",
            presets = presets,
            completedTripLabels = setOf("Morning Commute (Out)"),
            tripState = TripState.IDLE,
            promptsEnabled = true,
            nowMs = 2_000_000L,
            lastPromptMs = 0L,
            snoozeUntilMs = 0L
        )
        assertNotNull(second)
        assertEquals("Evening Return", second!!.primaryLabel)
        assertEquals("Extra Drive", second.secondaryExtraLabel)
    }

    @Test
    fun `prompt resolver mentions multiple uncaptured trips on the same date`() {
        val presets = listOf(
            TripPreset("p1", "Errands", "Market Run (Out)", TransportMode.CAR, SeatRole.DRIVER, plannedDate = "2026-05-10"),
            TripPreset("p2", "Errands", "Hardware Store (Out)", TransportMode.CAR, SeatRole.DRIVER, plannedDate = "2026-05-10")
        )
        val suggestion = TripPromptResolver.resolve(
            detectedMode = TransportMode.CAR,
            todayIso = "2026-05-10",
            presets = presets,
            completedTripLabels = emptySet(),
            tripState = TripState.IDLE,
            promptsEnabled = true,
            nowMs = 1_000_000L,
            lastPromptMs = 0L,
            snoozeUntilMs = 0L
        )
        assertNotNull(suggestion)
        assertEquals("Market Run (Out)", suggestion!!.primaryLabel)
        assertTrue(suggestion.body.contains("Hardware Store (Out)"))
        assertEquals("Extra Drive", suggestion.secondaryExtraLabel)
    }

    @Test
    fun `prompt resolver falls back to generic extra drive once today trips are captured`() {
        val presets = listOf(
            TripPreset("p1", "Day", "Planned Out", TransportMode.CAR, SeatRole.DRIVER, plannedDate = "2026-05-10"),
            TripPreset("p2", "Next", "Tomorrow Drive", TransportMode.CAR, SeatRole.DRIVER, plannedDate = "2026-05-11")
        )
        val fallback = TripPromptResolver.resolve(
            detectedMode = TransportMode.CAR,
            todayIso = "2026-05-10",
            presets = presets,
            completedTripLabels = setOf("Planned Out"),
            tripState = TripState.IDLE,
            promptsEnabled = true,
            nowMs = 1_000_000L,
            lastPromptMs = 0L,
            snoozeUntilMs = 0L
        )
        assertNotNull(fallback)
        assertFalse(fallback!!.isPlannedMatch)
        assertEquals("Extra Drive", fallback.primaryLabel)
        assertEquals(SeatRole.DRIVER, fallback.seatRole)
        assertEquals(null, fallback.secondaryExtraLabel)
    }

    @Test
    fun `prompt resolver matches undated bicycle preset and falls back to extra bike ride`() {
        val presets = listOf(
            TripPreset("b1", "Bike", "Bike Commute (Out)", TransportMode.BICYCLE, SeatRole.DRIVER, plannedDate = null)
        )
        val plannedBike = TripPromptResolver.resolve(
            detectedMode = TransportMode.BICYCLE,
            todayIso = "2026-05-10",
            presets = presets,
            completedTripLabels = emptySet(),
            tripState = TripState.IDLE,
            promptsEnabled = true,
            nowMs = 1_000_000L,
            lastPromptMs = 0L,
            snoozeUntilMs = 0L
        )
        assertNotNull(plannedBike)
        assertTrue(plannedBike!!.isPlannedMatch)
        assertEquals("Bike Commute (Out)", plannedBike.primaryLabel)
        assertEquals("Extra Bike Ride", plannedBike.secondaryExtraLabel)

        val extraBike = TripPromptResolver.resolve(
            detectedMode = TransportMode.BICYCLE,
            todayIso = "2026-05-10",
            presets = presets,
            completedTripLabels = setOf("Bike Commute (Out)"),
            tripState = TripState.IDLE,
            promptsEnabled = true,
            nowMs = 1_000_000L,
            lastPromptMs = 0L,
            snoozeUntilMs = 0L
        )
        assertNotNull(extraBike)
        assertFalse(extraBike!!.isPlannedMatch)
        assertEquals("Extra Bike Ride", extraBike.primaryLabel)
    }

    @Test
    fun `prompt resolver suppresses when recording, disabled, snoozed, or within cooldown unless bypassed`() {
        val presets = listOf(
            TripPreset("p1", "Day", "Planned Out", TransportMode.CAR, SeatRole.DRIVER, plannedDate = "2026-05-10")
        )
        val now = 2_000_000L

        assertEquals(null, TripPromptResolver.resolve(TransportMode.CAR, "2026-05-10", presets, emptySet(), TripState.RECORDING, true, now, 0L, 0L))
        assertEquals(null, TripPromptResolver.resolve(TransportMode.CAR, "2026-05-10", presets, emptySet(), TripState.IDLE, false, now, 0L, 0L))
        assertEquals(null, TripPromptResolver.resolve(TransportMode.CAR, "2026-05-10", presets, emptySet(), TripState.IDLE, true, now, now - 5_000L, 0L))
        assertEquals(null, TripPromptResolver.resolve(TransportMode.CAR, "2026-05-10", presets, emptySet(), TripState.IDLE, true, now, 0L, now + 60_000L))
        assertEquals(null, TripPromptResolver.resolve(TransportMode.WALK, "2026-05-10", presets, emptySet(), TripState.IDLE, true, now, 0L, 0L))

        val bypassed = TripPromptResolver.resolve(
            detectedMode = TransportMode.CAR,
            todayIso = "2026-05-10",
            presets = presets,
            completedTripLabels = emptySet(),
            tripState = TripState.IDLE,
            promptsEnabled = true,
            nowMs = now,
            lastPromptMs = now - 5_000L,
            snoozeUntilMs = now + 60_000L,
            bypassCooldown = true
        )
        assertNotNull(bypassed)
        assertEquals("Planned Out", bypassed!!.primaryLabel)
    }

    @Test
    fun `unlock url percent-encodes exclamation marks and trims trailing slash`() {
        val url = CloudTripUploader.buildUnlockUrl("https://example.test/", "DemoPass-2026!")
        assertEquals("https://example.test/?unlock=DemoPass-2026%21", url)
    }

    @Test
    fun `remote config client parses and clamps thresholds cleanly`() {
        val json = """{"version":4,"autoDetectEnabled":false,"motionPromptsEnabled":true,"autoStartVerifyMph":16,"stopHysteresisSec":120,"autoStopNonCar":true,"filterEngineMode":"ADAPTIVE_DUAL_POLE","fifoBatchLatencyMs":2000,"legacyWakeLockEnabled":false}"""
        val parsed = RemoteConfigClient.parseConfigJson(json, SdkConfig())
        assertNotNull(parsed)
        val (cfg, ver) = parsed!!
        assertEquals(4, ver)
        assertEquals(false, cfg.autoDetectEnabled)
        assertEquals(16f, cfg.autoStartVerifyMph, 0.01f)
        assertEquals(120, cfg.stopHysteresisSec)
        assertEquals(true, cfg.autoStopNonCar)
        assertEquals(FilterEngineMode.ADAPTIVE_DUAL_POLE, cfg.filterEngineMode)
        assertEquals(2000, cfg.fifoBatchLatencyMs)
    }

    @Test
    fun `trip state machine honors custom autoStartVerifyMph threshold`() {
        val fsm = TripStateMachine()
        val t0 = 50_000L
        assertEquals(null, fsm.onLocationUpdate(t0, speedMph = 13f, accuracyM = 5f, autoDetectEnabled = true, stopHysteresisMs = 90_000L, autoStartVerifyMph = 16f))
        assertEquals(TripState.IDLE, fsm.state)

        fsm.onLocationUpdate(t0 + 1_000L, speedMph = 18f, accuracyM = 5f, autoDetectEnabled = true, stopHysteresisMs = 90_000L, autoStartVerifyMph = 16f)
        assertEquals(TripState.VERIFYING, fsm.state)
        fsm.onLocationUpdate(t0 + 6_500L, speedMph = 18f, accuracyM = 5f, autoDetectEnabled = true, stopHysteresisMs = 90_000L, autoStartVerifyMph = 16f)
        assertEquals(TripState.RECORDING, fsm.state)
    }

    @Test
    fun `trip state machine skips auto-stop when allowAutoStop is false`() {
        val fsm = TripStateMachine()
        val t0 = 50_000L
        fsm.manualStart(t0)
        assertEquals(TripState.RECORDING, fsm.state)
        fsm.onLocationUpdate(t0 + 5_000L, speedMph = 0f, accuracyM = 5f, autoDetectEnabled = true, stopHysteresisMs = 90_000L, allowAutoStop = false)
        assertEquals(TripState.RECORDING, fsm.state)
    }

    @Test
    fun `home departure evaluator suppresses inside home geofence`() {
        val evaluator = HomeDepartureEvaluator()
        val cfg = HomeGeofenceConfig(enabled = true, lat = 37.7749, lon = -122.4194, radiusMeters = 120f, minInVehicleSec = 60, minSpeedMph = 10f)
        val res = evaluator.evaluateLocation(100_000L, 37.7750, -122.4194, speedMph = 15f, accuracyM = 5f, config = cfg, isIdle = true)
        assertEquals(HomeDepartureDecision.SUPPRESS_EARLY_AUTO_START, res)
    }

    @Test
    fun `home departure evaluator suppresses exit until over 60s in vehicle`() {
        val evaluator = HomeDepartureEvaluator()
        val cfg = HomeGeofenceConfig(enabled = true, lat = 37.7749, lon = -122.4194, radiusMeters = 120f, minInVehicleSec = 60, minSpeedMph = 10f)
        val t0 = 100_000L
        evaluator.evaluateLocation(t0, 37.7749, -122.4194, speedMph = 0f, accuracyM = 5f, config = cfg, isIdle = true)
        val justExited = evaluator.evaluateLocation(t0 + 30_000L, 37.7778, -122.4194, speedMph = 25f, accuracyM = 5f, config = cfg, isIdle = true)
        assertEquals(HomeDepartureDecision.SUPPRESS_EARLY_AUTO_START, justExited)
        val at60s = evaluator.evaluateLocation(t0 + 90_000L, 37.7798, -122.4194, speedMph = 25f, accuracyM = 5f, config = cfg, isIdle = true)
        assertEquals(HomeDepartureDecision.SUPPRESS_EARLY_AUTO_START, at60s)
    }

    @Test
    fun `home departure evaluator requires speed strictly above 10 mph after 60s`() {
        val evaluator = HomeDepartureEvaluator()
        val cfg = HomeGeofenceConfig(enabled = true, lat = 37.7749, lon = -122.4194, radiusMeters = 120f, minInVehicleSec = 60, minSpeedMph = 10f)
        val t0 = 100_000L
        evaluator.onGeofenceEnter()
        evaluator.onGeofenceExit(t0)
        evaluator.onVehicleActivityEnter(t0)
        val at10Mph = evaluator.evaluateLocation(t0 + 65_000L, 37.7778, -122.4194, speedMph = 10f, accuracyM = 5f, config = cfg, isIdle = true)
        assertEquals(HomeDepartureDecision.SUPPRESS_EARLY_AUTO_START, at10Mph)
    }

    @Test
    fun `home departure evaluator triggers once above 60s and 10 mph and leaves away trips normal`() {
        val evaluator = HomeDepartureEvaluator()
        val cfg = HomeGeofenceConfig(enabled = true, lat = 37.7749, lon = -122.4194, radiusMeters = 120f, minInVehicleSec = 60, minSpeedMph = 10f)
        val t0 = 100_000L
        assertEquals(HomeDepartureDecision.NONE, evaluator.evaluateLocation(t0, 37.7948, -122.4194, speedMph = 22f, accuracyM = 5f, config = cfg, isIdle = true))
        evaluator.evaluateLocation(t0 + 1_000L, 37.7749, -122.4194, speedMph = 0f, accuracyM = 5f, config = cfg, isIdle = true)
        evaluator.onVehicleActivityEnter(t0 + 2_000L)
        val trigger = evaluator.evaluateLocation(t0 + 65_000L, 37.7778, -122.4194, speedMph = 16f, accuracyM = 5f, config = cfg, isIdle = true)
        assertEquals(HomeDepartureDecision.TRIGGER_AUTO_RECORD, trigger)
        val secondRead = evaluator.evaluateLocation(t0 + 66_000L, 37.7783, -122.4194, speedMph = 22f, accuracyM = 5f, config = cfg, isIdle = true)
        assertEquals(HomeDepartureDecision.NONE, secondRead)
    }

    @Test
    fun `location speed arbitrator protects GPS speed and derives fallback when speedless`() {
        val arb = LocationSpeedArbitrator()
        val t0 = 1_000_000L
        val gps1 = arb.evaluateFix(t0, "gps", 37.7749, -122.4194, 6f, hasSpeed = true, reportedSpeedMps = 13.4112f)
        assertNotNull(gps1)
        assertEquals(30f, gps1!!, 0.2f)

        val staleFused = arb.evaluateFix(t0 + 500L, "fused", 37.7749, -122.4194, 35f, hasSpeed = false, reportedSpeedMps = 0f)
        assertEquals(null, staleFused)

        val fusedFallback = arb.evaluateFix(t0 + 5_000L, "fused", 37.7755, -122.4194, 12f, hasSpeed = false, reportedSpeedMps = 0f)
        assertNotNull(fusedFallback)
        assertTrue(fusedFallback!! in 20f..45f)
    }

    @Test
    fun `remote config client parses autoStopMode MANUAL_ONLY and extended hysteresis`() {
        val json = """{"version":5,"autoStartVerifyMph":12,"autoStopMode":"MANUAL_ONLY","stopHysteresisSec":300,"filterEngineMode":"STANDARD_LOW_PASS","fifoBatchLatencyMs":0}"""
        val parsed = RemoteConfigClient.parseConfigJson(json, SdkConfig())
        assertNotNull(parsed)
        val (cfg, ver) = parsed!!
        assertEquals(5, ver)
        assertEquals(AutoStopMode.MANUAL_ONLY, cfg.autoStopMode)
        assertEquals(0, cfg.stopHysteresisSec)
        assertEquals(12f, cfg.autoStartVerifyMph, 0.01f)
    }

    @Test
    fun `cloud trip uploader parses trip id lists from both array and object payloads`() {
        val arrayPayload = """[{"id":"abc12345","distanceMiles":3.2},{"id":"ios98765"}]"""
        assertEquals(listOf("abc12345", "ios98765"), CloudTripUploader.parseCloudTripIds(arrayPayload))

        val wrappedPayload = """{"trips":[{"id":"trip-1"},{"id":""},{"id":"trip-2"}]}"""
        assertEquals(listOf("trip-1", "trip-2"), CloudTripUploader.parseCloudTripIds(wrappedPayload))
    }

    @Test
    fun `trip device label unifies S20 models and parses summaries cleanly`() {
        assertEquals("S20", TripDeviceLabel.detectAndroidDeviceTag("SM-G986U"))
        assertEquals("S20", TripDeviceLabel.detectAndroidDeviceTag("SM-G981U1"))
        assertEquals("S23", TripDeviceLabel.detectAndroidDeviceTag("SM-S911U"))
        assertEquals("S20", TripDeviceLabel.extractDeviceTag("Outbound Drive [S20]"))
        assertEquals("iPhone", TripDeviceLabel.extractDeviceTag("Legacy [iOS]"))
        assertEquals("Outbound Drive", TripDeviceLabel.stripDeviceTag("Outbound Drive [S20]"))
        assertEquals("Outbound Drive [S20]", TripDeviceLabel.ensureDeviceTag("Outbound Drive", "S20"))
        assertEquals("Outbound Drive [S23]", TripDeviceLabel.ensureDeviceTag("Outbound Drive [S23]", "S20"))

        val summariesJson = """[{"id":"a94032b7","tripLabel":"Return Drive [S20]","seatRole":"FRONT_PASSENGER","transportMode":"CAR"}]"""
        val parsed = CloudTripUploader.parseCloudTripSummaries(summariesJson)
        assertEquals(1, parsed.size)
        assertEquals("a94032b7", parsed[0].id)
        assertEquals("Return Drive [S20]", parsed[0].tripLabel)
        assertEquals("CAR", parsed[0].transportMode)
    }
}
