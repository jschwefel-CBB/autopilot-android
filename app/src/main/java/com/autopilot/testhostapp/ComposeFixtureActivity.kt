package com.autopilot.testhostapp

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp

/**
 * Reproduces the ScopeDOPE Compose failure mode under AutoPilot's own CI 5x gate:
 * a NON-SCROLLABLE AlertDialog containing multiple OutlinedTextFields whose
 * contentDescription is set via Modifier.semantics — so the desc lands on a
 * non-focusable android.view.View wrapper (NOT a focusable EditText), exactly
 * like ScopeDOPE's caliber/name/muzzleVel fields.
 *
 * The bug this guards against: typing into field A leaves the subsequent lookup
 * of field B (a present, visible, View-wrapper-desc node) failing
 * UiObjectNotFoundException because the legacy UiObject read a stale a11y snapshot
 * after the Compose recomposition. The compose-fixture plan types into fieldA then
 * fieldB; if the find-after-type fix regresses, the plan fails and the 5x gate
 * goes red — instead of the failure only surfacing on a ScopeDOPE round.
 *
 * The dialog is opened automatically on launch so a plan reaches the fields with
 * no preamble. The dialog is intentionally NOT scrollable (fixed content), to
 * also assert the runner does not waste time / mask the miss with scroll attempts.
 */
class ComposeFixtureActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { FixtureDialog() }
    }
}

private fun Modifier.descTag(tag: String): Modifier =
    this.semantics { contentDescription = tag }

@androidx.compose.runtime.Composable
private fun FixtureDialog() {
    var fieldA by remember { mutableStateOf("") }
    var fieldB by remember { mutableStateOf("") }
    var fieldC by remember { mutableStateOf("") }
    var open by remember { mutableStateOf(true) }

    if (!open) return

    AlertDialog(
        onDismissRequest = { /* keep open for the test */ },
        confirmButton = {
            TextButton(
                onClick = { open = false },
                modifier = Modifier.descTag("fixtureSaveButton")
            ) { Text("Save") }
        },
        title = { Text("Compose Fixture") },
        text = {
            // Fixed, non-scrollable Column — mirrors the ammo Add-Custom-Ammo dialog.
            Column(modifier = Modifier.padding(4.dp)) {
                OutlinedTextField(
                    value = fieldA,
                    onValueChange = { fieldA = it },
                    label = { Text("Field A") },
                    modifier = Modifier.descTag("fixtureFieldA")
                )
                OutlinedTextField(
                    value = fieldB,
                    onValueChange = { fieldB = it },
                    label = { Text("Field B") },
                    modifier = Modifier.descTag("fixtureFieldB")
                )
                OutlinedTextField(
                    value = fieldC,
                    onValueChange = { fieldC = it },
                    label = { Text("Field C") },
                    modifier = Modifier.descTag("fixtureFieldC")
                )
            }
        }
    )
}
