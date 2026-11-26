// SPDX-License-Identifier: AGPL-3.0-only
// Kurulum akışı (tuval: Telefon-Karsilama, -PSN, -PS5, -Hedef): izin → karşılama (3 sayfa) → PSN girişi (ikinci
// hesap; PsnLoginScreen.kt) → PS5 eşleme → altyazı nerede. Uygulamada hesap yoktur.
package com.couchsubs.mobile.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.couchsubs.mobile.R
import com.couchsubs.mobile.Services
import com.couchsubs.mobile.remoteplay.ConsoleDiscovery
import com.couchsubs.mobile.remoteplay.ConsoleInfo
import com.couchsubs.mobile.remoteplay.Registration
import com.couchsubs.mobile.remoteplay.RegistrationException
import com.couchsubs.mobile.remoteplay.RegistrationFailure
import com.couchsubs.mobile.settings.SubtitleTarget
import com.couchsubs.mobile.tv.DisplayChoice
import com.couchsubs.mobile.tv.FoundTv
import com.couchsubs.mobile.tv.TvBrand
import com.couchsubs.mobile.tv.TvDiscovery
import com.couchsubs.mobile.core.ManualAddress
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** One welcome screen before the three setup steps. */
@Composable
fun WelcomeScreen(onDemo: () -> Unit = {}, onDone: () -> Unit) {
    ScreenScaffold(top = { TopBar() }, bottom = {
        PrimaryButton(stringResource(R.string.welcome_start), onClick = onDone)
    }) {
        Heading(stringResource(R.string.welcome1_title), stringResource(R.string.welcome1_body))
    }
}

/** Kurulumda PSN ve PS5 adımlarının sağ üstünde "Şimdilik geç" (PS5'siz de uygulama gezilebilsin). */
@Composable
internal fun SkipAction(onSkip: (() -> Unit)?) {
    if (onSkip != null) GhostButton(stringResource(R.string.skip_for_now), onClick = onSkip)
}

@Composable
fun InfoDialog(title: String, body: String, onDismiss: () -> Unit) {
    AlertDialog(onDismissRequest = onDismiss, containerColor = Tokens.Color.surface2,
        title = { Text(title, style = Tokens.Type.headline, color = Tokens.Color.text) },
        text = { Text(body, style = Tokens.Type.body, color = Tokens.Color.secondary) },
        confirmButton = { TextButton(onDismiss) { Text(stringResource(R.string.ok), style = Tokens.Type.headline, color = Tokens.Color.accentText) } })
}

