// SPDX-License-Identifier: AGPL-3.0-only
// 1.0.48 "SubTV": the account in the UI. A status card (free trial / subscribed / permanent / ended) with "your account
// number", the "I have a code" box, the full screen that covers the app when the trial or plan ends, the blocking
// "update the app" screen, and the dismissible announcement. All decisions come from AccountRules (JVM-tested).
package com.couchsubs.mobile.ui

import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.window.DialogProperties
import com.couchsubs.mobile.BuildConfig
import com.couchsubs.mobile.R
import com.couchsubs.mobile.account.AccessKind
import com.couchsubs.mobile.account.AccountRules
import com.couchsubs.mobile.account.AccountScreen
import com.couchsubs.mobile.account.AccountStatus
import com.couchsubs.mobile.account.ActivityLog
import com.couchsubs.mobile.account.AppIntegrity
import com.couchsubs.mobile.account.RedeemProblem
import com.couchsubs.mobile.account.RedeemReply
import com.couchsubs.mobile.account.SecureData
import com.couchsubs.mobile.account.SubTvAccount
import com.couchsubs.mobile.update.UpdateController
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** The status line of the card and of the settings tile. */
@Composable
private fun statusLine(status: AccountStatus?): String {
    if (status == null) return stringResource(R.string.account_loading)
    return when (AccountRules.kind(status)) {
        AccessKind.TRIAL_NOT_STARTED -> stringResource(R.string.account_status_trial_not_started)
        AccessKind.TRIAL -> stringResource(R.string.account_status_trial, AccountRules.trialDaysLeft(status, System.currentTimeMillis()))
        AccessKind.SUBSCRIBED -> stringResource(R.string.account_status_subscribed, dateText(status.planUntil))
        AccessKind.PERMANENT -> stringResource(R.string.account_status_permanent)
        AccessKind.ENDED -> stringResource(R.string.account_status_ended)
    }
}

private fun dateText(iso: String?): String =
    AccountRules.epochMs(iso)?.let { java.text.DateFormat.getDateInstance(java.text.DateFormat.MEDIUM).format(java.util.Date(it)) } ?: iso.orEmpty()

/** "Your account number: XXXXXXXX" and a copy button; the owner finds the user by this number. Nothing before the first sign-in. */
@Composable
private fun AccountNumberRow() {
    val number by SubTvAccount.accountNumber.collectAsState()
    val context = LocalContext.current
    val shown = number ?: return
    var copied by remember { mutableStateOf(false) }
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Tokens.Space.inline)) {
        // The number is Latin text inside Arabic: isolated so its characters keep their order.
        Text(stringResource(R.string.account_number, "${Char(0x2068)}$shown${Char(0x2069)}"), style = Tokens.Type.body, color = Tokens.Color.text,
            modifier = Modifier.weight(1f))
        GhostButton(stringResource(if (copied) R.string.account_number_copied else R.string.account_number_copy)) {
            context.getSystemService(ClipboardManager::class.java)?.setPrimaryClip(ClipData.newPlainText(context.getString(R.string.app_name), shown))
            copied = true
        }
    }
}

/** Live 1-second ticking countdown for active trial or paid/code subscription; triggers refresh when hitting 0. */
@Composable
private fun RemainingCountdownLine(status: AccountStatus?) {
    if (status == null) return
    var nowMs by remember { mutableLongStateOf(System.currentTimeMillis()) }
    val rem = AccountRules.remainingTime(status, nowMs) ?: return
    LaunchedEffect(status.planUntil, status.trialStartedAt, status.plan) {
        var refreshedAtZero = false
        while (true) {
            nowMs = System.currentTimeMillis()
            val cur = AccountRules.remainingTime(status, nowMs)
            if (cur == null) break
            if (cur.totalMs <= 0L) {
                if (!refreshedAtZero) {
                    refreshedAtZero = true
                    SubTvAccount.refreshStatus()
                }
                break
            }
            delay(1_000L)
        }
    }
    val clockText = String.format(java.util.Locale.US, "%02d:%02d:%02d", rem.hours, rem.minutes, rem.seconds)
    Text(
        text = stringResource(R.string.account_countdown, rem.days, "\u2068$clockText\u2069"),
        style = Tokens.Type.captionNumeric,
        color = Tokens.Color.accentText,
    )
}

/** Home screen card: where the account stands, live countdown, and the account number. */
@Composable
fun AccountStatusCard() {
    val status by SubTvAccount.status.collectAsState()
    // The first read at app start may have found no connection: try again when the home screen opens.
    LaunchedEffect(Unit) { if (SubTvAccount.status.value == null) SubTvAccount.refreshStatus() }
    Tile(Modifier.fillMaxWidth()) {
        TileLabel(stringResource(R.string.account_title))
        val ended = status?.let { AccountRules.kind(it) == AccessKind.ENDED } == true
        Text(statusLine(status), style = Tokens.Type.headline, color = if (ended) Tokens.Color.danger else Tokens.Color.text)
        RemainingCountdownLine(status)
        AccountNumberRow()
    }
}

