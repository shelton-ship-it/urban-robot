package io.pixgo.app.data.network

import android.webkit.CookieManager

/**
 * A única WebView da app (checkout/planos, ver AccountAction.UPGRADE) é
 * uma excepção deliberada, autorizada pelo dono do projecto — não uma
 * camada geral a encapsular a app inteira (isso continua proibido pela
 * missão original). Sem isto, a WebView pediria login de novo, porque o
 * android.webkit.CookieManager tem o SEU PRÓPRIO cookie store, separado
 * do CookieJar do OkHttp usado pelo resto da app.
 */
object WebViewCookieSync {
    fun syncSessionCookies() {
        val cm = CookieManager.getInstance()
        cm.setAcceptCookie(true)
        NetworkModule.exportCookiesForWebView().forEach { cookie ->
            val scheme = if (cookie.secure) "https" else "http"
            val url = "$scheme://${cookie.domain.trimStart('.')}/"
            val cookieStr = buildString {
                append(cookie.name).append('=').append(cookie.value)
                // hostOnly => sem atributo Domain (senão o cookie passaria a valer em subdomínios).
                if (!cookie.hostOnly) append("; Domain=").append(cookie.domain)
                append("; Path=").append(cookie.path)
                if (cookie.secure) append("; Secure")
                if (cookie.httpOnly) append("; HttpOnly")
                // pixgo_session é emitido com SameSite=None (session-cookie.js); sem isto o
                // Chromium assume Lax e a sessão não acompanha os fetch do hub dentro da WebView.
                if (cookie.secure) append("; SameSite=None")
            }
            cm.setCookie(url, cookieStr)
        }
        cm.flush()
    }
}
