// SPDX-License-Identifier: AGPL-3.0-only
package com.couchsubs.mobile.ui

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.mapSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.couchsubs.mobile.R
import com.couchsubs.mobile.Services
import com.couchsubs.mobile.reporting.MediaError
import com.couchsubs.mobile.reporting.ProblemReporter
import com.couchsubs.mobile.reporting.ReportMedia
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.Locale

private data class ReportAttachment(
    val uri: String,
    val name: String,
    val mimeType: String,
    val sizeBytes: Long,
)

private val attachmentsSaver = listSaver<List<ReportAttachment>, Any>(
    save = { items -> items.flatMap { listOf(it.uri, it.name, it.mimeType, it.sizeBytes) } },
    restore = { flat ->
        flat.chunked(4).mapNotNull { chunk ->
            if (chunk.size == 4) ReportAttachment(
                uri = chunk[0] as String,
                name = chunk[1] as String,
                mimeType = chunk[2] as String,
                sizeBytes = (chunk[3] as Number).toLong(),
            ) else null
        }
    },
)

private val uploadedPathsSaver = mapSaver<Map<String, String>>(
    save = { it },
    restore = { restored -> restored.entries.associate { (k, v) -> k to (v as String) } },
)

private fun resolveAttachment(context: Context, uri: Uri): Pair<ReportAttachment?, Int?> {
    val resolver = context.contentResolver
    val mimeType = resolver.getType(uri)
    var displayName: String? = null
    var sizeBytes = -1L
    resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)?.use { cursor ->
        if (cursor.moveToFirst()) {
            val nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            if (nameIndex >= 0 && !cursor.isNull(nameIndex)) displayName = cursor.getString(nameIndex)
            val sizeIndex = cursor.getColumnIndex(OpenableColumns.SIZE)
            if (sizeIndex >= 0 && !cursor.isNull(sizeIndex)) sizeBytes = cursor.getLong(sizeIndex)
        }
    }
    if (sizeBytes < 0L) {
        runCatching { resolver.openAssetFileDescriptor(uri, "r")?.use { it.length } }.getOrNull()?.let {
            if (it >= 0L) sizeBytes = it
        }
    }
    val error = ReportMedia.validate(mimeType, sizeBytes)
    if (error != null) {
        val errorRes = when (error) {
            MediaError.UNSUPPORTED_TYPE -> R.string.report_media_unsupported
            MediaError.TOO_LARGE -> R.string.report_media_too_large
        }
        return null to errorRes
    }
    val normalizedMime = ReportMedia.normalizeMime(mimeType) ?: return null to R.string.report_media_unsupported
    val name = displayName?.trim()?.takeIf { it.isNotEmpty() } ?: context.getString(R.string.report_media_fallback_name)
    return ReportAttachment(uri.toString(), name, normalizedMime, sizeBytes) to null
}

@Composable
private fun formatAttachmentSize(sizeBytes: Long): String =
    if (sizeBytes >= 1024L * 1024L) {
        stringResource(R.string.report_size_mb, String.format(Locale.US, "%.1f", sizeBytes / (1024.0 * 1024.0)))
    } else {
        stringResource(R.string.report_size_kb, maxOf(1L, (sizeBytes + 512L) / 1024L))
    }

