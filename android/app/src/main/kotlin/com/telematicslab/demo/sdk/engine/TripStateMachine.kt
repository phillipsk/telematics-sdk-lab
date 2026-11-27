package com.telematicslab.demo.sdk.engine

import com.telematicslab.demo.sdk.api.FsmTransition
import com.telematicslab.demo.sdk.api.TripState

/**
 * Stateful trip-detection state machine implementing passive start verification
 * and traffic-light stop hysteresis:
 * IDLE -> VERIFYING -> RECORDING <-> STOP_PENDING -> FINALIZING -> IDLE.
 */
class TripStateMachine {

    companion object {
        const val MAX_ACCEPTABLE_ACCURACY_M = 65f
        const val AUTO_START_VERIFY_MPH = 10f
        const val VERIFY_SUSTAINED_MPH = 8f
        const val VERIFY_ABORT_MPH = 4f
        const val VERIFY_DURATION_MS = 5_000L
        const val STOP_ENTRY_MPH = 2.0f
        const val STOP_RESUME_MPH = 4.0f
        private const val MAX_FSM_LOG = 100
    }

    var state: TripState = TripState.IDLE
        private set

    private var verifyingSinceMs: Long = 0L
    private var stopPendingSinceMs: Long = 0L
    private val transitions = ArrayList<FsmTransition>()

    @Synchronized
    fun getTransitions(): List<FsmTransition> = transitions.toList()

    @Synchronized
    fun stopPendingRemainingSec(nowMs: Long, stopHysteresisMs: Long): Int {
        if (state != TripState.STOP_PENDING || stopPendingSinceMs <= 0L) return 0
        val elapsed = (nowMs - stopPendingSinceMs).coerceAtLeast(0L)
        val remainingMs = (stopHysteresisMs - elapsed).coerceAtLeast(0L)
        return ((remainingMs + 999L) / 1000L).toInt()
    }

    @Synchronized
    fun manualStart(nowMs: Long): Boolean {
        if (state == TripState.RECORDING || state == TripState.STOP_PENDING) return false
        transition(nowMs, TripState.RECORDING, "Manual trip start")
        stopPendingSinceMs = 0L
        verifyingSinceMs = 0L
        return true
    }

    @Synchronized
    fun manualStop(nowMs: Long): Boolean {
        if (state == TripState.IDLE) return false
        transition(nowMs, TripState.FINALIZING, "Manual trip stop")
        stopPendingSinceMs = 0L
        verifyingSinceMs = 0L
        return true
    }

    @Synchronized
    fun markFinalized(nowMs: Long) {
        if (state != TripState.IDLE) {
            transition(nowMs, TripState.IDLE, "Trip persisted & archived")
        }
        stopPendingSinceMs = 0L
        verifyingSinceMs = 0L
    }

    @Synchronized
    fun onLocationUpdate(
        nowMs: Long,
        speedMph: Float,
        accuracyM: Float,
        autoDetectEnabled: Boolean,
        stopHysteresisMs: Long,
        autoStartVerifyMph: Float = AUTO_START_VERIFY_MPH,
        allowAutoStop: Boolean = true
    ): TripState? {
        val goodFix = accuracyM in 0.1f..MAX_ACCEPTABLE_ACCURACY_M
        val sustainedMph = (autoStartVerifyMph * 0.8f).coerceAtLeast(4f)
        val previous = state

        when (state) {
            TripState.IDLE -> {
                if (autoDetectEnabled && goodFix && speedMph >= autoStartVerifyMph) {
                    verifyingSinceMs = nowMs
                    transition(
                        nowMs,
                        TripState.VERIFYING,
                        "Speed ${speedMph.toInt()} mph >= ${autoStartVerifyMph.toInt()} mph (±${accuracyM.toInt()}m)"
                    )
                }
            }

            TripState.VERIFYING -> {
                if (!autoDetectEnabled) {
                    transition(nowMs, TripState.IDLE, "Auto-detect disabled")
                } else if (goodFix && speedMph < VERIFY_ABORT_MPH) {
                    transition(nowMs, TripState.IDLE, "Verification aborted (${speedMph.toInt()} mph < 4 mph)")
                } else if (goodFix && speedMph >= sustainedMph && nowMs - verifyingSinceMs >= VERIFY_DURATION_MS) {
                    transition(nowMs, TripState.RECORDING, "Sustained motion verified (>= 5s)")
                }
            }

            TripState.RECORDING -> {
                if (autoDetectEnabled && allowAutoStop && goodFix && speedMph < STOP_ENTRY_MPH) {
                    stopPendingSinceMs = nowMs
                    transition(nowMs, TripState.STOP_PENDING, "Stopped (< 2 mph); starting ${stopHysteresisMs / 1000}s hysteresis")
                }
            }

            TripState.STOP_PENDING -> {
                if (!autoDetectEnabled || !allowAutoStop) {
                    stopPendingSinceMs = 0L
                    transition(nowMs, TripState.RECORDING, "Auto-stop paused; resuming recording")
                } else if (goodFix && speedMph >= STOP_RESUME_MPH) {
                    stopPendingSinceMs = 0L
                    transition(nowMs, TripState.RECORDING, "Resumed driving (${speedMph.toInt()} mph); bridged stop")
                } else if (nowMs - stopPendingSinceMs >= stopHysteresisMs) {
                    transition(nowMs, TripState.FINALIZING, "Stop hysteresis (${stopHysteresisMs / 1000}s) elapsed")
                }
            }

            TripState.FINALIZING -> Unit
        }

        return if (state != previous) state else null
    }

    private fun transition(nowMs: Long, to: TripState, reason: String) {
        val from = state
        if (from == to) return
        state = to
        if (transitions.size >= MAX_FSM_LOG) {
            transitions.removeAt(0)
        }
        transitions.add(FsmTransition(nowMs, from, to, reason))
    }
}
