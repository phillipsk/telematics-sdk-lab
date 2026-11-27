package com.telematicslab.demo.sdk.engine

import com.telematicslab.demo.sdk.api.ImuSample
import kotlin.math.min

/**
 * Fixed-capacity circular buffer backed by pre-allocated primitive arrays.
 * Zero heap allocations on [push] so high-rate 50 Hz IMU ingestion does not churn GC.
 */
class ImuRingBuffer(val capacity: Int = 1500) {

    private val timestamps = LongArray(capacity)
    private val longG = FloatArray(capacity)
    private val latG = FloatArray(capacity)
    private val vertG = FloatArray(capacity)
    private val rawLongG = FloatArray(capacity)
    private val rawLatG = FloatArray(capacity)
    private val rawVertG = FloatArray(capacity)
    private val rawPhoneX = FloatArray(capacity)
    private val rawPhoneY = FloatArray(capacity)
    private val rawPhoneZ = FloatArray(capacity)
    private val rawTotalG = FloatArray(capacity)
    private val gyroX = FloatArray(capacity)
    private val gyroY = FloatArray(capacity)
    private val gyroZ = FloatArray(capacity)
    private val speedMph = FloatArray(capacity)

    private var head = 0
    private var size = 0

    @Synchronized
    fun push(
        timestampMs: Long,
        longG: Float,
        latG: Float,
        vertG: Float,
        rawLongG: Float,
        rawLatG: Float,
        rawVertG: Float,
        rawPhoneX: Float,
        rawPhoneY: Float,
        rawPhoneZ: Float,
        rawTotalG: Float,
        gyroX: Float,
        gyroY: Float,
        gyroZ: Float,
        speedMph: Float
    ) {
        timestamps[head] = timestampMs
        this.longG[head] = longG
        this.latG[head] = latG
        this.vertG[head] = vertG
        this.rawLongG[head] = rawLongG
        this.rawLatG[head] = rawLatG
        this.rawVertG[head] = rawVertG
        this.rawPhoneX[head] = rawPhoneX
        this.rawPhoneY[head] = rawPhoneY
        this.rawPhoneZ[head] = rawPhoneZ
        this.rawTotalG[head] = rawTotalG
        this.gyroX[head] = gyroX
        this.gyroY[head] = gyroY
        this.gyroZ[head] = gyroZ
        this.speedMph[head] = speedMph

        head = (head + 1) % capacity
        if (size < capacity) size++
    }

    @Synchronized
    fun count(): Int = size

    @Synchronized
    fun clear() {
        head = 0
        size = 0
    }

    @Synchronized
    fun snapshotLast(maxSamples: Int): List<ImuSample> {
        if (size == 0 || maxSamples <= 0) return emptyList()
        val take = min(size, maxSamples)
        val out = ArrayList<ImuSample>(take)
        val startIdx = (head - take + capacity) % capacity
        for (i in 0 until take) {
            val idx = (startIdx + i) % capacity
            out.add(sampleAt(idx))
        }
        return out
    }

    @Synchronized
    fun snapshotWindow(fromMs: Long, toMs: Long): List<ImuSample> {
        if (size == 0 || toMs < fromMs) return emptyList()
        val out = ArrayList<ImuSample>()
        val oldestIdx = (head - size + capacity) % capacity
        for (i in 0 until size) {
            val idx = (oldestIdx + i) % capacity
            val ts = timestamps[idx]
            if (ts in fromMs..toMs) {
                out.add(sampleAt(idx))
            }
        }
        return out
    }

    private fun sampleAt(idx: Int): ImuSample = ImuSample(
        timestampMs = timestamps[idx],
        longG = longG[idx],
        latG = latG[idx],
        vertG = vertG[idx],
        rawLongG = rawLongG[idx],
        rawLatG = rawLatG[idx],
        rawVertG = rawVertG[idx],
        rawPhoneX = rawPhoneX[idx],
        rawPhoneY = rawPhoneY[idx],
        rawPhoneZ = rawPhoneZ[idx],
        rawTotalG = rawTotalG[idx],
        gyroX = gyroX[idx],
        gyroY = gyroY[idx],
        gyroZ = gyroZ[idx],
        speedMph = speedMph[idx]
    )
}
