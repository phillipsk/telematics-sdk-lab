package com.telematicslab.demo.sdk.startup

import android.app.ActivityManager
import android.app.Application
import android.app.ApplicationExitInfo
import android.content.ComponentCallbacks2
import android.content.Context
import android.content.res.Configuration
import android.os.Build
import androidx.startup.Initializer
import com.telematicslab.demo.sdk.api.TelematicsSdk
import com.telematicslab.demo.sdk.fgs.FgsCoordinator

/**
 * Tracks OS memory pressure callbacks ([onTrimMemory]) and queries API 30+
 * [ApplicationExitInfo] to capture historical process exit reasons without
 * guessing why an earlier background session ended.
 */
class MemoryDiagnosticsTracker(appContext: Context) : ComponentCallbacks2 {

    @Volatile var trimEventsCount: Int = 0
        private set
    @Volatile var lastTrimLevel: String = "NONE"
        private set
    @Volatile var lastExitReason: String = "UNKNOWN"
        private set

    init {
        (appContext.applicationContext as? Application)?.registerComponentCallbacks(this)
        lastExitReason = queryPreviousExitReason(appContext)
    }

    override fun onTrimMemory(level: Int) {
        trimEventsCount++
        lastTrimLevel = when (level) {
            ComponentCallbacks2.TRIM_MEMORY_UI_HIDDEN -> "UI_HIDDEN"
            ComponentCallbacks2.TRIM_MEMORY_RUNNING_MODERATE -> "RUNNING_MODERATE"
            ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW -> "RUNNING_LOW"
            ComponentCallbacks2.TRIM_MEMORY_RUNNING_CRITICAL -> "RUNNING_CRITICAL"
            ComponentCallbacks2.TRIM_MEMORY_BACKGROUND -> "BACKGROUND"
            ComponentCallbacks2.TRIM_MEMORY_MODERATE -> "MODERATE"
            ComponentCallbacks2.TRIM_MEMORY_COMPLETE -> "COMPLETE"
            else -> "LEVEL_$level"
        }
    }

    override fun onConfigurationChanged(newConfig: Configuration) = Unit

    @Deprecated("Deprecated in Android API 34")
    override fun onLowMemory() {
        trimEventsCount++
        lastTrimLevel = "ON_LOW_MEMORY"
    }

    private fun queryPreviousExitReason(context: Context): String {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return "API_<30"
        return runCatching {
            val am = context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
                ?: return "UNAVAILABLE"
            val reasons = am.getHistoricalProcessExitReasons(context.packageName, 0, 1)
            val latest = reasons.firstOrNull() ?: return "FIRST_RUN"
            when (latest.reason) {
                ApplicationExitInfo.REASON_EXIT_SELF -> "EXIT_SELF"
                ApplicationExitInfo.REASON_SIGNALED -> "SIGNALED"
                ApplicationExitInfo.REASON_LOW_MEMORY -> "LOW_MEMORY"
                ApplicationExitInfo.REASON_CRASH -> "CRASH"
                ApplicationExitInfo.REASON_CRASH_NATIVE -> "CRASH_NATIVE"
                ApplicationExitInfo.REASON_ANR -> "ANR"
                ApplicationExitInfo.REASON_INITIALIZATION_FAILURE -> "INIT_FAILURE"
                ApplicationExitInfo.REASON_PERMISSION_CHANGE -> "PERM_CHANGE"
                ApplicationExitInfo.REASON_EXCESSIVE_RESOURCE_USAGE -> "EXCESSIVE_RESOURCE"
                ApplicationExitInfo.REASON_USER_REQUESTED -> "USER_SWIPE_OR_STOP"
                ApplicationExitInfo.REASON_USER_STOPPED -> "USER_STOPPED"
                else -> "CODE_${latest.reason}"
            }
        }.getOrDefault("UNAVAILABLE")
    }
}

class MemoryDiagnosticsInitializer : Initializer<MemoryDiagnosticsTracker> {
    override fun create(context: Context): MemoryDiagnosticsTracker {
        return MemoryDiagnosticsTracker(context.applicationContext).also {
            StartupRegistry.memoryDiagnostics = it
            StartupRegistry.recordInitializer("MemoryDiagnosticsInitializer")
        }
    }

    override fun dependencies(): List<Class<out Initializer<*>>> = emptyList()
}

class FgsKickoffInitializer : Initializer<FgsCoordinator> {
    override fun create(context: Context): FgsCoordinator {
        return FgsCoordinator(context.applicationContext).also {
            StartupRegistry.fgsCoordinator = it
            StartupRegistry.recordInitializer("FgsKickoffInitializer")
        }
    }

    override fun dependencies(): List<Class<out Initializer<*>>> = emptyList()
}

class DriveDetectorKickoffInitializer : Initializer<TelematicsSdk> {
    override fun create(context: Context): TelematicsSdk {
        val t0 = System.nanoTime()
        val sdk = TelematicsSdk.initializeFromStartup(
            context = context.applicationContext,
            memoryTracker = StartupRegistry.memoryDiagnostics ?: MemoryDiagnosticsTracker(context.applicationContext),
            fgsCoordinator = StartupRegistry.fgsCoordinator ?: FgsKickoffInitializer().create(context.applicationContext)
        )
        StartupRegistry.recordInitializer("DriveDetectorKickoffInitializer")
        val elapsedMs = ((System.nanoTime() - t0) / 1_000_000L).coerceAtLeast(1L)
        StartupRegistry.startupInitMs = elapsedMs
        sdk.onStartupCompleted(elapsedMs, StartupRegistry.activeInitializers())
        return sdk
    }

    override fun dependencies(): List<Class<out Initializer<*>>> = listOf(
        MemoryDiagnosticsInitializer::class.java,
        FgsKickoffInitializer::class.java
    )
}

internal object StartupRegistry {
    @Volatile var memoryDiagnostics: MemoryDiagnosticsTracker? = null
    @Volatile var fgsCoordinator: FgsCoordinator? = null
    @Volatile var startupInitMs: Long = 0L

    private val initializers = ArrayList<String>()

    @Synchronized
    fun recordInitializer(name: String) {
        if (name !in initializers) initializers.add(name)
    }

    @Synchronized
    fun activeInitializers(): List<String> = initializers.toList()
}
