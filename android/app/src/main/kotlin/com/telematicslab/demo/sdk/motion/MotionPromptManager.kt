package com.telematicslab.demo.sdk.motion

import android.Manifest
import android.annotation.SuppressLint
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.google.android.gms.location.ActivityRecognition
import com.google.android.gms.location.ActivityTransition
import com.google.android.gms.location.ActivityTransitionRequest
import com.google.android.gms.location.ActivityTransitionResult
import com.google.android.gms.location.DetectedActivity
import com.telematicslab.demo.MainActivity
import com.telematicslab.demo.sdk.api.SeatRole
import com.telematicslab.demo.sdk.api.TelematicsSdk
import com.telematicslab.demo.sdk.api.TransportMode
import com.telematicslab.demo.sdk.engine.TripPromptResolver
import com.telematicslab.demo.sdk.engine.TripPromptSuggestion
import com.telematicslab.demo.sdk.fgs.TelematicsRecordingService
import com.telematicslab.demo.sdk.storage.TripPresetStore
import com.telematicslab.demo.sdk.storage.TripRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.ZoneId

/**
 * Registers low-power Activity Recognition transitions for IN_VEHICLE and ON_BICYCLE
 * and posts interactive recording notifications matched against today's planned trips.
 */
class MotionPromptManager(context: Context) {

    private val appContext = context.applicationContext
    private val prefs = appContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    private val notificationManager = appContext.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

    companion object {
        private const val TAG = "MotionPromptManager"
        private const val PREFS_NAME = "motion_prompt_prefs"
        private const val KEY_ENABLED = "enabled"
        private const val KEY_LAST_PROMPT_MS = "last_prompt_ms"
        private const val KEY_SNOOZE_UNTIL_MS = "snooze_until_ms"
        private const val KEY_LAST_SIGNAL = "last_signal"
        private const val KEY_LAST_DETECTED_MODE = "last_detected_mode"
        private const val KEY_LAST_DETECTED_MODE_MS = "last_detected_mode_ms"

        const val CHANNEL_ID = "telematics_trip_prompts"
        const val NOTIF_ID = 4202
        const val STATUS_NOTIF_ID = 4203

        const val ACTION_START_PROMPTED_TRIP = "com.telematicslab.demo.ACTION_START_PROMPTED_TRIP"
        const val ACTION_SNOOZE_PROMPT = "com.telematicslab.demo.ACTION_SNOOZE_PROMPT"
        const val EXTRA_TRIP_LABEL = "extra_trip_label"
        const val EXTRA_TRANSPORT_MODE = "extra_transport_mode"
        const val EXTRA_SEAT_ROLE = "extra_seat_role"

        private const val RC_PRIMARY = 4210
        private const val RC_EXTRA = 4211
        private const val RC_SNOOZE = 4212
        private const val RC_TRANSITION_PI = 4213

        @Volatile
        var isRegistered: Boolean = false
            private set
    }

    init {
        ensureNotificationChannel()
    }

    fun isEnabled(): Boolean = prefs.getBoolean(KEY_ENABLED, true)

