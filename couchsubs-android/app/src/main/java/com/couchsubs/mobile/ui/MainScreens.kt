// SPDX-License-Identifier: AGPL-3.0-only
// Ana ekran (tuval: Telefon-Ana, -Calisiyor, -Tur) ve oyun paketleri (-Paketler). Uygulama ve paketler ücretsiz.
package com.couchsubs.mobile.ui

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.text.format.DateUtils
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
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
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.couchsubs.mobile.R
import com.couchsubs.mobile.Services
import com.couchsubs.mobile.core.GameCatalog
import com.couchsubs.mobile.packs.PackUpdateOutcome
import com.couchsubs.mobile.packs.PackUpdateSummary
import com.couchsubs.mobile.service.FramePreview
import com.couchsubs.mobile.service.LiveStatus
import com.couchsubs.mobile.settings.SubtitlePlace
import com.couchsubs.mobile.tv.InstalledTv
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Boşta ↔ çeviri ekranı geçişinin süresi (ms). */
private const val MOTION_MS = 250

/** Paket kimliğinden kısa ad ve kod (tuvaldeki karolar). */
fun packShort(id: String, title: String): Pair<String, String> =
    title.filter { it.isLetterOrDigit() }.take(3).uppercase() to title

/** Localized installed-line count. */
fun linesText(context: Context, entries: Int): String =
    if (entries >= 1000) context.getString(R.string.lines_thousands, (entries + 500) / 1000) else context.getString(R.string.lines_count, entries)

fun durationText(context: Context, millis: Long): String {
    val minutes = millis / 60_000
    return if (minutes >= 60) context.getString(R.string.duration_hours, minutes / 60, minutes % 60) else context.getString(R.string.duration_minutes, minutes)
}

@Composable
fun MainScreen(
    status: LiveStatus,
    onStart: () -> Unit,
    onStop: () -> Unit,
    onPhoneView: () -> Unit,
    onTarget: () -> Unit,
    onDemo: () -> Unit = {},
    onExitDemo: () -> Unit = {},
    onPairing: () -> Unit,
    onPacks: () -> Unit,
    onProfile: () -> Unit,
    onSettings: () -> Unit,
    onHistory: () -> Unit = {},
    onTvUpdate: (InstalledTv) -> Unit = {},
    onHome: Boolean = true,
) {
    val context = LocalContext.current
    val settings = Services.settings
    LaunchedEffect(Unit) {
        androidx.compose.runtime.withFrameNanos { }
        com.couchsubs.mobile.reporting.ProblemReporter.start(settings.updateChannel)
        if (com.couchsubs.mobile.account.SubTvAccount.status.value == null) {
            com.couchsubs.mobile.account.SubTvAccount.refreshStatus()
        }
    }
    val xbox = settings.xbox
    val paired = if (xbox) settings.xboxConsole != null && Services.xboxAuth.signedIn else settings.console != null
    val topBar: @Composable () -> Unit = {
        TopBar {
            // History and settings are the only home actions.
            IconAction(CouchIcons.History, stringResource(R.string.history), onHistory)
            SettingsAction(onSettings)
        }
    }

    Box(Modifier.fillMaxSize()) {
            // Başlatınca ve durdurunca boşta ve çeviri ekranları solarak yer değiştirir.
            AnimatedContent(status.running, transitionSpec = { fadeIn(tween(MOTION_MS)) togetherWith fadeOut(tween(MOTION_MS)) },
                label = "running") { running ->
                if (running) RunningScreen(status, topBar, if (status.demo) onExitDemo else onStop, onPhoneView, onTvUpdate, onPacks)
                else ScreenScaffold(top = topBar, bottom = {
                    // PS5 eşlenmemişse (kurulum atlandı) başlatmak yerine eşleme açılır.
                    if (!paired) {
                        PrimaryButton(stringResource(R.string.main_pair_button), onClick = onPairing)
                    }
                }) {
                    if (paired) Heading(stringResource(R.string.main_ready), stringResource(R.string.main_ready_body))
                    else {
                        Heading(stringResource(R.string.main_unpaired), stringResource(R.string.main_unpaired_body))
                    }
                    AnimatedNote(status.error)
                    AccountBanners()
                    var place by remember { mutableStateOf(settings.subtitlePlace) }
                    DisposableEffect(settings) {
                        place = settings.subtitlePlace
                        val listener = settings.onTargetChanged { place = settings.subtitlePlace }
                        onDispose { settings.removeListener(listener) }
                    }
                    // Eşleme ile canlı bağlantı farklı durumlardır; hazır olmayan cihaz yeşil gösterilmez.
                    Row(horizontalArrangement = Arrangement.spacedBy(Tokens.Space.inline)) {
                        StatusChip(stringResource(if (xbox) R.string.platform_xbox else R.string.platform_ps), paired, Modifier.weight(1f), onPairing)
                        if (place.showsOnTv) {
                            StatusChip(settings.tvName ?: stringResource(R.string.main_target_tv), status.connectedTvs > 0,
                                Modifier.weight(1f), onTarget)
                        }
                    }
                    SubtitlePlaceTile(settings = settings, connectedTvs = status.connectedTvs, onSetupTv = onTarget)
                    if (paired) TranslationStartDisc(onStart = onStart)
                    if (!paired) Spacer(Modifier.height(Tokens.Space.screen))
                    status.recent.firstOrNull()?.let { line ->
                        Tile(Modifier.fillMaxWidth()) {
                            TileLabel(stringResource(R.string.running_now))
                            Text(line.translation, style = Tokens.Type.headline, color = Tokens.Color.text)
                        }
                    }

                }
            }
    }
}

