package com.autopilot.testhostapp

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.node.RootForTest
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp

/**
 * Compose test fixtures reproducing the real Compose automation shapes under
 * AutoPilot's own CI 5x gate. Two modes via the "mode" intent extra:
 *
 *  - "dialog" (default): a NON-scrollable AlertDialog of OutlinedTextFields whose
 *    contentDescription is set via Modifier.semantics → desc on a non-focusable
 *    android.view.View wrapper. Reproduces find-after-type for an on-screen sibling.
 *
 *  - "scroll": a SCROLLABLE LazyColumn of MANY OutlinedTextFields with imePadding,
 *    so a lower field is below the fold once the keyboard is up. This reproduces the
 *    a real Compose app's below-the-fold-field shape that the non-scrollable dialog
 *    fixture did NOT — research's #1 hypothesis: a LazyColumn item below the fold is
 *    NOT composed, so it has no accessibility node and no wait can find it. The
 *    runner-side FIND-FAIL-DUMP (in AutoPilotRunner) reveals whether the node is
 *    absent (not composed) vs present-but-stale.
 */
class ComposeFixtureActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val mode = intent.getStringExtra("mode") ?: "dialog"
        setContent {
            EnableUiAutomatorAccessibility()
            when (mode) {
                "scroll" -> ScrollFixture()
                "churn" -> ChurnDialog()
                "wrapper" -> WrapperDialog()
                else -> FixtureDialog()
            }
        }
    }
}

/**
 * Compose batches accessibility events; UiAutomator's scroll detection relies on
 * those events, so on a headless / x86_64 emulator (CI) scrollUntil/scrollIntoView
 * sees "no movement" and bails even though the list CAN scroll — the exact
 * compose-scroll-fixture failure (list never moved in the CI dump). Forcing
 * accessibility on and flushing the batch interval to 0 makes UiAutomator detect
 * and drive the scroll reliably. (Documented Compose↔UiAutomator interop fix.)
 *
 * NOTE: this must be set in the APP UNDER TEST — an out-of-process UiAutomator run
 * cannot enable it for an app it does not control. Real Compose apps that want
 * reliable out-of-process scrolling (any installed app) must add the same override.
 */
@OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)
@Composable
private fun EnableUiAutomatorAccessibility() {
    val view = LocalView.current
    LaunchedEffect(view) {
        (view as? RootForTest)?.let { root ->
            try {
                root.forceAccessibilityForTesting(true)
                root.setAccessibilityEventBatchIntervalMillis(0L)
            } catch (_: Throwable) {
            }
        }
    }
}

private fun Modifier.descTag(tag: String): Modifier =
    this.semantics { contentDescription = tag }

@Composable
private fun FixtureDialog() {
    var fieldA by remember { mutableStateOf("") }
    var fieldB by remember { mutableStateOf("") }
    var fieldC by remember { mutableStateOf("") }
    var open by remember { mutableStateOf(true) }
    if (!open) return

    AlertDialog(
        onDismissRequest = { },
        confirmButton = {
            TextButton(onClick = { open = false }, modifier = Modifier.descTag("fixtureSaveButton")) {
                Text("Save")
            }
        },
        title = { Text("Compose Fixture") },
        text = {
            Column(modifier = Modifier.padding(4.dp)) {
                OutlinedTextField(fieldA, { fieldA = it }, label = { Text("Field A") },
                    modifier = Modifier.descTag("fixtureFieldA"))
                OutlinedTextField(fieldB, { fieldB = it }, label = { Text("Field B") },
                    modifier = Modifier.descTag("fixtureFieldB"))
                OutlinedTextField(fieldC, { fieldC = it }, label = { Text("Field C") },
                    modifier = Modifier.descTag("fixtureFieldC"))
            }
        }
    )
}

/**
 * Recomposition-churn variant: a NON-scrolling dialog (fields present + visible,
 * like a real Compose modal dialog per the external dump) but every keystroke
 * triggers HEAVY recomposition — cross-field validation + a derived "valid" state +
 * a recomposition counter shown in each label — producing a busy accessibility
 * event stream (research #1b). This targets the "present-but-stale" mechanism the
 * simple FixtureDialog (trivial state, idles instantly) does NOT reproduce: type
 * into churnFieldA, then immediately find sibling churnFieldB while the tree is
 * still churning. If the runner misses churnFieldB, the FIND-FAIL-DUMP shows
 * present-but-stale (cache) — the actual real-app failure mode, reproduced.
 */