    fun setEnabled(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_ENABLED, enabled).apply()
    }

    fun getLastSignal(): String = prefs.getString(KEY_LAST_SIGNAL, "None") ?: "None"

    fun recentDetectedMode(maxAgeMs: Long = 10 * 60_000L): TransportMode? {
        val ts = prefs.getLong(KEY_LAST_DETECTED_MODE_MS, 0L)
        if (ts <= 0L || System.currentTimeMillis() - ts > maxAgeMs) return null
        val name = prefs.getString(KEY_LAST_DETECTED_MODE, null) ?: return null
        return runCatching { TransportMode.valueOf(name) }.getOrNull()
    }

    fun hasActivityRecognitionPermission(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ContextCompat.checkSelfPermission(appContext, Manifest.permission.ACTIVITY_RECOGNITION) ==
                PackageManager.PERMISSION_GRANTED
        } else {
            true
        }
    }

    fun hasPostNotificationsPermission(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            ContextCompat.checkSelfPermission(appContext, Manifest.permission.POST_NOTIFICATIONS) ==
                PackageManager.PERMISSION_GRANTED
        } else {
            true
        }
    }

    @SuppressLint("MissingPermission")
    fun syncRegistration() {
        val client = ActivityRecognition.getClient(appContext)
        val pi = buildTransitionPendingIntent()

        if (!isEnabled() || !hasActivityRecognitionPermission()) {
            client.removeActivityTransitionUpdates(pi)
                .addOnCompleteListener {
                    isRegistered = false
                    Log.i(TAG, "Activity transitions unregistered (enabled=${isEnabled()}, perm=${hasActivityRecognitionPermission()})")
                }
            return
        }

        val transitions = listOf(
            ActivityTransition.Builder()
                .setActivityType(DetectedActivity.IN_VEHICLE)
                .setActivityTransition(ActivityTransition.ACTIVITY_TRANSITION_ENTER)
                .build(),
            ActivityTransition.Builder()
                .setActivityType(DetectedActivity.IN_VEHICLE)
                .setActivityTransition(ActivityTransition.ACTIVITY_TRANSITION_EXIT)
                .build(),
            ActivityTransition.Builder()
                .setActivityType(DetectedActivity.ON_BICYCLE)
                .setActivityTransition(ActivityTransition.ACTIVITY_TRANSITION_ENTER)
                .build()
        )
        val request = ActivityTransitionRequest(transitions)
        client.requestActivityTransitionUpdates(request, pi)
            .addOnSuccessListener {
                isRegistered = true
                Log.i(TAG, "Activity transitions registered for IN_VEHICLE + ON_BICYCLE")
            }
            .addOnFailureListener { err ->
                isRegistered = false
                Log.w(TAG, "Failed to register activity transitions: ${err.message}")
            }
    }

    suspend fun handleDetectedMotion(mode: TransportMode, bypassCooldown: Boolean = false): TripPromptSuggestion? {
        val presets = TripPresetStore(appContext).loadPresets()
        val completedLabels = TripRepository(appContext).loadAllTrips().map { it.tripLabel }.toSet()
        val todayIso = LocalDate.now(ZoneId.systemDefault()).toString()
        val nowMs = System.currentTimeMillis()
        val lastPromptMs = prefs.getLong(KEY_LAST_PROMPT_MS, 0L)
        val snoozeUntilMs = prefs.getLong(KEY_SNOOZE_UNTIL_MS, 0L)

        val suggestion = TripPromptResolver.resolve(
            detectedMode = mode,
            todayIso = todayIso,
            presets = presets,
            completedTripLabels = completedLabels,
            tripState = TelematicsSdk.currentTripStateOrIdle(),
            promptsEnabled = isEnabled(),
            nowMs = nowMs,
            lastPromptMs = lastPromptMs,
            snoozeUntilMs = snoozeUntilMs,
            bypassCooldown = bypassCooldown
        )

        val signalSummary = if (suggestion != null) {
            "${mode.label} -> ${suggestion.primaryLabel}"
        } else {
            "${mode.label} (suppressed)"
        }
        prefs.edit().apply {
            putString(KEY_LAST_SIGNAL, signalSummary)
            if (!bypassCooldown) {
                putString(KEY_LAST_DETECTED_MODE, mode.name)
                putLong(KEY_LAST_DETECTED_MODE_MS, nowMs)
            }
        }.apply()

        if (suggestion != null) {
            postPromptNotification(suggestion)
            prefs.edit()
                .putLong(KEY_LAST_PROMPT_MS, nowMs)
                .putString(KEY_LAST_SIGNAL, signalSummary)
                .apply()
        }
        return suggestion
    }

    fun snoozePrompts(durationMs: Long = TripPromptResolver.DEFAULT_SNOOZE_MS) {
        val until = System.currentTimeMillis() + durationMs
        prefs.edit()
            .putLong(KEY_SNOOZE_UNTIL_MS, until)
            .putString(KEY_LAST_SIGNAL, "Snoozed 30m")
            .apply()
        cancelPromptNotification()
    }

    fun cancelPromptNotification() {
        notificationManager.cancel(NOTIF_ID)
    }

    fun notifyAutoRecordingStarted(label: String, mode: TransportMode, seatRole: SeatRole) {
        if (!hasPostNotificationsPermission()) return
        ensureNotificationChannel()
        cancelPromptNotification()

        val openIntent = PendingIntent.getActivity(
            appContext,
            4220,
            Intent(appContext, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
            },
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val stopIntent = PendingIntent.getService(
            appContext,
            4221,
            Intent(appContext, TelematicsRecordingService::class.java).apply {
                action = TelematicsRecordingService.ACTION_STOP_TRIP
            },
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val body = "Mode: ${mode.label} (${seatRole.label}). Tracking GPS + 50 Hz IMU."
        val notif = NotificationCompat.Builder(appContext, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_menu_compass)
            .setContentTitle("Auto-Recording Started • $label")
            .setContentText(body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_STATUS)
            .setAutoCancel(true)
            .setContentIntent(openIntent)
            .addAction(android.R.drawable.ic_media_pause, "Stop Trip", stopIntent)
            .build()

        notificationManager.notify(STATUS_NOTIF_ID, notif)
    }

    fun notifyTripCompleted(
        label: String,
        distanceMiles: Float,
        durationSec: Int,
        score: Int,
        eventsCount: Int,
        syncedToCloud: Boolean
    ) {
        if (!hasPostNotificationsPermission()) return
        ensureNotificationChannel()

        val openIntent = PendingIntent.getActivity(
            appContext,
            4222,
            Intent(appContext, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
            },
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val title = if (syncedToCloud) {
            "Trip Saved & Synced • $label"
        } else {
            "Trip Saved Locally • $label"
        }
        val body = "${"%.2f".format(distanceMiles)} mi • ${durationSec / 60}m ${durationSec % 60}s • Score $score • $eventsCount events"
        val notif = NotificationCompat.Builder(appContext, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_menu_compass)
            .setContentTitle(title)
            .setContentText(body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_STATUS)
            .setAutoCancel(true)
            .setContentIntent(openIntent)
            .build()

        notificationManager.notify(STATUS_NOTIF_ID, notif)
    }

    private fun postPromptNotification(suggestion: TripPromptSuggestion) {
        if (!hasPostNotificationsPermission()) {
            Log.w(TAG, "POST_NOTIFICATIONS not granted; skipping prompt notification")
            return
        }
        ensureNotificationChannel()

        val primaryIntent = buildStartTripActivityPendingIntent(
            requestCode = RC_PRIMARY,
            label = suggestion.primaryLabel,
            mode = suggestion.mode,
            seatRole = suggestion.seatRole
        )

        val snoozeIntent = PendingIntent.getBroadcast(
            appContext,
            RC_SNOOZE,
            Intent(appContext, TripActivityTransitionReceiver::class.java).apply {
                action = ACTION_SNOOZE_PROMPT
            },
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val builder = NotificationCompat.Builder(appContext, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_menu_mylocation)
            .setContentTitle(suggestion.title)
            .setContentText(suggestion.body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(suggestion.body))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_RECOMMENDATION)
            .setAutoCancel(true)
            .setContentIntent(primaryIntent)
            .addAction(
                android.R.drawable.ic_media_play,
                if (suggestion.isPlannedMatch) "Record Planned" else "Record Now",
                primaryIntent
            )

        suggestion.secondaryExtraLabel?.let { extraLbl ->
            val extraIntent = buildStartTripActivityPendingIntent(
                requestCode = RC_EXTRA,
                label = extraLbl,
                mode = suggestion.mode,
                seatRole = SeatRole.DRIVER
            )
            builder.addAction(android.R.drawable.ic_input_add, "Extra Trip", extraIntent)
        }

        builder.addAction(android.R.drawable.ic_menu_recent_history, "Snooze 30m", snoozeIntent)
        notificationManager.notify(NOTIF_ID, builder.build())
    }

    private fun buildStartTripActivityPendingIntent(
        requestCode: Int,
        label: String,
        mode: TransportMode,
        seatRole: SeatRole
    ): PendingIntent {
        val intent = Intent(appContext, MainActivity::class.java).apply {
            action = ACTION_START_PROMPTED_TRIP
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
            putExtra(EXTRA_TRIP_LABEL, label)
            putExtra(EXTRA_TRANSPORT_MODE, mode.name)
            putExtra(EXTRA_SEAT_ROLE, seatRole.name)
        }
        return PendingIntent.getActivity(
            appContext,
            requestCode,
            intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
    }

    private fun buildTransitionPendingIntent(): PendingIntent {
        val intent = Intent(appContext, TripActivityTransitionReceiver::class.java)
        val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            PendingIntent.FLAG_MUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        } else {
            PendingIntent.FLAG_UPDATE_CURRENT
        }
        return PendingIntent.getBroadcast(appContext, RC_TRANSITION_PI, intent, flags)
    }

    private fun ensureNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O &&
            notificationManager.getNotificationChannel(CHANNEL_ID) == null
        ) {
            val ch = NotificationChannel(
                CHANNEL_ID,
                "Trip Recording Prompts",
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "Prompts to start recording when vehicle or bicycle motion is detected."
            }
            notificationManager.createNotificationChannel(ch)
        }
    }
}

class TripActivityTransitionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        if (intent == null) return
        val manager = MotionPromptManager(context)
        if (intent.action == MotionPromptManager.ACTION_SNOOZE_PROMPT) {
            manager.snoozePrompts()
            return
        }

        if (!ActivityTransitionResult.hasResult(intent)) return
        val result = ActivityTransitionResult.extractResult(intent) ?: return
        val nowMs = System.currentTimeMillis()
        TelematicsSdk.ensureInitialized(context.applicationContext)

        val inVehicleExit = result.transitionEvents.any { ev ->
            ev.activityType == DetectedActivity.IN_VEHICLE &&
                ev.transitionType == ActivityTransition.ACTIVITY_TRANSITION_EXIT
        }
        if (inVehicleExit) {
            TelematicsSdk.onVehicleActivityTransition(false, nowMs)
        }

        val matchedMode = result.transitionEvents
            .firstOrNull { ev ->
                ev.transitionType == ActivityTransition.ACTIVITY_TRANSITION_ENTER &&
                    (ev.activityType == DetectedActivity.IN_VEHICLE || ev.activityType == DetectedActivity.ON_BICYCLE)
            }
            ?.let { ev ->
                when (ev.activityType) {
                    DetectedActivity.IN_VEHICLE -> TransportMode.CAR
                    DetectedActivity.ON_BICYCLE -> TransportMode.BICYCLE
                    else -> null
                }
            } ?: return

        if (matchedMode == TransportMode.CAR) {
            TelematicsSdk.onVehicleActivityTransition(true, nowMs)
        }

        val pendingResult = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                TelematicsSdk.ensureInitialized(context.applicationContext)
                TelematicsSdk.ensurePassiveSensorsRunning(startFgs = true)
                manager.handleDetectedMotion(matchedMode)
            } finally {
                pendingResult.finish()
            }
        }
    }
}

class MotionPromptBootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        val action = intent?.action ?: return
        if (action == Intent.ACTION_BOOT_COMPLETED || action == Intent.ACTION_MY_PACKAGE_REPLACED) {
            MotionPromptManager(context).syncRegistration()
            HomeGeofenceManager(context).syncRegistration()
        }
    }
}