/** Maintenance message and the announcement (dismissible; a dismissed text stays hidden, a new one shows). */
@Composable
fun AccountBanners() {
    val status by SubTvAccount.status.collectAsState()
    var dismissed by remember { mutableStateOf(SubTvAccount.dismissedAnnouncement()) }
    val current = status ?: return
    val maintenanceFallback = stringResource(R.string.account_maintenance)
    if (current.maintenanceOn) NoteTile(current.maintenanceMessage.ifEmpty { maintenanceFallback })
    if (current.announcement.isNotEmpty() && current.announcement != dismissed) {
        Tile(Modifier.fillMaxWidth()) {
            Row(verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.spacedBy(Tokens.Space.inline)) {
                Text(current.announcement, style = Tokens.Type.body, color = Tokens.Color.text, modifier = Modifier.weight(1f))
                IconAction(Icons.Filled.Close, stringResource(R.string.close)) {
                    SubTvAccount.dismissAnnouncement(current.announcement)
                    dismissed = current.announcement
                }
            }
        }
    }
}

private fun redeemText(context: Context, problem: RedeemProblem): String = context.getString(when (problem) {
    RedeemProblem.INVALID_CODE -> R.string.redeem_invalid
    RedeemProblem.CODE_EXPIRED -> R.string.redeem_expired
    RedeemProblem.CODE_EXHAUSTED -> R.string.redeem_exhausted
    RedeemProblem.ALREADY_REDEEMED -> R.string.redeem_already_used
    RedeemProblem.ALREADY_PERMANENT -> R.string.redeem_already_permanent
    RedeemProblem.CODE_INACTIVE -> R.string.redeem_inactive
    RedeemProblem.NETWORK -> R.string.account_unreachable
    RedeemProblem.OTHER -> R.string.redeem_failed
})

/** "I have a code" + "Activate": the code the owner sent after a sale. On success the status is read again (SubTvAccount.redeem). */
@Composable
fun RedeemCodeBox() {
    val context = LocalContext.current
    var code by remember { mutableStateOf("") }
    var working by remember { mutableStateOf(false) }
    var problem by remember { mutableStateOf<String?>(null) }
    Column(verticalArrangement = Arrangement.spacedBy(Tokens.Space.inline)) {
        SectionTitle(stringResource(R.string.redeem_title))
        Field(code, { code = it; problem = null }, stringResource(R.string.redeem_field),
            keyboard = KeyboardOptions(capitalization = KeyboardCapitalization.Characters, keyboardType = KeyboardType.Ascii))
        problem?.let { Text(it, style = Tokens.Type.body, color = Tokens.Color.danger) }
        SecondaryButton(stringResource(if (working) R.string.redeem_working else R.string.redeem_button), enabled = code.isNotBlank() && !working) {
            working = true
            problem = null
            val app = context.applicationContext
            // Runs in the account's own scope: the success message must still show when this box leaves the screen
            // (the subscribe screen closes as soon as the status says the plan is active).
            SubTvAccount.redeemAsync(code) { reply ->
                working = false
                when (reply) {
                    is RedeemReply.Success -> {
                        code = ""
                        Toast.makeText(app, R.string.redeem_success, Toast.LENGTH_LONG).show()
                    }
                    is RedeemReply.Failure -> problem = redeemText(app, AccountRules.redeemProblem(reply.code))
                }
            }
        }
    }
}

/** Settings tile: the status, the account number and the code box, so a trial user can redeem a code early. */
@Composable
fun AccountSettingsTile() {
    val status by SubTvAccount.status.collectAsState()
    val number by SubTvAccount.accountNumber.collectAsState()
    val context = LocalContext.current
    LaunchedEffect(Unit) { if (SubTvAccount.status.value == null) SubTvAccount.refreshStatus() }
    Tile(Modifier.fillMaxWidth()) {
        TileLabel(stringResource(R.string.account_settings_title))
        Text(statusLine(status), style = Tokens.Type.headline, color = Tokens.Color.text)
        RemainingCountdownLine(status)
        AccountNumberRow()
        val refCount = status?.referralCount ?: 0
        Text(stringResource(R.string.referral_progress, refCount), style = Tokens.Type.caption, color = Tokens.Color.secondary)
        if (!number.isNullOrBlank()) {
            SecondaryButton(stringResource(R.string.referral_share_button)) {
                val shareMsg = context.getString(R.string.referral_share_text, number)
                val send = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, shareMsg)
                runCatching { context.startActivity(Intent.createChooser(send, null)) }
            }
        }
        RedeemCodeBox()
    }
}

