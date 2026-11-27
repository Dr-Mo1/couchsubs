// SPDX-License-Identifier: AGPL-3.0-only
package com.couchsubs.mobile.remoteplay

import com.couchsubs.mobile.core.RunningGame
import java.net.Inet4Address
import java.net.NetworkInterface

/**
 * Ağda bulunan PS5. [runningAppTitleId] ve [runningAppName]: o an çalışan uygulama (keşif yanıtındaki
 * `running-app-titleid`, `running-app-name`); ana ekranda ya da dinlenme modunda null.
 */
data class ConsoleInfo(
    val host: String,
    val name: String,
    val isAwake: Boolean,
    val runningAppTitleId: String? = null,
    val runningAppName: String? = null,
)

/** PS5'e kaydedilmiş bu telefonun bağlantı bilgileri (Windows: RegisteredConsole). Şifreli saklanır. */
class RegisteredConsole(val host: String, val name: String, val registKey: ByteArray, val morning: ByteArray) {
    /** Uyandırma için kayıt anahtarının onaltılık değeri. */
    val wakeCredential: Long?
        get() {
            val end = registKey.indexOf(0).let { if (it < 0) registKey.size else it }
            val text = String(registKey, 0, end, Charsets.US_ASCII)
            return try {
                java.lang.Long.parseUnsignedLong(text, 16)
            } catch (error: NumberFormatException) {
                null
            }
        }

    fun withHost(newHost: String) = RegisteredConsole(newHost, name, registKey, morning)
}

/**
 * Per-(PSN account, console) registration vault stored encrypted in [SecretStore].
 * Each PSN account on a PS5 has its own one-time registration (`registKey` + `morning`); keeping them keyed by
 * `accountId` + console identity ensures switching PSN accounts or updating the app never loses earlier pairings.
 */
object ConsoleVault {
    private val b64Enc = java.util.Base64.getEncoder()
    private val b64Dec = java.util.Base64.getDecoder()

    data class Entry(val accountKey: String, val console: RegisteredConsole)

    fun accountKey(accountId: ByteArray): String = b64Enc.encodeToString(accountId)

    fun parse(json: String?): List<Entry> {
        if (json.isNullOrBlank()) return emptyList()
        return try {
            val array = org.json.JSONArray(json)
            (0 until array.length()).mapNotNull { index ->
                val obj = array.optJSONObject(index) ?: return@mapNotNull null
                val account = obj.optString("accountId", "")
                val host = obj.optString("host", "")
                val name = obj.optString("name", "")
                val regist = obj.optString("registKey", "")
                val morning = obj.optString("morning", "")
                if (account.isEmpty() || host.isEmpty() || regist.isEmpty() || morning.isEmpty()) null
                else Entry(account, RegisteredConsole(host, name, b64Dec.decode(regist), b64Dec.decode(morning)))
            }
        } catch (_: Exception) {
            emptyList()
        }
    }

    fun serialize(entries: List<Entry>): String {
        val array = org.json.JSONArray()
        for (entry in entries) {
            array.put(
                org.json.JSONObject()
                    .put("accountId", entry.accountKey)
                    .put("host", entry.console.host)
                    .put("name", entry.console.name)
                    .put("registKey", b64Enc.encodeToString(entry.console.registKey))
                    .put("morning", b64Enc.encodeToString(entry.console.morning)),
            )
        }
        return array.toString()
    }

    private fun sameConsole(a: RegisteredConsole, b: RegisteredConsole): Boolean =
        if (a.name.isNotBlank() && b.name.isNotBlank()) a.name == b.name else a.host == b.host

    /** Saves or updates [console] under [accountId] (most-recent entry first), preserving other accounts and consoles. */
    fun save(vaultJson: String?, accountId: ByteArray, console: RegisteredConsole): String {
        if (accountId.size != 8) return vaultJson.orEmpty()
        val key = accountKey(accountId)
        val filtered = parse(vaultJson).filterNot { it.accountKey == key && sameConsole(it.console, console) }
        return serialize(listOf(Entry(key, console)) + filtered)
    }