/** Son satırın ölçümleri tek satırda; bilinmeyen değer yazılmaz, hiç ölçüm yoksa null. */
fun metricsText(context: Context, pair: com.couchsubs.mobile.core.ShownPair): String? {
    val total = pair.totalMs ?: return null
    val parts = mutableListOf<String>()
    pair.ocrMs?.let { parts.add(context.getString(R.string.metrics_ocr, it)) }
    if (pair.origin == "paket") parts.add(context.getString(R.string.metrics_pack))
    else pair.translationMs?.let { parts.add(context.getString(R.string.metrics_translation, it)) }
    parts.add(context.getString(R.string.metrics_delay, total))
    return parts.joinToString(" · ")
}

fun engineName(context: Context): String = when (Services.settings.engine) {
    com.couchsubs.mobile.settings.EngineChoice.SERVER -> context.getString(R.string.engine_server)
    com.couchsubs.mobile.settings.EngineChoice.FREE -> context.getString(R.string.engine_free_short)
    com.couchsubs.mobile.settings.EngineChoice.DEEPL -> context.getString(R.string.engine_deepl)
    com.couchsubs.mobile.settings.EngineChoice.GEMINI -> context.getString(R.string.engine_gemini)
    com.couchsubs.mobile.settings.EngineChoice.OPENAI -> context.getString(R.string.engine_openai)
    com.couchsubs.mobile.settings.EngineChoice.CLAUDE -> context.getString(R.string.engine_claude)
    com.couchsubs.mobile.settings.EngineChoice.LOCAL -> context.getString(R.string.engine_local)
}

/** Deneme modunda çeviri ekranının üstü: "Deneme modu" rozeti, "Çık" ve hattın okuduğu örnek kare (iPhone ile aynı). */
@Composable
private fun DemoHeader(onExit: () -> Unit) {
    DisposableEffect(Unit) {
        FramePreview.watch(true)
        onDispose { FramePreview.watch(false) }
    }
    val frame by FramePreview.frame.collectAsState()
    Row(verticalAlignment = Alignment.CenterVertically) {
        Pill(stringResource(R.string.demo_mode), pro = true)
        Spacer(Modifier.weight(1f))
        GhostButton(stringResource(R.string.demo_mode_exit_short), onClick = onExit)
    }
    Tile(Modifier.fillMaxWidth()) {
        frame?.let {
            Image(it.asImageBitmap(), contentDescription = null, contentScale = ContentScale.FillWidth,
                modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(Tokens.Radius.inner)))
        }
        Text(stringResource(R.string.demo_mode_frame_note), style = Tokens.Type.caption, color = Tokens.Color.secondary)
    }
}

