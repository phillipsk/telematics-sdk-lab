package com.telematicslab.demo.sdk.internal

import android.content.Context
import android.os.Build
import com.telematicslab.demo.sdk.api.LiveTelemetryState
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader
import java.io.OutputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.URLDecoder
import java.util.Locale

/**
 * Loopback-only (127.0.0.1:8765) HTTP + Server-Sent Events server enabling real-time
 * desktop workbench streaming over `adb forward tcp:8765 tcp:8765` with zero external
 * network exposure or backend setup.
 */
internal class LocalTelemetryBridgeServer(
    private val context: Context,
    private val stateFlow: StateFlow<LiveTelemetryState>,
    private val scope: CoroutineScope,
    val port: Int = 8765
) {

    @Volatile
    var isRunning: Boolean = false
        private set

    private var serverSocket: ServerSocket? = null
    private var acceptJob: Job? = null

    @Synchronized
    fun start() {
        if (isRunning) return
        acceptJob = scope.launch(Dispatchers.IO) {
            SdkSafeExecutor.runSuspendSafely("LocalTelemetryBridgeServer.start", Unit) {
                val ss = ServerSocket(port, 16, InetAddress.getByName("127.0.0.1"))
                ss.reuseAddress = true
                serverSocket = ss
                isRunning = true

                while (isActive && !ss.isClosed) {
                    val client = try {
                        ss.accept()
                    } catch (_: Exception) {
                        break
                    }
                    launch(Dispatchers.IO) {
                        handleClient(client)
                    }
                }
            }
            isRunning = false
        }
    }

    @Synchronized
    fun stop() {
        isRunning = false
        runCatching { serverSocket?.close() }
        serverSocket = null
        acceptJob?.cancel()
        acceptJob = null
    }

    private suspend fun handleClient(socket: Socket) {
        try {
            socket.use { sock ->
                sock.soTimeout = 5_000
                val reader = BufferedReader(InputStreamReader(sock.getInputStream(), Charsets.UTF_8))
                val requestLine = reader.readLine() ?: return
                while (true) {
                    val line = reader.readLine() ?: break
                    if (line.isEmpty()) break
                }

                val parts = requestLine.split(" ")
                val method = parts.getOrNull(0) ?: "GET"
                val rawTarget = parts.getOrNull(1) ?: "/"
                val path = rawTarget.substringBefore("?")
                val query = rawTarget.substringAfter("?", "")
                val out = sock.getOutputStream()

                if (method == "OPTIONS") {
                    writeHttp(out, 204, "text/plain", "")
                    return
                }

                when (path) {
                    "/health" -> {
                        val json = JSONObject().apply {
                            put("status", "ok")
                            put("device", Build.MODEL)
                            put("api", Build.VERSION.SDK_INT)
                            put("port", port)
                        }.toString()
                        writeHttp(out, 200, "application/json", json)
                    }
                    "/state" -> {
                        writeHttp(out, 200, "application/json", serializeLiveState(stateFlow.value))
                    }
                    "/trips" -> {
                        writeHttp(out, 200, "application/json", serializeTripsIndex(stateFlow.value))
                    }
                    "/trip" -> {
                        val idParam = parseQueryParam(query, "id")
                        val file = if (idParam != null && idParam.matches(Regex("[A-Za-z0-9_-]{1,32}"))) {
                            File(File(context.filesDir, "trips"), "trip_$idParam.json")
                        } else {
                            null
                        }
                        if (file != null && file.exists()) {
                            writeHttp(out, 200, "application/json", file.readText())
                        } else {
                            writeHttp(out, 404, "application/json", "{\"error\":\"trip_not_found\"}")
                        }
                    }
                    "/stream" -> {
                        sock.soTimeout = 0
                        streamSse(out)
                    }
                    else -> {
                        writeHttp(out, 404, "application/json", "{\"error\":\"not_found\"}")
                    }
                }
            }
        } catch (ce: CancellationException) {
            throw ce
        } catch (_: Throwable) {
            // Client disconnect or socket reset — normal for SSE clients
        }
    }

    private suspend fun streamSse(out: OutputStream) {
        val headers = buildString {
            append("HTTP/1.1 200 OK\r\n")
            append("Content-Type: text/event-stream; charset=utf-8\r\n")
            append("Cache-Control: no-cache\r\n")
            append("Connection: keep-alive\r\n")
            append("Access-Control-Allow-Origin: *\r\n\r\n")
        }
        out.write(headers.toByteArray(Charsets.UTF_8))
        out.flush()

        while (isRunning) {
            val payload = "data: ${serializeLiveState(stateFlow.value)}\n\n"
            out.write(payload.toByteArray(Charsets.UTF_8))
            out.flush()
            delay(80L)
        }
    }

    private fun writeHttp(out: OutputStream, code: Int, contentType: String, body: String) {
        val bodyBytes = body.toByteArray(Charsets.UTF_8)
        val statusText = when (code) {
            200 -> "OK"
            204 -> "No Content"
            404 -> "Not Found"
            else -> "Error"
        }
        val header = buildString {
            append("HTTP/1.1 $code $statusText\r\n")
            append("Content-Type: $contentType; charset=utf-8\r\n")
            append("Content-Length: ${bodyBytes.size}\r\n")
            append("Access-Control-Allow-Origin: *\r\n")
            append("Access-Control-Allow-Methods: GET, OPTIONS\r\n")
            append("Cache-Control: no-cache\r\n")
            append("Connection: close\r\n\r\n")
        }
        out.write(header.toByteArray(Charsets.UTF_8))
        if (bodyBytes.isNotEmpty()) out.write(bodyBytes)
        out.flush()
    }

    private fun parseQueryParam(query: String, key: String): String? {
        if (query.isBlank()) return null
        return query.split("&").firstNotNullOfOrNull { pair ->
            val eq = pair.indexOf('=')
            if (eq > 0 && pair.substring(0, eq) == key) {
                runCatching { URLDecoder.decode(pair.substring(eq + 1), "UTF-8") }.getOrNull()
            } else null
        }
    }

    private fun serializeTripsIndex(state: LiveTelemetryState): String {
        val arr = JSONArray()
        state.savedTrips.forEach { t ->
            arr.put(JSONObject().apply {
                put("id", t.id)
                put("startTimeMs", t.startTimeMs)
                put("durationSec", t.durationSec)
                put("distanceMiles", t.distanceMiles.toDouble())
                put("maxSpeedMph", t.maxSpeedMph.toDouble())
                put("score", t.score.overall)
                put("eventsCount", t.events.size)
                put("isSimulated", t.isSimulated)
                put("rawPayloadBytes", t.rawPayloadBytes)
                put("gzipPayloadBytes", t.gzipPayloadBytes)
                put("idempotentChunkCount", t.idempotentChunkCount)
            })
        }
        return arr.toString()
    }

    private fun serializeLiveState(s: LiveTelemetryState): String {
        val root = JSONObject()
        root.put("timestampMs", System.currentTimeMillis())
        root.put("deviceModel", Build.MODEL)
        root.put("tripState", s.tripState.name)
        root.put("stopPendingRemainingSec", s.stopPendingRemainingSec)
        root.put("tripElapsedSec", s.tripElapsedSec)
        root.put("tripDistanceMiles", s.tripDistanceMiles.toDouble())
        root.put("maxSpeedMph", s.maxSpeedMph.toDouble())
        root.put("isSimulatingTrip", s.isSimulatingTrip)
        root.put("screenOn", s.screenOn)
        root.put("isHandheld", s.isHandheld)
        root.put("gyroMagRadS", s.gyroMagRadS.toDouble())

        root.put("score", JSONObject().apply {
            put("overall", s.liveScore.overall)
            put("braking", s.liveScore.braking)
            put("acceleration", s.liveScore.acceleration)
            put("cornering", s.liveScore.cornering)
            put("speeding", s.liveScore.speeding)
            put("distraction", s.liveScore.distraction)
        })

        root.put("frame", JSONObject().apply {
            put("phoneLinearX", s.frame.phoneLinearX.toDouble())
            put("phoneLinearY", s.frame.phoneLinearY.toDouble())
            put("phoneLinearZ", s.frame.phoneLinearZ.toDouble())
            put("rawLongG", s.frame.rawLongG.toDouble())
            put("rawLatG", s.frame.rawLatG.toDouble())
            put("rawVertG", s.frame.rawVertG.toDouble())
            put("filteredLongG", s.frame.filteredLongG.toDouble())
            put("filteredLatG", s.frame.filteredLatG.toDouble())
            put("filteredVertG", s.frame.filteredVertG.toDouble())
            put("rawTotalG", s.frame.rawTotalG.toDouble())
            put("pitchDeg", s.frame.pitchDeg.toDouble())
            put("rollDeg", s.frame.rollDeg.toDouble())
            put("azimuthDeg", s.frame.azimuthDeg.toDouble())
            put("headingDeg", s.frame.headingDeg.toDouble())
            put("headingLocked", s.frame.headingLocked)
        })

        root.put("gnss", JSONObject().apply {
            put("satellitesVisible", s.gnss.satellitesVisible)
            put("satellitesUsed", s.gnss.satellitesUsed)
            put("avgCn0DbHz", s.gnss.avgCn0DbHz.toDouble())
            put("accuracyMeters", s.gnss.accuracyMeters.toDouble())
            put("hasFix", s.gnss.hasFix)
            put("lat", s.gnss.lat)
            put("lon", s.gnss.lon)
            put("speedMph", s.gnss.speedMph.toDouble())
            put("bearingDeg", s.gnss.bearingDeg.toDouble())
        })

        root.put("power", JSONObject().apply {
            put("batteryPct", s.power.batteryPct)
            put("batteryTempC", s.power.batteryTempC.toDouble())
            put("standbyBucketName", s.power.standbyBucketName)
            put("ignoringBatteryOptimizations", s.power.ignoringBatteryOptimizations)
            put("imuSampleHz", s.power.imuSampleHz.toDouble())
            put("avgBatchIntervalMs", s.power.avgBatchIntervalMs.toDouble())
            put("fifoBatchLatencyMs", s.power.fifoBatchLatencyMs)
            put("legacyWakeLockEnabled", s.power.legacyWakeLockEnabled)
            put("manualWakeLockHeldMs", s.power.manualWakeLockHeldMs)
            put("fgsUptimeSec", s.power.fgsUptimeSec)
            put("ringBufferFill", s.power.ringBufferFill)
            put("ringBufferCapacity", s.power.ringBufferCapacity)
        })

        root.put("diagnostics", JSONObject().apply {
            put("startupInitMs", s.diagnostics.startupInitMs)
            put("lastExitReason", s.diagnostics.lastExitReason)
            put("trimMemoryEvents", s.diagnostics.trimMemoryEvents)
            put("caughtBoundaryFaults", s.diagnostics.caughtBoundaryFaults)
            put("quarantinedFiles", s.diagnostics.quarantinedFiles)
            put("fgsStartCount", s.diagnostics.fgsStartCount)
            put("filterEngineMode", s.config.filterEngineMode.name)
            put("lowPassAlpha", s.config.lowPassAlpha.toDouble())
            put("latestWakeLockDecision", s.diagnostics.wakeLockDecisionLog.lastOrNull() ?: "")
        })

        root.put("events", JSONArray().apply {
            s.liveEvents.take(10).forEach { ev ->
                put(JSONObject().apply {
                    put("id", ev.id)
                    put("type", ev.type.name)
                    put("displayName", ev.type.displayName)
                    put("elapsedTripSec", String.format(Locale.US, "%.1f", ev.elapsedTripSec).toDouble())
                    put("details", ev.details)
                })
            }
        })

        return root.toString()
    }
}
