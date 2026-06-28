package com.autopilot.testhostapp

import android.content.Intent
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Drives the Compose fixtures (ComposeFixtureActivity) under the CI 5x gate so the
 * Compose automation shapes are caught HERE, not on a downstream real-app run.
 *
 *  - composeFindAfterTypePlan: non-scrollable AlertDialog, sibling find-after-type.
 *  - composeScrollFindPlan: scrollable LazyColumn, find a below-the-fold field after
 *    typing — reproduces a real Compose app's scrollable-form shape. On a failed find the
 *    runner logs FIND-FAIL-DUMP (what the runner's UiAutomation sees vs external).
 */
@RunWith(AndroidJUnit4::class)
class ComposeFixtureTest {

    private fun launch(mode: String): UiDevice {
        val instr = InstrumentationRegistry.getInstrumentation()
        val device = UiDevice.getInstance(instr)
        val intent = Intent().apply {
            setClassName(
                "com.autopilot.testhostapp",
                "com.autopilot.testhostapp.ComposeFixtureActivity"
            )
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
            putExtra("mode", mode)
        }
        // targetContext (app-under-test) — starting it from the test context is a
        // cross-uid SecurityException.
        instr.targetContext.startActivity(intent)
        return device
    }

    private fun runPlanAndAssert(asset: String, firstFieldDesc: String) {
        val mode = when {
            asset.contains("scroll") -> "scroll"
            asset.contains("churn") -> "churn"
            asset.contains("wrapper") -> "wrapper"
            else -> "dialog"
        }
        val device = launch(mode)
        device.wait(Until.hasObject(By.desc(firstFieldDesc)), 10_000L)
        val runner = AutoPilotRunner(
            targetPackageOverride = "com.autopilot.testhostapp",
            assetName = asset
        )
        val results = runner.run()
        results.forEach { r ->
            val status = when { r.skipped -> "SKIP"; r.passed -> "PASS"; else -> "FAIL" }
            android.util.Log.i("AutoPilotRunner",
                "[$status] ${r.id ?: "?"}: ${r.message.ifEmpty { "ok" }}")
        }
        val failures = results.filter { !it.skipped && !it.passed }
        assertTrue(
            "$asset failed steps:\n${failures.joinToString("\n") { "  ${it.id ?: "?"}: ${it.message}" }}",
            failures.isEmpty()
        )
    }

    @Test
    fun composeFindAfterTypePlan() {
        runPlanAndAssert("compose-fixture.json", "fixtureFieldA")
    }

    @Test
    fun composeScrollFindPlan() {
        runPlanAndAssert("compose-scroll-fixture.json", "scrollFieldA")
    }

    @Test
    fun composeChurnFindPlan() {
        runPlanAndAssert("compose-churn-fixture.json", "churnFieldA")
    }

    @Test
    fun composeWrapperFindPlan() {
        runPlanAndAssert("compose-wrapper-fixture.json", "wrapperFieldA")
    }
}
