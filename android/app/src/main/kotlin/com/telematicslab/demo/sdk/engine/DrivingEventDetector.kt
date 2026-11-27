package com.telematicslab.demo.sdk.engine

import com.telematicslab.demo.sdk.api.DrivingEvent
import com.telematicslab.demo.sdk.api.EventType
import com.telematicslab.demo.sdk.api.ImpactAnalysis
import com.telematicslab.demo.sdk.api.ImpactVerdict
import com.telematicslab.demo.sdk.api.SdkConfig
import java.util.UUID
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.sqrt

/**
 * Real-time driving event detector and 2-stage impact vs. phone-drop classifier.
 * Uses sustained-duration accumulators + cooldowns to reject single-sample bumps,
 * and cross-checks high-G spikes against pre/post GNSS Δv in [ImuRingBuffer].
 */
class DrivingEventDetector(private val ringBuffer: ImuRingBuffer) {

    private data class ThresholdTracker(
        var activeStartMs: Long = 0L,
        var peakVal: Float = 0f,
        var startSpeedMph: Float = 0f,
        var lastEmittedMs: Long = 0L
    ) {
        fun reset() {
            activeStartMs = 0L
            peakVal = 0f
            startSpeedMph = 0f
        }
    }

    private data class PendingImpact(
        val triggerMs: Long,
        val evaluateAtMs: Long,
        val peakG: Float,
        val peakGyroRadS: Float,
        val lat: Double,
        val lon: Double,
        val syntheticPreSpeedMph: Float? = null,
        val syntheticPostSpeedMph: Float? = null
    )

    private val brakeTracker = ThresholdTracker()
    private val accelTracker = ThresholdTracker()
    private val cornerLeftTracker = ThresholdTracker()
    private val cornerRightTracker = ThresholdTracker()
    private val speedingTracker = ThresholdTracker()
    private val screenTracker = ThresholdTracker()
    private val handheldTracker = ThresholdTracker()

    private var lastImpactTriggerMs: Long = 0L
    private var pendingImpact: PendingImpact? = null
    private var tripStartMs: Long = 0L

    data class DetectionOutput(
        val newEvents: List<DrivingEvent> = emptyList(),
        val newImpacts: List<ImpactAnalysis> = emptyList()
    )

    @Synchronized
    fun reset(tripStartMs: Long) {
        this.tripStartMs = tripStartMs
        brakeTracker.reset(); brakeTracker.lastEmittedMs = 0L
        accelTracker.reset(); accelTracker.lastEmittedMs = 0L
        cornerLeftTracker.reset(); cornerLeftTracker.lastEmittedMs = 0L
        cornerRightTracker.reset(); cornerRightTracker.lastEmittedMs = 0L
        speedingTracker.reset(); speedingTracker.lastEmittedMs = 0L
        screenTracker.reset(); screenTracker.lastEmittedMs = 0L
        handheldTracker.reset(); handheldTracker.lastEmittedMs = 0L
        lastImpactTriggerMs = 0L
        pendingImpact = null
    }

