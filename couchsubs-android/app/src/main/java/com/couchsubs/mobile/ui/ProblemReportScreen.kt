// SPDX-License-Identifier: AGPL-3.0-only
package com.couchsubs.mobile.ui

import android.widget.Toast
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Switch
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import com.couchsubs.mobile.R
import com.couchsubs.mobile.Services
import com.couchsubs.mobile.reporting.ProblemReporter
import kotlinx.coroutines.launch
import java.io.File

@Composable
fun ProblemReportScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val active = remember { java.util.concurrent.atomic.AtomicBoolean(true) }
    DisposableEffect(Unit) { onDispose { active.set(false) } }
    var description by rememberSaveable { mutableStateOf("") }
    var name by rememberSaveable { mutableStateOf("") }
    var attach by rememberSaveable { mutableStateOf(true) }
    var sending by remember { mutableStateOf(false) }
    var failed by remember { mutableStateOf(false) }
    var pendingPath by rememberSaveable { mutableStateOf<String?>(null) }
    // Sending belongs to Services.scope: leaving the screen does not cancel the persisted report.
    ScreenScaffold(top = { TopBar(stringResource(R.string.report_title), onBack = onBack) }, bottom = {
        if (sending) CircularProgressIndicator()
        PrimaryButton(stringResource(R.string.report_send), enabled = description.isNotBlank() && !sending) {
            sending = true
            failed = false
            val message = description
            val sender = name
            val includeLog = attach
            Services.scope.launch(kotlinx.coroutines.Dispatchers.Main) {
                val (success, file) = ProblemReporter.user(message, sender, includeLog, pendingPath?.let(::File))
                pendingPath = file?.path
                sending = false
                if (success) {
                    Toast.makeText(context, R.string.report_sent, Toast.LENGTH_LONG).show()
                    if (active.get()) onBack()
                } else failed = true
            }
        }
    }) {
        Field(description, { if (!sending) { description = it.take(4000) } }, stringResource(R.string.report_description),
            lines = 5, placeholder = stringResource(R.string.report_hint))
        Field(name, { if (!sending) name = it.take(80) }, stringResource(R.string.report_name))
        RowItem(stringResource(R.string.report_attach)) { Switch(attach, { if (!sending) attach = it }) }
        if (failed) NoteTile(stringResource(R.string.report_failed))
    }
}

@Composable
fun SupportSettings(onReport: () -> Unit) {
    var automatic by remember { mutableStateOf(ProblemReporter.automatic) }
    var improve by remember { mutableStateOf(ProblemReporter.improveTranslation) }
    Tile(Modifier.fillMaxWidth()) {
        TileLabel(stringResource(R.string.report_support))
        RowItem(stringResource(R.string.report_automatic), stringResource(R.string.report_automatic_hint)) {
            Switch(automatic, { automatic = it; ProblemReporter.automatic = it })
        }
        RowItem(stringResource(R.string.report_improve), stringResource(R.string.report_improve_hint)) {
            Switch(improve, { improve = it; ProblemReporter.improveTranslation = it })
        }
        if (ProblemReporter.configured) RowItem(stringResource(R.string.report_title), onClick = onReport)
    }
}
