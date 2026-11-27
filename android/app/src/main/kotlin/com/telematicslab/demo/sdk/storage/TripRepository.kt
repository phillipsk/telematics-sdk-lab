package com.telematicslab.demo.sdk.storage

import android.content.Context
import android.net.Uri
import androidx.core.content.FileProvider
import com.telematicslab.demo.sdk.api.DrivingEvent
import com.telematicslab.demo.sdk.api.EventType
import com.telematicslab.demo.sdk.api.ExportFormat
import com.telematicslab.demo.sdk.api.FsmTransition
import com.telematicslab.demo.sdk.api.ImpactAnalysis
import com.telematicslab.demo.sdk.api.ImpactVerdict
import com.telematicslab.demo.sdk.api.ImuSample
import com.telematicslab.demo.sdk.api.SeatRole
import com.telematicslab.demo.sdk.api.SyncStatus
import com.telematicslab.demo.sdk.api.TrackPoint
import com.telematicslab.demo.sdk.api.TransportMode
import com.telematicslab.demo.sdk.api.TripRecord
import com.telematicslab.demo.sdk.api.TripScoreBreakdown
import com.telematicslab.demo.sdk.api.TripState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.zip.GZIPOutputStream
import kotlin.math.ceil

/**
 * Bounded offline trip store under `filesDir/trips/` with:
 * - 50 MB storage cap + impact-preserving priority eviction
 * - Corrupt-file quarantine (`filesDir/trips/quarantine/`)
 * - Gzip payload compression & 16 KB idempotent chunk estimation
 * - Automatic JSON, CSV, and GPX archiving to `externalFilesDir/exports/`
 */
class TripRepository(private val context: Context) {

    companion object {
        private const val MAX_STORAGE_BYTES = 50L * 1024L * 1024L
        private const val CHECKPOINT_FILE = "active_checkpoint.json"
        private const val CHUNK_TARGET_BYTES = 16_384.0
    }

    @Volatile
    var quarantinedFilesCount: Int = 0
        private set

    private val tripsDir: File
        get() = File(context.filesDir, "trips").apply { mkdirs() }

    private val quarantineDir: File
        get() = File(tripsDir, "quarantine").apply { mkdirs() }

    private val exportsDir: File
        get() = File(context.getExternalFilesDir(null) ?: context.filesDir, "exports").apply { mkdirs() }

    suspend fun loadAllTrips(): List<TripRecord> = withContext(Dispatchers.IO) {
        quarantinedFilesCount = quarantineDir.listFiles()?.size ?: 0
        val files = tripsDir.listFiles { file ->
            file.isFile && file.name.startsWith("trip_") && file.name.endsWith(".json")
        } ?: return@withContext emptyList()

        files.mapNotNull { file ->
            val parsed = runCatching { parseTrip(JSONObject(file.readText())) }.getOrNull()
            if (parsed == null) quarantineFile(file)
            parsed
        }.sortedByDescending { it.startTimeMs }
    }

    suspend fun saveTrip(trip: TripRecord): List<TripRecord> = withContext(Dispatchers.IO) {
        val rawJson = serializeTrip(trip).toString()
        val rawBytes = rawJson.toByteArray(Charsets.UTF_8)
        val gzipBytes = gzipLength(rawBytes)
        val chunks = ceil(gzipBytes / CHUNK_TARGET_BYTES).toInt().coerceAtLeast(1)

        val enriched = trip.copy(
            rawPayloadBytes = rawBytes.size,
            gzipPayloadBytes = gzipBytes,
            idempotentChunkCount = chunks
        )

        val jsonText = serializeTrip(enriched).toString()
        File(tripsDir, "trip_${enriched.id}.json").writeText(jsonText)
        File(tripsDir, CHECKPOINT_FILE).delete()
        archiveAllExportFormats(enriched)
        enforceCap()
        loadAllTrips()
    }

