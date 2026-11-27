package com.telematicslab.demo

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeLeft
import androidx.compose.ui.test.swipeRight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.height
import androidx.compose.ui.unit.width
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.telematicslab.demo.sdk.api.GnssHealth
import com.telematicslab.demo.sdk.api.ImpactAnalysis
import com.telematicslab.demo.sdk.api.ImpactVerdict
import com.telematicslab.demo.sdk.api.LiveTelemetryState
import com.telematicslab.demo.sdk.api.PermissionState
import com.telematicslab.demo.sdk.api.PowerStats
import com.telematicslab.demo.sdk.api.SdkConfig
import com.telematicslab.demo.sdk.api.SdkDiagnostics
import com.telematicslab.demo.sdk.api.SeatRole
import com.telematicslab.demo.sdk.api.SyncStatus
import com.telematicslab.demo.sdk.api.TrackPoint
import com.telematicslab.demo.sdk.api.TransportMode
import com.telematicslab.demo.sdk.api.TripPreset
import com.telematicslab.demo.sdk.api.TripRecord
import com.telematicslab.demo.sdk.api.TripScoreBreakdown
import com.telematicslab.demo.sdk.api.TripState
import com.telematicslab.demo.ui.screens.DriveScreen
import com.telematicslab.demo.ui.screens.SensorsLabScreen
import com.telematicslab.demo.ui.screens.TripsScreen
import com.telematicslab.demo.ui.theme.TelematicsLabTheme
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.math.abs

@RunWith(AndroidJUnit4::class)
class TelematicsUiLayoutTest {

    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun tripsScreenBadgesNeverSquishOrOverlapStartButton() {
        val samplePresets = listOf(
            TripPreset(
                id = "p1",
                group = "Holiday Routes",
                label = "Sample Outbound (Out)",
                mode = TransportMode.CAR,
                seatRole = SeatRole.FRONT_PASSENGER,
                plannedDate = "2026-11-26"
            )
        )
        val sampleTrips = listOf(
            TripRecord(
                id = "demo001",
                tripLabel = "Sample Outbound (Out) [S20]",
                seatRole = SeatRole.FRONT_PASSENGER,
                transportMode = TransportMode.CAR,
                syncStatus = SyncStatus.SYNCED,
                startTimeMs = 1_795_716_154_000L,
                endTimeMs = 1_795_717_154_000L,
                durationSec = 1000,
                distanceMiles = 7.66f,
                maxSpeedMph = 63.8f,
                avgSpeedMph = 32.0f,
                avgVertRmsG = 0.04f,
                score = TripScoreBreakdown(overall = 93),
                events = emptyList(),
                impacts = emptyList(),
                track = emptyList(),
                fsmLog = emptyList(),
                batteryDeltaPct = 1,
                avgAccuracyM = 4.5f,
                manualWakeLockMs = 0L,
                isSimulated = true
            )
        )

        composeRule.setContent {
            TelematicsLabTheme(darkTheme = true) {
                TripsScreen(
                    trips = sampleTrips,
                    presets = samplePresets,
                    tripState = TripState.IDLE,
                    config = SdkConfig(),
                    permissions = PermissionState(
                        fineLocationGranted = true,
                        coarseLocationGranted = true,
                        backgroundLocationGranted = true,
                        postNotificationsGranted = true,
                        activityRecognitionGranted = true,
                        ignoringBatteryOptimizations = true
                    ),
                    diagnostics = SdkDiagnostics(),
                    power = PowerStats(imuSampleHz = 50f),
                    gnss = GnssHealth(),
                    onRequestForegroundPermissions = {},
                    onRequestBackgroundLocation = {},
                    onRequestBatteryUnrestricted = {},
                    onRefreshHealth = {},
                    onStartPreset = {},
                    onDeleteTrip = {},
                    onUpdateTripMeta = { _, _, _, _ -> },
                    onSyncAll = {},
                    onConfigureCloudSync = { _, _ -> },
                    onExportTrip = { _, _ -> null },
                    onRunSimulatedDrive = {}
                )
            }
        }

        val capturedBounds = composeRule.onNodeWithText("CAPTURED", useUnmergedTree = true).getUnclippedBoundsInRoot()
        val s20Bounds = composeRule.onNodeWithText("S20", useUnmergedTree = true).getUnclippedBoundsInRoot()
        val frontPassNodes = composeRule.onAllNodesWithText("Front Passenger", useUnmergedTree = true)
        val frontPassPresetBounds = frontPassNodes[0].getUnclippedBoundsInRoot()
        val frontPassTripBounds = frontPassNodes[1].getUnclippedBoundsInRoot()
        val syncedBounds = composeRule.onNodeWithText("Cloud Synced", useUnmergedTree = true).getUnclippedBoundsInRoot()
        val demoBounds = composeRule.onNodeWithText("DEMO", useUnmergedTree = true).getUnclippedBoundsInRoot()
        val startBounds = composeRule.onAllNodesWithText("Start", useUnmergedTree = true).onFirst().getUnclippedBoundsInRoot()

        assertTrue("CAPTURED badge height should stay single-line (was ${capturedBounds.height})", capturedBounds.height <= 36.dp)
        assertTrue("CAPTURED badge width should not squish (was ${capturedBounds.width})", capturedBounds.width >= 48.dp)
        assertTrue("S20 badge height should stay single-line (was ${s20Bounds.height})", s20Bounds.height <= 36.dp)
        assertTrue("Preset Front Passenger badge should stay single-line (was ${frontPassPresetBounds.height})", frontPassPresetBounds.height <= 36.dp)
        assertTrue("Trip Front Passenger badge should stay single-line (was ${frontPassTripBounds.height})", frontPassTripBounds.height <= 36.dp)
        assertTrue("Cloud Synced badge should stay single-line (was ${syncedBounds.height})", syncedBounds.height <= 36.dp)
        assertTrue("DEMO badge should stay single-line (was ${demoBounds.height})", demoBounds.height <= 36.dp)
        assertTrue("CAPTURED badge must not overlap Start button", capturedBounds.right <= startBounds.left + 1.dp)
    }