@Composable
private fun ChurnDialog() {
    var a by remember { mutableStateOf("") }
    var b by remember { mutableStateOf("") }
    var c by remember { mutableStateOf("") }
    var d by remember { mutableStateOf("") }
    var open by remember { mutableStateOf(true) }
    if (!open) return
    // Derived/validation state recomputed every keystroke across all fields —
    // forces a broad recomposition + a11y content-change burst on each change.
    val allFilled = a.isNotEmpty() && b.isNotEmpty() && c.isNotEmpty() && d.isNotEmpty()
    val sum = (a.length + b.length + c.length + d.length)

    AlertDialog(
        onDismissRequest = { },
        confirmButton = {
            TextButton(
                onClick = { open = false },
                enabled = allFilled,
                modifier = Modifier.descTag("churnSaveButton")
            ) { Text(if (allFilled) "Save" else "Fill all") }
        },
        title = { Text("Churn $sum") },
        text = {
            Column(modifier = Modifier.imePadding().padding(4.dp)) {
                // Each label includes the live sum so EVERY field's node recomposes
                // on every keystroke into any field — a deliberately broad churn.
                OutlinedTextField(a, { a = it }, label = { Text("A $sum") },
                    modifier = Modifier.descTag("churnFieldA"))
                OutlinedTextField(b, { b = it }, isError = b.isEmpty(),
                    label = { Text("B $sum") }, modifier = Modifier.descTag("churnFieldB"))
                OutlinedTextField(c, { c = it }, isError = c.isEmpty(),
                    label = { Text("C $sum") }, modifier = Modifier.descTag("churnFieldC"))
                OutlinedTextField(d, { d = it }, isError = d.isEmpty(),
                    label = { Text("D $sum") }, modifier = Modifier.descTag("churnFieldD"))
            }
        }
    )
}

/**
 * Wrapper variant: the contentDescription is set on a Column WRAPPING each
 * OutlinedTextField (not on the field itself), so ContentDescription merges onto
 * the wrapper ancestor — a non-focusable android.view.View DISTINCT from the inner
 * EditText. This is a real Compose app's shape (a dump showed the desc on a
 * separate View node, not the EditText), which the other fixtures (desc directly
 * on the field) do NOT reproduce. find-after-type: type wrapperFieldA, then find
 * sibling wrapperFieldB by the wrapper desc and read its value (must come from the
 * inner EditText, not the wrapper).
 */
@Composable
private fun WrapperDialog() {
    var a by remember { mutableStateOf("") }
    var b by remember { mutableStateOf("") }
    var c by remember { mutableStateOf("") }
    var open by remember { mutableStateOf(true) }
    if (!open) return

    AlertDialog(
        onDismissRequest = { },
        confirmButton = {
            TextButton(onClick = { open = false }, modifier = Modifier.descTag("wrapperSaveButton")) {
                Text("Save")
            }
        },
        title = { Text("Wrapper Fixture") },
        text = {
            Column(modifier = Modifier.imePadding().padding(4.dp)) {
                // desc on the WRAPPING Column, NOT on the field → merges onto a
                // non-field ancestor View (real-app shape).
                Column(modifier = Modifier.descTag("wrapperFieldA")) {
                    OutlinedTextField(a, { a = it }, label = { Text("A") })
                }
                Column(modifier = Modifier.descTag("wrapperFieldB")) {
                    OutlinedTextField(b, { b = it }, label = { Text("B") })
                }
                Column(modifier = Modifier.descTag("wrapperFieldC")) {
                    OutlinedTextField(c, { c = it }, label = { Text("C") })
                }
            }
        }
    )
}

/**
 * Scrollable variant: many fields in a LazyColumn with imePadding. scrollFieldA is
 * at the top (always composed); scrollFieldLow is far down (id "scrollFieldLow"),
 * below the fold once the keyboard is up — the field a plan tries to reach after
 * typing into scrollFieldA.
 */
@Composable
private fun ScrollFixture() {
    val values = remember { mutableStateMapOf<String, String>() }
    // 12 fields; the one tagged "scrollFieldLow" sits deep in the list.
    val ids = buildList {
        add("scrollFieldA")
        repeat(9) { add("scrollFieldMid$it") }
        add("scrollFieldLow")
        add("scrollSaveField")
    }
    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .imePadding()
            .padding(16.dp)
    ) {
        items(ids) { id ->
            OutlinedTextField(
                value = values[id] ?: "",
                onValueChange = { values[id] = it },
                label = { Text(id) },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 12.dp)
                    .descTag(id)
            )
        }
    }
}
