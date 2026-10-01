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
                append("; Domain=").append(cookie.domain)
                append("; Path=").append(cookie.path)
                if (cookie.secure) append("; Secure")
            }
            cm.setCookie(url, cookieStr)
        }
        cm.flush()
    }
}
