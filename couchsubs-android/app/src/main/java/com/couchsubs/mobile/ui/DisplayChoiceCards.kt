// SPDX-License-Identifier: AGPL-3.0-only
// The display chooser's cards («اختر شاشة العرض»): one card per target, each opening its own guide and setup entry.
package com.couchsubs.mobile.ui

import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Info
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import com.couchsubs.mobile.R
import com.couchsubs.mobile.tv.DisplayChoice
import com.couchsubs.mobile.tv.FoundTv
import com.couchsubs.mobile.tv.InstalledTv
import com.couchsubs.mobile.tv.TvBrand
import com.couchsubs.mobile.tv.TvChoice
import com.couchsubs.mobile.tv.TvGuide
import com.couchsubs.mobile.tv.TvGuides

internal fun DisplayChoice.icon(): ImageVector = when (this) {
    DisplayChoice.PHONE -> CouchIcons.Smartphone
    DisplayChoice.UNKNOWN -> Icons.Filled.Info
    else -> CouchIcons.Tv
}

internal fun DisplayChoice.title(): Int = when (this) {
    DisplayChoice.ANDROID_TV -> R.string.chooser_android_title
    DisplayChoice.LG -> R.string.chooser_lg_title
    DisplayChoice.SAMSUNG -> R.string.chooser_samsung_title
    DisplayChoice.BROWSER -> R.string.chooser_browser_title
    DisplayChoice.PHONE -> R.string.target_phone
    DisplayChoice.UNKNOWN -> R.string.chooser_unknown_title
}

internal fun DisplayChoice.note(): Int = when (this) {
    DisplayChoice.ANDROID_TV -> R.string.chooser_android_note
    DisplayChoice.LG -> R.string.chooser_lg_note
    DisplayChoice.SAMSUNG -> R.string.chooser_samsung_note
    DisplayChoice.BROWSER -> R.string.chooser_browser_note
    DisplayChoice.PHONE -> R.string.target_phone_note
    DisplayChoice.UNKNOWN -> R.string.chooser_unknown_note
}

/**
 * One card: the header (icon, name, one-line note) is the button; the card opens to its [body]. [chosen] marks the card that
 * is the current display (the registered TV, the phone screen).
 */
@Composable
internal fun DisplayCard(choice: DisplayChoice, open: Boolean, chosen: Boolean, onClick: () -> Unit, body: @Composable ColumnScope.() -> Unit) {
    Tile(Modifier.fillMaxWidth(), style = if (open) TileStyle.ACTIVE else TileStyle.NORMAL) {
        Row(Modifier.fillMaxWidth().clickable(role = Role.Button, onClick = onClick), verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(Tokens.Space.inline)) {
            TileIcon(choice.icon())
            Column(Modifier.weight(1f)) {
                Text(stringResource(choice.title()), style = Tokens.Type.headline, color = Tokens.Color.text)
                Text(stringResource(choice.note()), style = Tokens.Type.caption, color = Tokens.Color.secondary)
            }
            if (chosen) SelectedCheck() else if (!open) Chevron()
        }
        if (open) body()
    }
}

/** One numbered step: the number sits in a small amber-outlined circle. */
@Composable
private fun NumberedStep(number: Int, text: String) {
    Row(horizontalArrangement = Arrangement.spacedBy(Tokens.Space.inline), verticalAlignment = Alignment.Top) {
        Box(Modifier.size(24.dp).border(1.dp, Tokens.Color.accentText, CircleShape), contentAlignment = Alignment.Center) {
            Text(number.toString(), style = Tokens.Type.captionNumeric, color = Tokens.Color.accentText)
        }
        Text(text, style = Tokens.Type.body, color = Tokens.Color.secondary, modifier = Modifier.weight(1f))
    }
}

/** The guide's requirements as bullets (they come first, before the steps). */
@Composable
internal fun GuideRequirements(guide: TvGuide) {
    TileLabel(stringResource(R.string.guide_requirements))
    guide.requirements.forEach { BodyText("• " + stringResource(it)) }
}

/** The numbered steps. [phoneIp] fills the Host PC IP step of Samsung (every guide string takes it; most have no placeholder). */
@Composable
internal fun GuideSteps(guide: TvGuide, phoneIp: String?) {
    TileLabel(stringResource(R.string.guide_steps))
    val ip = phoneIp ?: stringResource(R.string.tvinstall_phone_ip_unknown)
    guide.steps.forEachIndexed { index, step -> NumberedStep(index + 1, stringResource(step, ip)) }
}

/** The 2 to 4 tips for when it does not work. */
@Composable
internal fun GuideTips(guide: TvGuide) {
    TileLabel(stringResource(R.string.guide_tips))
    guide.tips.forEach { BodyText("• " + stringResource(it)) }
}

/**
 * "Installing the TV app from the phone is not available": LG and Android TV packages come from a build-time download that
 * can fail, so the install steps are not shown for a path that ends in "package missing". [onAlternative] adds the buttons
 * for the options that do work; [listBelow] says the TV list is on this screen.
 */
@Composable
internal fun UnavailableInstall(listBelow: Boolean, onAlternative: ((DisplayChoice) -> Unit)? = null) {
    NoteTile(stringResource(R.string.guide_unavailable))
    if (listBelow) BodyText(stringResource(R.string.guide_unavailable_installed))
    BodyText(stringResource(R.string.guide_unavailable_other))
    if (onAlternative != null) Row(horizontalArrangement = Arrangement.spacedBy(Tokens.Space.gap)) {
        GhostButton(stringResource(R.string.target_phone)) { onAlternative(DisplayChoice.PHONE) }
        GhostButton(stringResource(R.string.chooser_browser_short)) { onAlternative(DisplayChoice.BROWSER) }
    }
}

