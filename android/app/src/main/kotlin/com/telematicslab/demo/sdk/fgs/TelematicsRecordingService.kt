package com.telematicslab.demo.sdk.fgs

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.telematicslab.demo.MainActivity
import com.telematicslab.demo.sdk.api.TelematicsSdk
import com.telematicslab.demo.sdk.api.TripState

/**
 * Coordinates Foreground Service startup, notification channel initialization,
 * and defensive tracking of denied/succeeded FGS launches across API 31-36+.
 */
class FgsCoordinator(private val appContext: Context) {

    @Volatile var startAttempts: Int = 0
        private set
    @Volatile var deniedCount: Int = 0
        private set
    @Volatile var active: Boolean = false
        private set
    @Volatile var startedAtMs: Long = 0L
        private set

    init {
        TelematicsRecordingService.ensureChannel(
            appContext.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        )
    }

    fun startRecordingService() {
        startAttempts++
        val intent = Intent(appContext, TelematicsRecordingService::class.java).apply {
            action = TelematicsRecordingService.ACTION_START
        }
        try {
            ContextCompat.startForegroundService(appContext, intent)
        } catch (_: Exception) {
            deniedCount++
        }
    }

    fun updateNotification(state: TripState, speedMph: Float, distanceMi: Float, events: Int) {
        if (!active) return
        TelematicsRecordingService.updateNotification(appContext, state, speedMph, distanceMi, events)
    }

    fun stopRecordingService() {
        try {
            appContext.stopService(Intent(appContext, TelematicsRecordingService::class.java))
        } catch (_: Exception) {
        }
    }

    fun onServiceStarted() {
        active = true
        startedAtMs = System.currentTimeMillis()
    }

    fun onServiceStopped() {
        active = false
        startedAtMs = 0L
    }

    fun onServiceDenied() {
        deniedCount++
        active = false
        startedAtMs = 0L
    }

    fun uptimeSec(nowMs: Long = System.currentTimeMillis()): Long {
        return if (active && startedAtMs > 0L) (nowMs - startedAtMs) / 1000L else 0L
    }
}

/**
 * Location Foreground Service keeping IMU + GNSS telemetry active when the screen turns off
 * in the vehicle without holding redundant manual partial wake locks.
 */
class TelematicsRecordingService : Service() {

    companion object {
        const val CHANNEL_ID = "telematics_recording"
        const val NOTIF_ID = 4201
        const val ACTION_START = "com.telematicslab.demo.ACTION_START_FGS"
        const val ACTION_STOP_TRIP = "com.telematicslab.demo.ACTION_STOP_TRIP"
        const val ACTION_SHUTDOWN = "com.telematicslab.demo.ACTION_SHUTDOWN_FGS"

        fun ensureChannel(nm: NotificationManager) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && nm.getNotificationChannel(CHANNEL_ID) == null) {
                val ch = NotificationChannel(
                    CHANNEL_ID,
                    "Telematics Trip Recording",
                    NotificationManager.IMPORTANCE_LOW
                ).apply {
                    description = "Shows live trip recording status while screen is off."
                    setShowBadge(false)
                }
                nm.createNotificationChannel(ch)
            }
        }

        fun updateNotification(context: Context, state: TripState, speedMph: Float, distanceMi: Float, events: Int) {
            val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            ensureChannel(nm)
            val notif = buildNotification(context, state, speedMph, distanceMi, events)
            try {
                nm.notify(NOTIF_ID, notif)
            } catch (_: Exception) {
            }
        }

        private fun buildNotification(
            context: Context,
            state: TripState,
            speedMph: Float,
            distanceMi: Float,
            events: Int
        ): Notification {
            val openIntent = PendingIntent.getActivity(
                context,
                1,
                Intent(context, MainActivity::class.java).apply {
                    flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
                },
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
            )

            val stopIntent = PendingIntent.getService(
                context,
                2,
                Intent(context, TelematicsRecordingService::class.java).apply {
                    action = ACTION_STOP_TRIP
                },
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
            )

            val title = "Telematics Lab • ${state.label}"
            val text = "${speedMph.toInt()} mph • ${"%.2f".format(distanceMi)} mi • $events events"

            val builder = NotificationCompat.Builder(context, CHANNEL_ID)
                .setSmallIcon(android.R.drawable.ic_menu_compass)
                .setContentTitle(title)
                .setContentText(text)
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .setContentIntent(openIntent)

            if (state == TripState.RECORDING || state == TripState.STOP_PENDING || state == TripState.VERIFYING) {
                builder.addAction(android.R.drawable.ic_media_pause, "Stop Trip", stopIntent)
            }
            return builder.build()
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        TelematicsSdk.ensureInitialized(applicationContext)
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        ensureChannel(nm)

        when (intent?.action) {
            ACTION_STOP_TRIP -> {
                TelematicsSdk.stopTrip()
                stopSelf()
                return START_NOT_STICKY
            }
            ACTION_SHUTDOWN -> {
                stopSelf()
                return START_NOT_STICKY
            }
        }

        val live = TelematicsSdk.state.value
        val notif = buildNotification(
            this,
            live.tripState,
            live.gnss.speedMph,
            live.tripDistanceMiles,
            live.liveEvents.size
        )

        try {
            val fgsType = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION
            } else {
                0
            }
            ServiceCompat.startForeground(this, NOTIF_ID, notif, fgsType)
            TelematicsSdk.notifyFgsStarted()
            TelematicsSdk.ensurePassiveSensorsRunning(startFgs = false)
        } catch (_: Exception) {
            TelematicsSdk.notifyFgsDenied()
            stopSelf()
            return START_NOT_STICKY
        }

        return START_STICKY
    }

    override fun onDestroy() {
        TelematicsSdk.notifyFgsStopped()
        super.onDestroy()
    }
}
