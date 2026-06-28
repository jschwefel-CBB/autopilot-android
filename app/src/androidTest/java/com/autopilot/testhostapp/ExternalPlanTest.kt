package com.autopilot.testhostapp

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Runs an EXTERNAL AutoPilot plan against an EXTERNAL app — the entry point that
 * makes the Android agent usable with any installed app (any installed app), not
 * just the bundled TestHostApp.
 *
 * Invoke via adb, passing the on-device plan path and (optionally) the target
 * package as instrumentation args. The package is normally read from the plan's
 * `target.bundleId`, so `-e target` is only needed to override it:
 *
 *   adb push settings-behavior.json /data/local/tmp/plan.json
 *   adb shell am instrument -w \
 *     -e class com.autopilot.testhostapp.ExternalPlanTest \
 *     -e plan /data/local/tmp/plan.json \
 *     com.autopilot.testhostapp.test/androidx.test.runner.AndroidJUnitRunner
 *
 * With no `-e plan` arg this test is SKIPPED (assumption fails) so it never
 * interferes with the bundled AutoPilotRunnerTest run on CI.
 */
@RunWith(AndroidJUnit4::class)
class ExternalPlanTest {

    @Test
    fun runExternalPlan() {
        val args = InstrumentationRegistry.getArguments()
        val planPath = args.getString("plan")
        val targetPackage = args.getString("target")  // optional; plan.target.bundleId otherwise

        // No external plan supplied → this entry is not the one being invoked. Skip.
        assumeTrue(
            "ExternalPlanTest skipped: pass -e plan <device-path> to run an external plan",
            planPath != null
        )

        val runner = AutoPilotRunner(planPath = planPath, targetPackageOverride = targetPackage)
        val results = runner.run()

        results.forEach { result ->
            val status = when {
                result.skipped -> "SKIP"
                result.passed  -> "PASS"
                else           -> "FAIL"
            }
            android.util.Log.i(
                "AutoPilotRunner",
                "[$status] ${result.id ?: "?"}: ${result.message.ifEmpty { "ok" }}"
            )
        }

        val failures = results.filter { !it.skipped && !it.passed }
        assertTrue(
            "Failed steps:\n${failures.joinToString("\n") { "  ${it.id ?: "?"}: ${it.message}" }}",
            failures.isEmpty()
        )
    }
}
