package com.telematicslab.demo.sdk.engine

import com.telematicslab.demo.sdk.api.SeatRole
import com.telematicslab.demo.sdk.api.TransportMode
import com.telematicslab.demo.sdk.api.TripPreset
import com.telematicslab.demo.sdk.api.TripState

data class TripPromptSuggestion(
    val title: String,
    val body: String,
    val primaryLabel: String,
    val mode: TransportMode,
    val seatRole: SeatRole,
    val isPlannedMatch: Boolean,
    val secondaryExtraLabel: String?
)

/**
 * Resolves background vehicle/bicycle transition signals against the user's
 * planned trip schedule (cross-referencing [todayIso]) and captured history.
 */
object TripPromptResolver {

    const val DEFAULT_COOLDOWN_MS = 15 * 60_000L
    const val DEFAULT_SNOOZE_MS = 30 * 60_000L

    fun resolve(
        detectedMode: TransportMode,
        todayIso: String,
        presets: List<TripPreset>,
        completedTripLabels: Set<String>,
        tripState: TripState,
        promptsEnabled: Boolean,
        nowMs: Long,
        lastPromptMs: Long,
        snoozeUntilMs: Long,
        bypassCooldown: Boolean = false,
        cooldownMs: Long = DEFAULT_COOLDOWN_MS
    ): TripPromptSuggestion? {
        if (detectedMode == TransportMode.WALK) return null
        if (!promptsEnabled || tripState != TripState.IDLE) return null
        if (!bypassCooldown) {
            if (nowMs < snoozeUntilMs) return null
            if (lastPromptMs > 0L && nowMs - lastPromptMs < cooldownMs) return null
        }

        val uncaptured = presets.filter { it.mode == detectedMode && it.label !in completedTripLabels }
        val datedMatches = uncaptured.filter { it.plannedDate == todayIso }
        val undatedMatches = uncaptured.filter { it.plannedDate == null }
        val extraLabel = extraTripLabel(detectedMode)

        if (datedMatches.isNotEmpty()) {
            val primary = datedMatches.first()
            val alsoHint = datedMatches.getOrNull(1)?.let { " (or ${it.label})" } ?: ""
            val title = if (detectedMode == TransportMode.CAR) {
                "In Vehicle • Planned Today"
            } else {
                "Cycling • Planned Today"
            }
            return TripPromptSuggestion(
                title = title,
                body = "Did you forget to start \"${primary.label}\"$alsoHint? Tap to record now or start an extra trip.",
                primaryLabel = primary.label,
                mode = detectedMode,
                seatRole = primary.seatRole,
                isPlannedMatch = true,
                secondaryExtraLabel = extraLabel
            )
        }

        if (undatedMatches.isNotEmpty()) {
            val primary = undatedMatches.first()
            val title = if (detectedMode == TransportMode.CAR) {
                "In Vehicle • Planned Trip"
            } else {
                "Cycling • Planned Trip"
            }
            return TripPromptSuggestion(
                title = title,
                body = "Looks like you started \"${primary.label}\". Tap to record now or start an extra trip.",
                primaryLabel = primary.label,
                mode = detectedMode,
                seatRole = primary.seatRole,
                isPlannedMatch = true,
                secondaryExtraLabel = extraLabel
            )
        }

        val title = if (detectedMode == TransportMode.CAR) {
            "Vehicle Motion Detected"
        } else {
            "Bicycle Motion Detected"
        }
        val body = if (detectedMode == TransportMode.CAR) {
            "Taking an extra drive? Tap to start recording telemetry."
        } else {
            "Going for a bike ride? Tap to start recording telemetry."
        }
        return TripPromptSuggestion(
            title = title,
            body = body,
            primaryLabel = extraLabel,
            mode = detectedMode,
            seatRole = SeatRole.DRIVER,
            isPlannedMatch = false,
            secondaryExtraLabel = null
        )
    }

    private fun extraTripLabel(mode: TransportMode): String {
        return if (mode == TransportMode.CAR) "Extra Drive" else "Extra Bike Ride"
    }
}