    @Synchronized
    fun processSample(
        nowMs: Long,
        longG: Float,
        latG: Float,
        rawTotalG: Float,
        gyroX: Float,
        gyroY: Float,
        gyroZ: Float,
        speedMph: Float,
        lat: Double,
        lon: Double,
        screenOn: Boolean,
        handheld: Boolean,
        config: SdkConfig
    ): DetectionOutput {
        if (tripStartMs == 0L) tripStartMs = nowMs
        val events = ArrayList<DrivingEvent>()
        val impacts = ArrayList<ImpactAnalysis>()

        updateTracker(
            tracker = brakeTracker,
            condition = longG <= -config.hardBrakeG && speedMph >= 5f,
            magnitude = -longG,
            nowMs = nowMs,
            minDurationMs = 350L,
            cooldownMs = 4_000L,
            speedMph = speedMph
        )?.let { (peak, dur, startSpd) ->
            events += buildEvent(
                EventType.HARD_BRAKE, nowMs, peak, "g", dur, startSpd, speedMph, lat, lon,
                "Decelerated at ${"%.2f".format(peak)}g for ${dur}ms"
            )
        }

        updateTracker(
            tracker = accelTracker,
            condition = longG >= config.rapidAccelG && speedMph >= 3f,
            magnitude = longG,
            nowMs = nowMs,
            minDurationMs = 450L,
            cooldownMs = 4_000L,
            speedMph = speedMph
        )?.let { (peak, dur, startSpd) ->
            events += buildEvent(
                EventType.RAPID_ACCEL, nowMs, peak, "g", dur, startSpd, speedMph, lat, lon,
                "Accelerated at +${"%.2f".format(peak)}g for ${dur}ms"
            )
        }

        updateTracker(
            tracker = cornerLeftTracker,
            condition = latG <= -config.harshCornerG && speedMph >= 8f,
            magnitude = abs(latG),
            nowMs = nowMs,
            minDurationMs = 450L,
            cooldownMs = 4_000L,
            speedMph = speedMph
        )?.let { (peak, dur, startSpd) ->
            events += buildEvent(
                EventType.HARSH_CORNER_LEFT, nowMs, peak, "g", dur, startSpd, speedMph, lat, lon,
                "Lateral cornering ${"%.2f".format(peak)}g for ${dur}ms"
            )
        }

        updateTracker(
            tracker = cornerRightTracker,
            condition = latG >= config.harshCornerG && speedMph >= 8f,
            magnitude = abs(latG),
            nowMs = nowMs,
            minDurationMs = 450L,
            cooldownMs = 4_000L,
            speedMph = speedMph
        )?.let { (peak, dur, startSpd) ->
            events += buildEvent(
                EventType.HARSH_CORNER_RIGHT, nowMs, peak, "g", dur, startSpd, speedMph, lat, lon,
                "Lateral cornering ${"%.2f".format(peak)}g for ${dur}ms"
            )
        }

        updateTracker(
            tracker = speedingTracker,
            condition = speedMph > config.speedingLimitMph,
            magnitude = speedMph,
            nowMs = nowMs,
            minDurationMs = 3_000L,
            cooldownMs = 30_000L,
            speedMph = speedMph
        )?.let { (peak, dur, startSpd) ->
            events += buildEvent(
                EventType.SPEEDING, nowMs, peak, "mph", dur, startSpd, speedMph, lat, lon,
                "Exceeded ${config.speedingLimitMph.toInt()} mph (peak ${peak.toInt()} mph)"
            )
        }

        updateTracker(
            tracker = screenTracker,
            condition = screenOn && speedMph >= 10f,
            magnitude = speedMph,
            nowMs = nowMs,
            minDurationMs = 3_000L,
            cooldownMs = 20_000L,
            speedMph = speedMph
        )?.let { (peak, dur, startSpd) ->
            events += buildEvent(
                EventType.DISTRACTION_SCREEN, nowMs, peak, "mph", dur, startSpd, speedMph, lat, lon,
                "Screen active for ${dur / 1000}s while driving"
            )
        }

        updateTracker(
            tracker = handheldTracker,
            condition = handheld && speedMph >= 10f,
            magnitude = speedMph,
            nowMs = nowMs,
            minDurationMs = 2_500L,
            cooldownMs = 20_000L,
            speedMph = speedMph
        )?.let { (peak, dur, startSpd) ->
            events += buildEvent(
                EventType.DISTRACTION_HANDHELD, nowMs, peak, "mph", dur, startSpd, speedMph, lat, lon,
                "Handheld motion detected for ${"%.1f".format(dur / 1000f)}s"
            )
        }

        val gyroMag = sqrt(gyroX * gyroX + gyroY * gyroY + gyroZ * gyroZ)
        val activePending = pendingImpact
        if (activePending == null && rawTotalG >= config.impactThresholdG && nowMs - lastImpactTriggerMs >= 5_000L) {
            lastImpactTriggerMs = nowMs
            pendingImpact = PendingImpact(
                triggerMs = nowMs,
                evaluateAtMs = nowMs + 2_200L,
                peakG = rawTotalG,
                peakGyroRadS = gyroMag,
                lat = lat,
                lon = lon
            )
        } else if (activePending != null) {
            val updatedPeakG = max(activePending.peakG, rawTotalG)
            val updatedPeakGyro = max(activePending.peakGyroRadS, gyroMag)
            pendingImpact = activePending.copy(peakG = updatedPeakG, peakGyroRadS = updatedPeakGyro)
            if (nowMs >= activePending.evaluateAtMs) {
                val analysis = evaluatePendingImpact(pendingImpact!!, speedMph)
                impacts += analysis
                if (analysis.verdict == ImpactVerdict.POTENTIAL_IMPACT) {
                    events += buildEvent(
                        EventType.POTENTIAL_IMPACT,
                        activePending.triggerMs,
                        analysis.peakG,
                        "g",
                        2_200L,
                        analysis.preSpeedMph,
                        analysis.postSpeedMph,
                        activePending.lat,
                        activePending.lon,
                        analysis.reason
                    )
                }
                pendingImpact = null
            }
        }

        return DetectionOutput(events, impacts)
    }

