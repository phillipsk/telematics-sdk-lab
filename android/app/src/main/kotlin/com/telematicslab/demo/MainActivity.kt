package com.telematicslab.demo

import android.Manifest
import android.annotation.SuppressLint
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.MenuBook
import androidx.compose.material.icons.filled.DirectionsCar
import androidx.compose.material.icons.filled.Route
import androidx.compose.material.icons.filled.Sensors
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.telematicslab.demo.sdk.api.SeatRole
import com.telematicslab.demo.sdk.api.TelematicsSdk
import com.telematicslab.demo.sdk.api.TransportMode
import com.telematicslab.demo.sdk.motion.MotionPromptManager
import com.telematicslab.demo.ui.screens.DriveScreen
import com.telematicslab.demo.ui.screens.GuideScreen
import com.telematicslab.demo.ui.screens.SensorsLabScreen
import com.telematicslab.demo.ui.screens.TripsScreen
import com.telematicslab.demo.ui.theme.TelematicsLabTheme
import com.telematicslab.demo.ui.theme.TelemetryColors
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        TelematicsSdk.ensureInitialized(this)
        handlePromptIntent(intent)
        setContent {
            TelematicsLabTheme {
                TelematicsLabRoot()
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handlePromptIntent(intent)
    }

    private fun handlePromptIntent(intent: Intent?) {
        if (intent?.action != MotionPromptManager.ACTION_START_PROMPTED_TRIP) return
        val label = intent.getStringExtra(MotionPromptManager.EXTRA_TRIP_LABEL) ?: "Extra Drive"
        val mode = runCatching {
            TransportMode.valueOf(intent.getStringExtra(MotionPromptManager.EXTRA_TRANSPORT_MODE) ?: "CAR")
        }.getOrDefault(TransportMode.CAR)
        val role = runCatching {
            SeatRole.valueOf(intent.getStringExtra(MotionPromptManager.EXTRA_SEAT_ROLE) ?: "DRIVER")
        }.getOrDefault(SeatRole.DRIVER)
        TelematicsSdk.startTripWithMeta(label, mode, role)
        intent.action = null
    }

    override fun onStart() {
        super.onStart()
        TelematicsSdk.onHostForeground()
    }

    override fun onResume() {
        super.onResume()
        TelematicsSdk.refreshPermissions()
        TelematicsSdk.reloadPresets()
    }

    override fun onStop() {
        super.onStop()
        if (!isChangingConfigurations) {
            TelematicsSdk.onHostBackground()
        }
    }
}