/** Çeviri sürerken (tuval Telefon-Calisiyor): süre, paket, canlı satır ve önceki satırlar, sayılar. */
@Composable
private fun RunningScreen(status: LiveStatus, topBar: @Composable () -> Unit, onStop: () -> Unit, onPhoneView: () -> Unit,
                          onTvInstall: (InstalledTv) -> Unit = {}, onPacks: () -> Unit = {}) {
    val context = LocalContext.current
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(1000)
            now = System.currentTimeMillis()
        }
    }
    ScreenScaffold(top = topBar, bottom = {
        // İkincil düğmeler yan yana (iPhone ile aynı): altyazı ekranı telefonda gösterirken ya da deneme modunda, "TV'de
        // dene" TV'ye çevirirken, "Ekranı çevir" yalnız görüntü gelirken.
        val target = Services.settings.target
        if (target.showsOnPhone || status.demo || target.showsOnTv || status.streaming)
            Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min), horizontalArrangement = Arrangement.spacedBy(Tokens.Space.inline)) {
                if (target.showsOnPhone || status.demo)
                    SecondaryButton(stringResource(R.string.main_phone_view), Modifier.weight(1f).fillMaxHeight(), onClick = onPhoneView)
                if (target.showsOnTv) SecondaryButton(stringResource(R.string.settings_test_subtitle), Modifier.weight(1f).fillMaxHeight()) {
                    com.couchsubs.mobile.service.TranslationService.sendTestSubtitle(context.getString(R.string.test_subtitle_text))
                }
                if (status.streaming) ScreenTranslateButton(status, Modifier.weight(1f).fillMaxHeight())
            }
        DestructiveButton(stringResource(if (status.demo) R.string.demo_mode_exit else R.string.stop), onClick = onStop)
    }) {
        if (status.demo) DemoHeader(onStop)
        Row(horizontalArrangement = Arrangement.spacedBy(Tokens.Space.gap), verticalAlignment = Alignment.CenterVertically) {
            val elapsed = ((now - status.startedAt).coerceAtLeast(0) / 1000)
            // Bir saati geçince "1:12:10" (DateUtils: s:dd:ss), öncesinde "12:10"; rakamlar tablo rakamı, süre akarken kaymaz.
            StatusLabel(stringResource(R.string.running_pill, DateUtils.formatElapsedTime(elapsed)), live = true,
                style = Tokens.Type.captionNumeric)
        }
        PackSummaryTile(onPacks = onPacks)
        // Görüntü gelirken durum metni ("Altyazılar okunuyor") üstteki "Çeviri sürüyor" satırını tekrarlar; yalnız
        // bağlanırken ve bağlantı sorunlarında gösterilir.
        if (!status.streaming && status.phase.isNotEmpty()) Text(status.phase, style = Tokens.Type.caption, color = Tokens.Color.secondary)
        // Oturum yeniden denemeyi bıraktıysa hemen, süren denemelerde 45 sn sonra: uygulamayı kapatıp açmadan bağlantı
        // baştan kurulur (ReconnectOffer; Mac/iPhone ile aynı).
        if (status.running && !status.demo && !status.streaming &&
            com.couchsubs.mobile.remoteplay.ReconnectOffer.shows(status.notLiveSince, status.gaveUp, now))
            SecondaryButton(stringResource(R.string.reconnect)) { com.couchsubs.mobile.service.TranslationService.reconnect() }
        AnimatedNote(status.error)
        AccountBanners()
        // TV'ye çevirirken telefon kilitlenebilir: pil kısıtlaması açıksa çeviri ekran kapanınca durabilir.
        if (!status.demo && Services.settings.target.showsOnTv) BackgroundLimitNote()
        // TV uygulaması bağlı değil: CouchSubs TV'de nerede bulunur (markaya göre), otomatik açma neden olmadı ve
        // "Otomatik açmayı dene" (Windows, Mac ve iPhone ile aynı).
        if (!status.demo && Services.settings.target.showsOnTv && status.connectedTvs == 0)
            Services.settings.installedTv?.let { tv -> TvOpenCard(tv.brand, status.tvOpenProblem) { onTvInstall(tv) } }
        val current = status.recent.firstOrNull()
        // İlk altyazı gelene kadar ve bağlantı koptuğunda: kolu açınca PS5'te ana hesap seçilmeli (GitHub #231, MainAccountTip).
        if (com.couchsubs.mobile.remoteplay.MainAccountTip.visible(ps5 = !Services.settings.xbox, demo = status.demo,
                hasSubtitle = current != null, streaming = status.streaming))
            NoteTile(stringResource(R.string.psn_main_account_steps), Tokens.Color.secondary)
        // Otomatik ekran yazısı beta.63'te açık geliyordu; bir kez kapatıldı, kullanıcıya söylenir.
        if (com.couchsubs.mobile.settings.AppSettings.autoScreenTextNotice)
            NoteTile(stringResource(R.string.auto_screen_text_notice), Tokens.Color.secondary)
        // Görüntü bir dakikadır geliyor ama hiç altyazı satırı okunmadı (GitHub #503, #566, #572; SubtitleOffTip).
        if (status.subtitleOffTip) {
            NoteTile(stringResource(R.string.subtitle_off_tip), Tokens.Color.secondary)
            if (Services.settings.watchSubtitleBackground) NoteTile(stringResource(R.string.subtitle_background_tip), Tokens.Color.secondary)
        }
        Tile(Modifier.fillMaxWidth()) {
            TileLabel(stringResource(R.string.running_now))
            // Show the latest translation; the full bilingual log is in History.
            Text(current?.translation ?: stringResource(R.string.running_waiting), style = Tokens.Type.subtitleCurrent,
                color = if (current != null) Tokens.Color.accentText else Tokens.Color.secondary)
        }

    }
}