    suspend fun updateTripMetadata(
        tripId: String,
        label: String,
        seatRole: SeatRole,
        mode: TransportMode
    ): List<TripRecord> =
        withContext(Dispatchers.IO) {
            val file = File(tripsDir, "trip_$tripId.json")
            if (file.exists()) {
                val parsed = runCatching { parseTrip(JSONObject(file.readText())) }.getOrNull()
                if (parsed != null) {
                    val updated = parsed.copy(
                        tripLabel = label.trim(),
                        seatRole = seatRole,
                        transportMode = mode,
                        syncStatus = if (parsed.syncStatus == SyncStatus.SYNCED) SyncStatus.PENDING_UPLOAD else parsed.syncStatus
                    )
                    file.writeText(serializeTrip(updated).toString())
                    archiveAllExportFormats(updated)
                }
            }
            loadAllTrips()
        }

    suspend fun updateTripSyncStatus(tripId: String, syncStatus: SyncStatus): List<TripRecord> =
        withContext(Dispatchers.IO) {
            val file = File(tripsDir, "trip_$tripId.json")
            if (file.exists()) {
                val parsed = runCatching { parseTrip(JSONObject(file.readText())) }.getOrNull()
                if (parsed != null) {
                    val updated = parsed.copy(syncStatus = syncStatus)
                    file.writeText(serializeTrip(updated).toString())
                }
            }
            loadAllTrips()
        }

    suspend fun readRawTripJson(tripId: String): String? = withContext(Dispatchers.IO) {
        val file = File(tripsDir, "trip_$tripId.json")
        if (file.exists()) file.readText() else null
    }

    suspend fun applyCloudMetadata(
        tripId: String,
        label: String,
        seatRole: SeatRole?,
        mode: TransportMode?
    ): Boolean = withContext(Dispatchers.IO) {
        val file = File(tripsDir, "trip_$tripId.json")
        if (!file.exists()) return@withContext false
        val parsed = runCatching { parseTrip(JSONObject(file.readText())) }.getOrNull() ?: return@withContext false
        val cleanLabel = label.trim().ifBlank { parsed.tripLabel }
        val nextRole = seatRole ?: parsed.seatRole
        val nextMode = mode ?: parsed.transportMode
        if (cleanLabel == parsed.tripLabel && nextRole == parsed.seatRole && nextMode == parsed.transportMode) {
            return@withContext false
        }
        val updated = parsed.copy(
            tripLabel = cleanLabel,
            seatRole = nextRole,
            transportMode = nextMode,
            syncStatus = SyncStatus.SYNCED
        )
        file.writeText(serializeTrip(updated).toString())
        archiveAllExportFormats(updated)
        true
    }

    suspend fun importCloudTripJson(rawJson: String): TripRecord? = withContext(Dispatchers.IO) {
        val parsed = runCatching { parseTrip(JSONObject(rawJson)) }.getOrNull() ?: return@withContext null
        val normalized = if (parsed.rawPayloadBytes > 0) {
            parsed.copy(syncStatus = SyncStatus.SYNCED)
        } else {
            val rawBytes = serializeTrip(parsed).toString().toByteArray(Charsets.UTF_8)
            val gzipBytes = gzipLength(rawBytes)
            val chunks = ceil(gzipBytes / CHUNK_TARGET_BYTES).toInt().coerceAtLeast(1)
            parsed.copy(
                syncStatus = SyncStatus.SYNCED,
                rawPayloadBytes = rawBytes.size,
                gzipPayloadBytes = gzipBytes,
                idempotentChunkCount = chunks
            )
        }
        File(tripsDir, "trip_${normalized.id}.json").writeText(serializeTrip(normalized).toString())
        archiveAllExportFormats(normalized)
        enforceCap()
        normalized
    }

    suspend fun saveCheckpoint(trip: TripRecord) = withContext(Dispatchers.IO) {
        runCatching {
            val tmp = File(tripsDir, "$CHECKPOINT_FILE.tmp")
            val target = File(tripsDir, CHECKPOINT_FILE)
            tmp.writeText(serializeTrip(trip).toString())
            if (!tmp.renameTo(target)) {
                target.writeText(tmp.readText())
                tmp.delete()
            }
        }
    }

