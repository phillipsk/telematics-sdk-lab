package com.telematicslab.demo.sdk.internal

import android.content.Context
import android.util.Log
import com.telematicslab.demo.sdk.api.AutoStopMode
import com.telematicslab.demo.sdk.api.FilterEngineMode
import com.telematicslab.demo.sdk.api.SdkConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.URL

/**
 * Fetches and caches Cloud Run Remote SDK Config (`GET /api/config`), overriding
 * thresholds and feature flags cleanly without requiring an APK rebuild.
 */
internal class RemoteConfigClient(context: Context) {

    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    companion object {
        private const val TAG = "RemoteConfigClient"
        private const val PREFS_NAME = "remote_config_prefs"
        private const val KEY_RAW_JSON = "cached_json"

        internal fun parseConfigJson(rawJson: String, base: SdkConfig): Pair<SdkConfig, Int>? {
            val trimmed = rawJson.trim()
            if (!trimmed.startsWith("{") || !trimmed.endsWith("}")) return null

            val version = readInt(trimmed, "version") ?: return null
            val autoDetect = readBoolean(trimmed, "autoDetectEnabled") ?: base.autoDetectEnabled
            val motionPrompts = readBoolean(trimmed, "motionPromptsEnabled") ?: base.motionPromptsEnabled
            val autoStartMph = (readFloat(trimmed, "autoStartVerifyMph") ?: base.autoStartVerifyMph).coerceIn(5f, 35f)
            val rawStopHyst = readInt(trimmed, "stopHysteresisSec") ?: base.stopHysteresisSec
            val autoStopMode = when (readString(trimmed, "autoStopMode")) {
                AutoStopMode.MANUAL_ONLY.name -> AutoStopMode.MANUAL_ONLY
                AutoStopMode.QUICK_90S.name -> AutoStopMode.QUICK_90S
                AutoStopMode.SMART_5M.name -> AutoStopMode.SMART_5M
                else -> if (rawStopHyst == 0) AutoStopMode.MANUAL_ONLY else base.autoStopMode
            }
            val stopHyst = if (autoStopMode == AutoStopMode.MANUAL_ONLY) 0 else rawStopHyst.coerceIn(30, 600)
            val autoStopNonCar = readBoolean(trimmed, "autoStopNonCar") ?: base.autoStopNonCar
            val filterMode = when (readString(trimmed, "filterEngineMode")) {
                FilterEngineMode.ADAPTIVE_DUAL_POLE.name -> FilterEngineMode.ADAPTIVE_DUAL_POLE
                FilterEngineMode.STANDARD_LOW_PASS.name -> FilterEngineMode.STANDARD_LOW_PASS
                else -> base.filterEngineMode
            }
            val alpha = (readFloat(trimmed, "lowPassAlpha") ?: base.lowPassAlpha).coerceIn(0.08f, 0.80f)
            val brakeG = (readFloat(trimmed, "hardBrakeG") ?: base.hardBrakeG).coerceIn(0.15f, 0.80f)
            val accelG = (readFloat(trimmed, "rapidAccelG") ?: base.rapidAccelG).coerceIn(0.15f, 0.80f)
            val cornerG = (readFloat(trimmed, "harshCornerG") ?: base.harshCornerG).coerceIn(0.15f, 0.80f)
            val speedMph = (readFloat(trimmed, "speedingLimitMph") ?: base.speedingLimitMph).coerceIn(35f, 95f)
            val impactG = (readFloat(trimmed, "impactThresholdG") ?: base.impactThresholdG).coerceIn(2.0f, 5.0f)
            val fifoMs = when (readInt(trimmed, "fifoBatchLatencyMs")) {
                0, 500, 2000 -> readInt(trimmed, "fifoBatchLatencyMs")!!
                else -> base.fifoBatchLatencyMs
            }
            val legacyWake = readBoolean(trimmed, "legacyWakeLockEnabled") ?: base.legacyWakeLockEnabled

            val merged = base.copy(
                autoDetectEnabled = autoDetect,
                motionPromptsEnabled = motionPrompts,
                autoStartVerifyMph = autoStartMph,
                autoStopMode = autoStopMode,
                stopHysteresisSec = stopHyst,
                autoStopNonCar = autoStopNonCar,
                filterEngineMode = filterMode,
                lowPassAlpha = alpha,
                hardBrakeG = brakeG,
                rapidAccelG = accelG,
                harshCornerG = cornerG,
                speedingLimitMph = speedMph,
                impactThresholdG = impactG,
                fifoBatchLatencyMs = fifoMs,
                legacyWakeLockEnabled = legacyWake,
                remoteConfigVersion = version
            )
            return merged to version
        }

        private fun readBoolean(json: String, key: String): Boolean? {
            val m = Regex(""""$key"\s*:\s*(true|false)""").find(json) ?: return null
            return m.groupValues[1].toBooleanStrictOrNull()
        }

        private fun readFloat(json: String, key: String): Float? {
            val m = Regex(""""$key"\s*:\s*(-?\d+(?:\.\d+)?)""").find(json) ?: return null
            return m.groupValues[1].toFloatOrNull()
        }

        private fun readInt(json: String, key: String): Int? {
            val m = Regex(""""$key"\s*:\s*(-?\d+)""").find(json) ?: return null
            return m.groupValues[1].toIntOrNull()
        }

        private fun readString(json: String, key: String): String? {
            val m = Regex(""""$key"\s*:\s*"([^"]*)"""").find(json) ?: return null
            return m.groupValues[1]
        }
    }

    fun loadCachedConfig(base: SdkConfig): Pair<SdkConfig, Int>? {
        val cached = prefs.getString(KEY_RAW_JSON, null) ?: return null
        return parseConfigJson(cached, base)
    }

    suspend fun fetchRemoteConfig(
        endpointUrl: String,
        passcode: String,
        base: SdkConfig
    ): Pair<SdkConfig, Int>? = withContext(Dispatchers.IO) {
        val cleanUrl = endpointUrl.trim().trimEnd('/')
        val cleanCode = passcode.trim()
        if (cleanUrl.isBlank() || cleanCode.isBlank()) return@withContext null

        var conn: HttpURLConnection? = null
        try {
            val url = URL("$cleanUrl/api/config")
            conn = (url.openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = 10_000
                readTimeout = 10_000
                setRequestProperty("X-Workbench-Passcode", cleanCode)
            }
            val code = conn.responseCode
            if (code !in 200..299) {
                Log.w(TAG, "Remote config fetch HTTP $code")
                return@withContext null
            }
            val body = conn.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
            val parsed = parseConfigJson(body, base)
            if (parsed != null) {
                prefs.edit().putString(KEY_RAW_JSON, body).apply()
                Log.i(TAG, "Synced remote config v${parsed.second} (autoStart=${parsed.first.autoStartVerifyMph.toInt()} mph, fifo=${parsed.first.fifoBatchLatencyMs}ms)")
            }
            parsed
        } catch (err: Exception) {
            Log.w(TAG, "Remote config fetch failed: ${err.message}")
            null
        } finally {
            conn?.disconnect()
        }
    }
}
