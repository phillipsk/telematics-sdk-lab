package com.telematicslab.demo.sdk.engine

import com.telematicslab.demo.sdk.api.FilterEngineMode
import com.telematicslab.demo.sdk.api.FrameMappingResult
import kotlin.math.PI
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Transforms phone-frame accelerometer signals into World ENU (East-North-Up)
 * and finally into the Vehicle Coordinate Frame (Longitudinal, Lateral, Vertical G)
 * using the 3D orientation rotation matrix + GNSS heading, then filters via [SensorEngineBridge].
 */
class CoordinateFrameMapper(
    private val filterBridge: SensorEngineBridge = SensorEngineBridge()
) {

    companion object {
        const val GRAVITY_MS2 = 9.80665f
        private const val HEADING_LOCK_MIN_SPEED_MPS = 2.0f // ~4.5 mph
    }

    private var lastLockedHeadingDeg: Float? = null

    @Synchronized
    fun reset() {
        lastLockedHeadingDeg = null
        filterBridge.reset()
    }

    @Synchronized
    fun mapWithRotationMatrix(
        linearAccelPhone: FloatArray,
        rawAccelPhone: FloatArray,
        rotMatrix9: FloatArray,
        orientationRad: FloatArray,
        gpsBearingDeg: Float?,
        gpsSpeedMps: Float,
        alpha: Float,
        engineMode: FilterEngineMode = FilterEngineMode.STANDARD_LOW_PASS
    ): FrameMappingResult {
        val px = linearAccelPhone.getOrElse(0) { 0f }
        val py = linearAccelPhone.getOrElse(1) { 0f }
        val pz = linearAccelPhone.getOrElse(2) { 0f }

        val rx = rawAccelPhone.getOrElse(0) { 0f }
        val ry = rawAccelPhone.getOrElse(1) { 0f }
        val rz = rawAccelPhone.getOrElse(2) { 0f }
        val rawTotalG = sqrt(rx * rx + ry * ry + rz * rz) / GRAVITY_MS2

        // Rotate phone-frame linear acceleration to World ENU: a_world = R * a_phone
        val aEast = rotMatrix9[0] * px + rotMatrix9[1] * py + rotMatrix9[2] * pz
        val aNorth = rotMatrix9[3] * px + rotMatrix9[4] * py + rotMatrix9[5] * pz
        val aUp = rotMatrix9[6] * px + rotMatrix9[7] * py + rotMatrix9[8] * pz

        val azimuthDeg = normalizeDeg(Math.toDegrees(orientationRad.getOrElse(0) { 0f }.toDouble()).toFloat())
        val pitchDeg = Math.toDegrees(orientationRad.getOrElse(1) { 0f }.toDouble()).toFloat()
        val rollDeg = Math.toDegrees(orientationRad.getOrElse(2) { 0f }.toDouble()).toFloat()

        // Lock onto GPS bearing when moving; preserve last valid heading at traffic lights.
        val headingLocked: Boolean
        val headingDeg: Float
        if (gpsBearingDeg != null && gpsSpeedMps >= HEADING_LOCK_MIN_SPEED_MPS) {
            headingDeg = normalizeDeg(gpsBearingDeg)
            lastLockedHeadingDeg = headingDeg
            headingLocked = true
        } else if (lastLockedHeadingDeg != null) {
            headingDeg = lastLockedHeadingDeg!!
            headingLocked = true
        } else {
            headingDeg = azimuthDeg
            headingLocked = false
        }

        // Forward = (sin(theta), cos(theta)), Right = (cos(theta), -sin(theta))
        val thetaRad = headingDeg * (PI / 180.0)
        val sinT = sin(thetaRad).toFloat()
        val cosT = cos(thetaRad).toFloat()

        val rawLongG = (aEast * sinT + aNorth * cosT) / GRAVITY_MS2
        val rawLatG = (aEast * cosT - aNorth * sinT) / GRAVITY_MS2
        val rawVertG = aUp / GRAVITY_MS2

        val filtered = filterBridge.filter(
            mode = engineMode,
            rawLongG = rawLongG,
            rawLatG = rawLatG,
            rawVertG = rawVertG,
            alpha = alpha
        )

        return FrameMappingResult(
            phoneLinearX = px,
            phoneLinearY = py,
            phoneLinearZ = pz,
            phoneRawX = rx,
            phoneRawY = ry,
            phoneRawZ = rz,
            worldEastMs2 = aEast,
            worldNorthMs2 = aNorth,
            worldUpMs2 = aUp,
            rawLongG = rawLongG,
            rawLatG = rawLatG,
            rawVertG = rawVertG,
            filteredLongG = filtered.longG,
            filteredLatG = filtered.latG,
            filteredVertG = filtered.vertG,
            rawTotalG = rawTotalG,
            pitchDeg = pitchDeg,
            rollDeg = rollDeg,
            azimuthDeg = azimuthDeg,
            headingDeg = headingDeg,
            headingLocked = headingLocked
        )
    }

    fun rotationMatrixFromGravity(gravity: FloatArray): Pair<FloatArray, FloatArray> {
        val gx = gravity.getOrElse(0) { 0f }
        val gy = gravity.getOrElse(1) { 0f }
        val gz = gravity.getOrElse(2) { GRAVITY_MS2 }
        val norm = sqrt(gx * gx + gy * gy + gz * gz).coerceAtLeast(0.001f)
        val ux = gx / norm
        val uy = gy / norm
        val uz = gz / norm

        val refX = if (kotlin.math.abs(ux) < 0.9f) 1f else 0f
        val refY = if (kotlin.math.abs(ux) < 0.9f) 0f else 1f
        val refZ = 0f

        var ex = refY * uz - refZ * uy
        var ey = refZ * ux - refX * uz
        var ez = refX * uy - refY * ux
        val eNorm = sqrt(ex * ex + ey * ey + ez * ez).coerceAtLeast(0.001f)
        ex /= eNorm; ey /= eNorm; ez /= eNorm

        val nx = uy * ez - uz * ey
        val ny = uz * ex - ux * ez
        val nz = ux * ey - uy * ex

        val matrix = floatArrayOf(
            ex, ey, ez,
            nx, ny, nz,
            ux, uy, uz
        )
        val pitch = atan2(-uy.toDouble(), sqrt((ux * ux + uz * uz).toDouble())).toFloat()
        val roll = atan2(ux.toDouble(), uz.toDouble()).toFloat()
        return matrix to floatArrayOf(0f, pitch, roll)
    }

    private fun normalizeDeg(deg: Float): Float {
        val mod = deg % 360f
        return if (mod < 0f) mod + 360f else mod
    }
}