    @Synchronized
    fun evaluateSyntheticImpact(
        nowMs: Long,
        peakG: Float,
        peakGyroRadS: Float,
        preSpeedMph: Float,
        postSpeedMph: Float,
        lat: Double,
        lon: Double
    ): DetectionOutput {
        val pending = PendingImpact(
            triggerMs = nowMs,
            evaluateAtMs = nowMs,
            peakG = peakG,
            peakGyroRadS = peakGyroRadS,
            lat = lat,
            lon = lon,
            syntheticPreSpeedMph = preSpeedMph,
            syntheticPostSpeedMph = postSpeedMph
        )
        val analysis = evaluatePendingImpact(pending, postSpeedMph)
        val events = if (analysis.verdict == ImpactVerdict.POTENTIAL_IMPACT) {
            listOf(
                buildEvent(
                    EventType.POTENTIAL_IMPACT, nowMs, peakG, "g", 1_500L,
                    preSpeedMph, postSpeedMph, lat, lon, analysis.reason
                )
            )
        } else {
            emptyList()
        }
        return DetectionOutput(events, listOf(analysis))
    }

    private fun evaluatePendingImpact(pending: PendingImpact, currentSpeedMph: Float): ImpactAnalysis {
        val preSpeed: Float
        val postSpeed: Float
        if (pending.syntheticPreSpeedMph != null && pending.syntheticPostSpeedMph != null) {
            preSpeed = pending.syntheticPreSpeedMph
            postSpeed = pending.syntheticPostSpeedMph
        } else {
            val preSamples = ringBuffer.snapshotWindow(pending.triggerMs - 3_000L, pending.triggerMs - 500L)
            val postSamples = ringBuffer.snapshotWindow(pending.triggerMs + 800L, pending.evaluateAtMs)
            preSpeed = if (preSamples.isNotEmpty()) preSamples.map { it.speedMph }.average().toFloat() else currentSpeedMph
            postSpeed = if (postSamples.isNotEmpty()) postSamples.map { it.speedMph }.average().toFloat() else currentSpeedMph
        }

        val deltaV = (preSpeed - postSpeed).coerceAtLeast(0f)
        val isVehicleImpact = preSpeed >= 12f && deltaV >= 8f

        val verdict = if (isVehicleImpact) ImpactVerdict.POTENTIAL_IMPACT else ImpactVerdict.REJECTED_PHONE_DROP
        val reason = if (isVehicleImpact) {
            "Peak ${"%.1f".format(pending.peakG)}g + speed drop ${"%.0f".format(preSpeed)}→${"%.0f".format(postSpeed)} mph (Δv=${"%.0f".format(deltaV)} mph)"
        } else {
            "Rejected: peak ${"%.1f".format(pending.peakG)}g, gyro ${"%.1f".format(pending.peakGyroRadS)} rad/s, pre=${"%.0f".format(preSpeed)} mph, Δv=${"%.0f".format(deltaV)} mph (< 8 mph)"
        }

        return ImpactAnalysis(
            timestampMs = pending.triggerMs,
            peakG = pending.peakG,
            preSpeedMph = preSpeed,
            postSpeedMph = postSpeed,
            deltaVMph = deltaV,
            peakGyroRadS = pending.peakGyroRadS,
            verdict = verdict,
            reason = reason
        )
    }

    private fun updateTracker(
        tracker: ThresholdTracker,
        condition: Boolean,
        magnitude: Float,
        nowMs: Long,
        minDurationMs: Long,
        cooldownMs: Long,
        speedMph: Float
    ): Triple<Float, Long, Float>? {
        if (condition) {
            if (tracker.activeStartMs == 0L) {
                tracker.activeStartMs = nowMs
                tracker.peakVal = magnitude
                tracker.startSpeedMph = speedMph
            } else if (magnitude > tracker.peakVal) {
                tracker.peakVal = magnitude
            }
            return null
        } else if (tracker.activeStartMs != 0L) {
            val dur = nowMs - tracker.activeStartMs
            val peak = tracker.peakVal
            val startSpd = tracker.startSpeedMph
            tracker.reset()
            if (dur >= minDurationMs && nowMs - tracker.lastEmittedMs >= cooldownMs) {
                tracker.lastEmittedMs = nowMs
                return Triple(peak, dur, startSpd)
            }
        }
        return null
    }

    private fun buildEvent(
        type: EventType,
        nowMs: Long,
        peak: Float,
        unit: String,
        durationMs: Long,
        speedStartMph: Float,
        speedEndMph: Float,
        lat: Double,
        lon: Double,
        details: String
    ): DrivingEvent {
        val snippet = ringBuffer.snapshotWindow(nowMs - 2_500L, nowMs + 2_500L)
        val elapsedSec = ((nowMs - tripStartMs).coerceAtLeast(0L) / 1000f)
        return DrivingEvent(
            id = UUID.randomUUID().toString().take(8),
            type = type,
            timestampMs = nowMs,
            elapsedTripSec = elapsedSec,
            peakValue = peak,
            unit = unit,
            durationMs = durationMs,
            speedStartMph = speedStartMph,
            speedEndMph = speedEndMph,
            lat = lat,
            lon = lon,
            details = details,
            waveformSnippet = snippet
        )
    }
}
