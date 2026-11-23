// SPDX-License-Identifier: AGPL-3.0-only
// Over-the-air update, Android side: checks update.json, downloads and verifies the APK (UpdatePolicy.kt) and hands it to a
// PackageInstaller session. The dialog (ui/UpdateDialog.kt) only observes [UpdateController.state]. Checks are silent:
// offline or a GitHub error is a log line, never UI (except the manual button in Settings).
package com.couchsubs.mobile.update

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.os.Build
import android.util.Log
import com.couchsubs.mobile.BuildConfig
import com.couchsubs.mobile.Services
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException

sealed interface UpdateState {
    data object Idle : UpdateState
    /** A newer release exists: the dialog asks "Update now / Later". */
    data class Available(val info: UpdateInfo) : UpdateState
    data class Downloading(val info: UpdateInfo, val bytes: Long) : UpdateState
    /** "Install unknown apps" is off for this app: the dialog explains and opens that settings page; [UpdateController.onResume] continues. */
    data class NeedsPermission(val info: UpdateInfo) : UpdateState
    /** Session committed; waiting for the system's confirmation screen. */
    data class Installing(val info: UpdateInfo) : UpdateState
    data class Failed(val info: UpdateInfo, val reason: Reason, val detail: String? = null) : UpdateState

    enum class Reason { NETWORK, CORRUPT, INSTALL }
}

enum class CheckResult { AVAILABLE, UP_TO_DATE, FAILED }

object UpdateController {
    private const val TAG = "TargamgiUpdate"

    private val current = MutableStateFlow<UpdateState>(UpdateState.Idle)
    val state: StateFlow<UpdateState> = current

    /** Highest versionCode the user dismissed with "Later" since this cold start (see UpdatePolicy.shouldShow). */
    @Volatile private var dismissedCode = 0
    private val checkLock = Mutex()
    @Volatile private var work: Job? = null

    /** Downloaded APKs live only here; the whole folder is wiped on start. */
    private fun folder(context: Context) = File(context.cacheDir, "update")

    private fun infoOf(state: UpdateState): UpdateInfo? = when (state) {
        UpdateState.Idle -> null
        is UpdateState.Available -> state.info
        is UpdateState.Downloading -> state.info
        is UpdateState.NeedsPermission -> state.info
        is UpdateState.Installing -> state.info
        is UpdateState.Failed -> state.info
    }

    /** Cold start of the main activity: forget "Later", delete old APKs and abandoned install sessions, check for an update. */
    fun coldStart(context: Context) {
        val app = context.applicationContext
        dismissedCode = 0
        Services.scope.launch(Dispatchers.IO) {
            // A download or install already under way (activity recreated, process alive) must not lose its file.
            if (current.value is UpdateState.Idle || current.value is UpdateState.Available) cleanup(app)
            check(app, manual = false)
        }
    }

    private fun cleanup(context: Context) {
        folder(context).listFiles()?.forEach { it.delete() }
        try {
            val installer = context.packageManager.packageInstaller
            installer.mySessions.forEach { installer.abandonSession(it.sessionId) }
        } catch (error: RuntimeException) {
            Log.i(TAG, "old install sessions not cleaned: ${error.message}")
        }
    }

    /**
     * Reads update.json. A newer release (not dismissed this cold start) moves [state] to Available so the dialog shows.
     * [manual] (Settings button) clears the dismissal, so the dialog comes back, and the caller shows the result text.
     */
    suspend fun check(context: Context, manual: Boolean): CheckResult {
        if (BuildConfig.UPDATE_URL.isBlank()) return CheckResult.FAILED
        if (manual) dismissedCode = 0
        return checkLock.withLock {
            withContext(Dispatchers.IO) {
                try {
                    val info = UpdatePolicy.parse(UpdateDownload.fetchText(BuildConfig.UPDATE_URL))
                    when {
                        info == null -> {
                            Log.i(TAG, "update.json is missing a field or invalid")
                            CheckResult.FAILED
                        }
                        !UpdatePolicy.isNewer(info, BuildConfig.VERSION_CODE) -> CheckResult.UP_TO_DATE
                        else -> {
                            if (UpdatePolicy.shouldShow(info, BuildConfig.VERSION_CODE, dismissedCode)) {
                                // A download, install or error already on screen is not replaced.
                                current.update { if (it is UpdateState.Idle || it is UpdateState.Available) UpdateState.Available(info) else it }
                            }
                            CheckResult.AVAILABLE
                        }
                    }
                } catch (error: CancellationException) {
                    throw error
                } catch (error: Exception) {
                    Log.i(TAG, "update check failed: ${error.message}")
                    CheckResult.FAILED
                }
            }
        }
    }

    /** "Update now" / "Try again": download, verify, install. */
    fun startUpdate(context: Context) {
        val info = infoOf(current.value) ?: return
        if (current.value is UpdateState.Downloading || current.value is UpdateState.Installing) return
        val app = context.applicationContext
        work?.cancel()
        work = Services.scope.launch(Dispatchers.IO) { downloadAndInstall(app, info) }
    }

