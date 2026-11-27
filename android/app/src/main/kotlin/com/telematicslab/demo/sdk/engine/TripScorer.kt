package com.telematicslab.demo.sdk.engine

import com.telematicslab.demo.sdk.api.DrivingEvent
import com.telematicslab.demo.sdk.api.EventType
import com.telematicslab.demo.sdk.api.TripScoreBreakdown
import kotlin.math.roundToInt

/**
 * Computes a 0-100 multi-pillar safety score across braking, acceleration, cornering,
 * speeding, distraction, and smoothness, normalized per mile with a 0.25-mile floor.
 */
object TripScorer {

    fun score(events: List<DrivingEvent>, distanceMiles: Float): TripScoreBreakdown {
        val normMiles = distanceMiles.coerceAtLeast(0.25f)

        val brakeCount = events.count { it.type == EventType.HARD_BRAKE }
        val accelCount = events.count { it.type == EventType.RAPID_ACCEL }
        val cornerCount = events.count {
            it.type == EventType.HARSH_CORNER_LEFT || it.type == EventType.HARSH_CORNER_RIGHT
        }
        val speedCount = events.count { it.type == EventType.SPEEDING }
        val screenCount = events.count { it.type == EventType.DISTRACTION_SCREEN }
        val handheldCount = events.count { it.type == EventType.DISTRACTION_HANDHELD }
        val impactCount = events.count { it.type == EventType.POTENTIAL_IMPACT }

        val braking = (100f - (brakeCount / normMiles) * 14f).roundToInt().coerceIn(20, 100)
        val acceleration = (100f - (accelCount / normMiles) * 12f).roundToInt().coerceIn(20, 100)
        val cornering = (100f - (cornerCount / normMiles) * 14f).roundToInt().coerceIn(20, 100)
        val speeding = (100f - (speedCount / normMiles) * 18f).roundToInt().coerceIn(20, 100)
        val distraction = (100f - ((handheldCount * 16f + screenCount * 8f) / normMiles)).roundToInt().coerceIn(15, 100)
        val smoothness = ((braking + acceleration + cornering) / 3f).roundToInt()

        val weighted = (
            braking * 0.25f +
                acceleration * 0.15f +
                cornering * 0.20f +
                speeding * 0.20f +
                distraction * 0.20f
            ).roundToInt() - (impactCount * 20)

        return TripScoreBreakdown(
            overall = weighted.coerceIn(0, 100),
            braking = braking,
            acceleration = acceleration,
            cornering = cornering,
            speeding = speeding,
            distraction = distraction,
            smoothness = smoothness
        )
    }
}
