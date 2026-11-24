// SPDX-License-Identifier: AGPL-3.0-only
// Minimal rescue screen shown when the same versionCode fails to start twice in a row.
// Skips Services, packs and translation so the updater always remains reachable.
package com.couchsubs.mobile.ui

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import com.couchsubs.mobile.R
import com.couchsubs.mobile.update.CheckResult
import com.couchsubs.mobile.update.UpdateController
import com.couchsubs.mobile.update.UpdatePolicy
import com.couchsubs.mobile.update.UpdateState

@Composable
fun RescueScreen(onRetry: () -> Unit) {
    val context = LocalContext.current
    val state by UpdateController.state.collectAsState()
    val english = context.resources.getBoolean(R.bool.ui_english)
    var checking by remember { mutableStateOf(true) }
    var result by remember { mutableStateOf<CheckResult?>(null) }

    LaunchedEffect(Unit) {
        checking = true
        result = UpdateController.check(context.applicationContext, manual = true)
        checking = false
    }

    ScreenScaffold(
        top = { TopBar() },
        bottom = {
            when (val shown = state) {
                is UpdateState.Available -> PrimaryButton(stringResource(R.string.rescue_update)) { UpdateController.startUpdate(context) }
                is UpdateState.NeedsPermission -> PrimaryButton(stringResource(R.string.update_open_settings)) { openRescueInstallPermission(context) }
                is UpdateState.Failed -> PrimaryButton(stringResource(R.string.rescue_update)) { UpdateController.startUpdate(context) }
                else -> Unit
            }
            SecondaryButton(stringResource(R.string.update_retry), onClick = onRetry)
        },
    ) {
        Text(stringResource(R.string.rescue_body), style = Tokens.Type.body, color = Tokens.Color.text)
        when (val shown = state) {
            UpdateState.Idle -> {
                if (checking) {
                    CircularProgressIndicator(color = Tokens.Color.accent)
                } else if (result == CheckResult.UP_TO_DATE) {
                    Text(stringResource(R.string.update_up_to_date), style = Tokens.Type.body, color = Tokens.Color.secondary)
                } else if (result == CheckResult.FAILED) {
                    Text(stringResource(R.string.update_check_failed), style = Tokens.Type.body, color = Tokens.Color.secondary)
                }
            }
            is UpdateState.Available -> {
                Text(
                    stringResource(R.string.update_version, "\u2068${shown.info.versionName}\u2069"),
                    style = Tokens.Type.headline,
                    color = Tokens.Color.text,
                )
                val notes = shown.info.notes(english)
                if (notes.isNotBlank()) Text(notes, style = Tokens.Type.body, color = Tokens.Color.secondary)
            }
            is UpdateState.Downloading -> {
                val percent = UpdatePolicy.percent(shown.bytes, shown.info.sizeBytes)
                Text(stringResource(R.string.update_downloading), style = Tokens.Type.body, color = Tokens.Color.secondary)
                LinearProgressIndicator(
                    progress = { percent / 100f },
                    modifier = Modifier.fillMaxWidth(),
                    color = Tokens.Color.accent,
                    trackColor = Tokens.Color.border,
                )
                Text(
                    stringResource(
                        R.string.update_progress,
                        percent,
                        UpdatePolicy.megabytes(shown.bytes),
                        UpdatePolicy.megabytes(shown.info.sizeBytes),
                    ),
                    style = Tokens.Type.caption,
                    color = Tokens.Color.secondary,
                )
            }
            is UpdateState.NeedsPermission -> {
                Text(stringResource(R.string.update_permission), style = Tokens.Type.body, color = Tokens.Color.secondary)
            }
            is UpdateState.Installing -> {
                CircularProgressIndicator(color = Tokens.Color.accent)
                Text(stringResource(R.string.update_installing), style = Tokens.Type.body, color = Tokens.Color.secondary)
            }
            is UpdateState.Failed -> {
                val reason = stringResource(
                    when (shown.reason) {
                        UpdateState.Reason.NETWORK -> R.string.update_failed_network
                        UpdateState.Reason.CORRUPT -> R.string.update_failed_corrupt
                        UpdateState.Reason.INSTALL -> R.string.update_failed_install
                    },
                )
                Text(
                    reason + (shown.detail?.takeIf { shown.reason == UpdateState.Reason.INSTALL && it.isNotBlank() }?.let { " ($it)" } ?: ""),
                    style = Tokens.Type.body,
                    color = Tokens.Color.danger,
                )
            }
        }
    }
}

private fun openRescueInstallPermission(context: Context) {
    try {
        context.startActivity(
            Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${context.packageName}"))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        )
    } catch (error: ActivityNotFoundException) {
        android.util.Log.w("TargamgiUpdate", "unknown-sources settings page not found", error)
    }
}
