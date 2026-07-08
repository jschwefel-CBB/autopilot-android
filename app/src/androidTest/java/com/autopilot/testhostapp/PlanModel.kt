package com.autopilot.testhostapp

import com.google.gson.annotations.SerializedName

data class Plan(
    val schemaVersion: String = "",
    val name: String = "",
    val target: TargetApp = TargetApp(),
    val defaults: PlanDefaults? = null,
    val steps: List<Step> = emptyList()
)

data class TargetApp(
    val bundleId: String? = null,
    // Permissions to grant the target package before the run (e.g.
    // "android.permission.ACCESS_FINE_LOCATION"), so their runtime dialogs never
    // block the UI. Granted via `pm grant`; ignored for the bundled host app.
    val permissions: List<String> = emptyList()
)

data class PlanDefaults(
    val timeoutMs: Long? = null,
    val retryIntervalMs: Long? = null,
    // Best-effort: if a runtime-permission system dialog appears mid-run, tap its
    // allow/while-using affordance to clear it. Opt-in (default false) — the
    // runner only dismisses dialogs when a plan asks it to.
    val dismissPermissionDialogs: Boolean? = null,
    // Skip the host-app initial scrollToTop() (the MainActivity-specific swipes).
    // Set true for host-app plans that drive a different surface (e.g. the Compose
    // fixture dialog) where those swipes would disturb the UI. Default false →
    // existing unified-plan behavior unchanged.
    val skipInitialScroll: Boolean? = null
)

/**
 * Coverage tier of a step: cumulative happyPath ⊂ integrationSuite ⊂ tryToBreakIt.
 * Mirrors autopilot-core's StepLevel. A LABEL — it does not invert pass/fail.
 * Parsed as a raw String on [Step] (Gson) and validated in the runner; this enum
 * carries the valid set and rank.
 */
enum class StepLevel(val raw: String, val rank: Int) {
    HAPPY_PATH("happyPath", 0),
    INTEGRATION_SUITE("integrationSuite", 1),
    TRY_TO_BREAK_IT("tryToBreakIt", 2);

    companion object {
        fun from(raw: String?): StepLevel? = entries.firstOrNull { it.raw == raw }
        val validList: String get() = entries.joinToString(", ") { it.raw }
    }
}

data class Step(
    val id: String? = null,
    val comment: String? = null,
    val action: String? = null,
    // REQUIRED coverage tier. Gson leaves it null when absent, so the runner
    // validates it (see AutoPilotRunner.loadPlan) to enforce parity with core.
    val level: String? = null,
    val target: SelectorJson? = null,
    val args: ArgsJson? = null,
    // "assert" is a reserved keyword in Kotlin; use SerializedName to map from JSON
    @SerializedName("assert")
    val assertSpec: AssertJson? = null
)

data class SelectorJson(
    val identifier: String? = null,
    val role: String? = null,
    val title: String? = null,
    val index: Int? = null,
    val within: SelectorJson? = null
)

data class ArgsJson(
    val text: String? = null,
    val clear: Boolean? = null,
    val focus: Boolean? = null,
    val present: Boolean? = null,
    val seconds: Double? = null,
    val deltaY: Double? = null,
    val to: SelectorJson? = null,
    val menuPath: List<String>? = null,
    val color: String? = null,
    val keys: String? = null,
    val padding: Int? = null,
    // Demo args (schema 1.2). Present so a v1.2 demo plan parses cleanly; the demo
    // actions (highlight/caption/pace) are SKIPPED on Android — the instrumented
    // test process cannot draw an overlay on the app under test (needs
    // SYSTEM_ALERT_WINDOW, not granted), a genuine platform limitation. See the
    // runner's action dispatch.
    val holdMs: Int? = null,
    val position: String? = null,
    val typeMsPerChar: Int? = null,
    val stepDelayMs: Int? = null
)

data class AssertJson(
    val property: String? = null,
    val op: String? = null,
    val expected: String? = null
)

data class StepResult(
    val id: String?,
    val passed: Boolean,
    val skipped: Boolean,
    val message: String = ""
)