@Composable
fun ProblemReportScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val active = remember { java.util.concurrent.atomic.AtomicBoolean(true) }
    DisposableEffect(Unit) { onDispose { active.set(false) } }
    var description by rememberSaveable { mutableStateOf("") }
    var name by rememberSaveable { mutableStateOf("") }
    var attach by rememberSaveable { mutableStateOf(true) }
    var attachments by rememberSaveable(stateSaver = attachmentsSaver) { mutableStateOf(emptyList()) }
    var uploadedPaths by rememberSaveable(stateSaver = uploadedPathsSaver) { mutableStateOf(emptyMap()) }
    var sending by remember { mutableStateOf(false) }
    var uploadProgress by remember { mutableStateOf<Pair<Int, Int>?>(null) }
    var mediaErrorRes by remember { mutableStateOf<Int?>(null) }
    var failedRes by remember { mutableStateOf<Int?>(null) }
    var pendingPath by rememberSaveable { mutableStateOf<String?>(null) }

    val picker = rememberLauncherForActivityResult(
        ActivityResultContracts.PickMultipleVisualMedia(ReportMedia.MAX_COUNT),
    ) { uris ->
        if (uris.isEmpty()) return@rememberLauncherForActivityResult
        mediaErrorRes = null
        val appContext = context.applicationContext
        val current = attachments
        scope.launch {
            val (updated, errorRes) = withContext(Dispatchers.IO) {
                val list = current.toMutableList()
                var firstError: Int? = null
                for (uri in uris) {
                    if (list.size >= ReportMedia.MAX_COUNT) break
                    if (list.any { it.uri == uri.toString() }) continue
                    val (item, err) = resolveAttachment(appContext, uri)
                    if (item != null) list.add(item) else if (firstError == null) firstError = err
                }
                list.toList() to firstError
            }
            attachments = updated
            mediaErrorRes = errorRes
        }
    }

    // Sending belongs to Services.scope: leaving the screen does not cancel the persisted report.
    ScreenScaffold(top = { TopBar(stringResource(R.string.report_title), onBack = onBack) }, bottom = {
        if (sending) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(Tokens.Space.inline),
            ) {
                CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                uploadProgress?.let { (current, total) ->
                    Text(
                        stringResource(R.string.report_media_uploading, current, total),
                        style = Tokens.Type.caption,
                        color = Tokens.Color.secondary,
                    )
                }
            }
        }
        PrimaryButton(stringResource(R.string.report_send), enabled = description.isNotBlank() && !sending) {
            sending = true
            failedRes = null
            mediaErrorRes = null
            uploadProgress = null
            val message = description
            val sender = name
            val includeLog = attach
            val chosen = attachments
            val knownUploads = uploadedPaths
            val appContext = context.applicationContext
            Services.scope.launch(Dispatchers.Main) {
                val (uploadsOk, updatedUploads) = ReportMedia.uploadAll(
                    items = chosen,
                    alreadyUploaded = knownUploads,
                    key = { it.uri },
                    onProgress = { current, total -> uploadProgress = current to total },
                ) { item ->
                    ProblemReporter.uploadMedia(appContext, Uri.parse(item.uri), item.mimeType, item.sizeBytes)
                }
                uploadedPaths = updatedUploads
                uploadProgress = null
                if (!uploadsOk) {
                    sending = false
                    failedRes = R.string.report_media_upload_failed
                    return@launch
                }
                val mediaPaths = chosen.mapNotNull { updatedUploads[it.uri] }
                val (success, file) = ProblemReporter.user(
                    message,
                    sender,
                    includeLog,
                    pendingPath?.let(::File),
                    media = mediaPaths,
                )
                pendingPath = file?.path
                sending = false
                if (success) {
                    Toast.makeText(appContext, R.string.report_sent, Toast.LENGTH_LONG).show()
                    if (active.get()) onBack()
                } else failedRes = R.string.report_failed
            }
        }
    }) {
        Field(description, { if (!sending) { description = it.take(4000) } }, stringResource(R.string.report_description),
            lines = 5, placeholder = stringResource(R.string.report_hint))
        Field(name, { if (!sending) name = it.take(80) }, stringResource(R.string.report_name))
        RowItem(stringResource(R.string.report_attach)) { Switch(attach, { if (!sending) attach = it }) }
        TextButton(
            onClick = {
                mediaErrorRes = null
                picker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageAndVideo))
            },
            enabled = !sending && attachments.size < ReportMedia.MAX_COUNT,
            modifier = Modifier.heightIn(min = Tokens.Control.buttonSmall),
        ) {
            Text(
                stringResource(R.string.report_attach_media),
                style = Tokens.Type.headline,
                color = if (!sending && attachments.size < ReportMedia.MAX_COUNT) Tokens.Color.accentText else Tokens.Color.secondary,
            )
        }
        if (attachments.isNotEmpty()) {
            RowList(
                attachments.map { item ->
                    {
                        Row(
                            Modifier.fillMaxWidth().padding(vertical = Tokens.Space.hair),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(Tokens.Space.inline),
                        ) {
                            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                Text(item.name, style = Tokens.Type.body, color = Tokens.Color.text, maxLines = 1,
                                    overflow = TextOverflow.Ellipsis)
                                Text(formatAttachmentSize(item.sizeBytes), style = Tokens.Type.caption, color = Tokens.Color.secondary)
                            }
                            IconButton(
                                onClick = {
                                    if (!sending) {
                                        attachments = attachments.filterNot { it.uri == item.uri }
                                        mediaErrorRes = null
                                    }
                                },
                                enabled = !sending,
                            ) {
                                Icon(Icons.Filled.Close, stringResource(R.string.report_media_remove), tint = Tokens.Color.secondary)
                            }
                        }
                    }
                },
            )
        }
        mediaErrorRes?.let { NoteTile(stringResource(it)) }
        failedRes?.let { NoteTile(stringResource(it)) }
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
