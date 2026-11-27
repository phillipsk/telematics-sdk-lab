package com.telematicslab.demo.sdk.sensor

import android.app.usage.UsageStatsManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.os.Build
import android.os.PowerManager
import com.telematicslab.demo.sdk.api.PowerStats
import com.telematicslab.demo.sdk.internal.TimedWakeLockRequester

/**
 * Tracks battery, Standby Bucket, Doze exemption, and enforces timed partial WakeLock
 * boundaries for both continuous remote-config tests and event-gated short bursts.
 */
class PowerHealthMonitor(private val context: Context) : TimedWakeLockRequester {

    private val powerManager = context.getSystemService(Context.POWER_SERVICE) as PowerManager
    private val usageStatsManager = context.getSystemService(Context.USAGE_STATS_SERVICE) as? UsageStatsManager

    private var wakeLock: PowerManager.WakeLock? = null
    private var lockAcquiredAtMs: Long = 0L
    private var accumulatedLockMs: Long = 0L
    private var tripStartBatteryPct: Int? = null

    fun markTripStarted() {
        tripStartBatteryPct = readBatterySnapshot().pct
        accumulatedLockMs = 0L
        lockAcquiredAtMs = 0L
    }

    @Synchronized
    override fun requestTimedLock(durationMs: Long, tag: String) {
        val nowMs = System.currentTimeMillis()
        val wl = wakeLock ?: powerManager.newWakeLock(
            PowerManager.PARTIAL_WAKE_LOCK,
            "TelematicsLab:$tag"
        ).also {
            it.setReferenceCounted(false)
            wakeLock = it
        }
        if (wl.isHeld && lockAcquiredAtMs > 0L) {
            accumulatedLockMs += (nowMs - lockAcquiredAtMs).coerceAtLeast(0L)
        }
        wl.acquire(durationMs.coerceIn(1_000L, 20_000L))
        lockAcquiredAtMs = nowMs
    }

    @Synchronized
    fun updateLegacyWakeLockGate(shouldHold: Boolean, nowMs: Long) {
        if (shouldHold) {
            val wl = wakeLock ?: powerManager.newWakeLock(
                PowerManager.PARTIAL_WAKE_LOCK,
                "TelematicsLab:LegacySimLock"
            ).also {
                it.setReferenceCounted(false)
                wakeLock = it
            }
            if (!wl.isHeld) {
                wl.acquire(15_000L)
                lockAcquiredAtMs = nowMs
            } else if (lockAcquiredAtMs > 0L && nowMs - lockAcquiredAtMs >= 12_000L) {
                accumulatedLockMs += (nowMs - lockAcquiredAtMs)
                wl.acquire(15_000L)
                lockAcquiredAtMs = nowMs
            }
        } else {
            releaseLegacyWakeLock(nowMs)
        }
    }

    @Synchronized
    fun releaseLegacyWakeLock(nowMs: Long = System.currentTimeMillis()) {
        val wl = wakeLock
        if (wl != null && wl.isHeld) {
            if (lockAcquiredAtMs > 0L) {
                accumulatedLockMs += (nowMs - lockAcquiredAtMs).coerceAtLeast(0L)
            }
            try {
                wl.release()
            } catch (_: Exception) {
            }
        }
        lockAcquiredAtMs = 0L
    }

    @Synchronized
    fun currentHeldWakeLockMs(nowMs: Long = System.currentTimeMillis()): Long {
        val active = if (wakeLock?.isHeld == true && lockAcquiredAtMs > 0L) {
            (nowMs - lockAcquiredAtMs).coerceAtLeast(0L)
        } else {
            0L
        }
        return accumulatedLockMs + active
    }

    fun snapshot(
        imuHz: Float,
        avgBatchIntervalMs: Float,
        fifoBatchLatencyMs: Int,
        legacyWakeLockEnabled: Boolean,
        fgsUptimeSec: Long,
        ringFill: Int,
        ringCapacity: Int
    ): PowerStats {
        val batt = readBatterySnapshot()
        val startPct = tripStartBatteryPct ?: batt.pct
        val bucketName = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P && usageStatsManager != null) {
            when (usageStatsManager.appStandbyBucket) {
                UsageStatsManager.STANDBY_BUCKET_ACTIVE -> "ACTIVE"
                UsageStatsManager.STANDBY_BUCKET_WORKING_SET -> "WORKING_SET"
                UsageStatsManager.STANDBY_BUCKET_FREQUENT -> "FREQUENT"
                UsageStatsManager.STANDBY_BUCKET_RARE -> "RARE"
                45 -> "RESTRICTED"
                else -> "EXEMPT/OTHER"
            }
        } else {
            "N/A (<API 28)"
        }

        return PowerStats(
            batteryPct = batt.pct,
            batteryStartPct = startPct,
            batteryTempC = batt.tempC,
            isCharging = batt.charging,
            standbyBucketName = bucketName,
            ignoringBatteryOptimizations = powerManager.isIgnoringBatteryOptimizations(context.packageName),
            powerSaveMode = powerManager.isPowerSaveMode,
            imuSampleHz = imuHz,
            avgBatchIntervalMs = avgBatchIntervalMs,
            fifoBatchLatencyMs = fifoBatchLatencyMs,
            legacyWakeLockEnabled = legacyWakeLockEnabled,
            manualWakeLockHeldMs = currentHeldWakeLockMs(),
            fgsUptimeSec = fgsUptimeSec,
            ringBufferFill = ringFill,
            ringBufferCapacity = ringCapacity
        )
    }

    private data class BatterySnapshot(val pct: Int, val tempC: Float, val charging: Boolean)

    private fun readBatterySnapshot(): BatterySnapshot {
        val intent = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        val level = intent?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
        val scale = intent?.getIntExtra(BatteryManager.EXTRA_SCALE, 100) ?: 100
        val pct = if (level >= 0 && scale > 0) (level * 100) / scale else 100
        val tempTenths = intent?.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, 250) ?: 250
        val status = intent?.getIntExtra(BatteryManager.EXTRA_STATUS, -1) ?: -1
        val charging = status == BatteryManager.BATTERY_STATUS_CHARGING ||
            status == BatteryManager.BATTERY_STATUS_FULL
        return BatterySnapshot(pct, tempTenths / 10f, charging)
    }
}