/**
 * Çeviri sürüyor ama TV uygulaması bağlı değil: kullanıcı CouchSubs'ı TV'de kendisinin açması gerektiğini bilmiyordu
 * ("ilk başta olmadı, TV'den de uygulamayı açmak lazımmış", 21 Kas). Düğme açma döngüsünü beklemeden yeniden denetir.
 */
@Composable
private fun TvOpenCard(brand: com.couchsubs.mobile.tv.TvBrand, problem: com.couchsubs.mobile.tv.TvOpenProblem?, onInstall: () -> Unit) {
    Tile(Modifier.fillMaxWidth()) {
        Text(stringResource(com.couchsubs.mobile.tv.TvOpenProblem.openOnTv(brand)), style = Tokens.Type.body, color = Tokens.Color.text)
        com.couchsubs.mobile.tv.TvOpenProblem.reason(problem)?.let {
            Text(stringResource(it), style = Tokens.Type.caption, color = Tokens.Color.warning)
        }
        SecondaryButton(stringResource(R.string.tv_open_try)) {
            com.couchsubs.mobile.tv.TvOpenRetry.request()
            com.couchsubs.mobile.service.LiveState.update { it.copy(tvOpenProblem = null) }
        }
        // TV uygulaması kurulu değilse ya da açılmıyorsa bu telefondan kurulur (iPhone'daki "TV uygulamasını bu telefondan kur").
        GhostButton(stringResource(R.string.tvinstall_entry), onClick = onInstall)
    }
}