/** A TV brand's card: what the install needs (or why it is not offered), the TVs of that brand on the network, setup entry. */
@Composable
internal fun ColumnScope.TvCardBody(
    brand: TvBrand, installable: Boolean, phoneIp: String?, tvs: List<FoundTv>?, chosen: InstalledTv?, chosenName: String?,
    onPick: (FoundTv) -> Unit, onSearchAgain: () -> Unit, onManual: () -> Unit, onInstall: (() -> Unit)?,
    onAlternative: (DisplayChoice) -> Unit,
) {
    val guide = TvGuides.of(DisplayChoice.of(brand))
    if (!installable) UnavailableInstall(listBelow = true, onAlternative = onAlternative)
    else if (guide != null) {
        GuideRequirements(guide)
        GuideSteps(guide, phoneIp)
    }
    TileLabel(stringResource(R.string.chooser_found_title))
    val listed = TvChoice.list(tvs.orEmpty(), chosen, chosenName).filter { it.brand == brand }
    when {
        tvs == null -> Text(stringResource(R.string.target_searching), style = Tokens.Type.caption, color = Tokens.Color.secondary)
        listed.isEmpty() -> Text(stringResource(if (installable && onInstall != null) R.string.target_tvs_none_help else R.string.target_tvs_none),
            style = Tokens.Type.caption, color = Tokens.Color.secondary)
    }
    listed.forEach { tv ->
        Row(Modifier.fillMaxWidth().clickable(role = Role.Button) { onPick(tv) }.padding(vertical = Tokens.Space.inline),
            verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(tv.name, style = Tokens.Type.body, color = Tokens.Color.text)
                Text(brandName(tv.brand), style = Tokens.Type.caption, color = Tokens.Color.secondary)
            }
            if (TvChoice.isSelected(tv, chosen)) SelectedCheck()
            else if (tvs?.any { it.host == tv.host && it.brand == tv.brand } == true) StatusLabel(stringResource(R.string.target_tv_on_network))
        }
    }
    if (chosen?.brand == brand) Text(stringResource(R.string.target_tv_selected_note), style = Tokens.Type.caption, color = Tokens.Color.secondary)
    Row(horizontalArrangement = Arrangement.spacedBy(Tokens.Space.gap)) {
        if (tvs != null) GhostButton(stringResource(R.string.target_search_again), onClick = onSearchAgain)
        GhostButton(stringResource(R.string.target_unlisted), onClick = onManual)
    }
    if (installable && onInstall != null) SecondaryButton(stringResource(R.string.tvinstall_entry), onClick = onInstall)
    // The tips also help a TV that already has the app (off, other network, app to reopen after a restart).
    guide?.let { GuideTips(it) }
}

/** "Another smart TV with a web browser": the page the phone serves, with the exact address to type (and its QR code). */
@Composable
internal fun ColumnScope.BrowserCardBody() {
    val guide = TvGuides.of(DisplayChoice.BROWSER) ?: return
    // The page shows subtitles alone (no game picture): said first so nobody expects an overlay.
    Text(stringResource(R.string.chooser_browser_only), style = Tokens.Type.body, color = Tokens.Color.text)
    GuideRequirements(guide)
    GuideSteps(guide, null)
    SecondScreenBody()
    GuideTips(guide)
}

/** The phone screen's guide (no TV, nothing to install). */
@Composable
internal fun ColumnScope.PhoneCardBody() {
    val guide = TvGuides.of(DisplayChoice.PHONE) ?: return
    GuideRequirements(guide)
    GuideSteps(guide, null)
    GuideTips(guide)
}

/** «لا أعرف نوع شاشتي»: where the brand and system name are, which option to try, and what the app found on the network. */
@Composable
internal fun ColumnScope.UnknownCardBody(tvs: List<FoundTv>?, onPick: (FoundTv) -> Unit, onOpen: (DisplayChoice) -> Unit) {
    BodyText(stringResource(R.string.chooser_unknown_step1))
    BodyText(stringResource(R.string.chooser_unknown_step2))
    for ((choice, text) in listOf(
        DisplayChoice.LG to R.string.chooser_unknown_lg, DisplayChoice.SAMSUNG to R.string.chooser_unknown_samsung,
        DisplayChoice.ANDROID_TV to R.string.chooser_unknown_android, DisplayChoice.BROWSER to R.string.chooser_unknown_other,
    )) Row(Modifier.fillMaxWidth().clickable(role = Role.Button) { onOpen(choice) }.padding(vertical = Tokens.Space.inline),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Tokens.Space.inline)) {
        Text(stringResource(text), style = Tokens.Type.body, color = Tokens.Color.text, modifier = Modifier.weight(1f))
        Chevron()
    }
    TileLabel(stringResource(R.string.chooser_found_title))
    when {
        tvs == null -> Text(stringResource(R.string.target_searching), style = Tokens.Type.caption, color = Tokens.Color.secondary)
        tvs.isEmpty() -> Text(stringResource(R.string.target_tvs_none), style = Tokens.Type.caption, color = Tokens.Color.secondary)
        else -> {
            Text(stringResource(R.string.chooser_unknown_found), style = Tokens.Type.caption, color = Tokens.Color.secondary)
            tvs.forEach { tv ->
                Row(Modifier.fillMaxWidth().clickable(role = Role.Button) { onPick(tv) }.padding(vertical = Tokens.Space.inline),
                    verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(tv.name, style = Tokens.Type.body, color = Tokens.Color.text)
                        Text(brandName(tv.brand), style = Tokens.Type.caption, color = Tokens.Color.secondary)
                    }
                    Chevron()
                }
            }
        }
    }
}