    /** "Later" / "Close" / "Cancel": hides this release until the next cold start; a running download stops and its file is deleted. */
    fun later() {
        val info = infoOf(current.value) ?: return
        work?.cancel()
        dismissedCode = maxOf(dismissedCode, info.versionCode)
        current.value = UpdateState.Idle
    }

    /** Back from the "Install unknown apps" settings page: carry on if it was granted. */
    fun onResume(context: Context) {
        if (current.value is UpdateState.NeedsPermission && context.packageManager.canRequestPackageInstalls()) startUpdate(context)
    }

    private suspend fun downloadAndInstall(context: Context, info: UpdateInfo) {
        val file = File(folder(context), "targamgi-${info.versionCode}.apk")
        current.value = UpdateState.Downloading(info, 0)
        try {
            var reported = 0L
            UpdateDownload.downloadVerified(info, file) { done ->
                // About 4 updates per MB is plenty for a progress bar.
                if (done - reported >= 256 * 1024 || done == info.sizeBytes) {
                    reported = done
                    // Not after "Later"/"Cancel" already reset the state.
                    current.update { if (it is UpdateState.Downloading) UpdateState.Downloading(info, done) else it }
                }
            }
        } catch (error: UpdateException) {
            Log.w(TAG, "download failed: ${error.kind} ${error.message}")
            file.delete()
            current.value = UpdateState.Failed(info, if (error.kind == UpdateFailureKind.NETWORK) UpdateState.Reason.NETWORK else UpdateState.Reason.CORRUPT, error.message)
            return
        }
        install(context, info, file)
    }

    private fun install(context: Context, info: UpdateInfo, file: File) {
        if (!context.packageManager.canRequestPackageInstalls()) {
            current.value = UpdateState.NeedsPermission(info)
            return
        }
        current.value = UpdateState.Installing(info)
        try {
            commit(context, file)
        } catch (error: IOException) {
            Log.w(TAG, "install session failed", error)
            current.value = UpdateState.Failed(info, UpdateState.Reason.INSTALL, error.message)
        } catch (error: RuntimeException) {
            Log.w(TAG, "install session failed", error)
            current.value = UpdateState.Failed(info, UpdateState.Reason.INSTALL, error.message)
        }
    }

    /** PackageInstaller session: write the APK, commit with a PendingIntent to [UpdateInstallReceiver]. */
    private fun commit(context: Context, file: File) {
        val installer = context.packageManager.packageInstaller
        val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL).apply {
            setAppPackageName(context.packageName) // only an update of this app can be installed through this session
            setSize(file.length())
        }
        val id = installer.createSession(params)
        try {
            installer.openSession(id).use { session ->
                file.inputStream().use { input ->
                    session.openWrite("targamgi.apk", 0, file.length()).use { output ->
                        input.copyTo(output)
                        session.fsync(output)
                    }
                }
                // The system fills the status extras into this intent, so it must be mutable (API 31+); it is explicit, as required.
                val flags = PendingIntent.FLAG_UPDATE_CURRENT or (if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) PendingIntent.FLAG_MUTABLE else 0)
                val callback = PendingIntent.getBroadcast(context, id, Intent(context, UpdateInstallReceiver::class.java), flags)
                session.commit(callback.intentSender)
            }
        } catch (error: Exception) {
            installer.abandonSession(id)
            throw error
        }
    }

    /** Result from [UpdateInstallReceiver]. Cancelling the system screen offers the update again; anything else is an error. */
    fun installFailed(status: Int, message: String?) {
        val info = (current.value as? UpdateState.Installing)?.info ?: return
        Log.w(TAG, "install status $status: $message")
        current.value = if (status == PackageInstaller.STATUS_FAILURE_ABORTED) UpdateState.Available(info)
        else UpdateState.Failed(info, UpdateState.Reason.INSTALL, message)
    }
}

/** Receives the PackageInstaller session result (not exported: only our own PendingIntent reaches it). */
class UpdateInstallReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)
        val message = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE)
        when (status) {
            PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                @Suppress("DEPRECATION")
                val confirm = if (Build.VERSION.SDK_INT >= 33) intent.getParcelableExtra(Intent.EXTRA_INTENT, Intent::class.java)
                else intent.getParcelableExtra<Intent>(Intent.EXTRA_INTENT)
                try {
                    if (confirm == null) throw IllegalStateException("no confirmation intent")
                    context.startActivity(confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                } catch (error: RuntimeException) {
                    UpdateController.installFailed(status, error.message)
                }
            }
            // The app is replaced and restarted by the system; nothing to show.
            PackageInstaller.STATUS_SUCCESS -> Log.i("TargamgiUpdate", "installed")
            else -> UpdateController.installFailed(status, message)
        }
    }
}