    /** Finds a saved registration for [accountId], preferring matching [preferredName] or [preferredHost] if given. */
    fun find(
        vaultJson: String?,
        accountId: ByteArray,
        preferredName: String? = null,
        preferredHost: String? = null,
    ): RegisteredConsole? {
        if (accountId.size != 8) return null
        val key = accountKey(accountId)
        val matches = parse(vaultJson).filter { it.accountKey == key }.map { it.console }
        if (matches.isEmpty()) return null
        if (!preferredName.isNullOrBlank()) matches.firstOrNull { it.name == preferredName }?.let { return it }
        if (!preferredHost.isNullOrBlank()) matches.firstOrNull { it.host == preferredHost }?.let { return it }
        return matches.first()
    }

    /** Seeds the vault with an existing single-slot [legacyConsole] when migrating an already-paired install. */
    fun migrate(vaultJson: String?, accountId: ByteArray?, legacyConsole: RegisteredConsole?): String? {
        if (accountId == null || accountId.size != 8 || legacyConsole == null) return vaultJson
        val existing = find(vaultJson, accountId, legacyConsole.name, legacyConsole.host)
        if (existing != null) return vaultJson
        return save(vaultJson, accountId, legacyConsole)
    }
}

/**
 * Kaydın kullanıcıya anlatılan sonucu (Windows: RegistrationFailure). [PAIRING_CLOSED]: PS5 ağda ama aramayı reddetti
 * (Cihazı Eşleştir ekranı kapalı ya da PS5 dinlenmede); [CONNECT_FAILED]: PS5 aramaya yanıt verdi ama eşleştirme
 * bağlantısı açılamadı; [NO_FREE_PORT]: telefonda boş port ya da soket kalmadı (GitHub #590).
 */
enum class RegistrationFailure {
    WRONG_ACCOUNT, WRONG_CODE, IN_USE, VERSION_MISMATCH, CONSOLE_CRASHED, BAD_ADDRESS, NO_ANSWER, NO_REPLY,
    PAIRING_CLOSED, CONNECT_FAILED, NO_FREE_PORT, FAILED,
}

class RegistrationException(val failure: RegistrationFailure, val reason: Int? = null, val detail: String? = null) :
    Exception("PS5 registration failed: $failure ${detail ?: ""}")

object Registration {
    private const val INVALID_PSN_ID = 0x80108b02.toInt()
    private const val REGIST_FAILED = 0x80108b09.toInt()
    private const val IN_USE = 0x80108b10.toInt()
    private const val VERSION = 0x80108b11.toInt()
    private const val CRASH = 0x80108b15.toInt()
    private const val SEARCH_ATTEMPTS = 3
    private const val REASON_PREFIX = "Reported Application Reason: "
    /** Boş port ya da soket kalmadığını söyleyen strerror metinleri (EADDRNOTAVAIL, ENOBUFS, EMFILE). */
    private val NO_FREE_PORT_ERRORS = arrayOf("assign requested address", "No buffer space", "Too many open files")

    /**
     * Bu hatadan sonra aynı kodla yeniden denensin mi. Yalnız arama yanıtsız kaldıysa: PS5 aramaya bir kez yanıt verdikten
     * sonra yeni aramayı reddeder; eskiden yapılan iki deneme daha hep reddediliyor ve kullanıcı asıl hata yerine
     * "Connection refused"u görüp PS5'in bağlanmayı reddettiğini sanıyordu (GitHub #590).
     */
    fun shouldRetry(failure: RegistrationFailure, attempt: Int) = failure == RegistrationFailure.NO_ANSWER && attempt < SEARCH_ATTEMPTS

    /** PS5'in gösterdiği 8 haneli kod; boşluk, tire, nokta atlanır. Başka karakter ya da 8'den farklı hane: null. */
    fun parsePin(text: String?): Int? {
        if (text == null) return null
        val digits = StringBuilder(8)
        var index = 0
        while (index < text.length) {
            val codePoint = text.codePointAt(index)
            index += Character.charCount(codePoint)
            if (Character.isWhitespace(codePoint) || codePoint.toChar() in "-–._") continue
            if (Character.getType(codePoint) != Character.DECIMAL_DIGIT_NUMBER.toInt()) return null
            digits.append(Character.digit(codePoint, 10))
        }
        return if (digits.length == 8) digits.toString().toLong().toInt() else null
    }

