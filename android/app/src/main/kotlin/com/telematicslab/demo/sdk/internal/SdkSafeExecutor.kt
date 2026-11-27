package com.telematicslab.demo.sdk.internal

import kotlinx.coroutines.CancellationException
import java.util.concurrent.atomic.AtomicInteger

/**
 * Defensive host-crash boundary for all public SDK entry points, callbacks, and background workers.
 * Always propagates [CancellationException] to preserve structured concurrency, while catching
 * unexpected faults so the SDK never crashes the host application.
 */
object SdkSafeExecutor {

    private val faultCount = AtomicInteger(0)

    fun faultCount(): Int = faultCount.get()

    fun resetForTest() {
        faultCount.set(0)
    }

    inline fun <T> runSafely(tag: String, fallback: T, block: () -> T): T {
        return try {
            block()
        } catch (ce: CancellationException) {
            throw ce
        } catch (_: Throwable) {
            recordFault(tag)
            fallback
        }
    }

    suspend inline fun <T> runSuspendSafely(tag: String, fallback: T, block: suspend () -> T): T {
        return try {
            block()
        } catch (ce: CancellationException) {
            throw ce
        } catch (_: Throwable) {
            recordFault(tag)
            fallback
        }
    }

    fun recordFault(@Suppress("UNUSED_PARAMETER") tag: String) {
        faultCount.incrementAndGet()
    }
}
