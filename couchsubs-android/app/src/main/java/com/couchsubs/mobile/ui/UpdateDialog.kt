// SPDX-License-Identifier: AGPL-3.0-only
// Over-the-air update UI: one dialog in the app's design that follows UpdateController.state (available, downloading with a
// progress bar, permission, installing, failed), and the "Check for updates" row in Settings.
package com.couchsubs.mobile.ui

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.couchsubs.mobile.R
import com.couchsubs.mobile.update.CheckResult
import com.couchsubs.mobile.update.UpdateController
import com.couchsubs.mobile.update.UpdateInfo
import com.couchsubs.mobile.update.UpdatePolicy
import com.couchsubs.mobile.update.UpdateState
import kotlinx.coroutines.launch

/** Shown above every screen (MainActivity); nothing is drawn while there is no update. */
@Composable
fun UpdateHost() {
    val context = LocalContext.current
    val state by UpdateController.state.collectAsState()
    val english = context.resources.getBoolean(R.bool.ui_english)
    when (val shown = state) {
        UpdateState.Idle -> Unit
        is UpdateState.Available -> UpdateAlert(
            confirm = stringResource(R.string.update_now), onConfirm = { UpdateController.startUpdate(context) },
            dismiss = stringResource(R.string.update_later), onDismiss = UpdateController::later,
        ) { Release(shown.info, english) }
        is UpdateState.Downloading -> UpdateAlert(
            dismiss = stringResource(R.string.cancel), onDismiss = UpdateController::later, dismissible = false,
        ) {
            val percent = UpdatePolicy.percent(shown.bytes, shown.info.sizeBytes)
            Release(shown.info, english, notes = false)
            Text(stringResource(R.string.update_downloading), style = Tokens.Type.body, color = Tokens.Color.secondary)
            LinearProgressIndicator(
                progress = { percent / 100f }, modifier = Modifier.fillMaxWidth(),
                color = Tokens.Color.accent, trackColor = Tokens.Color.border,
            )
            Text(stringResource(R.string.update_progress, percent, UpdatePolicy.megabytes(shown.bytes), UpdatePolicy.megabytes(shown.info.sizeBytes)),
                style = Tokens.Type.caption, color = Tokens.Color.secondary)
        }
        is UpdateState.NeedsPermission -> UpdateAlert(
            confirm = stringResource(R.string.update_open_settings), onConfirm = { openInstallPermission(context) },
            dismiss = stringResource(R.string.update_later), onDismiss = UpdateController::later,
        ) {
            Release(shown.info, english, notes = false)
            Text(stringResource(R.string.update_permission), style = Tokens.Type.body, color = Tokens.Color.secondary)
        }
        is UpdateState.Installing -> UpdateAlert(
            dismiss = stringResource(R.string.close), onDismiss = UpdateController::later,
        ) {
            Release(shown.info, english, notes = false)
            CircularProgressIndicator(color = Tokens.Color.accent)
            Text(stringResource(R.string.update_installing), style = Tokens.Type.body, color = Tokens.Color.secondary)
        }
        is UpdateState.Failed -> UpdateAlert(
            title = stringResource(R.string.update_failed_title),
            confirm = stringResource(R.string.update_retry), onConfirm = { UpdateController.startUpdate(context) },
            dismiss = stringResource(R.string.close), onDismiss = UpdateController::later,
        ) {
            val reason = stringResource(when (shown.reason) {
                UpdateState.Reason.NETWORK -> R.string.update_failed_network
                UpdateState.Reason.CORRUPT -> R.string.update_failed_corrupt
                UpdateState.Reason.INSTALL -> R.string.update_failed_install
            })
            Text(reason + (shown.detail?.takeIf { shown.reason == UpdateState.Reason.INSTALL && it.isNotBlank() }?.let { " ($it)" } ?: ""),
                style = Tokens.Type.body, color = Tokens.Color.secondary)
        }
    }
}