@Composable
fun PairScreen(onBack: (() -> Unit)? = null, onSkip: (() -> Unit)? = null, onDone: () -> Unit) {
    val settings = Services.settings
    var consoles by remember { mutableStateOf<List<ConsoleInfo>?>(null) }
    var host by remember { mutableStateOf(settings.console?.host ?: "") }
    var code by remember { mutableStateOf("") }
    var manual by remember { mutableStateOf(false) }
    var working by remember { mutableStateOf(false) }
    var problem by remember { mutableStateOf<String?>(null) }
    var addressError by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val failures = registrationErrors()
    LaunchedEffect(Unit) {
        consoles = withContext(Dispatchers.IO) { ConsoleDiscovery.discover(null, 3000) }
        if (host.isEmpty()) consoles?.firstOrNull()?.let { host = it.host }
        if (consoles.isNullOrEmpty()) manual = true
    }
    val pin = Registration.parsePin(code)
    ScreenScaffold(top = { TopBar(onBack = onBack) { SkipAction(onSkip) } }, bottom = {
        if (!manual) GhostButton(stringResource(R.string.pair_not_found)) { manual = true }
        PrimaryButton(stringResource(R.string.pair_button), enabled = !working && pin != null && host.isNotBlank()) {
            val accountId = settings.accountId ?: return@PrimaryButton
            // Elle yazılan adres temizlenir ("192.168.1.5:3000", "http://…", virgüllü yazım); adres değilse söylenir (#50).
            val address = ManualAddress.normalize(host)
            if (address == null) {
                problem = null
                addressError = true
                return@PrimaryButton
            }
            addressError = false
            working = true
            problem = null
            scope.launch {
                try {
                    settings.console = withContext(Dispatchers.IO) { Registration.register(address, accountId, pin!!) }
                    onDone()
                } catch (error: RegistrationException) {
                    com.couchsubs.mobile.reporting.ProblemReporter.error("ps5.pair", "${error.failure}: ${error.detail.orEmpty()}")
                    problem = failures(error.failure) + (error.detail?.let { " ($it)" } ?: "")
                } catch (error: Exception) {
                    com.couchsubs.mobile.reporting.ProblemReporter.error("ps5.pair", error.message)
                    problem = error.message
                } finally {
                    working = false
                }
            }
        }
    }) {
        Heading(stringResource(R.string.pair_title), step = stringResource(R.string.setup_step, 2))
        Text(
            stringResource(R.string.pair_body),
            style = Tokens.Type.body.copy(
                fontSize = Tokens.Type.body.fontSize + 5.sp,
                lineHeight = 28.sp,
                fontWeight = FontWeight.Bold,
            ),
            color = Tokens.Color.accentText,
        )
        when (val list = consoles) {
            null -> Tile(Modifier.fillMaxWidth()) { Text(stringResource(R.string.pair_searching), style = Tokens.Type.body, color = Tokens.Color.secondary) }
            else -> list.forEach { console ->
                Tile(Modifier.fillMaxWidth(), style = if (host == console.host) TileStyle.ACTIVE else TileStyle.NORMAL, onClick = { host = console.host }) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Tokens.Space.gap)) {
                        TileIcon(Icons.Filled.Check, Tokens.Color.success)
                        Column(verticalArrangement = Arrangement.spacedBy(Tokens.Space.hair)) {
                            Text(stringResource(R.string.pair_found, console.name), style = Tokens.Type.headline, color = Tokens.Color.text)
                            Text(stringResource(R.string.pair_same_wifi, console.host), style = Tokens.Type.caption, color = Tokens.Color.secondary)
                        }
                    }
                }
            }
        }
        if (manual) Field(host, { host = it; addressError = false }, stringResource(R.string.pair_address), keyboard = KeyboardOptions(keyboardType = KeyboardType.Uri))
        if (addressError) NoteTile(stringResource(R.string.address_invalid))
        Tile(Modifier.fillMaxWidth()) {
            TileLabel(stringResource(R.string.pair_code))
            // Görünmez alan rakamları alır; kutular gösterir.
            Box {
                CodeCells(code.filter { it.isDigit() })
                BasicTextField(code, { code = it.filter { c -> c.isDigit() }.take(8) }, Modifier.matchParentSizeCompat().alpha(0.01f),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword))
            }
            Text(stringResource(R.string.pair_code_note), style = Tokens.Type.caption, color = Tokens.Color.secondary)
        }
        problem?.let { NoteTile(stringResource(R.string.pair_failed, it)) }
        if (working) Text(stringResource(R.string.pair_working), style = Tokens.Type.body, color = Tokens.Color.secondary)
    }
}

private fun Modifier.matchParentSizeCompat() = this.fillMaxWidth().height(48.dp)

/** Kayıt hatalarının Türkçesi (Windows'taki iletilerle aynı anlam). */
@Composable
fun registrationErrors(): (RegistrationFailure) -> String {
    val context = LocalContext.current
    return { failure ->
        context.getString(when (failure) {
            RegistrationFailure.WRONG_ACCOUNT -> R.string.regist_wrong_account
            RegistrationFailure.WRONG_CODE -> R.string.regist_wrong_code
            RegistrationFailure.IN_USE -> R.string.regist_in_use
            RegistrationFailure.VERSION_MISMATCH -> R.string.regist_version
            RegistrationFailure.CONSOLE_CRASHED -> R.string.regist_crashed
            RegistrationFailure.BAD_ADDRESS -> R.string.regist_bad_address
            RegistrationFailure.NO_ANSWER -> R.string.regist_no_answer
            RegistrationFailure.NO_REPLY -> R.string.regist_no_reply
            RegistrationFailure.PAIRING_CLOSED -> R.string.regist_pairing_closed
            RegistrationFailure.CONNECT_FAILED -> R.string.regist_connect_failed
            RegistrationFailure.NO_FREE_PORT -> R.string.regist_no_free_port
            RegistrationFailure.FAILED -> R.string.regist_failed
        })
    }
}