    /** PS5'in bildirdiği nedenden ya da libchiaki'nin hata satırlarından kullanıcıya anlatılacak hata. */
    fun failureFrom(reason: Int?, errors: List<String>): RegistrationFailure {
        fun any(vararg needles: String) = errors.any { line -> needles.any { line.contains(it) } }
        return when {
            reason == INVALID_PSN_ID -> RegistrationFailure.WRONG_ACCOUNT
            reason == REGIST_FAILED -> RegistrationFailure.WRONG_CODE
            reason == IN_USE -> RegistrationFailure.IN_USE
            reason == VERSION -> RegistrationFailure.VERSION_MISMATCH
            reason == CRASH -> RegistrationFailure.CONSOLE_CRASHED
            reason != null -> RegistrationFailure.FAILED
            any("getaddrinfo") -> RegistrationFailure.BAD_ADDRESS
            // Nedeni (errno) libchiaki yaması yazar (third-party/patches/chiaki-regist-connect-retry.patch).
            any("send search", "for search", "Regist connect failed", "search response") && any(*NO_FREE_PORT_ERRORS) ->
                RegistrationFailure.NO_FREE_PORT
            any("search response") && any("Connection refused") -> RegistrationFailure.PAIRING_CLOSED
            // PS5 aramaya yanıt verdi, bağlantı açılamadı: "PS5 yanıt vermedi" değildir.
            any("connect for request") -> RegistrationFailure.CONNECT_FAILED
            any("search response", "connect for search", "send search", "Regist search failed", "Regist connect failed") ->
                RegistrationFailure.NO_ANSWER
            any("response HTTP header", "receiving response content", "receive response content") -> RegistrationFailure.NO_REPLY
            else -> RegistrationFailure.FAILED
        }
    }

    fun detailFrom(errors: List<String>): String? {
        errors.firstOrNull { it.contains(REASON_PREFIX) }?.let { return it.substringAfter(REASON_PREFIX) }
        return errors.firstOrNull()
    }

    /** PS5'in "Cihazı Bağla" ekranındaki kodla kayıt olur (bloklar, en çok ~30 sn × deneme). */
    fun register(host: String, accountId: ByteArray, pin: Int): RegisteredConsole {
        require(accountId.size == 8) { "PSN account id must be 8 bytes" }
        if (!NativeBridge.available) throw RegistrationException(RegistrationFailure.FAILED, detail = "native library missing")
        var attempt = 1
        while (true) {
            val outcome = NativeOutcome()
            NativeBridge.register(host, accountId, pin, outcome)
            if (outcome.code == 0) {
                return RegisteredConsole(host, outcome.nickname ?: "PS5", outcome.registKey!!, outcome.morning!!)
            }
            val lines = outcome.errorLines
            val reported = if (outcome.code == 1 && outcome.reason != 0) outcome.reason else null
            val failure = when (outcome.code) {
                1 -> failureFrom(reported, lines)
                2 -> RegistrationFailure.NO_REPLY
                else -> RegistrationFailure.FAILED
            }
            // Kod PS5'e gitmeden biten arama aşamasında paket kaybı olabilir; yeniden denemek güvenlidir.
            if (!shouldRetry(failure, attempt))
                throw RegistrationException(failure, reported, detailFrom(lines))
            attempt++
            Thread.sleep(500)
        }
    }
}

/** PS5'i ağda arar ve uyandırır (libchiaki'nin arama protokolü). */
object ConsoleDiscovery {
    /** [host] verilirse yalnız ona, yoksa telefonun bulunduğu /24 ağın bütün adreslerine sorar (bloklar). */
    fun discover(host: String?, timeoutMs: Int = 2000): List<ConsoleInfo> {
        if (!NativeBridge.available) return emptyList()
        val targets = if (host != null) listOf(host) else subnetAddresses()
        if (targets.isEmpty()) return emptyList()
        return NativeBridge.discover(targets.toTypedArray(), timeoutMs)?.mapNotNull { line ->
            parseLine(line)
        } ?: emptyList()
    }

