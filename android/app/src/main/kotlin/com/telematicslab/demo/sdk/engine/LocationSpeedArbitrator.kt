package com.telematicslab.demo.sdk.engine

/**
 * Pure-Kotlin location stream arbitrator that protects hardware GPS Doppler speed
 * from being overwritten by 0-mph BALANCED/network fused fixes and computes a
 * clean distance/time fallback speed when a high-accuracy fix omits `hasSpeed`.
 */
class LocationSpeedArbitrator {

    private var lastGpsFixMs: Long = 0L
    private var lastAcceptedLat: Double = 0.0
    private var lastAcceptedLon: Double = 0.0
    private var lastAcceptedMs: Long = 0L
    private var hasLastAccepted: Boolean = false

    @Synchronized
    fun reset() {
        lastGpsFixMs = 0L
        lastAcceptedLat = 0.0
        lastAcceptedLon = 0.0
        lastAcceptedMs = 0L
        hasLastAccepted = false
    }

    @Synchronized
    fun evaluateFix(
        nowMs: Long,
        provider: String,
        lat: Double,
        lon: Double,
        accuracyM: Float,
        hasSpeed: Boolean,
        reportedSpeedMps: Float
    ): Float? {
        val isGps = provider.equals("gps", ignoreCase = true)

        // Never let a coarse or speedless fused/network fix clobber a fresh hardware GPS stream.
        if (!isGps && lastGpsFixMs > 0L && nowMs - lastGpsFixMs <= 3_500L) {
            if (!hasSpeed || accuracyM > 25f) {
                return null
            }
        }

        if (isGps) {
            lastGpsFixMs = nowMs
        }

        var speedMph = if (hasSpeed && reportedSpeedMps >= 0f) {
            reportedSpeedMps * MPS_TO_MPH
        } else {
            0f
        }

        // Distance-over-time fallback when GPS/Fused reports a clean fix without Doppler speed.
        if ((!hasSpeed || speedMph < 0.1f) && hasLastAccepted && accuracyM in 0.1f..35f) {
            val dtSec = (nowMs - lastAcceptedMs) / 1000f
            if (dtSec in 0.8f..10.0f) {
                val meters = HomeDepartureEvaluator.distanceMeters(lastAcceptedLat, lastAcceptedLon, lat, lon)
                val minDisplacementM = maxOf(6f, accuracyM * 0.6f)
                if (meters > minDisplacementM && meters < 450f) {
                    val derivedMph = (meters / dtSec) * MPS_TO_MPH
                    if (derivedMph <= 95f) {
                        speedMph = derivedMph
                    }
                }
            }
        }

        if (accuracyM in 0.1f..55f) {
            lastAcceptedLat = lat
            lastAcceptedLon = lon
            lastAcceptedMs = nowMs
            hasLastAccepted = true
        }

        return speedMph.coerceIn(0f, 130f)
    }

    companion object {
        private const val MPS_TO_MPH = 2.2369363f
    }
}
