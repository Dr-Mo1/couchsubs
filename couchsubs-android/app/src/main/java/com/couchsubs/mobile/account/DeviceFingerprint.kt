// SPDX-License-Identifier: AGPL-3.0-only
package com.couchsubs.mobile.account

import android.media.MediaDrm
import android.os.Build
import java.security.MessageDigest
import java.util.UUID

/**
 * Hardware-backed device fingerprint derived from Widevine DRM's TrustZone/TEE `deviceUniqueId`.
 * Requires zero runtime permissions and survives Clear Data, Uninstall/Reinstall, Dual Apps /
 * Second Space / Work Profiles, IP/VPN changes, and Factory Resets.
 */
object DeviceFingerprint {
    private val WIDEVINE_UUID = UUID(-0x121074568629b532L, -0x5c37d8232ae2de13L)
    private const val DOMAIN_PREFIX = "subtv-wv-v1:"

    /** Pure JVM helper: hashes raw Widevine unique bytes into a 64-char lowercase SHA-256 hex ID. */
    fun hashWidevineBytes(raw: ByteArray?): String? {
        if (raw == null || raw.isEmpty()) return null
        val digest = MessageDigest.getInstance("SHA-256")
        digest.update(DOMAIN_PREFIX.toByteArray(Charsets.UTF_8))
        val out = digest.digest(raw)
        val hex = StringBuilder(out.size * 2)
        for (b in out) {
            hex.append(String.format("%02x", b.toInt() and 0xff))
        }
        return hex.toString()
    }

    /** Reads the hardware-backed Widevine unique ID from TEE; returns null only if Widevine is unavailable. */
    fun readHardwareId(): String? = runCatching {
        var drm: MediaDrm? = null
        try {
            drm = MediaDrm(WIDEVINE_UUID)
            val raw = drm.getPropertyByteArray(MediaDrm.PROPERTY_DEVICE_UNIQUE_ID)
            hashWidevineBytes(raw)
        } finally {
            if (drm != null) {
                runCatching {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) drm.close()
                    else @Suppress("DEPRECATION") drm.release()
                }
            }
        }
    }.getOrNull()
}
