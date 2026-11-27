package com.telematicslab.demo.sdk.storage

import android.content.Context
import com.telematicslab.demo.sdk.api.SeatRole
import com.telematicslab.demo.sdk.api.TransportMode
import com.telematicslab.demo.sdk.api.TripPreset
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * Loads one-click planned trip presets from `filesDir/trip_presets.json` on disk,
 * falling back to clean generic presets if no device-local schedule is present.
 */
class TripPresetStore(private val context: Context) {

    private val presetsFile: File
        get() = File(context.filesDir, "trip_presets.json")

    suspend fun loadPresets(): List<TripPreset> = withContext(Dispatchers.IO) {
        val file = presetsFile
        if (!file.exists()) return@withContext defaultGenericPresets()
        val parsed = runCatching {
            val arr = JSONArray(file.readText())
            buildList {
                for (i in 0 until arr.length()) {
                    val o = arr.getJSONObject(i)
                    val mode = runCatching { TransportMode.valueOf(o.optString("mode", "CAR")) }
                        .getOrDefault(TransportMode.CAR)
                    val role = runCatching { SeatRole.valueOf(o.optString("seatRole", "DRIVER")) }
                        .getOrDefault(SeatRole.DRIVER)
                    val plannedDate = o.optString("plannedDate", "").trim().ifEmpty { null }
                    add(
                        TripPreset(
                            id = o.getString("id"),
                            group = o.optString("group", "Planned"),
                            label = o.getString("label"),
                            mode = mode,
                            seatRole = role,
                            plannedDate = plannedDate
                        )
                    )
                }
            }
        }.getOrNull()
        if (parsed.isNullOrEmpty()) defaultGenericPresets() else parsed
    }

    private fun defaultGenericPresets(): List<TripPreset> = listOf(
        TripPreset("p1", "Drive", "Outbound Drive", TransportMode.CAR, SeatRole.FRONT_PASSENGER),
        TripPreset("p2", "Drive", "Return Drive", TransportMode.CAR, SeatRole.FRONT_PASSENGER),
        TripPreset("p3", "Multi-Modal", "Neighborhood Walk", TransportMode.WALK, SeatRole.DRIVER),
        TripPreset("p4", "Multi-Modal", "Bicycle Commute", TransportMode.BICYCLE, SeatRole.DRIVER),
        TripPreset("p5", "Testing", "Test Drive", TransportMode.CAR, SeatRole.DRIVER)
    )
}