/** Dialog in the app's design (same container, type and button colors as InfoDialog). */
@Composable
private fun UpdateAlert(
    dismiss: String,
    onDismiss: () -> Unit,
    title: String = stringResource(R.string.update_title),
    confirm: String? = null,
    onConfirm: () -> Unit = {},
    dismissible: Boolean = true,
    content: @Composable () -> Unit,
) {
    AlertDialog(
        // Tapping outside or Back counts as "Later", except while a download runs (only the Cancel button stops it).
        onDismissRequest = { if (dismissible) onDismiss() },
        containerColor = Tokens.Color.surface2,
        title = { Text(title, style = Tokens.Type.headline, color = Tokens.Color.text) },
        text = { Column(verticalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(Tokens.Space.inline)) { content() } },
        confirmButton = {
            if (confirm != null) TextButton(onConfirm) { Text(confirm, style = Tokens.Type.headline, color = Tokens.Color.accentText) }
        },
        dismissButton = { TextButton(onDismiss) { Text(dismiss, style = Tokens.Type.headline, color = Tokens.Color.secondary) } },
    )
}

/** Version line and (optionally) the release notes in the UI language. */
@Composable
private fun Release(info: UpdateInfo, english: Boolean, notes: Boolean = true) {
    // The version name is Latin text inside Arabic: isolated so the digits and dots keep their order.
    Text(stringResource(R.string.update_version, "\u2068${info.versionName}\u2069"), style = Tokens.Type.headline, color = Tokens.Color.text)
    val text = info.notes(english)
    if (notes && text.isNotBlank()) Column(Modifier.heightIn(max = 220.dp).verticalScroll(rememberScrollState())) {
        Text(text, style = Tokens.Type.body, color = Tokens.Color.secondary)
    }
}

/** Settings > "Install unknown apps" page of this app; the update continues when the user comes back (UpdateController.onResume). */
private fun openInstallPermission(context: Context) {
    try {
        context.startActivity(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${context.packageName}"))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    } catch (error: ActivityNotFoundException) {
        android.util.Log.w("TargamgiUpdate", "unknown-sources settings page not found", error)
    }
}

/** Settings row: runs the check by hand and says the result under the title. A newer release opens the dialog instead. */
@Composable
fun UpdateCheckRow() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var checking by remember { mutableStateOf(false) }
    var result by remember { mutableStateOf<CheckResult?>(null) }
    RowItem(
        stringResource(R.string.update_check),
        when {
            checking -> stringResource(R.string.update_checking)
            result == CheckResult.UP_TO_DATE -> stringResource(R.string.update_up_to_date)
            result == CheckResult.FAILED -> stringResource(R.string.update_check_failed)
            else -> null
        },
        onClick = {
            if (!checking) {
                checking = true
                scope.launch {
                    result = UpdateController.check(context.applicationContext, manual = true)
                    checking = false
                }
            }
        },
    )
}

/**
 * 1.0.48 part B: visible "Update app" button on the HOME screen, reusing the exact same UpdateController action.
 * Turns amber ("New update available") when a newer version is found, and shows a short message when up to date.
 */
@Composable
fun HomeUpdateButton() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val updateState by UpdateController.state.collectAsState()
    val updateAvailable by UpdateController.updateAvailable.collectAsState()
    var checking by remember { mutableStateOf(false) }
    var result by remember { mutableStateOf<CheckResult?>(null) }
    val hasUpdate = updateAvailable || updateState !is UpdateState.Idle
    val onClick: () -> Unit = {
        if (!checking) {
            checking = true
            result = null
            scope.launch {
                result = UpdateController.check(context.applicationContext, manual = true)
                checking = false
            }
        }
    }
    Column(verticalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(Tokens.Space.inline)) {
        if (hasUpdate) {
            PrimaryButton(
                text = stringResource(if (checking) R.string.update_checking else R.string.home_update_available),
                enabled = !checking,
                onClick = onClick,
            )
        } else {
            SecondaryButton(
                text = stringResource(if (checking) R.string.update_checking else R.string.home_update_button),
                enabled = !checking,
                onClick = onClick,
            )
        }
        val note = when (result) {
            CheckResult.UP_TO_DATE -> stringResource(R.string.home_update_up_to_date)
            CheckResult.FAILED -> stringResource(R.string.update_check_failed)
            else -> null
        }
        if (note != null) {
            Text(note, style = Tokens.Type.caption, color = Tokens.Color.secondary)
        }
    }
}
