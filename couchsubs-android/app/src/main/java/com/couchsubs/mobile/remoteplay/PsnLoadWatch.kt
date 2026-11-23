// SPDX-License-Identifier: AGPL-3.0-only
// Sony giriş sayfasının açılıp açılmadığına karar verir. Sayaç ekran açıldığı anda başlar ve WebView'in olaylarına
// bağlı değildir: WebView hiç yüklemeye başlamasa, çerez silme hiç dönmese ya da Sony'nin sayfası kendi yükleme
// simgesinde takılsa da süre dolunca hata ekranı çıkar (kullanıcı sonsuz yükleme ekranında kalmaz).
package com.couchsubs.mobile.remoteplay

class PsnLoadWatch(private val startedAtMs: Long) {
    /** Ana sayfa en az bir kez onPageFinished verdi. */
    var pageFinished = false
        private set

    /** Sony'nin sayfasında kullanıcının dokunabileceği bir alan (e-posta kutusu, düğme) çizildi; sayaç durur. */
    var ready = false
        private set

    /** Yönlendirme yakalandı (giriş bitti); sayaç durur. */
    var redirected = false
        private set

    fun onPageFinished() {
        pageFinished = true
    }

    /** Sayfadaki giriş alanı ve düğme sayısı (evaluateJavascript sonucu). */
    fun onInteractiveCount(result: String?) {
        if ((result?.trim('"')?.toIntOrNull() ?: 0) > 0) ready = true
    }

    fun onRedirect() {
        redirected = true
    }

    /** [nowMs] anında giriş sayfası hâlâ kullanılamıyorsa nedeni; yoksa null. */
    fun problemAt(nowMs: Long): PsnPageProblem? = when {
        ready || redirected -> null
        nowMs - startedAtMs < LOAD_TIMEOUT_MS -> null
        // Sayfa geldi ama Sony'nin uygulaması çizilmedi (boş ya da kendi yükleme simgesinde takıldı).
        pageFinished -> PsnPageProblem.BLANK
        else -> PsnPageProblem.TIMEOUT
    }

    companion object {
        /**
         * Ekran açıldıktan sonra Sony'nin giriş formu bu sürede çıkmazsa hata ekranı. Measured 2026-11-23 on DocMo's phone:
         * the form shows after ~10 s in Chrome and ~20 s in the app, so the old 20 s cut working sign-ins off.
         */
        const val LOAD_TIMEOUT_MS = 45_000L

        /**
         * Yönlendirme yakalandıktan sonra hesap kimliğinin Sony'den alınması (iki istek) en çok bu kadar beklenir; sonra
         * hata ve "Yeniden dene" gösterilir. Her isteğin kendi zaman aşımı (30 sn bağlanma + 30 sn okuma) iki istekte
         * iki dakikayı geçebiliyordu.
         */
        const val TOKEN_TIMEOUT_MS = 45_000L

        /**
         * Önceki oturumun çerezlerini silmek için en çok bu kadar beklenir; sonra sayfa yine açılır. Silme ekran açılınca
         * başlar; soğuk WebView'de (uygulamanın ilk girişi) 2 sn yetmiyordu (GitHub #54).
         */
        const val COOKIE_WAIT_MS = 5_000L

        /** Sayfa yüklendikten sonra giriş formunun çizilip çizilmediğine bu aralıkla bakılır. */
        const val READY_POLL_MS = 1_000L

        /**
         * Sayfada kullanıcının dokunabileceği öğe sayısı: yalnız ekranda gerçekten görünenler. A form that is in the DOM but
         * clipped away (2026-11-23: Sony's page laid out at zero height) hit-tests to the root, so it does not count.
         */
        const val INTERACTIVE_SCRIPT = "Array.prototype.filter.call(document.querySelectorAll('input:not([type=hidden]),button')," +
            "function(e){var r=e.getBoundingClientRect(),h=r.width&&r.height&&document.elementFromPoint(r.left+r.width/2," +
            "r.top+r.height/2);return h&&h!==document.documentElement&&h!==document.body}).length"
    }
}