    @Test
    fun tripDetailRendersAllChartsAndNavigatesBackCleanly() {
        val sampleTrack = (0 until 1500).map { i ->
            TrackPoint(
                elapsedSec = i.toFloat(),
                timestampMs = 1_795_716_000_000L + i * 1000L,
                lat = 37.7749 + i * 0.0001,
                lon = -122.4194 - i * 0.0001,
                speedMph = 35f,
                bearingDeg = 90f,
                accuracyM = 5f,
                satellitesUsed = 12,
                longG = 0.05f,
                latG = -0.04f,
                vertG = 0.02f,
                rawTotalG = 1.0f,
                yawRadS = 0.02f,
                screenOn = false,
                handheld = false
            )
        }
        val detailTrip = TripRecord(
            id = "detail1500",
            tripLabel = "Large Route [S23]",
            seatRole = SeatRole.FRONT_PASSENGER,
            transportMode = TransportMode.CAR,
            syncStatus = SyncStatus.SYNCED,
            startTimeMs = 1_795_716_000_000L,
            endTimeMs = 1_795_717_500_000L,
            durationSec = 1500,
            distanceMiles = 12.5f,
            maxSpeedMph = 65f,
            avgSpeedMph = 35f,
            avgVertRmsG = 0.04f,
            score = TripScoreBreakdown(overall = 94),
            events = emptyList(),
            impacts = listOf(
                ImpactAnalysis(
                    timestampMs = 1_795_716_500_000L,
                    peakG = 3.9f,
                    preSpeedMph = 0f,
                    postSpeedMph = 0f,
                    deltaVMph = 0f,
                    peakGyroRadS = 8.9f,
                    verdict = ImpactVerdict.REJECTED_PHONE_DROP,
                    reason = "Gyro spin 8.9 rad/s with Δv=0.0 mph"
                )
            ),
            track = sampleTrack,
            fsmLog = emptyList(),
            batteryDeltaPct = 1,
            avgAccuracyM = 5f,
            manualWakeLockMs = 0L,
            isSimulated = false
        )

        composeRule.setContent {
            TelematicsLabTheme(darkTheme = true) {
                TripsScreen(
                    trips = listOf(detailTrip),
                    presets = emptyList(),
                    tripState = TripState.IDLE,
                    config = SdkConfig(),
                    permissions = PermissionState(
                        fineLocationGranted = true,
                        coarseLocationGranted = true,
                        backgroundLocationGranted = true,
                        postNotificationsGranted = true,
                        activityRecognitionGranted = true,
                        ignoringBatteryOptimizations = true
                    ),
                    diagnostics = SdkDiagnostics(),
                    power = PowerStats(imuSampleHz = 50f),
                    gnss = GnssHealth(),
                    onRequestForegroundPermissions = {},
                    onRequestBackgroundLocation = {},
                    onRequestBatteryUnrestricted = {},
                    onRefreshHealth = {},
                    onStartPreset = {},
                    onDeleteTrip = {},
                    onUpdateTripMeta = { _, _, _, _ -> },
                    onSyncAll = {},
                    onConfigureCloudSync = { _, _ -> },
                    onExportTrip = { _, _ -> null },
                    onRunSimulatedDrive = {}
                )
            }
        }

        composeRule.onNodeWithTag("trip_card_detail1500").performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("trip_detail_back").assertIsDisplayed()
        composeRule.onNodeWithText("Trip Safety Score").assertExists()
        composeRule.onNodeWithText("S23").assertExists()
        composeRule.onNodeWithTag("trip_scrubber_slider").assertExists()
        composeRule.onNodeWithTag("zoom_2x").performScrollTo().performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithText("1. Offline Route Map (Speed + Event Pins)").assertExists()
        composeRule.onNodeWithTag("expand_speed_btn").performScrollTo().performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("fullscreen_done_btn").assertExists()
        composeRule.onNodeWithTag("fullscreen_done_btn").performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithText("4. Trip G-G Friction Circle Scatter").assertExists()
        composeRule.onNodeWithText("Stage-2 Impact Classifications (1)").assertExists()
        composeRule.onNodeWithTag("trip_detail_back").performScrollTo().performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("trip_card_detail1500").assertIsDisplayed()
    }