    /** "adres\tad\tuyanık(0/1)[\toyun kimliği\toyun adı]" (yerel kütüphanenin yanıt satırı). */
    fun parseLine(line: String): ConsoleInfo? {
        val parts = line.split('\t')
        if (parts.size < 3) return null
        return ConsoleInfo(parts[0], parts[1], parts[2] == "1", parts.getOrNull(3)?.ifEmpty { null }, parts.getOrNull(4)?.ifEmpty { null })
    }

    /** PS5'te o an çalışan oyun: yalnız bağlı PS5'in adresine sorulur (bloklar; ortak havuz için). */
    fun runningGame(host: String): RunningGame? =
        discover(host, 2000).firstOrNull()?.let { RunningGame.create(it.runningAppTitleId, it.runningAppName) }

    fun wake(console: RegisteredConsole) {
        val credential = console.wakeCredential ?: throw IllegalStateException("Registration key is not a wake credential")
        NativeBridge.wake(console.host, credential)
    }

    /**
     * Adresi değişen kayıtlı PS5: adı (kayıtta PS5'in verdiği ad) tek eşleşen; emin değilse null. Eskiden ağda tek PS5
     * varsa adına bakılmadan o seçiliyordu; aynı evde iki PS5 varken uygulama diğerine geçiyordu (GitHub #425).
     */
    fun pickMoved(found: List<ConsoleInfo>, name: String): ConsoleInfo? =
        found.filter { it.name == name }.singleOrNull()

    /** The registered PS5 found by a whole-network scan, and whether it now answers at another address than the saved one. */
    data class Relocation(val info: ConsoleInfo, val moved: Boolean)

    /**
     * Result of a whole-network scan for the registered PS5 ([name]); null when it was not found (or two PS5s carry the
     * name). Found at the SAVED address too (it only answered the scan, e.g. it was still waking): returned with
     * [Relocation.moved] false, so the caller keeps its awake/rest state instead of connecting blind.
     */
    fun relocation(found: List<ConsoleInfo>, name: String, savedHost: String): Relocation? =
        pickMoved(found, name)?.let { Relocation(it, it.host != savedHost) }

    /** Aramaya yanıt veren PS5 kayıtlı PS5 mi (ad eşleşmesi; kayıtta ad yoksa her yanıt kabul edilir). */
    fun isRegistered(info: ConsoleInfo, name: String): Boolean = name.isEmpty() || info.name == name

    /** Telefonun Wi-Fi ya da Ethernet adresinin /24 ağındaki diğer adresler. */
    fun subnetAddresses(): List<String> {
        val local = localIPv4() ?: return emptyList()
        val prefix = local.substringBeforeLast('.')
        return (1..254).map { "$prefix.$it" }.filter { it != local }
    }

    /** Özel ağdaki (192.168/10/172.16-31) ilk IPv4 adresi; hücresel ve VPN arayüzleri atlanır. */
    fun localIPv4(): String? = try {
        NetworkInterface.getNetworkInterfaces().toList()
            .filter { it.isUp && !it.isLoopback && !it.name.startsWith("rmnet") && !it.name.startsWith("tun") }
            .sortedBy { if (it.name.startsWith("wlan")) 0 else 1 }
            .flatMap { it.inetAddresses.toList() }
            .filterIsInstance<Inet4Address>()
            .map { it.hostAddress ?: "" }
            .firstOrNull { isPrivate(it) }
    } catch (error: Exception) {
        null
    }

    fun isPrivate(address: String): Boolean {
        val parts = address.split('.').mapNotNull { it.toIntOrNull() }
        if (parts.size != 4) return false
        return parts[0] == 10 || (parts[0] == 192 && parts[1] == 168) || (parts[0] == 172 && parts[1] in 16..31)
    }
}
