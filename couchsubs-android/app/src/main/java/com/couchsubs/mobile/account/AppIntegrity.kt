// SPDX-License-Identifier: AGPL-3.0-only
// Anti-tamper & APK signature integrity guard: blocks modded/re-signed/cloned release builds.
package com.couchsubs.mobile.account

import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.os.Build
import com.couchsubs.mobile.BuildConfig
import java.security.MessageDigest

/** Pure JVM-testable integrity rules. */
object AppIntegrityRules {
    private const val MASK = 0x5A
    private const val EXPECTED_PKG = "com.targamgi.app"

    // SHA-256 of DocMo's official release keystore (8e27d87f...4cb5c1a), XOR-masked with 0x5A.
    private val maskedCertSha256 = intArrayOf(
        0x8E xor MASK, 0x27 xor MASK, 0xD8 xor MASK, 0x7F xor MASK,
        0x97 xor MASK, 0x27 xor MASK, 0x81 xor MASK, 0x6C xor MASK,
        0xFC xor MASK, 0x7C xor MASK, 0xB1 xor MASK, 0x8A xor MASK,
        0xB7 xor MASK, 0x72 xor MASK, 0xFA xor MASK, 0x69 xor MASK,
        0x3D xor MASK, 0xC1 xor MASK, 0xC4 xor MASK, 0x93 xor MASK,
        0x03 xor MASK, 0xE3 xor MASK, 0x90 xor MASK, 0x34 xor MASK,
        0x82 xor MASK, 0xDA xor MASK, 0xF9 xor MASK, 0x1F xor MASK,
        0x14 xor MASK, 0xCB xor MASK, 0x5C xor MASK, 0x1A xor MASK,
    )

    fun expectedCertSha256(): ByteArray = ByteArray(maskedCertSha256.size) { i ->
        (maskedCertSha256[i] xor MASK).toByte()
    }

    fun isValid(
        isDebugBuild: Boolean,
        packageName: String,
        isDebuggableFlag: Boolean,
        signerCertSha256List: List<ByteArray>,
    ): Boolean {
        if (isDebugBuild) return true
        if (packageName != EXPECTED_PKG) return false
        if (isDebuggableFlag) return false
        if (signerCertSha256List.isEmpty()) return false
        val expected = expectedCertSha256()
        return signerCertSha256List.any { MessageDigest.isEqual(it, expected) }
    }
}

object AppIntegrity {
    @Volatile private var cachedOk: Boolean? = null

    fun verify(context: Context): Boolean {
        cachedOk?.let { return it }
        val app = context.applicationContext
        val ok = runCatching {
            val isDebuggable = (app.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE) != 0
            val certs = readSignerSha256s(app)
            AppIntegrityRules.isValid(
                isDebugBuild = BuildConfig.DEBUG,
                packageName = app.packageName,
                isDebuggableFlag = isDebuggable,
                signerCertSha256List = certs,
            )
        }.getOrDefault(false)
        cachedOk = ok
        return ok
    }

    @Suppress("DEPRECATION")
    private fun readSignerSha256s(context: Context): List<ByteArray> {
        val pm = context.packageManager
        val pkg = context.packageName
        val rawSigners = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            val info = pm.getPackageInfo(pkg, PackageManager.GET_SIGNING_CERTIFICATES)
            val signingInfo = info.signingInfo ?: return emptyList()
            if (signingInfo.hasMultipleSigners()) signingInfo.apkContentsSigners
            else signingInfo.signingCertificateHistory
        } else {
            val info = pm.getPackageInfo(pkg, PackageManager.GET_SIGNATURES)
            info.signatures
        } ?: return emptyList()
        val md = MessageDigest.getInstance("SHA-256")
        return rawSigners.map { sig -> md.digest(sig.toByteArray()) }
    }
}
