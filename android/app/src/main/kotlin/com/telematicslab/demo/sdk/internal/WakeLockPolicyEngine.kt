package com.telematicslab.demo.sdk.internal

import com.telematicslab.demo.sdk.api.DrivingEvent
import com.telematicslab.demo.sdk.api.EventType
import com.telematicslab.demo.sdk.api.TripState
import kotlinx.coroutines.flow.StateFlow
import java.util.Locale

data class WakeLockPolicy(
    val stateDenylist: Set<String> = setOf("RECORDING", "VERIFYING", "STOP_PENDING", "FINALIZING"),
    val eventAllowlist: Set<EventType> = setOf(EventType.POTENTIAL_IMPACT),
    val eventLockDurationMs: Long = 15_000L,
    val legacyContinuousLockEnabled: Boolean = false
)

interface TimedWakeLockRequester {
    fun requestTimedLock(durationMs: Long, tag: String)
}

interface EventSink {
    fun push(event: DrivingEvent)
}

/**
 * Remote-config-driven state controller that suppresses redundant continuous partial wake locks
 * when platform Foreground Service + location delivery already hold exempt locks.
 * Uses [Locale.ROOT] for case normalization so international locales (e.g. Turkish) never mismatch.
 */
class WakeLockStateController {

    private val decisionLog = ArrayList<String>()

    data class Decision(
        val shouldHoldContinuousLock: Boolean,
        val reason: String
    )

    @Synchronized
    fun evaluateState(state: TripState, policy: WakeLockPolicy): Decision {
        if (policy.legacyContinuousLockEnabled && state.isWakeful) {
            val msg = "${state.label}: legacy continuous WakeLock override ON"
            appendLog(msg)
            return Decision(true, msg)
        }

        val normalized = state.name.uppercase(Locale.ROOT)
        val decision = if (state.isWakeful && normalized !in policy.stateDenylist) {
            Decision(true, "$normalized: allowed (not in denylist)")
        } else if (state.isWakeful) {
            Decision(false, "$normalized: denied by remote-config denylist -> 0ms lock")
        } else {
            Decision(false, "$normalized: non-wakeful state -> released")
        }
        appendLog(decision.reason)
        return decision
    }

    @Synchronized
    fun recordEventGateDecision(entry: String) {
        appendLog(entry)
    }

    @Synchronized
    fun recentDecisions(): List<String> = decisionLog.toList()

    private fun appendLog(entry: String) {
        if (decisionLog.lastOrNull() == entry) return
        if (decisionLog.size >= 16) decisionLog.removeAt(0)
        decisionLog.add(entry)
    }
}

/**
 * Decorator around [EventSink] that reads reactive [StateFlow] remote-config policy and grants
 * bounded 15s wake-lock bursts ONLY for allowlisted high-priority events (e.g., potential crash),
 * while unconditionally forwarding every event to [delegate].
 */
class WakeLockGatingEventSink(
    private val delegate: EventSink,
    private val policyFlow: StateFlow<WakeLockPolicy>,
    private val requester: TimedWakeLockRequester
) : EventSink {

    override fun push(event: DrivingEvent) {
        val policy = policyFlow.value
        if (event.type in policy.eventAllowlist) {
            requester.requestTimedLock(
                durationMs = policy.eventLockDurationMs,
                tag = "Event:${event.type.name}"
            )
        }
        delegate.push(event)
    }
}