    @Test
    fun sensorsScreenChipsAndStatPillsNeverSquishOrMisalign() {
        composeRule.setContent {
            TelematicsLabTheme(darkTheme = true) {
                SensorsLabScreen(
                    state = LiveTelemetryState(
                        power = PowerStats(
                            batteryPct = 100,
                            batteryTempC = 23.0f,
                            standbyBucketName = "EXEMPT/OTHER",
                            ignoringBatteryOptimizations = true,
                            imuSampleHz = 50f
                        )
                    ),
                    onUpdateConfig = {},
                    onTestPhoneDrop = {},
                    onTestCrashImpact = {},
                    onTestWakeLockGate = {}
                )
            }
        }

        for (label in listOf("Vehicle G", "Raw Phone", "Gyroscope", "Raw vs Filt")) {
            val bounds = composeRule.onNodeWithText(label).getUnclippedBoundsInRoot()
            assertTrue("Chip '$label' wrapped onto multiple lines (height=${bounds.height})", bounds.height <= 42.dp)
            assertTrue("Chip '$label' width collapsed (width=${bounds.width})", bounds.width >= 40.dp)
        }

        val battBounds = composeRule.onNodeWithTag("pill_battery").getUnclippedBoundsInRoot()
        val standbyBounds = composeRule.onNodeWithTag("pill_standby").getUnclippedBoundsInRoot()
        val dozeBounds = composeRule.onNodeWithTag("pill_doze").getUnclippedBoundsInRoot()

        assertTrue(
            "StatPills in BATTERY row must have matching height (b=${battBounds.height}, s=${standbyBounds.height})",
            abs(battBounds.height.value - standbyBounds.height.value) <= 2f
        )
        assertTrue(
            "StatPills in STANDBY/DOZE row must have matching height (s=${standbyBounds.height}, d=${dozeBounds.height})",
            abs(standbyBounds.height.value - dozeBounds.height.value) <= 2f
        )
    }