@Composable
fun TargetScreen(onBack: (() -> Unit)? = null, onTvInstall: ((TvBrand) -> Unit)? = null, onDone: () -> Unit) {
    val context = LocalContext.current
    val settings = Services.settings
    val firstRun = !settings.onboarded
    var showTv by androidx.compose.runtime.saveable.rememberSaveable { mutableStateOf(!firstRun) }
    var target by remember { mutableStateOf(settings.target) }
    var tvs by remember { mutableStateOf<List<FoundTv>?>(null) }
    // Seçilen TV: çeviride açılacak ve önde tutulacak TV (iPhone ile aynı; TvChoice).
    var chosen by remember { mutableStateOf(settings.installedTv) }
    var chosenName by remember { mutableStateOf(settings.tvName) }
    // Elle TV ekleme penceresi: açıksa içinde önseçili marka.
    var manualFor by remember { mutableStateOf<TvBrand?>(null) }
    var searchVersion by remember { mutableStateOf(0) }
    // Açık kart: kaydedilmiş TV'nin ya da telefon ekranının kartı.
    var open by remember { mutableStateOf(DisplayChoice.initial(settings.target, settings.installedTv)) }
    LaunchedEffect(searchVersion) {
        tvs = null
        tvs = withContext(Dispatchers.IO) { runCatching { TvDiscovery.search() }.getOrDefault(emptyList()) }
    }
    fun choose(tv: FoundTv) {
        // Android TV'nin adresi bilinmiyorsa (elle seçildi) TV uygulaması telefonu kendisi bulur; kayıt yalnız adla olur.
        if (tv.host.isNotBlank()) chosen = com.couchsubs.mobile.tv.TvChoice.select(chosen, tv).also { settings.installedTv = it }
        chosenName = tv.name
        settings.tvName = tv.name
        if (!target.showsOnTv) target = SubtitleTarget.BOTH
        open = DisplayChoice.of(tv.brand)
    }
    // Kart başlığına dokunma: kartı açar (açıksa kapatır); TV kartı hedefi TV+telefon yapar, telefon kartı "على الهاتف فقط".
    fun show(choice: DisplayChoice) {
        open = choice
        if (choice == DisplayChoice.PHONE) {
            target = SubtitleTarget.PHONE
            if (firstRun) {
                settings.target = target
                settings.onboarded = true
                onDone()
            }
        }
        else if (choice.brand != null && !target.showsOnTv) target = SubtitleTarget.BOTH
    }
    fun toggle(choice: DisplayChoice) = if (open == choice) open = null else show(choice)
    ScreenScaffold(top = { TopBar(onBack = onBack) }, bottom = {
        if (!firstRun || showTv) PrimaryButton(stringResource(R.string.next_continue)) {
            settings.target = if (target.showsOnTv) SubtitleTarget.BOTH else SubtitleTarget.PHONE
            // Seçim yapılmadıysa ağda görülen ilk TV (açık kart bir markaysa o markanınki) kullanılır (eski davranış).
            if (chosen == null && target.showsOnTv) tvs?.firstOrNull { open?.brand == null || it.brand == open?.brand }?.let { choose(it) }
            settings.onboarded = true
            onDone()
        }
    }) {
        Heading(stringResource(R.string.target_title), stringResource(R.string.target_body), if (firstRun) stringResource(R.string.setup_step, 3) else null)
        val phoneIp = remember { phoneAddress(context) }
        if (firstRun) {
            TwoColumns(
                { StatusTile(CouchIcons.Tv, stringResource(R.string.subtitle_place_tv_and_phone), stringResource(R.string.display_tv_note), it,
                    onClick = { showTv = true; target = SubtitleTarget.BOTH }) },
                { StatusTile(CouchIcons.Smartphone, stringResource(R.string.subtitle_place_phone_only), stringResource(R.string.display_phone_note), it,
                    onClick = { show(DisplayChoice.PHONE) }) },
            )
        }
        if (!firstRun || showTv) DisplayChoice.entries.filter { it != DisplayChoice.PHONE }.forEach { choice ->
            if (choice == DisplayChoice.ANDROID_TV) TileLabel(stringResource(R.string.chooser_group_tv))
            if (choice == DisplayChoice.BROWSER) TileLabel(stringResource(R.string.chooser_group_other))
            val brand = choice.brand
            val isChosen = if (brand != null) target.showsOnTv && chosen?.brand == brand else false
            DisplayCard(choice, open == choice, isChosen, onClick = { toggle(choice) }) {
                when {
                    brand != null -> {
                        val installable = remember(brand) { com.couchsubs.mobile.tv.TvPackages.bundled(context, brand) }
                        TvCardBody(brand, installable, phoneIp, tvs, chosen, chosenName, onPick = { choose(it) }, onSearchAgain = { searchVersion++ },
                            onManual = { manualFor = brand }, onInstall = onTvInstall?.let { go -> { go(brand) } }, onAlternative = ::show)
                    }
                    choice == DisplayChoice.BROWSER -> BrowserCardBody()
                    choice == DisplayChoice.PHONE -> PhoneCardBody()
                    choice == DisplayChoice.UNKNOWN -> UnknownCardBody(tvs, onPick = { choose(it) }, onOpen = ::show)
                }
            }
        }
        manualFor?.let { initial ->
            ManualTvDialog(initial, onDismiss = { manualFor = null }, onSupported = { open(context, context.getString(R.string.url_tvs)) }) { tv ->
                manualFor = null
                choose(tv)
            }
        }
        // TV kurulumu bitti: her çeviride TV uygulaması kendiliğinden açılmaya çalışılır; açılmazsa TV'den açılır.
        if (onBack == null && target.showsOnTv && settings.installedTv != null)
            Text(stringResource(R.string.tv_open_wizard_note), style = Tokens.Type.caption, color = Tokens.Color.secondary)
    }
}

