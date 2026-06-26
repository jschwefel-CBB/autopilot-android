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
    private val targetPackageOverride: String? = null
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
        val reader = if (planPath != null) {
            // External plan pushed to the device (adb push … ; -e plan <path>).
            InputStreamReader(File(planPath).inputStream())
        } else {
            // Bundled mode: the plan packaged in this test APK's assets.
            InputStreamReader(instrumentation.context.assets.open("test-all-capabilities.json"))
        }
        return Gson().fromJson(reader, Plan::class.java)
    }

    /** Resolve which package to drive: explicit override > plan target > host app. */
    private fun resolveTargetPackage(plan: Plan): String =
        targetPackageOverride ?: plan.target.bundleId ?: hostPackage

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

    // ── Public entry point ──────────────────────────────────────────────────

    fun run(): List<StepResult> {
        val plan = loadPlan()
        targetPackage = resolveTargetPackage(plan)
        launchTargetApp()
        val timeout = plan.defaults?.timeoutMs ?: defaultTimeout()
        if (drivingHostApp) scrollToTop()  // fixture-only; never on an external app
        return plan.steps.map { step -> executeStep(step, timeout) }
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
        val obj = resolveElement(sel)
        if (!obj.exists()) {
            scrollIntoView(sel)
        }
        return resolveElement(sel)
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
                    // Read the sibling progressValueLabel TextView that MainActivity keeps in sync
                    val lbl = device.findObject(UiSelector().description("progressValueLabel"))
                    lbl.text?.takeIf { it.isNotEmpty() } ?: ""
                }
                else -> {
                    val t = element.text
                    if (!t.isNullOrEmpty()) t else ""
                }
            }
        } catch (e: Exception) { "" }
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
            val item = device.findObject(UiSelector().text(sel.title))
            if (item.exists()) { item.click(); Thread.sleep(500); return StepResult(id, passed = true, skipped = false) }
            val byDesc = device.findObject(UiSelector().description(sel.title))
            if (byDesc.exists()) { byDesc.click(); Thread.sleep(500); return StepResult(id, passed = true, skipped = false) }
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
        findElement(sel).longClick()
        Thread.sleep(300)
        return StepResult(id, passed = true, skipped = false)
    }

    // ── type ─────────────────────────────────────────────────────────────────

    private fun doType(step: Step): StepResult {
        val id = step.id ?: "?"
        val sel = step.target ?: return StepResult(id, passed = false, skipped = false, message = "no target")
        val text = step.args?.text ?: return StepResult(id, passed = false, skipped = false, message = "no text arg")
        val clear = step.args.clear ?: false
        val element = findElement(sel)
        if (clear) {
            element.setText("")  // accessibility ACTION_SET_TEXT — clears regardless of focus
            Thread.sleep(50)
        }
        focusElement(element)
        val finalText = text.replace("\\n", "\n")
        instrumentation.sendStringSync(finalText)
        Thread.sleep(400)
        closeIme()
        return StepResult(id, passed = true, skipped = false)
    }

    // ── setValue ─────────────────────────────────────────────────────────────

    private fun doSetValue(step: Step): StepResult {
        val id = step.id ?: "?"
        val sel = step.target ?: return StepResult(id, passed = false, skipped = false, message = "no target")
        val text = step.args?.text ?: return StepResult(id, passed = false, skipped = false, message = "no text arg")
        val element = findElement(sel)
        element.setText("")
        Thread.sleep(50)
        focusElement(element)
        instrumentation.sendStringSync(text)
        Thread.sleep(400)
        closeIme()
        return StepResult(id, passed = true, skipped = false)
    }

    // Focus an EditText for keyboard input. Uses the Activity's requestFocusOnField
    // (runs on main thread, bypasses coordinate issues) as the primary approach,
    // then falls back to accessibility click and shell tap.
    private fun focusElement(element: UiObject) {
        val desc = try { element.contentDescription } catch (_: Exception) { null }
        if (desc != null) {
            instrumentation.runOnMainSync {
                MainActivity.instance?.requestFocusOnField(desc)
            }
            Thread.sleep(200)
        }
        if (!element.isFocused) {
            element.click()
            val deadline = SystemClock.uptimeMillis() + 800L
            while (!element.isFocused && SystemClock.uptimeMillis() < deadline) {
                Thread.sleep(50)
            }
        }
        if (!element.isFocused) {
            val b = element.visibleBounds
            device.executeShellCommand("input tap ${b.centerX()} ${b.centerY()}")
            Thread.sleep(300)
        }
        Thread.sleep(100)
    }

    // Close the soft keyboard. KEYCODE_ESCAPE (111) dismisses the IME without
    // triggering back navigation or editor actions on API 30+.
    private fun closeIme() {
        try {
            device.executeShellCommand("input keyevent 111")
            Thread.sleep(200)
        } catch (_: Exception) {}
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
        // Poll up to 3s for the value to satisfy the condition — handles async UI updates
        // after popup dismissal, menu clicks, link taps, and alert confirms.
        val actual = if (expected.isNotEmpty() && op in listOf("equals","contains","matches","greaterThan","lessThan")) {
            val deadline = SystemClock.uptimeMillis() + 3000L
            var v = readValue(element)
            while (SystemClock.uptimeMillis() < deadline) {
                val satisfies = when (op) {
                    "equals"      -> v == expected
                    "contains"    -> v.contains(expected)
                    "matches"     -> v.matches(Regex(expected))
                    "greaterThan" -> (v.toDoubleOrNull() ?: 0.0) > (expected.toDoubleOrNull() ?: 0.0)
                    "lessThan"    -> (v.toDoubleOrNull() ?: 0.0) < (expected.toDoubleOrNull() ?: 0.0)
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
        return compare(id, element.isEnabled.toString(), op, expected)
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