    suspend fun recoverCheckpointIfAny(): TripRecord? = withContext(Dispatchers.IO) {
        val ck = File(tripsDir, CHECKPOINT_FILE)
        if (!ck.exists()) return@withContext null
        val parsed = runCatching { parseTrip(JSONObject(ck.readText())) }.getOrNull()
        if (parsed == null) {
            quarantineFile(ck)
            return@withContext null
        }
        if (parsed.track.size >= 3 || parsed.durationSec >= 5) {
            val rawBytes = serializeTrip(parsed).toString().toByteArray(Charsets.UTF_8)
            val gzipBytes = gzipLength(rawBytes)
            val chunks = ceil(gzipBytes / CHUNK_TARGET_BYTES).toInt().coerceAtLeast(1)
            val enriched = parsed.copy(
                rawPayloadBytes = rawBytes.size,
                gzipPayloadBytes = gzipBytes,
                idempotentChunkCount = chunks
            )
            File(tripsDir, "trip_${enriched.id}.json").writeText(serializeTrip(enriched).toString())
            archiveAllExportFormats(enriched)
        }
        ck.delete()
        parsed
    }

    suspend fun deleteTrip(tripId: String): List<TripRecord> = withContext(Dispatchers.IO) {
        File(tripsDir, "trip_$tripId.json").delete()
        loadAllTrips()
    }

