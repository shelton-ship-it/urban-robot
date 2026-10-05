package io.pixgo.app.ui.auth

import android.annotation.SuppressLint
import android.graphics.Color as AndroidColor
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import io.pixgo.app.data.auth.AuthRepository
import io.pixgo.app.data.network.NetworkModule
import io.pixgo.app.data.network.WebViewCookieSync
import kotlinx.coroutines.launch

/**
 * Réplica do fluxo REAL e ativo de login/registo do frontend_web:
 * app/auth/login/page.tsx e register/page.tsx NÃO têm formulário — fazem
 * window.location.replace('https://app.pixgo.qzz.io/auth/{login,register}
 * &amp;return_to=...'). No Android o "redirect" é esta WebView dedicada ao
 * hub (mesma exceção deliberada já autorizada para o checkout em
 * FastWebViewSheet); a app em si continua 100% nativa.
 *
 * Ao autenticar, o hub grava o cookie de sessão partilhado
 * Domain=.pixgo.qzz.io (session-cookie.js) e devolve o token Bearer no
 * corpo/localStorage. Sincronizamos os dois sentidos:
 *  - cookies do webkit -> jar OkHttp (NetworkModule.importCookiesFromWebView);
 *  - token via bridge JS (PixGoNative.onToken -> storeExternalToken), se o
 *    hub o expuser;
 * e hidratamos o estado nativo com AuthRepository.fetchMe(force = true) —
 * exatamente como o hub web faz a seguir ao redirect (GET /api/auth/me).
 *
 * Fecha sozinha quando o hub sai das rotas de autenticacao (equivalente ao
 * `window.location.href = returnTo` pós-login do fluxo ativo).
 */
private const val HUB_ORIGIN = "https://app.pixgo.qzz.io"

