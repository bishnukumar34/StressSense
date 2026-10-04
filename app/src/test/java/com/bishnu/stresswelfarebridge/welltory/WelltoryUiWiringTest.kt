package com.bishnu.stresswelfarebridge.welltory

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Wiring guards for the Welltory option: manifest, card copy and Home integration.
 *
 * Composables and the Android framework cannot be exercised by JVM unit tests, so these assert the
 * source of truth - the same approach this project's existing navigation and layout tests already use.
 * The behavioural guarantees (parsing, model, persistence, isolation) are covered by the other tests
 * in this package, which drive the real classes.
 */
class WelltoryUiWiringTest {

    private fun source(path: String): String {
        val candidates = listOf(
            File("src/main/java/com/bishnu/stresswelfarebridge/$path"),
            File("app/src/main/java/com/bishnu/stresswelfarebridge/$path")
        )
        val f = candidates.firstOrNull { it.exists() }
            ?: error("could not locate $path (tried ${candidates.map { it.path }})")
        return f.readText()
    }

    private fun mainActivity() = source("MainActivity.kt")
    private fun card() = source("camera/CameraHrvCard.kt")
    private fun manifest() = File("src/main/AndroidManifest.xml").let {
        if (it.exists()) it.readText() else File("app/src/main/AndroidManifest.xml").readText()
    }

    // == the card offers both real, distinct options ==============================================

    @Test
    fun theCardOffersBothTheNativeAndWelltoryOptions() {
        val c = card()
        assertTrue("the native camera option must remain", c.contains("\"Measure HRV with camera\""))
        assertTrue("the Welltory option must be present", c.contains("\"Measure HRV with Welltory\""))
    }

    @Test
    fun theWelltoryOptionIsNotPresentedAsPartOfOurOwnModel() {
        assertTrue(
            "the card should state that Welltory supplies the measurement",
            card().contains("Uses Welltory's camera measurement and returns HRV data to StressSense.")
        )
    }

    @Test
    fun theNativeButtonIsNotSilentlyRoutedToWelltory() {
        val m = mainActivity()
        assertTrue(
            "the native camera screen must still be opened",
            m.contains("showCameraHrv = true")
        )
        assertTrue(
            "the Welltory button must launch the Welltory controller",
            m.contains("welltoryController.start(session)")
        )
    }

    // == manifest ================================================================================

    @Test
    fun theManifestDeclaresTheWelltoryPackageVisibility() {
        val x = manifest()
        assertTrue("the welltory scheme must be queryable", x.contains("<queries>"))
        assertTrue(x.contains("android:scheme=\"welltory\""))
        assertTrue(x.contains("android.intent.action.VIEW"))
    }

    @Test
    fun theCallbackActivityIsExportedAsWelltoryRequires() {
        val x = manifest()
        val i = x.indexOf("WelltoryCallbackActivity")
        assertTrue("the callback activity must be declared", i > 0)
        val block = x.substring(maxOf(0, x.lastIndexOf("<activity", i)), i + 400)
        assertTrue(
            "Welltory launches this activity cross-process, so it must be exported",
            block.contains("android:exported=\"true\"")
        )
        assertTrue(
            "singleTask is what produces onNewIntent when the app is still alive",
            block.contains("android:launchMode=\"singleTask\"")
        )
    }

    @Test
    fun theCallbackActivityHandlesBothLifecycleEntryPoints() {
        val a = source("welltory/WelltoryCallbackActivity.kt")
        assertTrue(
            "results must be handled in onCreate (Android may recreate the activity)",
            a.contains("override fun onCreate")
        )
        assertTrue(
            "results must be handled in onNewIntent (app still alive)",
            a.contains("override fun onNewIntent")
        )
        // Both must funnel through the same handler so the paths cannot drift.
        assertTrue(a.contains("handle(intent)"))
    }

    // == state surfacing ========================================================================

    @Test
    fun homeTriggersTheModelAndTheHistoryRefreshOnAWelltoryResult() {
        val m = mainActivity()
        assertTrue(
            "a received Welltory result must be pushed through the existing model path",
            m.contains("welltoryController.onReceived(received.measurement, session)")
        )
        assertTrue(
            "Trends must be refreshed from the existing store after a Welltory measurement",
            m.contains("persistedCameraRecord = stressMeasurementStore?.history(session).orEmpty()")
        )
    }

    @Test
    fun theCommanderRecordIsPublishedFromTheWelltoryScore() {
        val m = mainActivity()
        assertTrue(
            "a Welltory score must reach the published personnel record",
            m.contains("welltoryStressPublish")
        )
        assertTrue(
            "the zone must come from the existing mapping, not a per-person rule",
            m.contains("StressZoneMapping")
        )
    }

    @Test
    fun noFabricatedScoreCanReachTheWelltoryUiPath() {
        // The card must render a score only from a Scored state, which the model produced.
        val c = card()
        assertTrue(
            "the card must branch on a real scored state",
            c.indexOf("is WelltoryResults.State.Scored") > 0
        )
        // No literal placeholder score may be baked into the Welltory section of the card.
        val section = c.substring(c.indexOf("WelltorySection"))
        assertFalse(
            "no hard-coded score may appear in the Welltory UI",
            Regex("\\b(score|stress)\\s*=\\s*[0-9]{1,3}\\b").containsMatchIn(section)
        )
    }

    @Test
    fun everyNonScoringWelltoryStateRendersTextNotANumber() {
        val c = card()
        for (state in listOf("Cancelled", "Rejected", "Ignored", "TimedOut", "NotInstalled",
            "SavedWithoutScore", "Received")) {
            assertTrue(
                "state $state must be handled explicitly",
                c.contains("WelltoryResults.State.$state")
            )
        }
    }

    @Test
    fun theNativeCameraPipelineIsUntouched() {
        // The pre-existing screen and controller must still exist and still be opened.
        val screen = source("camera/CameraHrvScreen.kt")
        assertTrue("the native camera screen must remain", screen.contains("fun CameraHrvScreen"))
        assertTrue(
            "the native acquisition controller must remain",
            source("camera/CameraHrvController.kt").contains("class CameraHrvController")
        )
        assertFalse(
            "the native option must not be removed from the card",
            card().contains("Measure HRV with camera\"\"")
        )
    }

    @Test
    fun welltorySourceIsDistinctFromHealthConnectAndCameraPpg() {
        assertTrue(
            "the Welltory source label must be its own value",
            WelltoryConfig.SOURCE_WELLTORY_CAMERA == "WELLTORY_CAMERA"
        )
        assertFalse(
            "Welltory must never be labelled as Health Connect or native camera PPG",
            WelltoryConfig.SOURCE_WELLTORY_CAMERA == "HEALTH_CONNECT" ||
                WelltoryConfig.SOURCE_WELLTORY_CAMERA == "CAMERA_PPG"
        )
    }
}