    suspend fun exportTrip(trip: TripRecord, format: ExportFormat): Pair<File, Uri> = withContext(Dispatchers.IO) {
        val stamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date(trip.startTimeMs))
        val file = File(exportsDir, "trip_${stamp}_${trip.id}.${format.extension}")
        val content = when (format) {
            ExportFormat.JSON -> serializeTrip(trip).toString(2)
            ExportFormat.CSV -> serializeCsv(trip)
            ExportFormat.GPX -> serializeGpx(trip)
        }
        file.writeText(content)
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
        file to uri
    }

    private fun archiveAllExportFormats(trip: TripRecord) {
        runCatching {
            val stamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date(trip.startTimeMs))
            File(exportsDir, "trip_${stamp}_${trip.id}.json").writeText(serializeTrip(trip).toString(2))
            File(exportsDir, "trip_${stamp}_${trip.id}.csv").writeText(serializeCsv(trip))
            File(exportsDir, "trip_${stamp}_${trip.id}.gpx").writeText(serializeGpx(trip))
        }
    }

    private fun quarantineFile(file: File) {
        runCatching {
            val dest = File(quarantineDir, "${System.currentTimeMillis()}_${file.name}")
            if (file.renameTo(dest)) quarantinedFilesCount++ else file.delete()
        }
    }

    private fun gzipLength(bytes: ByteArray): Int {
        val bos = ByteArrayOutputStream()
        GZIPOutputStream(bos).use { it.write(bytes) }
        return bos.size()
    }

    private fun enforceCap() {
        val files = tripsDir.listFiles { f -> f.name.startsWith("trip_") && f.name.endsWith(".json") }?.toMutableList()
            ?: return
        var total = files.sumOf { it.length() }
        if (total <= MAX_STORAGE_BYTES) return

        val parsed = files.mapNotNull { f ->
            val rec = runCatching { parseTrip(JSONObject(f.readText())) }.getOrNull()
            if (rec != null) f to rec else null
        }.sortedWith(
            compareBy<Pair<File, TripRecord>> { pair ->
                if (pair.second.impacts.any { it.verdict == ImpactVerdict.POTENTIAL_IMPACT }) 1 else 0
            }.thenBy { it.second.startTimeMs }
        )

        for ((file, _) in parsed) {
            if (total <= MAX_STORAGE_BYTES) break
            val len = file.length()
            if (file.delete()) total -= len
        }
    }

    private fun isoUtc(ms: Long): String {
        val fmt = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US).apply {
            timeZone = TimeZone.getTimeZone("UTC")
        }
        return fmt.format(Date(ms))
    }

    private fun serializeCsv(trip: TripRecord): String {
        val sb = StringBuilder()
        sb.appendLine("# Trip ID,${trip.id}")
        sb.appendLine("# Label,${trip.tripLabel.ifBlank { "Trip ${trip.id}" }.replace(',', ' ')}")
        sb.appendLine("# Seat Role,${trip.seatRole.name}")
        sb.appendLine("# Mode,${trip.transportMode.name}")
        sb.appendLine("# Vert RMS G,${"%.4f".format(Locale.US, trip.avgVertRmsG)}")
        sb.appendLine("# Start UTC,${isoUtc(trip.startTimeMs)}")
        sb.appendLine("# End UTC,${isoUtc(trip.endTimeMs)}")
        sb.appendLine("# Overall Score,${trip.score.overall},Distance Mi,${"%.3f".format(Locale.US, trip.distanceMiles)},Duration Sec,${trip.durationSec},Simulated,${trip.isSimulated}")
        sb.appendLine("elapsed_sec,timestamp_ms,iso_utc,lat,lon,speed_mph,bearing_deg,accuracy_m,satellites_used,long_g,lat_g,vert_g,raw_total_g,yaw_rad_s,screen_on,handheld")
        for (p in trip.track) {
            sb.appendLine(
                String.format(
                    Locale.US,
                    "%.2f,%d,%s,%.6f,%.6f,%.2f,%.1f,%.1f,%d,%.4f,%.4f,%.4f,%.4f,%.4f,%b,%b",
                    p.elapsedSec, p.timestampMs, isoUtc(p.timestampMs), p.lat, p.lon, p.speedMph, p.bearingDeg,
                    p.accuracyM, p.satellitesUsed, p.longG, p.latG, p.vertG, p.rawTotalG,
                    p.yawRadS, p.screenOn, p.handheld
                )
            )
        }
        return sb.toString()
    }

    private fun serializeGpx(trip: TripRecord): String {
        fun esc(s: String) = s
            .replace("&", "&amp;")
            .replace("<", "&lt;")
            .replace(">", "&gt;")
            .replace("\"", "&quot;")
        val title = esc(trip.tripLabel.ifBlank { "Trip ${trip.id}" })
        val sb = StringBuilder()
        sb.appendLine("""<?xml version="1.0" encoding="UTF-8"?>""")
        sb.appendLine("""<gpx version="1.1" creator="Telematics Lab" xmlns="http://www.topografix.com/GPX/1/1">""")
        sb.appendLine("  <metadata>")
        sb.appendLine("    <name>$title (${trip.transportMode.label} / ${trip.seatRole.label})</name>")
        sb.appendLine("    <time>${isoUtc(trip.startTimeMs)}</time>")
        sb.appendLine("  </metadata>")
        trip.events.filter { it.lat != 0.0 || it.lon != 0.0 }.forEach { ev ->
            sb.appendLine(String.format(Locale.US, """  <wpt lat="%.6f" lon="%.6f">""", ev.lat, ev.lon))
            sb.appendLine("    <time>${isoUtc(ev.timestampMs)}</time>")
            sb.appendLine("    <name>${esc(ev.type.name)}</name>")
            sb.appendLine("    <desc>${esc(ev.details)}</desc>")
            sb.appendLine("  </wpt>")
        }
        sb.appendLine("  <trk>")
        sb.appendLine("    <name>$title</name>")
        sb.appendLine("    <trkseg>")
        trip.track.filter { it.lat != 0.0 || it.lon != 0.0 }.forEach { p ->
            sb.appendLine(
                String.format(
                    Locale.US,
                    """      <trkpt lat="%.6f" lon="%.6f"><ele>%.1f</ele><time>%s</time></trkpt>""",
                    p.lat, p.lon, p.altitudeM, isoUtc(p.timestampMs)
                )
            )
        }
        sb.appendLine("    </trkseg>")
        sb.appendLine("  </trk>")
        sb.appendLine("</gpx>")
        return sb.toString()
    }

    private fun serializeTrip(trip: TripRecord): JSONObject {
        val obj = JSONObject()
        obj.put("id", trip.id)
        obj.put("tripLabel", trip.tripLabel)
        obj.put("seatRole", trip.seatRole.name)
        obj.put("transportMode", trip.transportMode.name)
        obj.put("syncStatus", trip.syncStatus.name)
        obj.put("startTimeMs", trip.startTimeMs)
        obj.put("endTimeMs", trip.endTimeMs)
        obj.put("durationSec", trip.durationSec)
        obj.put("distanceMiles", trip.distanceMiles.toDouble())
        obj.put("maxSpeedMph", trip.maxSpeedMph.toDouble())
        obj.put("avgSpeedMph", trip.avgSpeedMph.toDouble())
        obj.put("avgVertRmsG", trip.avgVertRmsG.toDouble())
        obj.put("batteryDeltaPct", trip.batteryDeltaPct)
        obj.put("avgAccuracyM", trip.avgAccuracyM.toDouble())
        obj.put("manualWakeLockMs", trip.manualWakeLockMs)
        obj.put("rawPayloadBytes", trip.rawPayloadBytes)
        obj.put("gzipPayloadBytes", trip.gzipPayloadBytes)
        obj.put("idempotentChunkCount", trip.idempotentChunkCount)
        obj.put("isSimulated", trip.isSimulated)

        obj.put("score", JSONObject().apply {
            put("overall", trip.score.overall)
            put("braking", trip.score.braking)
            put("acceleration", trip.score.acceleration)
            put("cornering", trip.score.cornering)
            put("speeding", trip.score.speeding)
            put("distraction", trip.score.distraction)
            put("smoothness", trip.score.smoothness)
        })

        obj.put("events", JSONArray().apply {
            trip.events.forEach { ev ->
                put(JSONObject().apply {
                    put("id", ev.id)
                    put("type", ev.type.name)
                    put("timestampMs", ev.timestampMs)
                    put("elapsedTripSec", ev.elapsedTripSec.toDouble())
                    put("peakValue", ev.peakValue.toDouble())
                    put("unit", ev.unit)
                    put("durationMs", ev.durationMs)
                    put("speedStartMph", ev.speedStartMph.toDouble())
                    put("speedEndMph", ev.speedEndMph.toDouble())
                    put("lat", ev.lat)
                    put("lon", ev.lon)
                    put("details", ev.details)
                    put("waveformSnippet", JSONArray().apply {
                        ev.waveformSnippet.forEach { s ->
                            put(JSONObject().apply {
                                put("ts", s.timestampMs)
                                put("lg", s.longG.toDouble())
                                put("lt", s.latG.toDouble())
                                put("vt", s.vertG.toDouble())
                                put("rlg", s.rawLongG.toDouble())
                                put("rlt", s.rawLatG.toDouble())
                                put("rvt", s.rawVertG.toDouble())
                                put("px", s.rawPhoneX.toDouble())
                                put("py", s.rawPhoneY.toDouble())
                                put("pz", s.rawPhoneZ.toDouble())
                                put("rt", s.rawTotalG.toDouble())
                                put("gx", s.gyroX.toDouble())
                                put("gy", s.gyroY.toDouble())
                                put("gz", s.gyroZ.toDouble())
                                put("sp", s.speedMph.toDouble())
                            })
                        }
                    })
                })
            }
        })

        obj.put("impacts", JSONArray().apply {
            trip.impacts.forEach { im ->
                put(JSONObject().apply {
                    put("timestampMs", im.timestampMs)
                    put("peakG", im.peakG.toDouble())
                    put("preSpeedMph", im.preSpeedMph.toDouble())
                    put("postSpeedMph", im.postSpeedMph.toDouble())
                    put("deltaVMph", im.deltaVMph.toDouble())
                    put("peakGyroRadS", im.peakGyroRadS.toDouble())
                    put("verdict", im.verdict.name)
                    put("reason", im.reason)
                })
            }
        })

        obj.put("track", JSONArray().apply {
            trip.track.forEach { p ->
                put(JSONObject().apply {
                    put("es", p.elapsedSec.toDouble())
                    put("ts", p.timestampMs)
                    put("lat", p.lat)
                    put("lon", p.lon)
                    put("alt", p.altitudeM)
                    put("sp", p.speedMph.toDouble())
                    put("br", p.bearingDeg.toDouble())
                    put("ac", p.accuracyM.toDouble())
                    put("sat", p.satellitesUsed)
                    put("lg", p.longG.toDouble())
                    put("lt", p.latG.toDouble())
                    put("vt", p.vertG.toDouble())
                    put("rt", p.rawTotalG.toDouble())
                    put("yw", p.yawRadS.toDouble())
                    put("sc", p.screenOn)
                    put("hh", p.handheld)
                })
            }
        })

        obj.put("fsmLog", JSONArray().apply {
            trip.fsmLog.forEach { tr ->
                put(JSONObject().apply {
                    put("ts", tr.timestampMs)
                    put("from", tr.from.name)
                    put("to", tr.to.name)
                    put("reason", tr.reason)
                })
            }
        })
        return obj
    }

    private fun parseTrip(obj: JSONObject): TripRecord {
        val sc = obj.getJSONObject("score")
        val score = TripScoreBreakdown(
            overall = sc.optInt("overall", 100),
            braking = sc.optInt("braking", 100),
            acceleration = sc.optInt("acceleration", 100),
            cornering = sc.optInt("cornering", 100),
            speeding = sc.optInt("speeding", 100),
            distraction = sc.optInt("distraction", 100),
            smoothness = sc.optInt("smoothness", 100)
        )

        val eventsArr = obj.optJSONArray("events") ?: JSONArray()
        val events = ArrayList<DrivingEvent>(eventsArr.length())
        for (i in 0 until eventsArr.length()) {
            val e = eventsArr.getJSONObject(i)
            val snipArr = e.optJSONArray("waveformSnippet") ?: JSONArray()
            val snippet = ArrayList<ImuSample>(snipArr.length())
            for (j in 0 until snipArr.length()) {
                val s = snipArr.getJSONObject(j)
                snippet += ImuSample(
                    timestampMs = s.getLong("ts"),
                    longG = s.getDouble("lg").toFloat(),
                    latG = s.getDouble("lt").toFloat(),
                    vertG = s.getDouble("vt").toFloat(),
                    rawLongG = s.optDouble("rlg", 0.0).toFloat(),
                    rawLatG = s.optDouble("rlt", 0.0).toFloat(),
                    rawVertG = s.optDouble("rvt", 0.0).toFloat(),
                    rawPhoneX = s.optDouble("px", 0.0).toFloat(),
                    rawPhoneY = s.optDouble("py", 0.0).toFloat(),
                    rawPhoneZ = s.optDouble("pz", 0.0).toFloat(),
                    rawTotalG = s.optDouble("rt", 1.0).toFloat(),
                    gyroX = s.optDouble("gx", 0.0).toFloat(),
                    gyroY = s.optDouble("gy", 0.0).toFloat(),
                    gyroZ = s.optDouble("gz", 0.0).toFloat(),
                    speedMph = s.optDouble("sp", 0.0).toFloat()
                )
            }
            events += DrivingEvent(
                id = e.getString("id"),
                type = EventType.valueOf(e.getString("type")),
                timestampMs = e.getLong("timestampMs"),
                elapsedTripSec = e.optDouble("elapsedTripSec", 0.0).toFloat(),
                peakValue = e.getDouble("peakValue").toFloat(),
                unit = e.getString("unit"),
                durationMs = e.getLong("durationMs"),
                speedStartMph = e.optDouble("speedStartMph", 0.0).toFloat(),
                speedEndMph = e.optDouble("speedEndMph", 0.0).toFloat(),
                lat = e.optDouble("lat", 0.0),
                lon = e.optDouble("lon", 0.0),
                details = e.optString("details", ""),
                waveformSnippet = snippet
            )
        }

        val impArr = obj.optJSONArray("impacts") ?: JSONArray()
        val impacts = ArrayList<ImpactAnalysis>(impArr.length())
        for (i in 0 until impArr.length()) {
            val im = impArr.getJSONObject(i)
            impacts += ImpactAnalysis(
                timestampMs = im.getLong("timestampMs"),
                peakG = im.getDouble("peakG").toFloat(),
                preSpeedMph = im.getDouble("preSpeedMph").toFloat(),
                postSpeedMph = im.getDouble("postSpeedMph").toFloat(),
                deltaVMph = im.getDouble("deltaVMph").toFloat(),
                peakGyroRadS = im.getDouble("peakGyroRadS").toFloat(),
                verdict = ImpactVerdict.valueOf(im.getString("verdict")),
                reason = im.optString("reason", "")
            )
        }

        val trackArr = obj.optJSONArray("track") ?: JSONArray()
        val track = ArrayList<TrackPoint>(trackArr.length())
        for (i in 0 until trackArr.length()) {
            val p = trackArr.getJSONObject(i)
            track += TrackPoint(
                elapsedSec = p.getDouble("es").toFloat(),
                timestampMs = p.getLong("ts"),
                lat = p.getDouble("lat"),
                lon = p.getDouble("lon"),
                altitudeM = p.optDouble("alt", 0.0),
                speedMph = p.getDouble("sp").toFloat(),
                bearingDeg = p.optDouble("br", 0.0).toFloat(),
                accuracyM = p.optDouble("ac", 0.0).toFloat(),
                satellitesUsed = p.optInt("sat", 0),
                longG = p.optDouble("lg", 0.0).toFloat(),
                latG = p.optDouble("lt", 0.0).toFloat(),
                vertG = p.optDouble("vt", 0.0).toFloat(),
                rawTotalG = p.optDouble("rt", 1.0).toFloat(),
                yawRadS = p.optDouble("yw", 0.0).toFloat(),
                screenOn = p.optBoolean("sc", false),
                handheld = p.optBoolean("hh", false)
            )
        }

        val fsmArr = obj.optJSONArray("fsmLog") ?: JSONArray()
        val fsmLog = ArrayList<FsmTransition>(fsmArr.length())
        for (i in 0 until fsmArr.length()) {
            val tr = fsmArr.getJSONObject(i)
            fsmLog += FsmTransition(
                timestampMs = tr.getLong("ts"),
                from = TripState.valueOf(tr.getString("from")),
                to = TripState.valueOf(tr.getString("to")),
                reason = tr.optString("reason", "")
            )
        }

        val seatRole = runCatching { SeatRole.valueOf(obj.optString("seatRole", "UNASSIGNED")) }.getOrDefault(SeatRole.UNASSIGNED)
        val transportMode = runCatching { TransportMode.valueOf(obj.optString("transportMode", "CAR")) }.getOrDefault(TransportMode.CAR)
        val syncStatus = runCatching { SyncStatus.valueOf(obj.optString("syncStatus", "LOCAL_ONLY")) }.getOrDefault(SyncStatus.LOCAL_ONLY)

        return TripRecord(
            id = obj.getString("id"),
            tripLabel = obj.optString("tripLabel", ""),
            seatRole = seatRole,
            transportMode = transportMode,
            syncStatus = syncStatus,
            startTimeMs = obj.getLong("startTimeMs"),
            endTimeMs = obj.getLong("endTimeMs"),
            durationSec = obj.getInt("durationSec"),
            distanceMiles = obj.getDouble("distanceMiles").toFloat(),
            maxSpeedMph = obj.getDouble("maxSpeedMph").toFloat(),
            avgSpeedMph = obj.getDouble("avgSpeedMph").toFloat(),
            avgVertRmsG = obj.optDouble("avgVertRmsG", 0.0).toFloat(),
            score = score,
            events = events,
            impacts = impacts,
            track = track,
            fsmLog = fsmLog,
            batteryDeltaPct = obj.optInt("batteryDeltaPct", 0),
            avgAccuracyM = obj.optDouble("avgAccuracyM", 0.0).toFloat(),
            manualWakeLockMs = obj.optLong("manualWakeLockMs", 0L),
            rawPayloadBytes = obj.optInt("rawPayloadBytes", 0),
            gzipPayloadBytes = obj.optInt("gzipPayloadBytes", 0),
            idempotentChunkCount = obj.optInt("idempotentChunkCount", 0),
            isSimulated = obj.optBoolean("isSimulated", false)
        )
    }
}
