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
 * Runs the compose-fixture plan against ComposeFixtureActivity — OutlinedTextFields
 * in a non-scrollable AlertDialog whose contentDescription sits on a non-focusable
 * android.view.View wrapper (the ScopeDOPE shape). Reproduces the find-after-type
 * case (type field A, then immediately find+type sibling field B) under the CI 5x
 * gate, so a regression of that fix fails CI here instead of only on a ScopeDOPE run.
 */
@RunWith(AndroidJUnit4::class)
class ComposeFixtureTest {

    @Test
    fun composeFindAfterTypePlan() {
        val instr = InstrumentationRegistry.getInstrumentation()
        val device = UiDevice.getInstance(instr)

        // Launch the Compose fixture activity (not the launcher MainActivity).
        // Use targetContext (the app-under-test's context) — starting an activity
        // in the target app from the TEST package's context is a cross-uid
        // SecurityException.
        val intent = Intent().apply {
            setClassName(
                "com.autopilot.testhostapp",
                "com.autopilot.testhostapp.ComposeFixtureActivity"
            )
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
        }
        instr.targetContext.startActivity(intent)
        // Wait for the dialog's first field to appear before the plan runs.
        device.wait(Until.hasObject(By.desc("fixtureFieldA")), 10_000L)

        // The runner drives by content-desc system-wide; targetPackageOverride keeps
        // it pointed at this host app (so it does NOT relaunch the launcher activity).
        val runner = AutoPilotRunner(
            targetPackageOverride = "com.autopilot.testhostapp",
            assetName = "compose-fixture.json"
        )
        val results = runner.run()

        results.forEach { r ->
            val status = when { r.skipped -> "SKIP"; r.passed -> "PASS"; else -> "FAIL" }
            android.util.Log.i(
                "AutoPilotRunner",
                "[$status] ${r.id ?: "?"}: ${r.message.ifEmpty { "ok" }}"
            )
        }
        val failures = results.filter { !it.skipped && !it.passed }
        assertTrue(
            "Compose fixture failed steps:\n${failures.joinToString("\n") { "  ${it.id ?: "?"}: ${it.message}" }}",
            failures.isEmpty()
        )
    }
}