/**
 * "TV'm listede yok" (iPhone ile aynı): marka seçilir, LG ve Samsung'da IP adresi yazılır (temizlenir, geçersizse
 * söylenir); Android TV'de adres gerekmez, oradaki uygulama telefonu kendisi bulur.
 */
@Composable
private fun ManualTvDialog(initial: TvBrand, onDismiss: () -> Unit, onSupported: () -> Unit, onConnect: (FoundTv) -> Unit) {
    val context = LocalContext.current
    var brand by remember { mutableStateOf(initial) }
    var host by remember { mutableStateOf("") }
    var invalid by remember { mutableStateOf(false) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.target_unlisted_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(Tokens.Space.inline)) {
                Text(stringResource(R.string.target_unlisted_body), style = Tokens.Type.caption, color = Tokens.Color.secondary)
                Segmented(TvBrand.entries.map { brandName(it) }, TvBrand.entries.indexOf(brand)) { brand = TvBrand.entries[it]; invalid = false }
                if (brand != TvBrand.ANDROID_TV) {
                    Field(host, { host = it; invalid = false }, stringResource(R.string.target_ip_hint),
                        keyboard = androidx.compose.foundation.text.KeyboardOptions(keyboardType = androidx.compose.ui.text.input.KeyboardType.Uri))
                    if (invalid) Text(stringResource(R.string.address_invalid), style = Tokens.Type.caption, color = Tokens.Color.danger)
                } else Text(stringResource(R.string.target_android_tv_found), style = Tokens.Type.caption, color = Tokens.Color.secondary)
                TextButton(onSupported) { Text(stringResource(R.string.target_unlisted_note)) }
            }
        },
        confirmButton = {
            TextButton({
                if (brand == TvBrand.ANDROID_TV) onConnect(FoundTv(context.getString(R.string.tvinstall_brand_android), "", brand))
                else com.couchsubs.mobile.core.ManualAddress.normalize(host)?.let { onConnect(FoundTv(it, it, brand)) } ?: run { invalid = true }
            }, enabled = brand == TvBrand.ANDROID_TV || host.isNotBlank()) { Text(stringResource(R.string.target_connect)) }
        },
        dismissButton = { TextButton(onDismiss) { Text(stringResource(R.string.cancel)) } },
        containerColor = Tokens.Color.surface2,
    )
}

/** "Yalnız OBS (yayın)": OBS'in tarayıcı kaynağına yazılacak adres (bu telefonun ağ adresiyle) ve kopyala düğmesi. */
@Composable
private fun ObsOnlyAddress() {
    val context = LocalContext.current
    val address = remember { com.couchsubs.mobile.service.TranslationService.viewerAddress() }
    var copied by remember { mutableStateOf(false) }
    LaunchedEffect(copied) {
        if (copied) {
            kotlinx.coroutines.delay(1500)
            copied = false
        }
    }
    Text(stringResource(R.string.target_obs_body), style = Tokens.Type.caption, color = Tokens.Color.secondary)
    address?.let { shown ->
        androidx.compose.foundation.text.selection.SelectionContainer {
            Text(shown, style = Tokens.Type.caption.copy(fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace), color = Tokens.Color.text)
        }
        SecondaryButton(stringResource(if (copied) R.string.history_copied else R.string.history_copy), Modifier.fillMaxWidth()) {
            context.getSystemService(android.content.ClipboardManager::class.java)?.setPrimaryClip(android.content.ClipData.newPlainText(context.getString(R.string.app_name), shown))
            copied = true
        }
    }
}

@Composable
fun brandName(brand: TvBrand): String = stringResource(when (brand) {
    TvBrand.LG -> R.string.tvinstall_brand_lg
    TvBrand.SAMSUNG -> R.string.tvinstall_brand_samsung
    TvBrand.ANDROID_TV -> R.string.tvinstall_brand_android
})
