package com.autopilot.testhostapp

import androidx.test.ext.junit.rules.ActivityScenarioRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.Assert.assertTrue

@RunWith(AndroidJUnit4::class)
class AutoPilotRunnerTest {

    @get:Rule
    val activityRule = ActivityScenarioRule(MainActivity::class.java)

    @Test
    fun runUnifiedPlan() {
        val runner = AutoPilotRunner()
        val results = runner.run()

        // Log all results for diagnostics
        results.forEach { result ->
            val status = when {
                result.skipped -> "SKIP"
                result.passed -> "PASS"
                else -> "FAIL"
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