/** Covers the app when the trial or the plan has ended: the plans, how to subscribe, the code box and "try again". */
@Composable
fun SubscribeScreen() {
    val context = LocalContext.current
    val status by SubTvAccount.status.collectAsState()
    val refreshing by SubTvAccount.refreshing.collectAsState()
    val scope = rememberCoroutineScope()
    var note by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(Unit) { ActivityLog.subscribeScreen() }
    ScreenScaffold(
        top = { TopBar() },
        bottom = {
            PrimaryButton(stringResource(R.string.subscribe_retry), enabled = !refreshing) {
                scope.launch {
                    // If the status now says the plan is active this screen is gone before the note is read.
                    note = context.getString(if (SubTvAccount.refreshStatus()) R.string.subscribe_still_ended else R.string.account_unreachable)
                }
            }
        },
    ) {
        Heading(stringResource(R.string.subscribe_title), stringResource(R.string.subscribe_body))
        AnimatedNote(note)
        val plans = status?.paidPlans.orEmpty()
        if (plans.isNotEmpty()) Tile(Modifier.fillMaxWidth()) {
            TileLabel(stringResource(R.string.subscribe_plans))
            for (plan in plans) {
                Text(stringResource(R.string.subscribe_plan_line, plan.name, AccountRules.priceText(plan.priceEgp), plan.durationDays),
                    style = Tokens.Type.headline, color = Tokens.Color.text)
            }
        }
        Tile(Modifier.fillMaxWidth()) {
            Text(stringResource(R.string.subscribe_contact_line), style = Tokens.Type.headline, color = Tokens.Color.text)
            val contact = stringResource(R.string.subscribe_contact)
            val shownContact = if (contact.equals("TODO_CONTACT", ignoreCase = true)) {
                stringResource(R.string.subscribe_contact_pending)
            } else {
                contact
            }
            Text(shownContact, style = Tokens.Type.title, color = Tokens.Color.accentText)
            AccountNumberRow()
        }
        Tile(Modifier.fillMaxWidth()) { RedeemCodeBox() }
    }
}

/** The server asks for a newer app: the existing "check for updates" action, nothing else. */
@Composable
fun UpdateRequiredScreen() {
    val context = LocalContext.current
    // Show the update dialog at once when a newer release exists (the same check the Settings row runs).
    LaunchedEffect(Unit) { UpdateController.check(context.applicationContext, manual = true) }
    ScreenScaffold(top = { TopBar() }) {
        Heading(stringResource(R.string.update_required_title), stringResource(R.string.update_required_body))
        RowList(listOf({ UpdateCheckRow() }))
    }
}

/** Blocking screen shown if the release APK was tampered with or re-signed with a non-official key. */
@Composable
fun IntegrityBlockedScreen() {
    ScreenScaffold(top = { TopBar() }) {
        Heading(stringResource(R.string.integrity_blocked_title), stringResource(R.string.integrity_blocked_body))
    }
}

/** Shown above every screen (MainActivity / PhoneSubtitleActivity), under the update dialog; nothing is drawn while access is fine. */
@Composable
fun AccountHost() {
    val context = LocalContext.current
    val intact = remember { AppIntegrity.verify(context) }
    if (!intact) {
        LaunchedEffect(Unit) { SecureData.wipe(context) }
        Blocking { IntegrityBlockedScreen() }
        return
    }
    val status by SubTvAccount.status.collectAsState()
    val gate by SubTvAccount.gateBlock.collectAsState()
    when (AccountRules.screen(status, gate, BuildConfig.VERSION_NAME)) {
        AccountScreen.NONE -> Unit
        AccountScreen.UPDATE_REQUIRED -> Blocking { UpdateRequiredScreen() }
        AccountScreen.SUBSCRIBE -> {
            LaunchedEffect(Unit) { SecureData.wipe(context) }
            Blocking { SubscribeScreen() }
        }
    }
}

/**
 * 1.0.48 part B: one-time privacy notice dialog on first launch with ONE OK button.
 * Activity events are sent only after the user taps OK.
 */
@Composable
fun PrivacyNoticeHost() {
    val accepted by ActivityLog.privacyAccepted.collectAsState()
    if (accepted) return
    AlertDialog(
        onDismissRequest = {},
        properties = DialogProperties(dismissOnBackPress = false, dismissOnClickOutside = false),
        containerColor = Tokens.Color.surface2,
        title = { Text(stringResource(R.string.privacy_notice_title), style = Tokens.Type.headline, color = Tokens.Color.text) },
        text = { Text(stringResource(R.string.privacy_notice_body), style = Tokens.Type.body, color = Tokens.Color.secondary) },
        confirmButton = {
            TextButton(onClick = { ActivityLog.acceptPrivacyNotice() }) {
                Text(stringResource(R.string.privacy_notice_ok), style = Tokens.Type.headline, color = Tokens.Color.accentText)
            }
        },
    )
}

/** A full screen that takes every touch and turns Back into leaving the app (there is nothing behind it to use). */
@Composable
private fun Blocking(content: @Composable () -> Unit) {
    val activity = LocalContext.current as? Activity
    BackHandler { activity?.finish() }
    Box(Modifier.fillMaxSize().pointerInput(Unit) { detectTapGestures { } }) { content() }
}
