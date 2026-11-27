package com.telematicslab.demo

import android.app.Application
import com.telematicslab.demo.sdk.api.TelematicsSdk

/**
 * Intentionally minimal host [Application]. The telematics SDK initializes lazily via
 * Jetpack App Startup ([androidx.startup.InitializationProvider]) before onCreate returns.
 */
class TelematicsApp : Application() {
    override fun onCreate() {
        super.onCreate()
        TelematicsSdk.ensureInitialized(this)
    }
}