@SuppressLint("SetJavaScriptEnabled")
@Composable
fun HubLoginSheet(mode: String, authRepository: AuthRepository, onClose: () -> Unit) {
    require(mode == "login" || mode == "register")
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var loading by remember { mutableStateOf(true) }
    var webViewRef by remember { mutableStateOf<WebView?>(null) }
    var closed by remember { mutableStateOf(false) }
    val json = kotlinx.serialization.json.Json { ignoreUnknownKeys = true; isLenient = true }

    val startUrl = "$HUB_ORIGIN/auth/$mode?return_to=${android.net.Uri.encode("$HUB_ORIGIN/main")}"

    fun finishAuth() {
        if (closed) return
        closed = true
        // Importa pixgo_session etc. do webkit para o jar OkHttp ANTES de hidratar.
        NetworkModule.importCookiesFromWebView(context.applicationContext, HUB_ORIGIN)
        scope.launch {
            try {
                authRepository.fetchMe(force = true)
            } finally {
                onClose()
            }
        }
    }

    /**
     * Detecção fiável da saída da rota /auth/ no hub (equivalente ao
     * `window.location.replace(returnTo)` pós-login do fluxo ativo):
     *  - window.location.replace dentro do próprio hub (/auth/login → /main)
     *    substitui a entrada de histórico e NEM SEMPRE dispara onPageFinished —
     *    é precisamente isso que causava o loop "login → WebView → login".
     *    Por isso verificamos também em doUpdateVisitedHistory e num polling
     *    leve do URL enquanto a sheet está aberta;
     *  - além da URL, exigimos sessão REAL antes de fechar: cookie
     *    pixgo_session OU pixgo_token/pixgo_refresh no localStorage da WebView
     *    (mesmas chaves de src/store/auth.ts do hub). Sem sessão confirmada a
     *    sheet mantém-se aberta; com sessão, guardamos token+refresh
     *    nativamente e hidratamos via fetchMe forçado (GET /api/auth/me),
     *    exatamente como o hub faz após o redirect.
     */
    fun checkAuthed(candidateUrl: String?) {
        if (closed) return
        val wv = webViewRef ?: return
        val currentUrl = candidateUrl ?: wv.url
        if (currentUrl == null || currentUrl.contains("/auth/")) return
        wv.evaluateJavascript(
            "(function(){try{" +
                "var c=document.cookie||'';" +
                "return JSON.stringify({t:localStorage.getItem('pixgo_token')," +
                "r:localStorage.getItem('pixgo_refresh'),hasCookie:c.indexOf('pixgo_session')>=0});" +
                "}catch(e){return JSON.stringify({hasCookie:(document.cookie||'').indexOf('pixgo_session')>=0});}})()"
        ) { raw ->
            val decoded = runCatching {
                // evaluateJavascript devolve uma STRING JSON — desempacota primeiro.
                val inner = json.parseToJsonElement(raw ?: "{}")
                (inner as? kotlinx.serialization.json.JsonPrimitive)?.content ?: raw
            }.getOrDefault(raw)
            val obj = runCatching { json.parseToJsonElement(decoded ?: "{}") as? kotlinx.serialization.json.JsonObject }.getOrNull()
            fun str(key: String): String? =
                (obj?.get(key) as? kotlinx.serialization.json.JsonPrimitive)
                    ?.takeIf { !it.isString || (it.content != "null" && it.content.isNotBlank()) }
                    ?.content
            val tok = str("t")
            val refresh = str("r")
            val hasCookie = (obj?.get("hasCookie") as? kotlinx.serialization.json.JsonPrimitive)?.content == "true"
            scope.launch {
                if (tok == null && refresh == null && !hasCookie) {
                    // Ainda sem sessão real — mantém o hub aberto (sem fechar em loop).
                    return@launch
                }
                if (tok != null || refresh != null) authRepository.storeHubTokens(tok, refresh)
                if (authRepository.state.value.token != null) finishAuth()
                // Sem token mesmo após importar sessão → deixa a pessoa continuar
                // o login no hub; nada fecha automaticamente.
            }
        }
    }

    BackHandler {
        val wv = webViewRef
        if (wv != null && wv.canGoBack()) wv.goBack() else onClose()
    }

    DisposableEffect(Unit) {
        onDispose { webViewRef?.destroy() }
    }

    // Rede de segurança contra redirects SPA invisíveis aos callbacks do WebView
    // (window.location.replace pode não disparar onPageFinished/doUpdateVisitedHistory):
    // enquanto a sheet está aberta, verifica periodicamente se o hub já saiu de
    // /auth/ com sessão real — e só então fecha com fetchMe(). Sem sessão, nada fecha.
    var authCheckTick by remember { mutableStateOf(0) }
    LaunchedEffect(authCheckTick) {
        if (authCheckTick > 0 && !closed) checkAuthed(null)
    }
    DisposableEffect(Unit) {
        val timer = java.util.Timer()
        timer.scheduleAtFixedRate(object : java.util.TimerTask() {
            override fun run() { authCheckTick++ }
        }, 1_500L, 1_200L)
        onDispose { timer.cancel() }
    }

    Box(Modifier.fillMaxSize().background(Color(0xFF0A0A0C))) {
        AndroidView(
            factory = { ctx ->
                WebView(ctx).apply {
                    setBackgroundColor(AndroidColor.TRANSPARENT)
                    settings.javaScriptEnabled = true
                    settings.domStorageEnabled = true
                    settings.loadWithOverviewMode = true
                    settings.useWideViewPort = true
                    settings.setSupportZoom(false)
                    android.webkit.CookieManager.getInstance().also { cm ->
                        cm.setAcceptCookie(true)
                        cm.setAcceptThirdPartyCookies(this, true)
                    }
                    // Bridge mínima: se o hub expuser o token Bearer na página
                    // (localStorage), entregamo-lo ao TokenManager existente.
                    addJavascriptInterface(
                        object {
                            @android.webkit.JavascriptInterface
                            fun onToken(token: String) {
                                if (token.isNotBlank()) {
                                    scope.launch { authRepository.storeExternalToken(token) }
                                }
                            }
                        },
                        "PixGoNative"
                    )
                    webViewClient = object : WebViewClient() {
                        // API >= 24: dispara para TODAS as cargas de página, incluindo
                        // window.location.replace (que não chama onPageFinished quando a
                        // mesma entrada de histórico é substituída). É o callback que fecha
                        // o loop "login → WebView → login".
                        override fun onReceivedError(
                            view: WebView?, request: android.webkit.WebResourceRequest?, error: android.webkit.WebResourceError?
                        ) {
                            if (request?.isForMainFrame == true) loading = false
                        }

                        override fun onPageStarted(view: WebView?, url: String?, favicon: android.graphics.Bitmap?) {
                            loading = true
                        }

                        override fun onPageFinished(view: WebView?, finishedUrl: String?) {
                            loading = false
                            checkAuthed(view?.url ?: finishedUrl)
                        }

                        override fun doUpdateVisitedHistory(view: WebView?, url: String?, isReload: Boolean) {
                            loading = false
                            checkAuthed(view?.url ?: url)
                        }
                    }
                    // Estado de login prévio (ex.: sessão guardada no jar) entra
                    // na WebView já autenticada, tal como o browser com cookie.
                    WebViewCookieSync.syncSessionCookies()
                    webViewRef = this
                    loadUrl(startUrl)
                }
            },
            modifier = Modifier.fillMaxSize()
        )

        if (loading) {
            Box(
                Modifier.fillMaxSize().background(Color(0xFF0A0A0C)),
                contentAlignment = Alignment.Center
            ) {
                CircularProgressIndicator(color = Color(0xFFE50914))
            }
        }

        IconButton(
            onClick = onClose,
            modifier = Modifier
                .align(Alignment.TopStart)
                .statusBarsPadding()
                .padding(8.dp)
        ) {
            Icon(Icons.Filled.Close, contentDescription = "Fechar", tint = Color.White)
        }
    }
}
