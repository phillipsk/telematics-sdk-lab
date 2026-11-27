package com.telematicslab.demo.sdk.internal

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/**
 * Durable cloud uploader & bidirectional archive client syncing trip JSON payloads with the
 * user's Cloud Run Workbench archive (`/api/trips`), authenticated via `X-Workbench-Passcode`.
 * Credentials are stored solely in SharedPreferences.
 */
internal class CloudTripUploader(context: Context) {

    private val prefs = context.getSharedPreferences("cloud_sync_prefs", Context.MODE_PRIVATE)

    companion object {
        internal fun buildUnlockUrl(endpoint: String, passcode: String): String {
            val cleanUrl = endpoint.trim().trimEnd('/')
            if (cleanUrl.isBlank()) return ""
            val cleanCode = passcode.trim()
            if (cleanCode.isBlank()) return cleanUrl
            val encoded = URLEncoder.encode(cleanCode, "UTF-8").replace("+", "%20")
            return "$cleanUrl/?unlock=$encoded"
        }

        internal fun parseCloudTripIds(rawJson: String): List<String> =
            parseCloudTripSummaries(rawJson).map { it.id }

        internal fun parseCloudTripSummaries(rawJson: String): List<CloudTripSummary> {
            val trimmed = rawJson.trim()
            if (trimmed.isEmpty()) return emptyList()
            val objRegex = Regex("""\{[^{}]*\}""")
            val idRegex = Regex(""""id"\s*:\s*"([^"]+)"""")
            fun extractString(block: String, key: String): String? {
                val m = Regex(""""$key"\s*:\s*"((?:\\.|[^"\\])*)"""").find(block) ?: return null
                return m.groupValues[1]
                    .replace("""\"""", "\"")
                    .replace("""\\""", "\\")
                    .replace("""\/""", "/")
                    .trim()
                    .ifEmpty { null }
            }

            val seen = LinkedHashSet<String>()
            val out = ArrayList<CloudTripSummary>()
            for (blockMatch in objRegex.findAll(trimmed)) {
                val block = blockMatch.value
                val id = idRegex.find(block)?.groupValues?.get(1)?.trim().orEmpty()
                if (id.isEmpty() || !seen.add(id)) continue
                out += CloudTripSummary(
                    id = id,
                    tripLabel = extractString(block, "tripLabel"),
                    seatRole = extractString(block, "seatRole"),
                    transportMode = extractString(block, "transportMode")
                )
            }
            return out
        }
    }

    internal data class CloudTripSummary(
        val id: String,
        val tripLabel: String?,
        val seatRole: String?,
        val transportMode: String?
    )

    fun getEndpointUrl(): String = prefs.getString("endpoint_url", "")?.trim().orEmpty()
    fun getPasscode(): String = prefs.getString("passcode", "")?.trim().orEmpty()
    fun hasPasscode(): Boolean = getPasscode().isNotBlank()

    fun getUnlockUrl(): String =
        buildUnlockUrl(getEndpointUrl(), getPasscode())

    fun configure(endpointUrl: String, passcode: String) {
        val cleanUrl = endpointUrl.trim().trimEnd('/')
        prefs.edit().apply {
            if (cleanUrl.isNotEmpty()) putString("endpoint_url", cleanUrl)
            if (passcode.isNotBlank()) putString("passcode", passcode.trim())
        }.apply()
    }

    suspend fun uploadTrip(tripId: String, rawTripJson: String): Boolean = withContext(Dispatchers.IO) {
        val endpoint = getEndpointUrl()
        val passcode = prefs.getString("passcode", "")?.trim().orEmpty()
        if (endpoint.isBlank() || passcode.isBlank()) return@withContext false

        var conn: HttpURLConnection? = null
        try {
            val url = URL("$endpoint/api/trips/$tripId")
            conn = (url.openConnection() as HttpURLConnection).apply {
                requestMethod = "PUT"
                connectTimeout = 15_000
                readTimeout = 20_000
                doOutput = true
                setRequestProperty("Content-Type", "application/json; charset=utf-8")
                setRequestProperty("X-Workbench-Passcode", passcode)
            }
            conn.outputStream.use { os ->
                os.write(rawTripJson.toByteArray(Charsets.UTF_8))
                os.flush()
            }
            val code = conn.responseCode
            code in 200..299
        } catch (_: Exception) {
            false
        } finally {
            conn?.disconnect()
        }
    }

    suspend fun listCloudTripIds(): List<String> =
        listCloudTripSummaries().map { it.id }

    suspend fun listCloudTripSummaries(): List<CloudTripSummary> = withContext(Dispatchers.IO) {
        val endpoint = getEndpointUrl()
        val passcode = getPasscode()
        if (endpoint.isBlank() || passcode.isBlank()) return@withContext emptyList()

        var conn: HttpURLConnection? = null
        try {
            val url = URL("$endpoint/api/trips")
            conn = (url.openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = 12_000
                readTimeout = 15_000
                setRequestProperty("X-Workbench-Passcode", passcode)
            }
            if (conn.responseCode !in 200..299) return@withContext emptyList()
            val body = conn.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
            parseCloudTripSummaries(body)
        } catch (_: Exception) {
            emptyList()
        } finally {
            conn?.disconnect()
        }
    }

    suspend fun downloadTripJson(tripId: String): String? = withContext(Dispatchers.IO) {
        val endpoint = getEndpointUrl()
        val passcode = getPasscode()
        if (endpoint.isBlank() || passcode.isBlank()) return@withContext null

        var conn: HttpURLConnection? = null
        try {
            val url = URL("$endpoint/api/trips/$tripId")
            conn = (url.openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = 15_000
                readTimeout = 25_000
                setRequestProperty("X-Workbench-Passcode", passcode)
            }
            if (conn.responseCode !in 200..299) return@withContext null
            conn.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
        } catch (_: Exception) {
            null
        } finally {
            conn?.disconnect()
        }
    }

    suspend fun deleteCloudTrip(tripId: String): Boolean = withContext(Dispatchers.IO) {
        val endpoint = getEndpointUrl()
        val passcode = getPasscode()
        if (endpoint.isBlank() || passcode.isBlank()) return@withContext false

        var conn: HttpURLConnection? = null
        try {
            val url = URL("$endpoint/api/trips/$tripId")
            conn = (url.openConnection() as HttpURLConnection).apply {
                requestMethod = "DELETE"
                connectTimeout = 12_000
                readTimeout = 15_000
                setRequestProperty("X-Workbench-Passcode", passcode)
            }
            conn.responseCode in 200..299 || conn.responseCode == 404
        } catch (_: Exception) {
            false
        } finally {
            conn?.disconnect()
        }
    }
}
