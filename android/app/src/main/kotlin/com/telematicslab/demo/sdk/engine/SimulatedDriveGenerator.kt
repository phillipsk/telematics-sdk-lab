package com.telematicslab.demo.sdk.engine

import com.telematicslab.demo.sdk.api.GnssHealth
import com.telematicslab.demo.sdk.api.SdkConfig
import kotlin.math.cos
import kotlin.math.sin
import kotlin.random.Random

/**
 * Generates an accelerated ~34-second realistic car trip and feeds rotated phone-frame
 * accelerometer/gyro/GPS ticks directly through the production [CoordinateFrameMapper],
 * [ImuRingBuffer], and [DrivingEventDetector].
 */
object SimulatedDriveGenerator {

    data class SimTick(
        val elapsedSec: Float,
        val phoneLinear: FloatArray,
        val phoneRaw: FloatArray,
        val rotMatrix9: FloatArray,
        val orientationRad: FloatArray,
        val gyro: FloatArray,
        val gnss: GnssHealth,
        val screenOn: Boolean,
        val handheld: Boolean
    )

    fun buildScriptedTicks(startTimeMs: Long, hz: Int = 25): List<SimTick> {
        val totalSec = 34f
        val steps = (totalSec * hz).toInt()
        val dt = 1f / hz
        val rng = Random(42)

        var lat = 37.7680
        var lon = -122.4210
        var speedMph = 0f
        var bearingDeg = 82f

        val pitchRad = Math.toRadians(55.0).toFloat()
        val rollRad = Math.toRadians(10.0).toFloat()

        val ticks = ArrayList<SimTick>(steps)

        for (i in 0 until steps) {
            val t = i * dt
            var targetLongG = 0f
            var targetLatG = 0f
            var extraSpikeG = 0f
            var yawRate = 0f
            var screenOn = false
            var handheld = false

            when {
                t < 3.0f -> targetLongG = 0.22f
                t in 3.0f..5.0f -> targetLongG = 0.38f
                t in 5.0f..10.5f -> targetLongG = 0.04f
                t in 10.5f..13.0f -> {
                    targetLongG = -0.05f
                    targetLatG = -0.44f
                    yawRate = -0.35f
                    bearingDeg = (bearingDeg - 36f * dt + 360f) % 360f
                }
                t in 13.0f..17.5f -> {
                    targetLongG = 0.03f
                    screenOn = true
                    handheld = true
                }
                t in 17.5f..21.0f -> targetLongG = 0.01f
                t in 21.0f..22.8f -> targetLongG = -0.48f
                t in 22.8f..26.5f -> targetLongG = -0.05f
                t in 26.5f..26.75f -> extraSpikeG = 4.4f
                t in 26.75f..31.5f -> targetLongG = if (speedMph > 14f) -0.14f else 0f
                else -> targetLongG = if (speedMph > 0.5f) -0.18f else 0f
            }

            val accelMs2 = targetLongG * CoordinateFrameMapper.GRAVITY_MS2
            val currentMps = speedMph * 0.44704f
            val nextMps = (currentMps + accelMs2 * dt).coerceIn(0f, 32f)
            speedMph = nextMps / 0.44704f

            val distM = nextMps * dt
            val bearingRad = Math.toRadians(bearingDeg.toDouble())
            val dNorth = distM * cos(bearingRad)
            val dEast = distM * sin(bearingRad)
            lat += dNorth / 111_111.0
            lon += dEast / (111_111.0 * cos(Math.toRadians(lat)))

            val longMs2 = (targetLongG + (rng.nextFloat() - 0.5f) * 0.04f) * CoordinateFrameMapper.GRAVITY_MS2
            val latMs2 = (targetLatG + (rng.nextFloat() - 0.5f) * 0.04f) * CoordinateFrameMapper.GRAVITY_MS2
            val vertMs2 = ((rng.nextFloat() - 0.5f) * 0.06f) * CoordinateFrameMapper.GRAVITY_MS2

            val aEast = longMs2 * sin(bearingRad).toFloat() + latMs2 * cos(bearingRad).toFloat()
            val aNorth = longMs2 * cos(bearingRad).toFloat() - latMs2 * sin(bearingRad).toFloat()
            val aUp = vertMs2

            val rotMatrix = buildRotationMatrix(bearingRad.toFloat(), pitchRad, rollRad)
            val pLinX = rotMatrix[0] * aEast + rotMatrix[3] * aNorth + rotMatrix[6] * aUp + (if (extraSpikeG > 0f) extraSpikeG * 5f else 0f)
            val pLinY = rotMatrix[1] * aEast + rotMatrix[4] * aNorth + rotMatrix[7] * aUp + (if (extraSpikeG > 0f) extraSpikeG * 6f else 0f)
            val pLinZ = rotMatrix[2] * aEast + rotMatrix[5] * aNorth + rotMatrix[8] * aUp + (if (extraSpikeG > 0f) extraSpikeG * 5f else 0f)

            val gPhoneX = rotMatrix[6] * CoordinateFrameMapper.GRAVITY_MS2
            val gPhoneY = rotMatrix[7] * CoordinateFrameMapper.GRAVITY_MS2
            val gPhoneZ = rotMatrix[8] * CoordinateFrameMapper.GRAVITY_MS2

            val gyroNoise = if (handheld) 1.2f else 0.03f
            val gx = (rng.nextFloat() - 0.5f) * gyroNoise + (if (extraSpikeG > 0f) 7.5f else 0f)
            val gy = (rng.nextFloat() - 0.5f) * gyroNoise + (if (extraSpikeG > 0f) -6.2f else 0f)
            val gz = yawRate + (rng.nextFloat() - 0.5f) * gyroNoise

            ticks += SimTick(
                elapsedSec = t,
                phoneLinear = floatArrayOf(pLinX, pLinY, pLinZ),
                phoneRaw = floatArrayOf(pLinX + gPhoneX, pLinY + gPhoneY, pLinZ + gPhoneZ),
                rotMatrix9 = rotMatrix,
                orientationRad = floatArrayOf(bearingRad.toFloat(), pitchRad, rollRad),
                gyro = floatArrayOf(gx, gy, gz),
                gnss = GnssHealth(
                    satellitesVisible = 18,
                    satellitesUsed = 14,
                    avgCn0DbHz = 36.4f + (rng.nextFloat() - 0.5f) * 1.5f,
                    accuracyMeters = 3.8f + rng.nextFloat() * 1.4f,
                    provider = "sim-gnss",
                    hasFix = true,
                    lat = lat,
                    lon = lon,
                    speedMph = speedMph,
                    bearingDeg = bearingDeg,
                    altitudeM = 12.0,
                    lastFixTimestampMs = startTimeMs + (t * 1000L).toLong()
                ),
                screenOn = screenOn,
                handheld = handheld
            )
        }
        return ticks
    }

    fun buildRotationMatrix(yawRad: Float, pitchRad: Float, rollRad: Float): FloatArray {
        val cy = cos(yawRad); val sy = sin(yawRad)
        val cp = cos(pitchRad); val sp = sin(pitchRad)
        val cr = cos(rollRad); val sr = sin(rollRad)

        val r00 = cy * cr + sy * sp * sr
        val r01 = sy * cp
        val r02 = cy * sr - sy * sp * cr

        val r10 = -sy * cr + cy * sp * sr
        val r11 = cy * cp
        val r12 = -sy * sr - cy * sp * cr

        val r20 = -cp * sr
        val r21 = sp
        val r22 = cp * cr

        return floatArrayOf(r00, r01, r02, r10, r11, r12, r20, r21, r22)
    }

    fun defaultConfigForDemo(base: SdkConfig): SdkConfig = base.copy(lowPassAlpha = 0.28f)
}