/** Oyun paketleri (ücretsiz): anahtarla toptan, karoya dokunarak paket başına açılıp kapatılır. */
@Composable
fun PacksScreen(onBack: () -> Unit, packInUse: String?) {
    val context = LocalContext.current
    val settings = Services.settings
    val version by Services.packsVersion.collectAsState()
    val status by Services.packStatus.collectAsState()
    var catalog by remember { mutableStateOf<List<Services.CatalogEntry>?>(null) }
    var disabled by remember { mutableStateOf(settings.disabledPacks) }
    var packsOn by remember { mutableStateOf(settings.packsEnabled) }
    LaunchedEffect(version) { catalog = withContext(Dispatchers.IO) { Services.catalog(context) } }
    val unlocked = packsOn
    // Oyunun "(ek satırlar)" paketi asıl paketin karosunda; ikisi birlikte açılıp kapanır (#353).
    val list = GameCatalog.packShelf(catalog.orEmpty(), { it.id }, { it.title }, { it.entries })
    // "Paketleri güncelle"ye basıldı, sonuç bekleniyor: sayfa bulanıklaşır (Android 12+; öncesinde koyu perde), ortada
    // gösterge döner; bitince ne geldiği uyarıda yazar (iPhone ile aynı).
    var updating by remember { mutableStateOf(false) }
    var outcome by remember { mutableStateOf<PackUpdateOutcome?>(null) }
    val scope = rememberCoroutineScope()
    val busy = updating || status == Services.PackStatus.DOWNLOADING
    fun updateNow() {
        if (updating) return
        updating = true
        outcome = null
        scope.launch {
            val before = withContext(Dispatchers.IO) {
                catalog?.filter { it.installed }?.map { it.id to it.entries }
                    ?: Services.packLibrary(context).infos.map { it.id to it.entries }
            }
            val result = try {
                Services.updatePacksNow(before)
            } finally {
                updating = false
            }
            outcome = result
        }
    }
    Box(Modifier.fillMaxSize()) {
        Box(Modifier.fillMaxSize().then(if (updating) Modifier.blur(12.dp) else Modifier)) {
            ScreenScaffold(top = {
                TopBar(stringResource(R.string.packs_title), onBack = onBack) {
                    Pill(if (list.isEmpty()) stringResource(R.string.packs_free) else stringResource(R.string.packs_free_count, list.size))
                }
            }, bottom = {
                SecondaryButton(stringResource(if (busy) R.string.packs_updating_short else R.string.packs_refresh),
                    enabled = !busy) { updateNow() }
            }) {
                GameSection(com.couchsubs.mobile.service.LiveState.status.collectAsState().value)
                NoteTile(stringResource(R.string.supported_games_all_note), Tokens.Color.secondary)
                Text(stringResource(R.string.packs_auto), style = Tokens.Type.body, color = Tokens.Color.secondary)
                RowList(listOf({
                    RowItem(stringResource(R.string.settings_packs), stringResource(R.string.packs_switch_note)) {
                        androidx.compose.material3.Switch(packsOn, { packsOn = it; settings.packsEnabled = it })
                    }
                }))
                // Güncelleme hatası sessizce yutulmaz (iPhone'daki gibi): ulaşılamazsa telefondaki paketlerin kullanıldığı yazar.
                when (status) {
                    Services.PackStatus.DOWNLOADING -> Text(stringResource(R.string.packs_updating), style = Tokens.Type.caption, color = Tokens.Color.secondary)
                    Services.PackStatus.OFFLINE -> NoteTile(stringResource(R.string.packs_offline))
                    Services.PackStatus.FAILED -> NoteTile(stringResource(R.string.packs_failed))
                    else -> {}
                }
                if (catalog == null) Text(stringResource(R.string.packs_loading), style = Tokens.Type.body, color = Tokens.Color.secondary)
                else if (list.isEmpty()) NoteTile(stringResource(R.string.packs_none_yet), Tokens.Color.secondary)
                list.chunked(2).forEach { row ->
                    Row(Modifier.height(IntrinsicSize.Min), horizontalArrangement = Arrangement.spacedBy(Tokens.Space.gap)) {
                        row.forEach { entry ->
                            val (code, short) = packShort(entry.id, gameLabel(context, entry.title))
                            val on = entry.packIds.none { it in disabled }
                            val playing = packInUse != null && packInUse in entry.titles
                            PackTile(code, short, linesText(context, entry.entries), Modifier.weight(1f).fillMaxHeight(),
                                style = if (playing) TileStyle.ACTIVE else TileStyle.NORMAL, dim = !unlocked || !on,
                                corner = { if (playing) StatusLabel(stringResource(R.string.packs_now)) else if (unlocked && !on) Pill(stringResource(R.string.packs_off)) },
                                // Karo paketin anahtarıdır: ekran okuyucu "açık/kapalı" diye okur.
                                toggled = on,
                                onClick = {
                                    disabled = if (on) disabled + entry.packIds else disabled - entry.packIds.toSet()
                                    settings.disabledPacks = disabled
                                })
                        }
                        if (row.size == 1) Spacer(Modifier.weight(1f))
                    }
                }
                ImportedPacksSection()
                Tile(Modifier.fillMaxWidth()) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Tokens.Space.gap)) {
                        TileIcon(Icons.Filled.Refresh)
                        Column(verticalArrangement = Arrangement.spacedBy(Tokens.Space.hair)) {
                            Text(stringResource(R.string.packs_next), style = Tokens.Type.headline, color = Tokens.Color.text)
                            Text(stringResource(R.string.packs_next_note), style = Tokens.Type.caption, color = Tokens.Color.secondary)
                        }
                    }
                }
            }
        }
        if (updating) PacksUpdatingOverlay()
    }
    outcome?.let { shown ->
        val failed = shown is PackUpdateOutcome.Failed
        AlertDialog(
            onDismissRequest = { outcome = null },
            title = { Text(stringResource(when (shown) {
                PackUpdateOutcome.UpToDate -> R.string.packs_result_current_title
                is PackUpdateOutcome.Updated -> R.string.packs_result_updated_title
                is PackUpdateOutcome.Failed -> R.string.packs_result_failed_title
            })) },
            text = { Text(packUpdateMessage(context, shown)) },
            confirmButton = {
                TextButton({
                    outcome = null
                    if (failed) updateNow()
                }) { Text(stringResource(if (failed) R.string.packs_retry else R.string.ok)) }
            },
            dismissButton = if (failed) ({ TextButton({ outcome = null }) { Text(stringResource(R.string.close)) } }) else null,
            containerColor = Tokens.Color.surface2,
        )
    }
}