@SuppressLint("BatteryLife")
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun TelematicsLabRoot() {
    val context = LocalContext.current
    val state by TelematicsSdk.state.collectAsStateWithLifecycle()
    val pagerState = rememberPagerState(initialPage = 0, pageCount = { 4 })
    val scope = rememberCoroutineScope()
    var isViewingTripDetail by rememberSaveable { mutableStateOf(false) }
    val snackbarHostState = remember { SnackbarHostState() }

    val permLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestMultiplePermissions()
    ) {
        TelematicsSdk.refreshPermissions()
    }

    val bgLocationLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) {
        TelematicsSdk.refreshPermissions()
    }

    val requestForegroundPermissions: () -> Unit = {
        val perms = buildList {
            add(Manifest.permission.ACCESS_FINE_LOCATION)
            add(Manifest.permission.ACCESS_COARSE_LOCATION)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                add(Manifest.permission.ACTIVITY_RECOGNITION)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                add(Manifest.permission.POST_NOTIFICATIONS)
            }
        }.toTypedArray()
        permLauncher.launch(perms)
    }

    val requestBackgroundLocation: () -> Unit = {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && state.permissions.fineLocationGranted) {
            bgLocationLauncher.launch(Manifest.permission.ACCESS_BACKGROUND_LOCATION)
        } else {
            val intent = Intent(
                Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                Uri.parse("package:${context.packageName}")
            )
            runCatching { context.startActivity(intent) }
        }
    }

    val requestBatteryUnrestricted: () -> Unit = {
        val directIntent = Intent(
            Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
            Uri.parse("package:${context.packageName}")
        )
        runCatching { context.startActivity(directIntent) }.onFailure {
            runCatching {
                context.startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
            }
        }
    }

    LaunchedEffect(Unit) {
        if (!state.permissions.fineLocationGranted ||
            !state.permissions.postNotificationsGranted ||
            !state.permissions.activityRecognitionGranted
        ) {
            requestForegroundPermissions()
        }
    }

    LaunchedEffect(state.statusMessage) {
        val msg = state.statusMessage
        if (msg != null) {
            snackbarHostState.showSnackbar(msg)
            TelematicsSdk.clearStatusMessage()
        }
    }

    val switchToTab: (Int) -> Unit = { index ->
        scope.launch {
            pagerState.animateScrollToPage(index)
        }
    }

    Scaffold(
        containerColor = TelemetryColors.BgDark,
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            TopAppBar(
                title = { Text("Telematics Lab", fontWeight = FontWeight.Bold) },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = TelemetryColors.BgDark,
                    titleContentColor = TelemetryColors.TextPrimary
                )
            )
        },
        bottomBar = {
            NavigationBar(containerColor = TelemetryColors.CardDark) {
                NavigationBarItem(
                    selected = pagerState.currentPage == 0,
                    onClick = { switchToTab(0) },
                    modifier = Modifier.testTag("nav_drive"),
                    icon = { Icon(Icons.Filled.DirectionsCar, contentDescription = "Drive") },
                    label = { Text("Drive", maxLines = 1) }
                )
                NavigationBarItem(
                    selected = pagerState.currentPage == 1,
                    onClick = { switchToTab(1) },
                    modifier = Modifier.testTag("nav_sensors"),
                    icon = { Icon(Icons.Filled.Sensors, contentDescription = "Sensors") },
                    label = { Text("Sensors", maxLines = 1) }
                )
                NavigationBarItem(
                    selected = pagerState.currentPage == 2,
                    onClick = { switchToTab(2) },
                    modifier = Modifier.testTag("nav_trips"),
                    icon = { Icon(Icons.Filled.Route, contentDescription = "Trips") },
                    label = { Text("Trips", maxLines = 1) }
                )
                NavigationBarItem(
                    selected = pagerState.currentPage == 3,
                    onClick = { switchToTab(3) },
                    modifier = Modifier.testTag("nav_guide"),
                    icon = { Icon(Icons.AutoMirrored.Filled.MenuBook, contentDescription = "Guide") },
                    label = { Text("Guide", maxLines = 1) }
                )
            }
        }
    ) { innerPadding ->
        HorizontalPager(
            state = pagerState,
            beyondViewportPageCount = 1,
            userScrollEnabled = !(pagerState.currentPage == 2 && isViewingTripDetail),
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .testTag("main_pager")
        ) { page ->
            when (page) {
                0 -> DriveScreen(
                    state = state,
                    onRequestForegroundPermissions = requestForegroundPermissions,
                    onRequestBackgroundLocation = requestBackgroundLocation,
                    onRequestBatteryUnrestricted = requestBatteryUnrestricted,
                    onUpdateConfig = { transform -> TelematicsSdk.updatePolicy(transform) },
                    onStartTrip = {
                        if (!state.permissions.fineLocationGranted && !state.permissions.coarseLocationGranted) {
                            requestForegroundPermissions()
                        }
                        TelematicsSdk.startTrip()
                    },
                    onStopTrip = { TelematicsSdk.stopTrip() },
                    onToggleAutoDetect = { enabled ->
                        if (enabled && !state.permissions.fineLocationGranted) {
                            requestForegroundPermissions()
                        }
                        TelematicsSdk.updatePolicy { it.copy(autoDetectEnabled = enabled) }
                    },
                    onToggleMotionPrompts = { enabled ->
                        if (enabled && !state.permissions.activityRecognitionGranted) {
                            requestForegroundPermissions()
                        }
                        TelematicsSdk.updatePolicy { it.copy(motionPromptsEnabled = enabled) }
                    },
                    onRefreshRemoteConfig = { TelematicsSdk.refreshRemoteConfig(userInitiated = true) },
                    onRunSimulatedDrive = {
                        TelematicsSdk.runSimulatedDrive()
                    }
                )
                1 -> SensorsLabScreen(
                    state = state,
                    onUpdateConfig = { transform -> TelematicsSdk.updatePolicy(transform) },
                    onTestPhoneDrop = { TelematicsSdk.triggerImpactLabTest(simulateCollision = false) },
                    onTestCrashImpact = { TelematicsSdk.triggerImpactLabTest(simulateCollision = true) },
                    onTestWakeLockGate = { TelematicsSdk.triggerWakeLockBurstDemo() }
                )
                2 -> TripsScreen(
                    trips = state.savedTrips,
                    presets = state.tripPresets,
                    tripState = state.tripState,
                    config = state.config,
                    permissions = state.permissions,
                    diagnostics = state.diagnostics,
                    power = state.power,
                    gnss = state.gnss,
                    onRequestForegroundPermissions = requestForegroundPermissions,
                    onRequestBackgroundLocation = requestBackgroundLocation,
                    onRequestBatteryUnrestricted = requestBatteryUnrestricted,
                    onRefreshHealth = {
                        TelematicsSdk.refreshPermissions()
                        TelematicsSdk.reloadPresets()
                        TelematicsSdk.refreshRemoteConfig(userInitiated = true)
                    },
                    onStartPreset = { preset ->
                        if (!state.permissions.fineLocationGranted && !state.permissions.coarseLocationGranted) {
                            requestForegroundPermissions()
                        }
                        TelematicsSdk.startTripWithPreset(preset)
                        switchToTab(0)
                    },
                    onDeleteTrip = { id -> TelematicsSdk.deleteTrip(id) },
                    onUpdateTripMeta = { id, lbl, role, mode -> TelematicsSdk.updateTripMeta(id, lbl, role, mode) },
                    onSyncAll = { TelematicsSdk.syncPendingTrips() },
                    onConfigureCloudSync = { url, code -> TelematicsSdk.configureCloudSync(url, code) },
                    onExportTrip = { trip, fmt -> TelematicsSdk.exportTrip(trip, fmt) },
                    onRunSimulatedDrive = {
                        switchToTab(0)
                        TelematicsSdk.runSimulatedDrive()
                    },
                    onDetailVisibilityChanged = { visible -> isViewingTripDetail = visible }
                )
                else -> GuideScreen(state = state)
            }
        }
    }
}
