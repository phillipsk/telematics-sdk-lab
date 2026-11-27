package com.telematicslab.demo.sdk.engine

import com.telematicslab.demo.sdk.api.FilterEngineMode

data class FilteredMotion(
    val longG: Float,
    val latG: Float,
    val vertG: Float
)

/**
 * Common motion-filtering contract allowing multiple underlying DSP engines
 * to serve the same downstream trip FSM and event detector.
 */
interface MotionFilterEngine {
    fun reset()
    fun filter(rawLongG: Float, rawLatG: Float, rawVertG: Float, alpha: Float): FilteredMotion
}

class StandardLowPassEngine : MotionFilterEngine {
    private var seeded = false
    private var longG = 0f
    private var latG = 0f
    private var vertG = 0f

    override fun reset() {
        seeded = false
        longG = 0f
        latG = 0f
        vertG = 0f
    }

    override fun filter(rawLongG: Float, rawLatG: Float, rawVertG: Float, alpha: Float): FilteredMotion {
        val a = alpha.coerceIn(0.05f, 1.0f)
        if (!seeded) {
            longG = rawLongG
            latG = rawLatG
            vertG = rawVertG
            seeded = true
        } else {
            longG = a * rawLongG + (1f - a) * longG
            latG = a * rawLatG + (1f - a) * latG
            vertG = a * rawVertG + (1f - a) * vertG
        }
        return FilteredMotion(longG, latG, vertG)
    }
}

/**
 * Dual-pole cascaded low-pass engine with stronger high-frequency road-chatter rejection
 * while keeping sustained vehicle acceleration maneuvers responsive.
 */
class AdaptiveTwoStageFilterEngine : MotionFilterEngine {
    private var seeded = false
    private var s1Long = 0f; private var s1Lat = 0f; private var s1Vert = 0f
    private var s2Long = 0f; private var s2Lat = 0f; private var s2Vert = 0f

    override fun reset() {
        seeded = false
        s1Long = 0f; s1Lat = 0f; s1Vert = 0f
        s2Long = 0f; s2Lat = 0f; s2Vert = 0f
    }

    override fun filter(rawLongG: Float, rawLatG: Float, rawVertG: Float, alpha: Float): FilteredMotion {
        val a1 = (alpha * 1.15f).coerceIn(0.06f, 0.90f)
        val a2 = (alpha * 0.85f).coerceIn(0.05f, 0.85f)
        if (!seeded) {
            s1Long = rawLongG; s1Lat = rawLatG; s1Vert = rawVertG
            s2Long = rawLongG; s2Lat = rawLatG; s2Vert = rawVertG
            seeded = true
        } else {
            s1Long = a1 * rawLongG + (1f - a1) * s1Long
            s1Lat = a1 * rawLatG + (1f - a1) * s1Lat
            s1Vert = a1 * rawVertG + (1f - a1) * s1Vert

            s2Long = a2 * s1Long + (1f - a2) * s2Long
            s2Lat = a2 * s1Lat + (1f - a2) * s2Lat
            s2Vert = a2 * s1Vert + (1f - a2) * s2Vert
        }
        return FilteredMotion(s2Long, s2Lat, s2Vert)
    }
}

/**
 * Dual-engine bridge routing IMU frames to the active [MotionFilterEngine] implementation
 * without coupling downstream consumers to concrete DSP backends.
 */
class SensorEngineBridge(
    private val standardEngine: MotionFilterEngine = StandardLowPassEngine(),
    private val adaptiveEngine: MotionFilterEngine = AdaptiveTwoStageFilterEngine()
) {
    private var activeMode: FilterEngineMode = FilterEngineMode.STANDARD_LOW_PASS

    @Synchronized
    fun reset() {
        standardEngine.reset()
        adaptiveEngine.reset()
    }

    @Synchronized
    fun filter(
        mode: FilterEngineMode,
        rawLongG: Float,
        rawLatG: Float,
        rawVertG: Float,
        alpha: Float
    ): FilteredMotion {
        activeMode = mode
        return when (mode) {
            FilterEngineMode.STANDARD_LOW_PASS -> standardEngine.filter(rawLongG, rawLatG, rawVertG, alpha)
            FilterEngineMode.ADAPTIVE_DUAL_POLE -> adaptiveEngine.filter(rawLongG, rawLatG, rawVertG, alpha)
        }
    }

    @Synchronized
    fun currentMode(): FilterEngineMode = activeMode
}
