package com.telematicslab.demo.sdk.motion

import android.Manifest
import android.annotation.SuppressLint
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import androidx.core.content.ContextCompat
import com.google.android.gms.location.Geofence
import com.google.android.gms.location.GeofencingEvent
import com.google.android.gms.location.GeofencingRequest
import com.google.android.gms.location.LocationServices
import com.telematicslab.demo.sdk.api.TelematicsSdk
import com.telematicslab.demo.sdk.engine.HomeGeofenceConfig

/**
 * Registers an optional OS-managed Play Services geofence around the user's configured
 * Home anchor stored privately in app preferences.
 */
class HomeGeofenceManager(context: Context) {

    private val appContext = context.applicationContext
    private val prefs = appContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    companion object {
        private const val TAG = "HomeGeofenceManager"
        private const val PREFS_NAME = "home_geofence_prefs"
        private const val GEOFENCE_ID = "home_departure_geofence"
        private const val RC_GEOFENCE_PI = 4230

        private const val KEY_ENABLED = "enabled"
        private const val KEY_LABEL = "label"
        private const val KEY_LAT = "lat"
        private const val KEY_LON = "lon"
        private const val KEY_RADIUS_M = "radius_m"
        private const val KEY_MIN_IN_VEHICLE_SEC = "min_in_vehicle_sec"
        private const val KEY_MIN_SPEED_MPH = "min_speed_mph"
        private const val KEY_LAST_EVENT = "last_event"

        @Volatile
        var isRegistered: Boolean = false
            private set
    }

    fun loadConfig(): HomeGeofenceConfig {
        val enabled = prefs.getBoolean(KEY_ENABLED, false)
        val label = prefs.getString(KEY_LABEL, "Home") ?: "Home"
        val lat = prefs.getString(KEY_LAT, "0")?.toDoubleOrNull() ?: 0.0
        val lon = prefs.getString(KEY_LON, "0")?.toDoubleOrNull() ?: 0.0
        val radiusM = prefs.getFloat(KEY_RADIUS_M, 120f).coerceAtLeast(50f)
        val minInVehSec = prefs.getInt(KEY_MIN_IN_VEHICLE_SEC, 60).coerceAtLeast(10)
        val minSpeedMph = prefs.getFloat(KEY_MIN_SPEED_MPH, 10f).coerceAtLeast(5f)
        return HomeGeofenceConfig(
            enabled = enabled,
            label = label,
            lat = lat,
            lon = lon,
            radiusMeters = radiusM,
            minInVehicleSec = minInVehSec,
            minSpeedMph = minSpeedMph
        )
    }

    fun getLastEvent(): String = prefs.getString(KEY_LAST_EVENT, "None") ?: "None"

    internal fun recordLastEvent(eventLabel: String) {
        prefs.edit().putString(KEY_LAST_EVENT, eventLabel).apply()
    }

    private fun hasRequiredPermissions(): Boolean {
        val fineGranted = ContextCompat.checkSelfPermission(appContext, Manifest.permission.ACCESS_FINE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED
        val bgGranted = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ContextCompat.checkSelfPermission(appContext, Manifest.permission.ACCESS_BACKGROUND_LOCATION) ==
                PackageManager.PERMISSION_GRANTED
        } else {
            true
        }
        return fineGranted && bgGranted
    }

    @SuppressLint("MissingPermission")
    fun syncRegistration() {
        val cfg = loadConfig()
        val client = LocationServices.getGeofencingClient(appContext)
        val pi = buildGeofencePendingIntent()

        if (!cfg.isConfigured || !hasRequiredPermissions()) {
            client.removeGeofences(pi).addOnCompleteListener {
                isRegistered = false
                if (cfg.isConfigured) {
                    Log.w(TAG, "Home geofence unregistered: missing Fine/Background Location permission")
                }
            }
            return
        }

        val geofence = Geofence.Builder()
            .setRequestId(GEOFENCE_ID)
            .setCircularRegion(cfg.lat, cfg.lon, cfg.radiusMeters)
            .setExpirationDuration(Geofence.NEVER_EXPIRE)
            .setTransitionTypes(Geofence.GEOFENCE_TRANSITION_EXIT or Geofence.GEOFENCE_TRANSITION_ENTER)
            .build()

        val request = GeofencingRequest.Builder()
            .setInitialTrigger(GeofencingRequest.INITIAL_TRIGGER_ENTER)
            .addGeofence(geofence)
            .build()

        client.removeGeofences(pi).addOnCompleteListener {
            client.addGeofences(request, pi)
                .addOnSuccessListener {
                    isRegistered = true
                    Log.i(
                        TAG,
                        "Registered Home geofence (r=${cfg.radiusMeters.toInt()}m, >${cfg.minInVehicleSec}s in-vehicle & >${cfg.minSpeedMph.toInt()} mph)"
                    )
                }
                .addOnFailureListener { err ->
                    isRegistered = false
                    Log.w(TAG, "Failed to register Home geofence: ${err.message}")
                }
        }
    }

    private fun buildGeofencePendingIntent(): PendingIntent {
        val intent = Intent(appContext, HomeGeofenceReceiver::class.java)
        val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            PendingIntent.FLAG_MUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        } else {
            PendingIntent.FLAG_UPDATE_CURRENT
        }
        return PendingIntent.getBroadcast(appContext, RC_GEOFENCE_PI, intent, flags)
    }
}

class HomeGeofenceReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        if (intent == null) return
        val event = GeofencingEvent.fromIntent(intent) ?: return
        if (event.hasError()) return

        val appCtx = context.applicationContext
        val manager = HomeGeofenceManager(appCtx)
        TelematicsSdk.ensureInitialized(appCtx)

        when (event.geofenceTransition) {
            Geofence.GEOFENCE_TRANSITION_EXIT -> {
                manager.recordLastEvent("EXITED")
                TelematicsSdk.onHomeGeofenceExited(System.currentTimeMillis())
            }
            Geofence.GEOFENCE_TRANSITION_ENTER -> {
                manager.recordLastEvent("HOME")
                TelematicsSdk.onHomeGeofenceEntered()
            }
        }
    }
}