    @Test
    fun driveScreenCtaButtonsMatchHeightAndEventsLegendClear() {
        composeRule.setContent {
            TelematicsLabTheme(darkTheme = true) {
                DriveScreen(
                    state = LiveTelemetryState(
                        permissions = PermissionState(
                            fineLocationGranted = true,
                            coarseLocationGranted = true,
                            backgroundLocationGranted = true,
                            postNotificationsGranted = true,
                            activityRecognitionGranted = true,
                            ignoringBatteryOptimizations = true
                        )
                    ),
                    onRequestForegroundPermissions = {},
                    onRequestBackgroundLocation = {},
                    onRequestBatteryUnrestricted = {},
                    onUpdateConfig = {},
                    onStartTrip = {},
                    onStopTrip = {},
                    onToggleAutoDetect = {},
                    onToggleMotionPrompts = {},
                    onRefreshRemoteConfig = {},
                    onRunSimulatedDrive = {}
                )
            }
        }

        val startBounds = composeRule.onNodeWithTag("drive_start_btn").getUnclippedBoundsInRoot()
        val demoBounds = composeRule.onNodeWithTag("drive_demo_btn").getUnclippedBoundsInRoot()
        assertTrue(
            "Start and 34s Demo buttons must have matching height (start=${startBounds.height}, demo=${demoBounds.height})",
            abs(startBounds.height.value - demoBounds.height.value) <= 2f
        )
        assertTrue("Start and 34s Demo buttons must not overlap", startBounds.right <= demoBounds.left + 1.dp)

        val eventsHeader = composeRule.onNodeWithText("Live Detected Events (0)").getUnclippedBoundsInRoot()
        val brakeLegend = composeRule.onNodeWithText("Brake").getUnclippedBoundsInRoot()
        assertTrue(
            "Events legend should sit cleanly below the heading (headerBottom=${eventsHeader.bottom}, legendTop=${brakeLegend.top})",
            eventsHeader.bottom <= brakeLegend.top + 1.dp
        )
    }
}

@RunWith(AndroidJUnit4::class)
class MainPagerSwipeTest {

    @get:Rule
    val composeRule = createEmptyComposeRule()

    @Test
    fun horizontalPagerSwipesFluidlyAcrossAllFourTabsAndBottomNavSyncs() {
        val inst = InstrumentationRegistry.getInstrumentation()
        for (perm in listOf(
            "android.permission.ACCESS_FINE_LOCATION",
            "android.permission.ACCESS_COARSE_LOCATION",
            "android.permission.ACCESS_BACKGROUND_LOCATION",
            "android.permission.ACTIVITY_RECOGNITION"
        )) {
            inst.uiAutomation.executeShellCommand("pm grant com.telematicslab.demo $perm").close()
        }

        ActivityScenario.launch(MainActivity::class.java).use {
            composeRule.waitForIdle()
            composeRule.onNodeWithTag("main_pager").assertIsDisplayed()

            // Swipe Drive -> Sensors -> Trips -> Guide
            composeRule.onNodeWithTag("main_pager").performTouchInput { swipeLeft() }
            composeRule.waitForIdle()
            composeRule.onNodeWithText("Live IMU Oscilloscope & Filter Engine Bridge").assertIsDisplayed()

            composeRule.onNodeWithTag("main_pager").performTouchInput { swipeLeft() }
            composeRule.waitForIdle()
            composeRule.onNodeWithText("Cloud Sync Archive Settings").assertExists()

            composeRule.onNodeWithTag("main_pager").performTouchInput { swipeLeft() }
            composeRule.waitForIdle()
            composeRule.onNodeWithText("Telematics & Mobile SDK Fundamentals").assertIsDisplayed()

            // Swipe Guide -> Trips -> Sensors
            composeRule.onNodeWithTag("main_pager").performTouchInput { swipeRight() }
            composeRule.waitForIdle()
            composeRule.onNodeWithText("Cloud Sync Archive Settings").assertExists()

            composeRule.onNodeWithTag("main_pager").performTouchInput { swipeRight() }
            composeRule.waitForIdle()
            composeRule.onNodeWithText("Live IMU Oscilloscope & Filter Engine Bridge").assertIsDisplayed()

            // Bottom bar jump to Guide & back to Drive
            composeRule.onNodeWithTag("nav_guide").performClick()
            composeRule.waitForIdle()
            composeRule.onNodeWithText("Telematics & Mobile SDK Fundamentals").assertIsDisplayed()

            composeRule.onNodeWithTag("nav_drive").performClick()
            composeRule.waitForIdle()
            composeRule.onNodeWithTag("drive_start_btn").assertIsDisplayed()
        }
    }
}
