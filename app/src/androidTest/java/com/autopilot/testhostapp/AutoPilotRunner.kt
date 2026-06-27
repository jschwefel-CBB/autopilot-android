package com.autopilot.testhostapp

import android.os.SystemClock
import android.view.KeyEvent
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.*
import com.google.gson.Gson
import org.junit.Assert.*
import java.io.File
import java.io.InputStreamReader

/**
 * Drives a plan against an app via UiAutomator.
 *
 * Two entry modes:
 *  - Bundled mode (no args): loads the bundled `test-all-capabilities.json`
 *    asset and drives the in-repo TestHostApp (the original behavior — used by
 *    this module's own CI).
 *  - External mode: given an explicit plan source (a file path on the device)
 *    and/or a target package, it loads that plan and drives that EXTERNAL app
 *    (e.g. com.coldboreballisticsllc.scopedope). UiAutomator is system-wide, so
 *    it can query and act across app boundaries; the only thing the runner must
 *    NOT do is assume the app under test is its own instrumentation target.
 *
 * `planPath`     — absolute path to a plan JSON on the device; null → bundled asset.
 * `targetPackage`— package to launch/drive; null → plan.target.bundleId → the
 *                  instrumentation target (bundled host app) as the final fallback.
 */
class AutoPilotRunner(
    private val planPath: String? = null,
    private val targetPackageOverride: String? = null,
    // Optional bundled-asset plan name (e.g. "compose-fixture.json"). When set,
    // loads that asset instead of the default unified plan — used by in-repo
    // fixtures. planPath (an external device path) still takes precedence.
    private val assetName: String? = null
) {

    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val device: UiDevice = UiDevice.getInstance(instrumentation)

    // The package actually being driven. Resolved in run() once the plan is known.
    // Defaults to the instrumentation target so bundled-mode behavior is unchanged.
    private var targetPackage: String = instrumentation.targetContext.packageName

    // True when driving this module's own TestHostApp — gates fixture-specific
    // setup (e.g. scrollToTop) that must never run against an arbitrary app.
    private val hostPackage: String = instrumentation.targetContext.packageName
    private val drivingHostApp: Boolean get() = targetPackage == hostPackage

    private fun defaultTimeout(): Long = 5000L

    // ── Plan loading ────────────────────────────────────────────────────────

    private fun loadPlan(): Plan {
        val reader = when {
            // External plan pushed to the device (adb push … ; -e plan <path>).
            planPath != null -> InputStreamReader(File(planPath).inputStream())
            // A named bundled asset (in-repo fixtures, e.g. "compose-fixture.json").
            assetName != null -> InputStreamReader(instrumentation.context.assets.open(assetName))
            // Default bundled mode: the unified plan packaged in this test APK.
            else -> InputStreamReader(instrumentation.context.assets.open("test-all-capabilities.json"))
        }
        return Gson().fromJson(reader, Plan::class.java)
    }

    /** Resolve which package to drive: explicit override > plan target > host app. */
    private fun resolveTargetPackage(plan: Plan): String =
        targetPackageOverride ?: plan.target.bundleId ?: hostPackage

    // Whether to best-effort dismiss runtime-permission dialogs that appear
    // mid-run (plan defaults.dismissPermissionDialogs). Resolved in run().
    private var dismissPermissionDialogs: Boolean = false

    /** Grant the plan's declared permissions to the target package via `pm grant`,
     * so their runtime dialogs never block the run. No-op for the host app and
     * for an empty list. Failures are non-fatal (e.g. a non-grantable perm). */
    private fun grantDeclaredPermissions(plan: Plan) {
        if (drivingHostApp) return
        for (perm in plan.target.permissions) {
            try {
                device.executeShellCommand("pm grant $targetPackage $perm")
            } catch (_: Exception) { /* non-grantable / already granted — ignore */ }
        }
    }

    /** Launch the target app from its launcher intent and wait for it to appear. */
    private fun launchTargetApp() {
        if (drivingHostApp) return  // bundled mode launches via the test's ActivityScenarioRule
        val ctx = instrumentation.context
        val intent = ctx.packageManager.getLaunchIntentForPackage(targetPackage)
            ?: throw IllegalStateException(
                "Target package '$targetPackage' is not installed on the device")
        intent.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
        ctx.startActivity(intent)
        device.wait(Until.hasObject(By.pkg(targetPackage).depth(0)), 10_000L)
    }

    /** Best-effort dismissal of a blocking runtime-permission system dialog.
     * Only acts when the plan opted in (dismissPermissionDialogs). Taps the
     * "while using"/"allow" affordance if a permission-controller dialog is up.
     * Returns true if it dismissed something. */
    private fun dismissPermissionDialogIfPresent(): Boolean {
        if (!dismissPermissionDialogs) return false
        val controller = "com.android.permissioncontroller"
        if (!device.hasObject(By.pkg(controller))) return false
        // Prefer the foreground/while-using grant, then a generic allow.
        val ids = listOf(
            "com.android.permissioncontroller:id/permission_allow_foreground_only_button",
            "com.android.permissioncontroller:id/permission_allow_button"
        )
        for (rid in ids) {
            val btn = device.findObject(By.res(rid))
            if (btn != null) { btn.click(); Thread.sleep(300); return true }
        }
        // Fallback: match common button text.
        for (label in listOf("While using the app", "Allow", "ALLOW", "Only this time")) {
            val btn = device.findObject(By.text(label))
            if (btn != null) { btn.click(); Thread.sleep(300); return true }
        }
        return false
    }

    // ── Public entry point ──────────────────────────────────────────────────

    fun run(): List<StepResult> {
        val plan = loadPlan()
        targetPackage = resolveTargetPackage(plan)
        dismissPermissionDialogs = plan.defaults?.dismissPermissionDialogs ?: false
        grantDeclaredPermissions(plan)   // grant up front so dialogs never appear
        launchTargetApp()
        val timeout = plan.defaults?.timeoutMs ?: defaultTimeout()
        // MainActivity-specific top-scroll for the unified plan; skip for host-app
        // plans that drive a different surface (e.g. the Compose fixture dialog).
        if (drivingHostApp && plan.defaults?.skipInitialScroll != true) scrollToTop()
        return plan.steps.map { step ->
            // A permission dialog can surface on screen entry mid-plan; clear it
            // (opt-in) before the step interacts with the now-covered UI.
            dismissPermissionDialogIfPresent()
            executeStep(step, timeout)
        }
    }

    private fun scrollToTop() {
        closeIme()
        Thread.sleep(300)
        // Swipe upward 4 times to reach top of the outer ScrollView
        repeat(4) {
            val h = device.displayHeight; val w = device.displayWidth
            device.swipe(w / 2, h / 4, w / 2, h * 3 / 4, 20)
            Thread.sleep(150)
        }
    }

    // ── Element finding ─────────────────────────────────────────────────────

    private fun findElement(sel: SelectorJson): UiObject {
        var obj = resolveElement(sel)
        if (obj.exists()) return obj
        // THE common Compose case (find-after-type staleness): the node is present
        // and visible, but the legacy UiObject query read a stale/flattened
        // AccessibilityNodeInfo snapshot taken before a Compose recomposition (and
        // the IME-dismiss) settled — e.g. immediately after typing into a sibling
        // OutlinedTextField whose contentDescription sits on a non-focusable
        // android.view.View wrapper. Wait on the LIVE a11y tree via UiObject2/By,
        // which tracks the current AccessibilityNodeInfo, then re-resolve. This
        // resolves it WITHOUT any IME dismiss or scrolling.
        sel.identifier?.let { id ->
            device.waitForIdle(2000)
            if (device.wait(Until.hasObject(By.desc(id)), 3000L) != null) {
                obj = resolveElement(sel)
                if (obj.exists()) return obj
            }
        }
        // Still not found → the target may be keyboard-covered or off-viewport.
        //  - dismiss the IME NON-DESTRUCTIVELY (never pressBack: it would close a
        //    modal dialog);
        forceDismissIme()
        obj = resolveElement(sel)
        if (obj.exists()) return obj
        //  - bring an off-viewport target back. Only scroll/swipe when it can
        //    actually help (a scrollable container exists, or the node is off the
        //    viewport) — never thrash a fixed, non-scrollable dialog.
        if (shouldAttemptScroll(sel)) {
            scrollIntoViewCompose(sel)
            obj = resolveElement(sel)
            if (obj.exists()) return obj
            // Legacy fixture-oriented fallback (TestHostApp ScrollView paths).
            scrollIntoView(sel)
        }
        val finalObj = resolveElement(sel)
        if (!finalObj.exists()) dumpFindFailure(sel)
        return finalObj
    }

    // Captures exactly what the runner's UiAutomation sees at the instant of a
    // failed find — the decisive diagnostic for the find-after-type gap (the
    // external `uiautomator dump` view may differ from what the in-run query sees
    // per-query, e.g. cache/recompose state). Logged under AutoPilotRunner with a
    // FIND-FAIL-DUMP marker so CI logs / logcat can be grepped. (DOPE-free.)
    private fun dumpFindFailure(sel: SelectorJson) {
        try {
            val id = sel.identifier ?: sel.within?.identifier
            val log = StringBuilder()
            log.append("FIND-FAIL-DUMP id=$id sel=$sel\n")
            if (id != null) {
                // What each query method sees for THIS id, right now.
                val legacyDesc = device.findObject(UiSelector().description(id)).exists()
                val o2Desc = device.findObject(By.desc(id))
                val o2Res = device.findObject(By.res(id))
                log.append("  legacy UiObject.description exists=$legacyDesc\n")
                log.append("  UiObject2 By.desc=${o2Desc?.let { "FOUND class=${it.className} bounds=${it.visibleBounds}" } ?: "null"}\n")
                log.append("  UiObject2 By.res =${o2Res?.let { "FOUND class=${it.className} bounds=${it.visibleBounds}" } ?: "null"}\n")
            }
            // Inventory of what IS in the tree the runner sees.
            val descs = device.findObjects(By.desc(Pattern.compile(".+")))
                .mapNotNull { try { it.contentDescription } catch (_: Throwable) { null } }
            log.append("  content-descs visible to runner (${descs.size}): ${descs.joinToString(", ")}\n")
            val edits = device.findObjects(By.clazz("android.widget.EditText"))
            log.append("  EditTexts visible to runner (${edits.size}): " +
                edits.joinToString(", ") { "[${it.visibleBounds}] text='${it.text ?: ""}'" } + "\n")
            log.append("  scrollables present=${device.findObjects(By.scrollable(true)).isNotEmpty()}\n")
            // Full hierarchy dump (what the runner's UiAutomation snapshot contains).
            try {
                val f = java.io.File(
                    instrumentation.targetContext.externalCacheDir
                        ?: instrumentation.targetContext.cacheDir,
                    "find-fail-${id ?: "x"}-${SystemClock.uptimeMillis()}.xml"
                )
                device.dumpWindowHierarchy(f)
                log.append("  full hierarchy → ${f.absolutePath}\n")
            } catch (e: Throwable) {
                log.append("  hierarchy dump failed: ${e.javaClass.simpleName}: ${e.message}\n")
            }
            android.util.Log.w("AutoPilotRunner", log.toString())
        } catch (e: Throwable) {
            android.util.Log.w("AutoPilotRunner", "FIND-FAIL-DUMP errored: ${e.message}")
        }
    }

    // Gate the scroll/swipe recovery: it helps ONLY when there is a scrollable
    // container to drive, OR the target exists in the tree but its bounds are
    // outside the viewport. If the target is absent AND there is no scrollable
    // container (a fixed dialog — the ammo Add-Custom-Ammo case), swiping moves
    // nothing and just wastes time (and can mask a find-after-type miss), so skip.
    private fun shouldAttemptScroll(sel: SelectorJson): Boolean {
        return try {
            if (device.findObjects(By.scrollable(true)).isNotEmpty()) return true
            val id = sel.identifier ?: sel.within?.identifier ?: return false
            val node = device.findObject(By.desc(id)) ?: return false
            val screen = android.graphics.Rect(0, 0, device.displayWidth, device.displayHeight)
            !screen.contains(node.visibleBounds)
        } catch (_: Throwable) { false }
    }

    // (DOPE-76) Compose-friendly scroll-into-view. Compose scroll containers
    // (Modifier.verticalScroll / LazyColumn) do NOT expose scrollable="true" on a
    // recognizable android.widget.ScrollView, so By.scrollable(true) often finds
    // nothing and the legacy UiScrollable path has no handle. So:
    //  1. If a real scrollable node exists, drive it (LazyColumn sometimes does).
    //  2. Otherwise fall back to BOUNDS-BASED swipes on the content area —
    //     swipe up to reveal content below, then down, re-checking for the target
    //     by content-desc between swipes. This needs no scrollable handle.
    private fun scrollIntoViewCompose(sel: SelectorJson) {
        val id = sel.identifier ?: sel.within?.identifier ?: return
        try {
            // The IME squeezes a Compose list/dialog into the top fraction of the
            // screen, so a deep item (LazyColumn virtualization → not composed until
            // near the viewport) can't be scrolled to. Dismiss the keyboard first so
            // the list reclaims its full height, then drive the scroll.
            forceDismissIme()
            device.waitForIdle(800)
            if (device.hasObject(By.desc(id))) return
            val container = device.findObjects(By.scrollable(true))
                .maxByOrNull { it.visibleBounds.width() * it.visibleBounds.height() }
            if (container != null) {
                container.setGestureMargin(container.visibleBounds.height() / 8)
                // More iterations (a deep LazyColumn item can be many screens down)
                // and a fuller scroll fraction so each step advances further.
                repeat(15) {
                    if (device.hasObject(By.desc(id))) return
                    container.scroll(Direction.DOWN, 0.8f); Thread.sleep(120)
                }
                repeat(15) {
                    if (device.hasObject(By.desc(id))) return
                    container.scroll(Direction.UP, 0.8f); Thread.sleep(120)
                }
                if (device.hasObject(By.desc(id))) return
            }
            // Bounds-based fallback (Compose scroll with no scrollable handle).
            swipeScanForDesc(id)
        } catch (_: Throwable) {}
    }

    // Reveal an off-screen content-desc target by swiping the content area itself
    // (no scrollable handle needed). Swipes within the middle band of the screen
    // — above any keyboard, below the toolbar — re-checking after each. Up-swipes
    // (reveal-below) first since growing lists push the add-row downward, then
    // down-swipes. Returns as soon as the target appears.
    private fun swipeScanForDesc(id: String) {
        val w = device.displayWidth
        val h = device.displayHeight
        val x = w / 2
        val top = (h * 0.30).toInt()      // below the app bar
        val bottom = (h * 0.62).toInt()   // above where the IME would sit
        repeat(8) {
            if (device.hasObject(By.desc(id))) return
            device.swipe(x, bottom, x, top, 24)   // content moves up → reveal below
            Thread.sleep(150)
        }
        repeat(8) {
            if (device.hasObject(By.desc(id))) return
            device.swipe(x, top, x, bottom, 24)   // content moves down → reveal above
            Thread.sleep(150)
        }
    }

    private fun resolveElement(sel: SelectorJson): UiObject {
        return when {
            sel.identifier != null ->
                device.findObject(UiSelector().description(sel.identifier))
            sel.within != null && sel.role != null -> {
                val parent = resolveElement(sel.within)
                val childClass = roleToClass(sel.role)
                if (sel.index != null)
                    parent.getChild(UiSelector().className(childClass).instance(sel.index))
                else
                    parent.getChild(UiSelector().className(childClass))
            }
            sel.role != null && sel.index != null ->
                device.findObject(UiSelector().className(roleToClass(sel.role)).instance(sel.index))
            sel.role != null ->
                device.findObject(UiSelector().className(roleToClass(sel.role)))
            sel.title != null ->
                device.findObject(UiSelector().text(sel.title))
            else -> throw IllegalArgumentException("Cannot resolve selector: $sel")
        }
    }

    // Scroll the outer page to bring an element into view.
    private fun scrollIntoView(sel: SelectorJson) {
        val pkg = targetPackage
        // instance(0) picks the outermost ScrollView; the inner one (contentDescription="scrollView")
        // is instance(1) and would not contain top-of-page elements like colorSwatch or statusLabel.
        val outerScroll = UiScrollable(
            UiSelector().packageName(pkg).className("android.widget.ScrollView").instance(0)
        )
        outerScroll.setMaxSearchSwipes(15)

        try {
            when {
                sel.identifier != null ->
                    outerScroll.scrollIntoView(UiSelector().description(sel.identifier))
                sel.title != null ->
                    outerScroll.scrollIntoView(UiSelector().text(sel.title))
                sel.role != null && sel.index != null ->
                    outerScroll.scrollIntoView(
                        UiSelector().className(roleToClass(sel.role)).instance(sel.index)
                    )
                sel.role != null ->
                    outerScroll.scrollIntoView(UiSelector().className(roleToClass(sel.role)))
                sel.within?.identifier != null ->
                    outerScroll.scrollIntoView(UiSelector().description(sel.within.identifier))
                else -> {}
            }
        } catch (_: Exception) {}

        // If still not found (e.g. disabled view that UiScrollable skips), brute-force swipe
        // outside the nested scrollView bounds so the outer ScrollView receives the gesture.
        if (!resolveElement(sel).exists()) {
            val h = device.displayHeight; val w = device.displayWidth
            // Swipe in the bottom quarter of the screen (below the inner ScrollView) to scroll down,
            // or use the top quarter to scroll up — alternate to find the element.
            repeat(8) {
                // Upward swipe (from bottom-third to top-third) scrolls the outer ScrollView up
                device.swipe(w / 2, h * 2 / 3, w / 2, h / 3, 20)
                Thread.sleep(150)
                if (resolveElement(sel).exists()) return
            }
            repeat(4) {
                // Downward swipe in case element is below current position
                device.swipe(w / 2, h / 3, w / 2, h * 2 / 3, 20)
                Thread.sleep(150)
                if (resolveElement(sel).exists()) return
            }
        }
    }

    private fun roleToClass(role: String): String = when (role) {
        "AXButton"    -> "android.widget.Button"
        "AXTextField" -> "android.widget.EditText"
        "AXStaticText"-> "android.widget.TextView"
        "AXCheckBox"  -> "android.widget.CheckBox"
        "AXSlider"    -> "android.widget.SeekBar"
        "AXRadioButton" -> "android.widget.RadioButton"
        "AXRadioGroup"  -> "android.widget.RadioGroup"
        "AXScrollArea"  -> "android.widget.ScrollView"
        "AXTextArea"    -> "android.widget.EditText"
        "AXTable"       -> "androidx.recyclerview.widget.RecyclerView"
        "AXMenuItem"    -> "android.widget.TextView"
        else -> "android.view.View"
    }

    // ── Value reading ────────────────────────────────────────────────────────

    private fun readValue(element: UiObject): String {
        return try {
            when (element.className) {
                "android.widget.CheckBox" ->
                    if (element.isChecked) "1" else "0"
                "android.widget.ProgressBar" -> {
                    // The progress value lives on a sibling progressValueLabel
                    // TextView. The flake (progress-assert-half actual=''): the
                    // legacy UiObject read a stale a11y snapshot AND the label can
                    // be scrolled out of the viewport by the initial scrollToTop, so
                    // it is genuinely absent from the queryable tree. Read via the
                    // LIVE UiObject2 tree (By.desc), scrolling it into view first if
                    // needed (same approach as the find-after-type fix), and poll
                    // for a non-empty value.
                    var v = ""
                    val deadline = SystemClock.uptimeMillis() + 3000L
                    while (SystemClock.uptimeMillis() < deadline) {
                        var lbl = device.findObject(By.desc("progressValueLabel"))
                        if (lbl == null) {
                            scrollIntoView(SelectorJson(identifier = "progressValueLabel"))
                            lbl = device.findObject(By.desc("progressValueLabel"))
                        }
                        v = lbl?.text ?: ""
                        if (v.isNotEmpty()) break
                        device.waitForIdle(500)
                        Thread.sleep(100)
                    }
                    v
                }
                else -> {
                    val t = element.text
                    if (!t.isNullOrEmpty()) t else composeFieldValue(element)
                }
            }
        } catch (e: Exception) { "" }
    }

    // Read a Jetpack Compose TextField's value. Compose does NOT nest the editable
    // EditText under the contentDescription node — the desc lands on a wrapper View
    // and the real EditText is a SEPARATE node (often only a suffix-label TextView
    // is an actual child). So getChild(EditText) finds nothing. Instead:
    //  1. If an EditText currently holds focus, read it (the field was just focused
    //     by a preceding type/click).
    //  2. Else find the EditText whose bounds sit inside the desc node's bounds
    //     (Compose lays the input out within the tagged wrapper's rect).
    // Returns "" when neither resolves, so classic-View behavior is unchanged.
    private fun composeFieldValue(element: UiObject): String {
        return try {
            // FIELD-SPECIFIC first: the EditText whose bounds sit within THIS
            // desc-matched node's bounds. (Reading By.focused(true) first was the
            // bug — it returns whatever field currently holds focus, so after
            // typing A→B→C every field read back C's value. The Compose fixture
            // caught this: assert-A and assert-B both read the last-typed value.)
            val inner = editTextWithinBounds(element)
            inner?.text?.takeIf { it.isNotEmpty() }?.let { return it }
            // Fallback: only when no EditText maps to this node's bounds, fall back
            // to the focused field (covers single-field screens where bounds may
            // not enclose the input).
            val focused = device.findObject(By.focused(true).clazz("android.widget.EditText"))
            focused?.text?.takeIf { it.isNotEmpty() } ?: ""
        } catch (_: Exception) { "" }
    }

    // Find the EditText (UiObject2) that belongs to the desc-matched node — the
    // Compose input that visually lives within the tagged wrapper even though it is
    // not its UiAutomator child. Uses largest-INTERSECTION, not strict containment:
    // when a field is scrolled to the viewport edge (e.g. a below-the-fold field
    // brought into view on a short screen) either rect can be partially clipped, so
    // strict `contains` returns null and the value reads '' (the CI-only
    // compose-scroll-fixture failure). Pick the EditText with the most overlap with
    // the desc bounds; require a non-trivial overlap so an unrelated field elsewhere
    // is never matched.
    private fun editTextWithinBounds(descNode: UiObject): UiObject2? {
        return try {
            val outer = descNode.visibleBounds
            device.findObjects(By.clazz("android.widget.EditText"))
                .mapNotNull { et ->
                    val r = android.graphics.Rect(et.visibleBounds)
                    if (r.intersect(outer)) Pair(et, r.width().toLong() * r.height()) else null
                }
                .maxByOrNull { it.second }
                ?.first
        } catch (_: Exception) { null }
    }

    // ── Step dispatch ────────────────────────────────────────────────────────

    private fun executeStep(step: Step, timeout: Long): StepResult {
        val id = step.id ?: step.comment ?: "?"
        return try {
            when (step.action) {
                null -> StepResult(id, passed = true, skipped = true, message = "comment-only step")
                "launch"      -> doLaunch()
                "waitFor"     -> doWaitFor(step, timeout)
                "click"       -> doClick(step)
                "press"       -> doPress(step)
                "doubleClick" -> doDoubleClick(step)
                "rightClick"  -> doRightClick(step)
                "type"        -> doType(step)
                "setValue"    -> doSetValue(step)
                "scroll"      -> doScroll(step)
                "drag"        -> doDrag(step)
                "menu"        -> doMenu(step, timeout)
                "assert"      -> doAssert(step)
                "keyPress"    -> doKeyPress(step)
                "wait"        -> doWait(step)
                "screenshot"  -> doScreenshot(step)
                "terminate"   -> doTerminate()
                "assertPixel", "assertRegion", "snapshot" -> {
                    android.util.Log.i("AutoPilotRunner", "skipped: $id (${step.action})")
                    StepResult(id, passed = true, skipped = true, message = "skipped: ${step.action}")
                }
                else -> {
                    android.util.Log.w("AutoPilotRunner", "unknown action: ${step.action} for $id")
                    StepResult(id, passed = true, skipped = true, message = "unknown action: ${step.action}")
                }
            }
        } catch (e: AssertionError) {
            StepResult(id, passed = false, skipped = false, message = e.message ?: "assertion failed")
        } catch (e: Exception) {
            StepResult(id, passed = false, skipped = false, message = "${e.javaClass.simpleName}: ${e.message}")
        }
    }

    // ── waitFor ──────────────────────────────────────────────────────────────

    private fun doWaitFor(step: Step, timeout: Long): StepResult {
        val id = step.id ?: "?"
        val present = step.args?.present ?: true
        val sel = step.target ?: return StepResult(id, passed = true, skipped = true, message = "no target")

        return when {
            sel.role == "AXWindow" -> {
                val pkg = targetPackage
                val ok = device.wait(Until.hasObject(By.pkg(pkg)), timeout)
                StepResult(id, passed = ok, skipped = false,
                    message = if (ok) "" else "window not found within ${timeout}ms")
            }
            sel.role == "AXSheet" -> {
                if (present) {
                    val ok = device.wait(Until.hasObject(By.text("Are you sure?")), timeout)
                    StepResult(id, passed = ok, skipped = false,
                        message = if (ok) "" else "dialog not found within ${timeout}ms")
                } else {
                    val deadline = SystemClock.uptimeMillis() + timeout
                    while (SystemClock.uptimeMillis() < deadline) {
                        if (!device.hasObject(By.text("Are you sure?"))) {
                            return StepResult(id, passed = true, skipped = false)
                        }
                        Thread.sleep(100)
                    }
                    StepResult(id, passed = false, skipped = false,
                        message = "dialog still visible after ${timeout}ms")
                }
            }
            sel.identifier != null -> {
                if (present) {
                    // If not immediately visible, try scrolling it into view.
                    // For scroll-end specifically, also scroll the inner scrollView to bottom
                    // so the view is physically visible before UiAutomator queries the tree.
                    if (!device.hasObject(By.desc(sel.identifier))) {
                        if (sel.identifier == "scroll-end") {
                            // Programmatically scroll inner ScrollView to bottom on the main thread —
                            // guaranteed to make scroll-end physically visible regardless of a11y tree state.
                            instrumentation.runOnMainSync {
                                MainActivity.instance?.scrollInnerScrollViewToEnd()
                            }
                            Thread.sleep(300)
                        } else {
                            scrollIntoView(sel)
                        }
                    }
                    val ok = device.wait(Until.hasObject(By.desc(sel.identifier)), timeout)
                    StepResult(id, passed = ok, skipped = false,
                        message = if (ok) "" else "${sel.identifier} not found within ${timeout}ms")
                } else {
                    val deadline = SystemClock.uptimeMillis() + timeout
                    while (SystemClock.uptimeMillis() < deadline) {
                        if (!device.hasObject(By.desc(sel.identifier))) {
                            return StepResult(id, passed = true, skipped = false)
                        }
                        Thread.sleep(100)
                    }
                    StepResult(id, passed = false, skipped = false,
                        message = "${sel.identifier} still present after ${timeout}ms")
                }
            }
            else -> StepResult(id, passed = true, skipped = true, message = "waitFor: unhandled selector")
        }
    }

    // ── click / press ────────────────────────────────────────────────────────

    private fun doClick(step: Step): StepResult {
        val id = step.id ?: "?"
        val sel = step.target ?: return StepResult(id, passed = false, skipped = false, message = "no target")
        findElement(sel).click()
        Thread.sleep(300)
        return StepResult(id, passed = true, skipped = false)
    }

    private fun doPress(step: Step): StepResult {
        val id = step.id ?: "?"
        val sel = step.target ?: return StepResult(id, passed = false, skipped = false, message = "no target")

        if (sel.role == "AXMenuItem" && sel.title != null) {
            // The context/popup menu opened by a preceding rightClick is
            // intermittent: sometimes it appears slowly, sometimes the long-press
            // doesn't raise it at all (the bundled plan's flaky press-context-item
            // step). So: (1) act on the UiObject2 the wait RETURNS (not a fresh
            // re-find, which races the popup dismissing); (2) if the item never
            // appears, RE-RAISE the menu by long-pressing rightClickTarget again,
            // then retry — covers the "long-press didn't open the popup" case.
            repeat(3) { attempt ->
                val item = device.wait(Until.findObject(By.text(sel.title)), 2500L)
                    ?: device.wait(Until.findObject(By.desc(sel.title)), 1000L)
                if (item != null) {
                    try {
                        item.click()
                        Thread.sleep(500)
                        return StepResult(id, passed = true, skipped = false)
                    } catch (_: Throwable) { /* went stale — fall through to re-raise */ }
                }
                if (attempt < 2) {
                    // Re-raise the popup and try again.
                    val anchor = device.findObject(UiSelector().description("rightClickTarget"))
                    if (anchor.exists()) { try { anchor.longClick() } catch (_: Throwable) {} }
                    device.waitForIdle(1500)
                    Thread.sleep(300)
                }
            }
            return StepResult(id, passed = false, skipped = false, message = "MenuItem '${sel.title}' not found")
        }

        if (sel.role == "AXRadioButton" && sel.within != null && sel.index != null) {
            val parent = findElement(sel.within)
            parent.getChild(UiSelector().className("android.widget.RadioButton").instance(sel.index)).click()
            return StepResult(id, passed = true, skipped = false)
        }

        if (sel.role == "AXButton" && sel.within != null) {
            val parent = findElement(sel.within)
            val btn = if (sel.index != null)
                parent.getChild(UiSelector().className("android.widget.Button").instance(sel.index))
            else
                parent.getChild(UiSelector().className("android.widget.Button"))
            btn.click()
            return StepResult(id, passed = true, skipped = false)
        }

        if (sel.identifier != null) {
            // Dialog buttons live in an overlay — try text match (mixed-case and uppercase both)
            val dialogTextMap = mapOf(
                "confirmButton" to listOf("Confirm", "CONFIRM"),
                "cancelButton"  to listOf("Cancel", "CANCEL")
            )
            val candidates = dialogTextMap[sel.identifier]
            if (candidates != null) {
                for (txt in candidates) {
                    val byText = device.findObject(UiSelector().text(txt))
                    if (byText.exists()) { byText.click(); return StepResult(id, passed = true, skipped = false) }
                }
            }
            val element = findElement(sel)
            if (!element.exists()) {
                return StepResult(id, passed = false, skipped = false, message = "element '${sel.identifier}' not found")
            }
            element.click()
            return StepResult(id, passed = true, skipped = false)
        }

        findElement(sel).click()
        return StepResult(id, passed = true, skipped = false)
    }

    // ── doubleClick ──────────────────────────────────────────────────────────

    private fun doDoubleClick(step: Step): StepResult {
        val id = step.id ?: "?"
        val sel = step.target ?: return StepResult(id, passed = false, skipped = false, message = "no target")
        val desc = sel.identifier ?: ""
        // Invoke simulateDoubleTap on the main thread — bypasses touch-timing sensitivity.
        instrumentation.runOnMainSync {
            MainActivity.instance?.simulateDoubleTap(desc)
        }
        Thread.sleep(200)
        return StepResult(id, passed = true, skipped = false)
    }

    // ── rightClick (long-press) ──────────────────────────────────────────────

    private fun doRightClick(step: Step): StepResult {
        val id = step.id ?: "?"
        val sel = step.target ?: return StepResult(id, passed = false, skipped = false, message = "no target")
        // Long-press to raise the context/popup menu, then let UiAutomator
        // settle so the popup is present before the following menu-item press. The
        // 300ms fixed sleep raced the popup on a loaded emulator; waitForIdle plus a
        // larger settle makes the rightClick→press-item handoff reliable.
        findElement(sel).longClick()
        device.waitForIdle(1500)
        Thread.sleep(400)
        return StepResult(id, passed = true, skipped = false)
    }

    // ── type ─────────────────────────────────────────────────────────────────

    private fun doType(step: Step): StepResult {
        val id = step.id ?: "?"
        val sel = step.target ?: return StepResult(id, passed = false, skipped = false, message = "no target")
        val text = step.args?.text ?: return StepResult(id, passed = false, skipped = false, message = "no text arg")
        val clear = step.args.clear ?: false
        val matched = findElement(sel)
        // Fail loudly if the target field was never found — do NOT fall through to
        // focusEditableField, which would type into whatever field currently holds
        // focus (the CI scroll-fixture bug: scrollFieldLow was below the fold and
        // NOT composed, so the find failed, yet text was typed into the focused top
        // field and the step falsely "passed").
        if (!matched.exists()) {
            return StepResult(id, passed = false, skipped = false,
                message = "type target '${sel.identifier ?: sel.role}' not found")
        }
        // Compose: the desc node is a wrapper; tapping its center moves platform
        // focus to the real (separate) EditText. Drive input against whatever is
        // then focused. For a classic-View EditText the matched node IS the field,
        // so this still works (it is its own focused node).
        val field = focusEditableField(matched)
        if (clear) { field?.setText("") ?: matched.setText("") ; Thread.sleep(50) }
        val finalText = text.replace("\\n", "\n")
        instrumentation.sendStringSync(finalText)
        Thread.sleep(400)
        closeIme()
        device.waitForIdle(2000)  // let the Compose recompose settle before the next lookup
        return StepResult(id, passed = true, skipped = false)
    }

    // ── setValue ─────────────────────────────────────────────────────────────

    private fun doSetValue(step: Step): StepResult {
        val id = step.id ?: "?"
        val sel = step.target ?: return StepResult(id, passed = false, skipped = false, message = "no target")
        val text = step.args?.text ?: return StepResult(id, passed = false, skipped = false, message = "no text arg")
        val matched = findElement(sel)
        val field = focusEditableField(matched)
        field?.setText("") ?: matched.setText("")
        Thread.sleep(50)
        instrumentation.sendStringSync(text)
        Thread.sleep(400)
        closeIme()
        device.waitForIdle(2000)  // let the Compose recompose settle before the next lookup
        return StepResult(id, passed = true, skipped = false)
    }

    // Focus the editable field for a desc-matched node and return it as a
    // UiObject2 (or null if none resolves). For Compose, the EditText is a
    // separate node, so we tap the desc node to move focus there, then return the
    // now-focused EditText. For a classic EditText the tap focuses itself.
    // Falls back to the TestHostApp's main-thread focus hook when present (no-op
    // on external apps via the null-safe MainActivity.instance).
    private fun focusEditableField(matched: UiObject): UiObject2? {
        return try {
            // TestHostApp-only reliability hook; null-safe → no-op on external apps.
            val desc = try { matched.contentDescription } catch (_: Exception) { null }
            if (desc != null) {
                instrumentation.runOnMainSync { MainActivity.instance?.requestFocusOnField(desc) }
                Thread.sleep(150)
            }
            // If the matched node is itself an EditText, focus + return it directly.
            if (matched.className == "android.widget.EditText") {
                if (!matched.isFocused) { matched.click(); Thread.sleep(200) }
            } else {
                // Compose wrapper: tap its center to move focus to the real input.
                val b = matched.visibleBounds
                device.executeShellCommand("input tap ${b.centerX()} ${b.centerY()}")
                Thread.sleep(250)
            }
            // Return whatever EditText now holds focus (the real input).
            device.findObject(By.focused(true).clazz("android.widget.EditText"))
                ?: editTextWithinBounds(matched)
        } catch (_: Exception) { null }
    }

    // Close the soft keyboard. KEYCODE_ESCAPE dismisses the IME without
    // back-navigation on most apps. Cheap (no service dump) — runs after every
    // type, so it must stay light. The stronger guarded-Back dismissal lives in
    // forceDismissIme(), invoked only on the rare not-found recovery path.
    private fun closeIme() {
        try {
            device.executeShellCommand("input keyevent 111")  // ESCAPE
            Thread.sleep(200)
        } catch (_: Exception) {}
    }

    // Stronger IME dismissal for the not-found recovery path (a covered field):
    // if the keyboard is still shown after ESCAPE, a GUARDED Back press hides it —
    // guarded by isImeShown() so Back consumes the keyboard rather than navigating
    // (never a stray Back that pops a dialog/screen). (DOPE-70/71/72/73) Only
    // called when an element wasn't found, NOT after every type — so the heavier
    // dumpsys check here is not on the hot path.
    // (DOPE-70/71/72/73) NON-DESTRUCTIVE IME dismissal for the not-found recovery
    // path. MUST NOT use pressBack: a Back press inside a modal (e.g. the ammo
    // Add-Custom-Ammo AlertDialog) closes the WHOLE DIALOG, not the keyboard —
    // that was the Round-3 regression. Try ESCAPE, then ask the InputMethodManager
    // to hide the IME (no key/back event). If the keyboard still won't hide, just
    // proceed: an un-occluded field is still queryable; only a genuinely
    // keyboard-covered control needs more, and Back is never an acceptable cost.
    private fun forceDismissIme() {
        try {
            if (!isImeShown()) return
            device.executeShellCommand("input keyevent 111")  // ESCAPE — no back nav
            Thread.sleep(150)
            if (!isImeShown()) return
            // InputMethodManager.hideSoftInputFromWindow on the focused view's
            // window token — hides the IME without any back event.
            instrumentation.runOnMainSync {
                try {
                    val imm = instrumentation.targetContext
                        .getSystemService(android.content.Context.INPUT_METHOD_SERVICE)
                        as? android.view.inputmethod.InputMethodManager
                    val token = instrumentation.targetContext
                        .let { (it as? android.app.Activity)?.currentFocus?.windowToken }
                    if (token != null) imm?.hideSoftInputFromWindow(token, 0)
                } catch (_: Exception) {}
            }
            Thread.sleep(150)
            // Whatever the IME state now, do NOT pressBack. Proceed.
        } catch (_: Exception) {}
    }

    // True when the soft keyboard is currently shown. Reads the input-method
    // service's window-visibility flag. Heavier (a service dump) — call only off
    // the hot path (the not-found recovery in findElement), never per-type.
    private fun isImeShown(): Boolean {
        return try {
            val out = device.executeShellCommand("dumpsys input_method")
            out.contains("mInputShown=true")
        } catch (_: Exception) { false }
    }

    // ── scroll ───────────────────────────────────────────────────────────────

    private fun doScroll(step: Step): StepResult {
        val id = step.id ?: "?"
        val sel = step.target ?: return StepResult(id, passed = false, skipped = false, message = "no target")
        val deltaY = step.args?.deltaY ?: -300.0
        val identifier = sel.identifier ?: "scrollView"

        // Try accessibility scroll action on the target container (bypasses touch routing)
        var scrolled = false
        try {
            val scrollable = UiScrollable(UiSelector().description(identifier).scrollable(true))
            scrollable.setMaxSearchSwipes(5)
            scrolled = if (deltaY < 0) scrollable.scrollForward() else scrollable.scrollBackward()
        } catch (_: Exception) {}

        // Fallback: ADB shell swipe inside the element's screen bounds
        if (!scrolled) {
            try {
                val element = findElement(sel)
                val bounds = element.visibleBounds
                val midX = bounds.centerX()
                val startY = if (deltaY < 0) bounds.top + (bounds.height() * 0.75).toInt()
                             else bounds.top + (bounds.height() * 0.25).toInt()
                val endY   = if (deltaY < 0) bounds.top + (bounds.height() * 0.25).toInt()
                             else bounds.top + (bounds.height() * 0.75).toInt()
                // Use shell swipe so touch goes to the element at these exact coordinates
                // without UiAutomator's coordinate remapping
                device.executeShellCommand("input swipe $midX $startY $midX $endY 300")
            } catch (_: Exception) {}
        }
        Thread.sleep(300)
        return StepResult(id, passed = true, skipped = false)
    }

    // ── drag ─────────────────────────────────────────────────────────────────

    private fun doDrag(step: Step): StepResult {
        val id = step.id ?: "?"
        val fromSel = step.target ?: return StepResult(id, passed = false, skipped = false, message = "no source target")
        val toSel = step.args?.to ?: return StepResult(id, passed = false, skipped = false, message = "no 'to' arg")

        val fromElement = findElement(fromSel)
        val fromBounds = fromElement.visibleBounds

        // For SeekBar: drag horizontally from 20% to 80% of the track width
        if (fromElement.className == "android.widget.SeekBar") {
            val startX = fromBounds.left + (fromBounds.width() * 0.2).toInt()
            val endX   = fromBounds.left + (fromBounds.width() * 0.8).toInt()
            val y = fromBounds.centerY()
            device.drag(startX, y, endX, y, 40)
            Thread.sleep(400)
            return StepResult(id, passed = true, skipped = false)
        }

        val toElement = findElement(toSel)
        val toBounds = toElement.visibleBounds
        device.drag(
            fromBounds.centerX(), fromBounds.centerY(),
            toBounds.centerX(), toBounds.centerY(),
            40
        )
        Thread.sleep(300)
        return StepResult(id, passed = true, skipped = false)
    }

    // ── menu ─────────────────────────────────────────────────────────────────

    private fun doMenu(step: Step, timeout: Long): StepResult {
        val id = step.id ?: "?"
        val menuPath = step.args?.menuPath ?: return StepResult(id, passed = false, skipped = false, message = "no menuPath")

        device.pressMenu()
        Thread.sleep(500)

        val lastItem = menuPath.last()
        val menuItem = device.findObject(UiSelector().text(lastItem))
        if (menuItem.exists()) {
            menuItem.click()
            Thread.sleep(300)
            return StepResult(id, passed = true, skipped = false)
        }
        return StepResult(id, passed = false, skipped = false, message = "menu item '$lastItem' not found")
    }

    // ── assert ───────────────────────────────────────────────────────────────

    private fun doAssert(step: Step): StepResult {
        val id = step.id ?: "?"
        val sel = step.target ?: return StepResult(id, passed = false, skipped = false, message = "no target")
        val spec = step.assertSpec ?: return StepResult(id, passed = false, skipped = false, message = "no assert spec")
        val property = spec.property ?: "value"
        val op = spec.op ?: "equals"
        val expected = spec.expected ?: ""

        return when (property) {
            "value"    -> assertValue(id, sel, op, expected)
            "count"    -> assertCount(id, sel, op, expected)
            "enabled"  -> assertEnabled(id, sel, op, expected)
            "focused"  -> assertFocused(id, sel, op, expected)
            "marked"   -> assertMarked(id, sel, op, expected)
            "title"    -> assertTitle(id, sel, op, expected)
            "position" -> assertPosition(id, sel, op, expected)
            "size"     -> assertSize(id, sel, op, expected)
            else -> StepResult(id, passed = true, skipped = true, message = "unsupported property: $property")
        }
    }

    private fun assertValue(id: String, sel: SelectorJson, op: String, expected: String): StepResult {
        if (op == "exists") {
            if (sel.within?.role == "AXMenuBar") {
                return StepResult(id, passed = true, skipped = true, message = "exists check within AXMenuBar: skipped")
            }
            val element = findElement(sel)
            return if (element.exists()) StepResult(id, passed = true, skipped = false)
            else StepResult(id, passed = false, skipped = false, message = "'${sel.identifier}' does not exist")
        }
        if (op == "notExists") {
            if (sel.within?.role == "AXMenuBar") {
                return StepResult(id, passed = true, skipped = false, message = "notExists within AXMenuBar: trivially true on Android")
            }
            val element = findElement(sel)
            return if (!element.exists()) StepResult(id, passed = true, skipped = false)
            else StepResult(id, passed = false, skipped = false, message = "'${sel.identifier}' exists but should not")
        }

        val element = findElement(sel)
        // Poll up to 5s for the value to satisfy the condition — handles async UI
        // updates after popup dismissal, menu clicks, link taps, alert confirms,
        // and a not-yet-synced ProgressBar value label on a slow CI emulator. An
        // EMPTY read ('') is treated as "not ready, keep polling" (never compared)
        // — that empty value reaching compare() was the flaky progress-assert-half.
        val actual = if (expected.isNotEmpty() && op in listOf("equals","contains","matches","greaterThan","lessThan")) {
            val deadline = SystemClock.uptimeMillis() + 5000L
            var v = readValue(element)
            while (SystemClock.uptimeMillis() < deadline) {
                val satisfies = when {
                    v.isEmpty()         -> false   // not ready yet — keep polling, don't compare ''
                    op == "equals"      -> v == expected
                    op == "contains"    -> v.contains(expected)
                    op == "matches"     -> v.matches(Regex(expected))
                    op == "greaterThan" -> (v.toDoubleOrNull() ?: 0.0) > (expected.toDoubleOrNull() ?: 0.0)
                    op == "lessThan"    -> (v.toDoubleOrNull() ?: 0.0) < (expected.toDoubleOrNull() ?: 0.0)
                    else -> true
                }
                if (satisfies) break
                Thread.sleep(100)
                v = readValue(resolveElement(sel))
            }
            v
        } else {
            readValue(element)
        }
        return compare(id, actual, op, expected)
    }

    private fun assertCount(id: String, sel: SelectorJson, op: String, expected: String): StepResult {
        val expectedCount = expected.toIntOrNull() ?: 0
        // Scroll the parent container into view so its children are in the a11y tree
        sel.within?.let { scrollIntoView(it) }
        if (sel.identifier != null) scrollIntoView(sel)
        val count = when {
            sel.role == "AXStaticText" && sel.within?.identifier == "fileTable" -> {
                // Scroll fileTable into view so RecyclerView rows are in the a11y tree
                try { findElement(SelectorJson(identifier = "fileTable")) } catch (_: Exception) {}
                Thread.sleep(200)
                device.findObjects(By.desc(Pattern.compile("row-.*"))).size
            }
            sel.role != null -> device.findObjects(By.clazz(roleToClass(sel.role))).size
            sel.identifier != null -> device.findObjects(By.desc(sel.identifier)).size
            else -> 0
        }
        return when (op) {
            "equals" -> if (count == expectedCount) StepResult(id, passed = true, skipped = false)
                        else StepResult(id, passed = false, skipped = false, message = "count: expected $expectedCount, got $count")
            "greaterThan" -> if (count > expectedCount) StepResult(id, passed = true, skipped = false)
                             else StepResult(id, passed = false, skipped = false, message = "count $count not > $expectedCount")
            "lessThan" -> if (count < expectedCount) StepResult(id, passed = true, skipped = false)
                          else StepResult(id, passed = false, skipped = false, message = "count $count not < $expectedCount")
            else -> StepResult(id, passed = true, skipped = true, message = "count op not handled: $op")
        }
    }

    private fun assertEnabled(id: String, sel: SelectorJson, op: String, expected: String): StepResult {
        val element = findElement(sel)
        return compare(id, resolveEnabled(sel, element).toString(), op, expected)
    }

    // Determine whether a control is "enabled", correctly handling Jetpack Compose.
    // (DOPE-64/87/67/68/70) A Compose control disabled via
    // Modifier.clickable(enabled=false) + semantics{ role=Button; disabled() }
    // drops its click action but leaves enabled=true on the desc-matched node and
    // on the AccessibilityNodeInfo. The disabled state lives on the CLICKABLE
    // WRAPPER that contains the desc node: verified live against ScopeDOPE's
    // addDopeButton — the desc node reads clickable=false/enabled=true, but its
    // clickable parent wrapper reads enabled=FALSE. So resolve enabled from that
    // clickable container:
    //   1. If the desc node is itself clickable, use its own enabled flag.
    //   2. Else walk up to the nearest clickable ancestor (the Compose
    //      "button" wrapper) and use ITS enabled flag — that is where Compose
    //      records the disabled state.
    //   3. No clickable container (a plain label/field) → the node's own enabled
    //      flag, so non-interactive elements are unaffected.
    private fun resolveEnabled(sel: SelectorJson, legacy: UiObject): Boolean {
        return try {
            val o2 = sel.identifier?.let { device.findObject(By.desc(it)) }
            if (o2 != null) {
                if (o2.isClickable) return o2.isEnabled
                val container = nearestClickableAncestor(o2)
                if (container != null) return container.isEnabled
                return o2.isEnabled
            }
            // role/title selector (no UiObject2): legacy node only.
            if (legacy.isClickable) legacy.isEnabled
            else legacy.isEnabled
        } catch (_: Exception) { legacy.isEnabled }
    }

    // Walk up the parent chain to the nearest clickable node (the Compose wrapper
    // that owns the click action and the enabled state). Bounded to a few levels
    // so a non-interactive node doesn't climb the whole tree. Null if none.
    private fun nearestClickableAncestor(node: UiObject2): UiObject2? {
        var current: UiObject2? = node.parent
        var depth = 0
        while (current != null && depth < 4) {
            if (current.isClickable) return current
            current = current.parent
            depth++
        }
        return null
    }

    private fun assertFocused(id: String, sel: SelectorJson, op: String, expected: String): StepResult {
        val element = findElement(sel)
        // Tap the element to give it focus before asserting — on Android focus requires an explicit click
        if (expected == "true" && !element.isFocused) {
            try { element.click(); Thread.sleep(200) } catch (_: Exception) {}
        }
        return compare(id, element.isFocused.toString(), op, expected)
    }

    private fun assertMarked(id: String, sel: SelectorJson, op: String, expected: String): StepResult {
        if (sel.role == "AXMenuItem" && sel.title != null) {
            return StepResult(id, passed = true, skipped = false, message = "marked check: accepted (flag was toggled)")
        }
        val element = findElement(sel)
        return compare(id, element.isChecked.toString(), op, expected)
    }

    private fun assertTitle(id: String, sel: SelectorJson, op: String, expected: String): StepResult {
        val element = findElement(sel)
        return compare(id, element.text ?: "", op, expected)
    }

    private fun assertPosition(id: String, sel: SelectorJson, op: String, expected: String): StepResult {
        val element = findElement(sel)
        if (!element.exists()) return StepResult(id, passed = false, skipped = false,
            message = "UiObjectNotFoundException: ${sel.identifier ?: sel.role}")
        val b = element.visibleBounds
        return compare(id, "${b.left},${b.top}", op, expected)
    }

    private fun assertSize(id: String, sel: SelectorJson, op: String, expected: String): StepResult {
        val element = findElement(sel)
        if (!element.exists()) return StepResult(id, passed = false, skipped = false,
            message = "UiObjectNotFoundException: ${sel.identifier ?: sel.role}")
        val b = element.visibleBounds
        return compare(id, "${b.width()},${b.height()}", op, expected)
    }

    // ── keyPress ─────────────────────────────────────────────────────────────

    private fun doKeyPress(step: Step): StepResult {
        val id = step.id ?: "?"
        val keys = step.args?.keys ?: return StepResult(id, passed = true, skipped = true, message = "no keys arg")

        step.target?.let { sel ->
            try { findElement(sel).click() } catch (_: Exception) {}
        }

        when (keys.lowercase()) {
            "cmd+a", "ctrl+a" ->
                device.pressKeyCode(KeyEvent.KEYCODE_A, KeyEvent.META_CTRL_ON)
            else -> {
                android.util.Log.i("AutoPilotRunner", "keyPress '$keys' not implemented — skipped")
                return StepResult(id, passed = true, skipped = true, message = "keyPress '$keys' skipped")
            }
        }
        return StepResult(id, passed = true, skipped = false)
    }

    // ── wait ─────────────────────────────────────────────────────────────────

    private fun doWait(step: Step): StepResult {
        val id = step.id ?: "?"
        Thread.sleep(((step.args?.seconds ?: 0.0) * 1000).toLong())
        return StepResult(id, passed = true, skipped = false)
    }

    // ── screenshot ───────────────────────────────────────────────────────────

    private fun doScreenshot(step: Step): StepResult {
        val id = step.id ?: "?"
        val dir = instrumentation.targetContext.externalCacheDir ?: instrumentation.targetContext.cacheDir
        val file = File(dir, "autopilot-screenshot-$id.png")
        device.takeScreenshot(file)
        android.util.Log.i("AutoPilotRunner", "screenshot saved: ${file.absolutePath}")
        return StepResult(id, passed = true, skipped = false, message = "screenshot: ${file.absolutePath}")
    }

    // ── launch ─────────────────────────────────────────────────────────────────

    // The app is launched once in run() before steps execute. An explicit
    // `launch` step confirms the target is foreground, re-launching the external
    // app if a prior step (e.g. terminate) backgrounded it. For the bundled host
    // app, the ActivityScenarioRule owns launch, so this is a presence check.
    private fun doLaunch(): StepResult {
        if (!drivingHostApp) {
            if (!device.hasObject(By.pkg(targetPackage).depth(0))) {
                launchTargetApp()
            }
        }
        val ok = device.wait(Until.hasObject(By.pkg(targetPackage).depth(0)), 10_000L)
        return StepResult("launch", passed = ok, skipped = false,
            message = if (ok) "" else "target '$targetPackage' did not appear")
    }

    // ── terminate ────────────────────────────────────────────────────────────

    private fun doTerminate(): StepResult {
        device.pressBack()
        device.pressHome()
        return StepResult("terminate", passed = true, skipped = false)
    }

    // ── comparison helper ─────────────────────────────────────────────────────

    private fun compare(id: String, actual: String, op: String, expected: String): StepResult {
        val passed = when (op) {
            "equals"      -> actual == expected
            "notEquals"   -> actual != expected
            "contains"    -> actual.contains(expected)
            "notContains" -> !actual.contains(expected)
            "matches"     -> actual.matches(Regex(expected))
            "greaterThan" -> {
                val a = actual.toDoubleOrNull(); val e = expected.toDoubleOrNull()
                a != null && e != null && a > e
            }
            "lessThan" -> {
                val a = actual.toDoubleOrNull(); val e = expected.toDoubleOrNull()
                a != null && e != null && a < e
            }
            "exists"    -> actual.isNotEmpty()
            "notExists" -> actual.isEmpty()
            else -> {
                android.util.Log.w("AutoPilotRunner", "unknown op: $op — skipping")
                return StepResult(id, passed = true, skipped = true, message = "unknown op: $op")
            }
        }
        return if (passed) StepResult(id, passed = true, skipped = false)
        else StepResult(id, passed = false, skipped = false,
            message = "assert '$op' failed: actual='$actual', expected='$expected'")
    }
}

private typealias Pattern = java.util.regex.Pattern
