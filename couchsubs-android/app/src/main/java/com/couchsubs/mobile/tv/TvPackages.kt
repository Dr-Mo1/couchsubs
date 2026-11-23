// SPDX-License-Identifier: AGPL-3.0-only
package com.couchsubs.mobile.tv

import android.content.Context
import java.io.File

/**
 * Uygulamayla gelen TV paketleri (derlemede son GitHub sürümünden indirilir, bkz. build.gradle tvReleasePackages):
 * `assets/tv/tv-packages.properties` hangi sürümden geldiklerini yazar.
 */
object TvPackages {
    const val ANDROID_TV_APK = "couchsubs-androidtv.apk"
    private const val INDEX = "tv/tv-packages.properties"

    /** `anahtar=değer` satırları. */
    fun parse(text: String): Map<String, String> = text.lineSequence().map { it.trim() }
        .filter { it.isNotEmpty() && !it.startsWith("#") && '=' in it }
        .associate { it.substringBefore('=').trim() to it.substringAfter('=').trim() }

    fun index(context: Context): Map<String, String> =
        runCatching { context.assets.open(INDEX).use { parse(String(it.readBytes(), Charsets.UTF_8)) } }.getOrDefault(emptyMap())

    const val LG_IPK = "couchsubs-lg.ipk"
    private const val SAMSUNG_WGT = "tv/couchsubs-samsung.wgt"

    /** [index] lists the package the phone can install on [brand]; Samsung's .wgt is not in the index (always built). */
    fun listed(index: Map<String, String>, brand: TvBrand): Boolean = when (brand) {
        TvBrand.LG -> LG_IPK in index
        TvBrand.ANDROID_TV -> ANDROID_TV_APK in index
        TvBrand.SAMSUNG -> true
    }

    /**
     * Whether this build can install the TV app on [brand] from the phone. The LG and Android TV packages are downloaded at
     * build time (tvReleasePackages) and are missing when that fails; the install screens must not offer a path that ends in
     * "package missing". The Samsung .wgt is built from source every time.
     */
    fun bundled(context: Context, brand: TvBrand): Boolean =
        if (brand == TvBrand.SAMSUNG) runCatching { context.assets.open(SAMSUNG_WGT).close() }.isSuccess else listed(index(context), brand)

    /** LG paketi, sürümdeki adı (`com.couchsubs.tv_0.9.18_all.ipk`) ve sürümü; paket yoksa null. */
    fun lg(context: Context): Triple<ByteArray, String, String>? {
        val name = index(context)[LG_IPK] ?: return null
        val version = com.couchsubs.mobile.tv.lg.LgInstaller.versionOfPackage(name) ?: return null
        return runCatching { context.assets.open("tv/$LG_IPK").use { it.readBytes() } }.getOrNull()?.let { Triple(it, name, version) }
    }

    /** Android TV paketi ve sürümü (APK'nın kendisinden okunur); paket yoksa null. */
    fun androidTv(context: Context): Pair<File, String?>? {
        if (ANDROID_TV_APK !in index(context)) return null
        val file = File(context.cacheDir, ANDROID_TV_APK)
        return try {
            context.assets.open("tv/$ANDROID_TV_APK").use { input -> file.outputStream().use { input.copyTo(it) } }
            file to context.packageManager.getPackageArchiveInfo(file.path, 0)?.versionName
        } catch (error: Exception) {
            null
        }
    }
}