/** Güncelleme sürerken sayfanın üstündeki perde: dokunuş ve Geri tuşu geçmez, ortada gösterge ve "Paketler güncelleniyor…". */
@Composable
private fun PacksUpdatingOverlay() {
    BackHandler { }
    // Bulanıklık Android 12'den önce çalışmaz; orada perde daha koyu.
    val scrim = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S) 0.45f else 0.7f
    Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = scrim)).pointerInput(Unit) { detectTapGestures { } },
        contentAlignment = Alignment.Center) {
        Column(Modifier.background(Tokens.Color.surface2, RoundedCornerShape(Tokens.Radius.tile)).padding(Tokens.Space.tile),
            horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(Tokens.Space.gap)) {
            CircularProgressIndicator(color = Tokens.Color.pro)
            Text(stringResource(R.string.packs_updating), style = Tokens.Type.headline, color = Tokens.Color.text)
        }
    }
}

/** "2 paket güncellendi: Grand Theft Auto VI (+12.300 satır), Red Dead Redemption 2 (+800 satır)." */
fun packUpdateMessage(context: Context, outcome: PackUpdateOutcome): String = when (outcome) {
    PackUpdateOutcome.UpToDate -> context.getString(R.string.packs_result_current)
    is PackUpdateOutcome.Updated -> packSummaryText(context, outcome.summary)
    is PackUpdateOutcome.Failed -> {
        val reason = when (outcome.reason) {
            PackUpdateOutcome.Reason.OFFLINE -> context.getString(R.string.packs_error_offline)
            PackUpdateOutcome.Reason.RATE_LIMITED -> context.getString(R.string.packs_error_rate)
            PackUpdateOutcome.Reason.SERVER -> context.getString(R.string.packs_error_server, outcome.status ?: 0)
            PackUpdateOutcome.Reason.PARTIAL -> context.getString(R.string.packs_error_partial)
            PackUpdateOutcome.Reason.UNKNOWN -> context.getString(R.string.packs_error_unknown)
        }
        (listOf(reason, context.getString(R.string.packs_result_still_used)) +
            listOfNotNull(packSummaryText(context, outcome.summary).takeIf { it.isNotEmpty() })).joinToString("\n\n")
    }
}

private fun packSummaryText(context: Context, summary: PackUpdateSummary): String {
    val locale = context.resources.configuration.locales[0]
    val number = { value: Int -> java.text.NumberFormat.getIntegerInstance(locale).format(value) }
    val parts = mutableListOf<String>()
    val updated = summary.updated
    if (updated.isNotEmpty()) {
        val items = updated.joinToString(", ") { change ->
            when {
                change.lineDelta > 0 -> context.getString(R.string.packs_result_item_more, gameLabel(context, change.title), number(change.lineDelta))
                change.lineDelta < 0 -> context.getString(R.string.packs_result_item_fewer, gameLabel(context, change.title), number(-change.lineDelta))
                else -> context.getString(R.string.packs_result_item_revised, gameLabel(context, change.title))
            }
        }
        parts += if (updated.size == 1) context.getString(R.string.packs_result_updated_one, items)
        else context.getString(R.string.packs_result_updated_many, updated.size, items)
    }
    val added = summary.added
    if (added.isNotEmpty()) {
        val items = added.joinToString(", ") { context.getString(R.string.packs_result_item_lines, gameLabel(context, it.title), number(it.lines)) }
        parts += if (added.size == 1) context.getString(R.string.packs_result_added_one, items)
        else context.getString(R.string.packs_result_added_many, added.size, items)
    }
    if (summary.newLines > 0) parts += context.getString(R.string.packs_result_total, number(summary.newLines))
    return parts.joinToString("\n")
}

