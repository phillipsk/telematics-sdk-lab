package com.telematicslab.demo.sdk.engine

import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

data class HomeGeofenceConfig(
    val enabled: Boolean = false,
    val label: String = "Home",
    val lat: Double = 0.0,
    val lon: Double = 0.0,
    val radiusMeters: Float = 120f,
    val minInVehicleSec: Int = 60,
    val minSpeedMph: Float = 10f
) {
    val isConfigured: Boolean
        get() = enabled && (lat != 0.0 || lon != 0.0) && radiusMeters >= 50f
}

enum class HomeDepartureDecision {
    NONE,
    SUPPRESS_EARLY_AUTO_START,
    TRIGGER_AUTO_RECORD
}

/**
 * Evaluates departure from an optional user-configured Home geofence so leaving home
 * starts auto-recording only once the user has been in-vehicle > [minInVehicleSec] seconds
 * and exceeds [minSpeedMph] mph, while away-from-home trips use the standard state machine.
 */
class HomeDepartureEvaluator {

    private var isInsideHome: Boolean = false
    private var exitedGeofenceAtMs: Long = 0L
    private var inVehicleSinceMs: Long = 0L
    private var inVehicleConfirmedByActivity: Boolean = false
    private var departureConsumedForCurrentExit: Boolean = false

    @Synchronized
    fun onGeofenceExit(nowMs: Long) {
        isInsideHome = false
        exitedGeofenceAtMs = nowMs
        departureConsumedForCurrentExit = false
    }

    @Synchronized
    fun onGeofenceEnter() {
        isInsideHome = true
        exitedGeofenceAtMs = 0L
        departureConsumedForCurrentExit = false
        if (!inVehicleConfirmedByActivity) {
            inVehicleSinceMs = 0L
        }
    }

    @Synchronized
    fun onVehicleActivityEnter(nowMs: Long) {
        inVehicleConfirmedByActivity = true
        if (inVehicleSinceMs == 0L) {
            inVehicleSinceMs = nowMs
        }
    }

    @Synchronized
    fun onVehicleActivityExit() {
        inVehicleConfirmedByActivity = false
        inVehicleSinceMs = 0L
    }

    @Synchronized
    fun onTripStarted() {
        if (exitedGeofenceAtMs > 0L) {
            departureConsumedForCurrentExit = true
        }
    }

    @Synchronized
    fun evaluateLocation(
        nowMs: Long,
        lat: Double,
        lon: Double,
        speedMph: Float,
        accuracyM: Float,
        config: HomeGeofenceConfig,
        isIdle: Boolean
    ): HomeDepartureDecision {
        if (!config.isConfigured || !isIdle || accuracyM <= 0f || accuracyM > TripStateMachine.MAX_ACCEPTABLE_ACCURACY_M) {
            return HomeDepartureDecision.NONE
        }

        val distM = distanceMeters(lat, lon, config.lat, config.lon)
        if (distM <= config.radiusMeters) {
            isInsideHome = true
            exitedGeofenceAtMs = 0L
            departureConsumedForCurrentExit = false
            if (!inVehicleConfirmedByActivity && speedMph < 4f) {
                inVehicleSinceMs = 0L
            }
            return HomeDepartureDecision.SUPPRESS_EARLY_AUTO_START
        }

        if (isInsideHome) {
            isInsideHome = false
            exitedGeofenceAtMs = nowMs
            departureConsumedForCurrentExit = false
        }

        if (exitedGeofenceAtMs > 0L && !departureConsumedForCurrentExit) {
            if (nowMs - exitedGeofenceAtMs > 20 * 60_000L) {
                exitedGeofenceAtMs = 0L
                return HomeDepartureDecision.NONE
            }

            if (inVehicleSinceMs == 0L && speedMph > config.minSpeedMph) {
                inVehicleSinceMs = nowMs
            }

            if (!inVehicleConfirmedByActivity && speedMph < 2f && inVehicleSinceMs > 0L &&
                nowMs - inVehicleSinceMs < config.minInVehicleSec * 1000L
            ) {
                inVehicleSinceMs = 0L
            }

            val inVehicleDurationMs = if (inVehicleSinceMs > 0L) {
                (nowMs - inVehicleSinceMs).coerceAtLeast(0L)
            } else {
                0L
            }

            if (inVehicleDurationMs > config.minInVehicleSec * 1000L && speedMph > config.minSpeedMph) {
                departureConsumedForCurrentExit = true
                return HomeDepartureDecision.TRIGGER_AUTO_RECORD
            }

            if (nowMs - exitedGeofenceAtMs <= 10 * 60_000L) {
                return HomeDepartureDecision.SUPPRESS_EARLY_AUTO_START
            }
        }

        return HomeDepartureDecision.NONE
    }

    companion object {
        private const val EARTH_RADIUS_M = 6_371_000.0

        fun distanceMeters(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Float {
            val dLat = Math.toRadians(lat2 - lat1)
            val dLon = Math.toRadians(lon2 - lon1)
            val rLat1 = Math.toRadians(lat1)
            val rLat2 = Math.toRadians(lat2)
            val a = sin(dLat / 2) * sin(dLat / 2) +
                cos(rLat1) * cos(rLat2) * sin(dLon / 2) * sin(dLon / 2)
            val c = 2.0 * atan2(sqrt(a), sqrt(1.0 - a))
            return (EARTH_RADIUS_M * c).toFloat()
        }
    }
}
