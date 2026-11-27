package com.telematicslab.demo.sdk.sensor

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.location.GnssStatus
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Bundle
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import androidx.core.content.ContextCompat
import com.google.android.gms.location.FusedLocationProviderClient
import com.google.android.gms.location.Granularity
import com.google.android.gms.location.LocationCallback
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationResult
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import com.telematicslab.demo.sdk.api.GnssHealth
import com.telematicslab.demo.sdk.engine.CoordinateFrameMapper
import com.telematicslab.demo.sdk.engine.LocationSpeedArbitrator

/**
 * Owns SensorManager + dual HIGH_ACCURACY GNSS (GPS_PROVIDER + Play Services FusedLocationProviderClient)
 * on a dedicated background [HandlerThread] so 50 Hz IMU ingestion and satellite speed never stall.
 */
class SensorPipeline(
    private val context: Context,
    private val onImuSample: (
        timestampMs: Long,
        phoneLinear: FloatArray,
        phoneRaw: FloatArray,
        rotMatrix9: FloatArray,
        orientationRad: FloatArray,
        gyro: FloatArray,
        pressureHpa: Float?,
        imuHz: Float,
        avgBatchIntervalMs: Float
    ) -> Unit,
    private val onGnssUpdate: (GnssHealth) -> Unit
) : SensorEventListener {

    private val sensorManager = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
    private val locationManager = context.getSystemService(Context.LOCATION_SERVICE) as LocationManager
    private val fusedClient: FusedLocationProviderClient = LocationServices.getFusedLocationProviderClient(context.applicationContext)

    private var thread: HandlerThread? = null
    private var handler: Handler? = null

    private var sensorsStarted = false
    private var locationStarted = false
    private var currentBatchLatencyMs = 0

    private val rawAccel = floatArrayOf(0f, 0f, CoordinateFrameMapper.GRAVITY_MS2)
    private val gravity = floatArrayOf(0f, 0f, CoordinateFrameMapper.GRAVITY_MS2)
    private val linearAccel = floatArrayOf(0f, 0f, 0f)
    private val gyro = floatArrayOf(0f, 0f, 0f)
    private val rotMatrix = floatArrayOf(
        1f, 0f, 0f,
        0f, 1f, 0f,
        0f, 0f, 1f
    )
    private val orientationRad = floatArrayOf(0f, 0f, 0f)

    private var hasLinearSensor = false
    private var hasGravitySensor = false
    private var hasRotationVector = false
    private var pressureHpa: Float? = null

    private var sampleWindowStartMs = 0L
    private var sampleCountInWindow = 0
    private var measuredImuHz = 0f
    private var lastDeliveryMs = 0L
    private var avgBatchIntervalMs = 20f

    private var gnssHealth = GnssHealth()
    private val fallbackMapper = CoordinateFrameMapper()
    private val speedArbitrator = LocationSpeedArbitrator()

    private val gpsListener = object : LocationListener {
        override fun onLocationChanged(location: Location) {
            emitLocation(location, "gps")
        }

        @Deprecated("Deprecated in Android API 29")
        override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) = Unit
        override fun onProviderEnabled(provider: String) = Unit
        override fun onProviderDisabled(provider: String) = Unit
    }

    private val fusedCallback = object : LocationCallback() {
        override fun onLocationResult(result: LocationResult) {
            result.lastLocation?.let { loc ->
                emitLocation(loc, loc.provider ?: "fused")
            }
        }
    }

    private val gnssCallback = object : GnssStatus.Callback() {
        override fun onSatelliteStatusChanged(status: GnssStatus) {
            val total = status.satelliteCount
            var used = 0
            var cn0Sum = 0f
            var cn0Count = 0
            for (i in 0 until total) {
                if (status.usedInFix(i)) used++
                val cn0 = status.getCn0DbHz(i)
                if (cn0 > 0f) {
                    cn0Sum += cn0
                    cn0Count++
                }
            }
            val avgCn0 = if (cn0Count > 0) cn0Sum / cn0Count else 0f
            gnssHealth = gnssHealth.copy(
                satellitesVisible = total,
                satellitesUsed = used,
                avgCn0DbHz = avgCn0
            )
            onGnssUpdate(gnssHealth)
        }
    }

    @Synchronized
    fun startSensors(fifoBatchLatencyMs: Int) {
        currentBatchLatencyMs = fifoBatchLatencyMs
        ensureThread()
        if (sensorsStarted) {
            sensorManager.unregisterListener(this)
        }
        val h = handler ?: return
        val maxReportUs = fifoBatchLatencyMs.coerceAtLeast(0) * 1000
        val samplingUs = 20_000 // 50 Hz target

        val accelSensor = sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
        val linSensor = sensorManager.getDefaultSensor(Sensor.TYPE_LINEAR_ACCELERATION)
        val gravSensor = sensorManager.getDefaultSensor(Sensor.TYPE_GRAVITY)
        val gyroSensor = sensorManager.getDefaultSensor(Sensor.TYPE_GYROSCOPE)
        val rotSensor = sensorManager.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR)
        val pressSensor = sensorManager.getDefaultSensor(Sensor.TYPE_PRESSURE)

        hasLinearSensor = linSensor != null
        hasGravitySensor = gravSensor != null
        hasRotationVector = rotSensor != null

        accelSensor?.let { sensorManager.registerListener(this, it, samplingUs, maxReportUs, h) }
        linSensor?.let { sensorManager.registerListener(this, it, samplingUs, maxReportUs, h) }
        gravSensor?.let { sensorManager.registerListener(this, it, samplingUs, maxReportUs, h) }
        gyroSensor?.let { sensorManager.registerListener(this, it, samplingUs, maxReportUs, h) }
        rotSensor?.let { sensorManager.registerListener(this, it, samplingUs, maxReportUs, h) }
        pressSensor?.let { sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_NORMAL, h) }

        sensorsStarted = true
    }

    @Synchronized
    fun updateBatchLatency(fifoBatchLatencyMs: Int) {
        if (fifoBatchLatencyMs == currentBatchLatencyMs && sensorsStarted) return
        startSensors(fifoBatchLatencyMs)
    }

    @SuppressLint("MissingPermission")
    @Synchronized
    fun startLocation() {
        if (!hasLocationPermission()) return
        ensureThread()
        val looper: Looper = thread?.looper ?: return
        if (locationStarted) return

        try {
            speedArbitrator.reset()
            if (locationManager.allProviders.contains(LocationManager.GPS_PROVIDER) &&
                locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER)
            ) {
                locationManager.requestLocationUpdates(
                    LocationManager.GPS_PROVIDER,
                    1_000L,
                    0f,
                    gpsListener,
                    looper
                )
            }

            val fusedReq = LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, 1_000L)
                .setMinUpdateIntervalMillis(500L)
                .setGranularity(Granularity.GRANULARITY_FINE)
                .setWaitForAccurateLocation(false)
                .build()
            fusedClient.requestLocationUpdates(fusedReq, fusedCallback, looper)

            val lastGps = locationManager.getLastKnownLocation(LocationManager.GPS_PROVIDER)
            if (lastGps != null) {
                emitLocation(lastGps, "gps")
            }

            @Suppress("DEPRECATION")
            locationManager.registerGnssStatusCallback(gnssCallback, handler)
            locationStarted = true
        } catch (_: SecurityException) {
            locationStarted = false
        } catch (_: Exception) {
            locationStarted = false
        }
    }

    @Synchronized
    fun stopLocation() {
        if (!locationStarted) return
        try {
            locationManager.removeUpdates(gpsListener)
            fusedClient.removeLocationUpdates(fusedCallback)
            locationManager.unregisterGnssStatusCallback(gnssCallback)
        } catch (_: Exception) {
        }
        locationStarted = false
    }

    @Synchronized
    fun stopAll() {
        stopLocation()
        if (sensorsStarted) {
            try {
                sensorManager.unregisterListener(this)
            } catch (_: Exception) {
            }
            sensorsStarted = false
        }
        thread?.quitSafely()
        thread = null
        handler = null
    }

    override fun onSensorChanged(event: SensorEvent) {
        val nowMs = System.currentTimeMillis()
        when (event.sensor.type) {
            Sensor.TYPE_ACCELEROMETER -> {
                rawAccel[0] = event.values[0]
                rawAccel[1] = event.values[1]
                rawAccel[2] = event.values[2]
                if (!hasGravitySensor) {
                    val alpha = 0.8f
                    gravity[0] = alpha * gravity[0] + (1f - alpha) * rawAccel[0]
                    gravity[1] = alpha * gravity[1] + (1f - alpha) * rawAccel[1]
                    gravity[2] = alpha * gravity[2] + (1f - alpha) * rawAccel[2]
                }
                if (!hasLinearSensor) {
                    linearAccel[0] = rawAccel[0] - gravity[0]
                    linearAccel[1] = rawAccel[1] - gravity[1]
                    linearAccel[2] = rawAccel[2] - gravity[2]
                }
                if (!hasRotationVector) {
                    val (rm, ori) = fallbackMapper.rotationMatrixFromGravity(gravity)
                    rm.copyInto(rotMatrix)
                    ori.copyInto(orientationRad)
                }

                if (sampleWindowStartMs == 0L) sampleWindowStartMs = nowMs
                sampleCountInWindow++
                val windowElapsed = nowMs - sampleWindowStartMs
                if (windowElapsed >= 1_000L) {
                    measuredImuHz = (sampleCountInWindow * 1000f) / windowElapsed
                    sampleWindowStartMs = nowMs
                    sampleCountInWindow = 0
                }
                if (lastDeliveryMs > 0L) {
                    val dtMs = (nowMs - lastDeliveryMs).toFloat()
                    if (dtMs > 35f || currentBatchLatencyMs == 0) {
                        avgBatchIntervalMs = 0.25f * dtMs + 0.75f * avgBatchIntervalMs
                    }
                }
                lastDeliveryMs = nowMs

                onImuSample(
                    nowMs,
                    linearAccel.copyOf(),
                    rawAccel.copyOf(),
                    rotMatrix.copyOf(),
                    orientationRad.copyOf(),
                    gyro.copyOf(),
                    pressureHpa,
                    measuredImuHz,
                    avgBatchIntervalMs
                )
            }

            Sensor.TYPE_LINEAR_ACCELERATION -> {
                linearAccel[0] = event.values[0]
                linearAccel[1] = event.values[1]
                linearAccel[2] = event.values[2]
            }

            Sensor.TYPE_GRAVITY -> {
                gravity[0] = event.values[0]
                gravity[1] = event.values[1]
                gravity[2] = event.values[2]
            }

            Sensor.TYPE_GYROSCOPE -> {
                gyro[0] = event.values[0]
                gyro[1] = event.values[1]
                gyro[2] = event.values[2]
            }

            Sensor.TYPE_ROTATION_VECTOR -> {
                SensorManager.getRotationMatrixFromVector(rotMatrix, event.values)
                SensorManager.getOrientation(rotMatrix, orientationRad)
            }

            Sensor.TYPE_PRESSURE -> {
                pressureHpa = event.values[0]
            }
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit

    private fun emitLocation(location: Location, sourceProvider: String) {
        val nowMs = System.currentTimeMillis()
        val acc = if (location.hasAccuracy()) location.accuracy else 99f
        val effectiveSpeedMph = speedArbitrator.evaluateFix(
            nowMs = nowMs,
            provider = sourceProvider,
            lat = location.latitude,
            lon = location.longitude,
            accuracyM = acc,
            hasSpeed = location.hasSpeed(),
            reportedSpeedMps = location.speed
        ) ?: return

        val bearing = if (location.hasBearing()) location.bearing else gnssHealth.bearingDeg
        gnssHealth = gnssHealth.copy(
            accuracyMeters = acc,
            provider = sourceProvider,
            hasFix = true,
            lat = location.latitude,
            lon = location.longitude,
            speedMph = effectiveSpeedMph,
            bearingDeg = bearing,
            altitudeM = if (location.hasAltitude()) location.altitude else gnssHealth.altitudeM,
            lastFixTimestampMs = nowMs
        )
        onGnssUpdate(gnssHealth)
    }

    private fun hasLocationPermission(): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED

    private fun ensureThread() {
        if (thread == null) {
            thread = HandlerThread("TelematicsSensorThread").apply { start() }
            handler = Handler(thread!!.looper)
        }
    }
}