fun open(context: Context, url: String) {
    try {
        context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    } catch (error: Exception) {
    }
}

/** "Ekranı çevir": oyun ekranının tamamını çevirir; çeviri sürerken ya da sonuçlar ekrandayken dokununca kaldırır (iPhone ile aynı). */
@Composable
fun ScreenTranslateButton(status: LiveStatus, modifier: Modifier = Modifier) {
    val label = when (status.screenState) {
        LiveStatus.ScreenState.IDLE -> R.string.screen_translate
        LiveStatus.ScreenState.WORKING -> R.string.screen_working
        LiveStatus.ScreenState.SHOWN -> R.string.screen_remove
    }
    SecondaryButton(stringResource(label), modifier) { com.couchsubs.mobile.service.TranslationService.translateScreen() }
}

@Composable
private fun TranslationStartDisc(modifier: Modifier = Modifier, onStart: () -> Unit) {
    Box(Modifier.fillMaxWidth().padding(vertical = 22.dp), contentAlignment = Alignment.Center) {
        Box(Modifier.size(234.dp).background(Tokens.Color.accent.copy(alpha = 0.05f), androidx.compose.foundation.shape.CircleShape))
        Box(Modifier.size(210.dp).background(Tokens.Color.accent.copy(alpha = 0.12f), androidx.compose.foundation.shape.CircleShape))
        androidx.compose.material3.Button(onClick = onStart,
            shape = androidx.compose.foundation.shape.CircleShape,
            colors = androidx.compose.material3.ButtonDefaults.buttonColors(containerColor = Tokens.Color.accent, contentColor = Tokens.Color.onAccent),
            modifier = modifier.then(Modifier.size(190.dp))) {
            Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(Tokens.Space.inline)) {
                Text(stringResource(R.string.start), style = Tokens.Type.title, textAlign = TextAlign.Center)
                Text(stringResource(R.string.targamgi_return_to_game), style = Tokens.Type.caption, textAlign = TextAlign.Center)
            }
        }
    }
}

/** Yalnız kurulu paketin gerçek boyutu; taslaktaki örnek sayı kullanılmaz. */
@Composable
internal fun PackSummaryTile(
    onPacks: (() -> Unit)? = null,
    style: com.couchsubs.mobile.translation.TranslationStyle = Services.settings.translationStyle,
) {
    val context = LocalContext.current
    val language = Services.settings.translationLanguage
    val version by Services.packsVersion.collectAsState()
    val secureState by com.couchsubs.mobile.account.SecureData.state.collectAsState()
    var pack by remember(language, style) { mutableStateOf<com.couchsubs.mobile.core.GamePackLibrary.Info?>(null) }
    LaunchedEffect(version, language, style) {
        pack = withContext(Dispatchers.IO) {
            val infos = Services.packLibrary(context).infos
            infos.firstOrNull { it.id == "gta-6-ar" || it.id == "gta-6-ar-clean" } ?: infos.firstOrNull()
        }
    }
    if (language == "ar") {
        val installed = pack
        if (installed != null) {
            Tile(Modifier.fillMaxWidth(), onClick = onPacks) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Tokens.Space.inline)) {
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(Tokens.Space.hair)) {
                        TileLabel(stringResource(R.string.supported_games_title))
                        Text(stringResource(R.string.supported_games_subtitle, installed.entries), style = Tokens.Type.body, color = Tokens.Color.text)
                    }
                    if (onPacks != null) Chevron()
                }
            }
        } else if (secureState == com.couchsubs.mobile.account.SecureData.SyncState.SYNCING) {
            Tile(Modifier.fillMaxWidth()) {
                Text(stringResource(R.string.phase_preparing_dictionary), style = Tokens.Type.body, color = Tokens.Color.secondary)
            }
        }
    }
}
