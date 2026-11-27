package com.telematicslab.demo.sdk.sensor

import android.app.KeyguardManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.PowerManager
import androidx.core.content.ContextCompat
import kotlin.math.sqrt

/**
 * Monitors screen interactive state and estimates whether the device is being
 * handheld vs. steady on a vehicle mount via rolling 1.5s gyroscope RMS.
 */
class DistractionMonitor(private val context: Context) {

    private val powerManager = context.getSystemService(Context.POWER_SERVICE) as PowerManager
    private val keyguardManager = context.getSystemService(Context.KEYGUARD_SERVICE) as KeyguardManager

    @Volatile
    var isScreenOn: Boolean = powerManager.isInteractive
        private set

    @Volatile
    var isUnlocked: Boolean = !keyguardManager.isKeyguardLocked
        private set

    @Volatile
    var isHandheld: Boolean = false
        private set

    private var registered = false

    private val gyroWindowSize = 75
    private val gyroMags = FloatArray(gyroWindowSize)
    private var gyroIdx = 0
    private var gyroCount = 0

    private val screenReceiver = object : BroadcastReceiver() {
        override fun onReceive(ctx: Context?, intent: Intent?) {
            when (intent?.action) {
                Intent.ACTION_SCREEN_ON -> {
                    isScreenOn = true
                    isUnlocked = !keyguardManager.isKeyguardLocked
                }
                Intent.ACTION_SCREEN_OFF -> {
                    isScreenOn = false
                    isUnlocked = false
                }
                Intent.ACTION_USER_PRESENT -> {
                    isScreenOn = true
                    isUnlocked = true
                }
            }
        }
    }

    fun start() {
        if (registered) return
        isScreenOn = powerManager.isInteractive
        isUnlocked = !keyguardManager.isKeyguardLocked
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_ON)
            addAction(Intent.ACTION_SCREEN_OFF)
            addAction(Intent.ACTION_USER_PRESENT)
        }
        ContextCompat.registerReceiver(context, screenReceiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED)
        registered = true
    }

    fun stop() {
        if (!registered) return
        try {
            context.unregisterReceiver(screenReceiver)
        } catch (_: Exception) {
        }
        registered = false
    }

    @Synchronized
    fun updateGyroSample(gx: Float, gy: Float, gz: Float): Boolean {
        val magSq = gx * gx + gy * gy + gz * gz
        gyroMags[gyroIdx] = magSq
        gyroIdx = (gyroIdx + 1) % gyroWindowSize
        if (gyroCount < gyroWindowSize) gyroCount++

        var sumSq = 0f
        for (i in 0 until gyroCount) {
            sumSq += gyroMags[i]
        }
        val rms = if (gyroCount > 0) sqrt(sumSq / gyroCount) else 0f
        isHandheld = rms > 0.55f
        return isHandheld
    }
}
