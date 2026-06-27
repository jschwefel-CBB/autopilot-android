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
    val dismissPermissionDialogs: Boolean? = null
)

data class Step(
    val id: String? = null,
    val comment: String? = null,
    val action: String? = null,
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
    val padding: Int? = null
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
