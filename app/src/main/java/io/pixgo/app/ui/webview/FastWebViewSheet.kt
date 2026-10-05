package io.pixgo.app.ui.webview

import android.annotation.SuppressLint
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.os.Message
import android.util.Log
import android.view.View
import android.view.ViewGroup
import android.webkit.ConsoleMessage
import android.webkit.CookieManager
import android.webkit.RenderProcessGoneDetail
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.compose.BackHandler
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import io.pixgo.app.BuildConfig
import io.pixgo.app.R
import io.pixgo.app.data.i18n.LocalTranslator
import io.pixgo.app.data.network.WebViewCookieSync
import io.pixgo.app.ui.common.PxBtnVariant
import io.pixgo.app.ui.common.PxButton
import io.pixgo.app.ui.common.PxEmptyState
import io.pixgo.app.ui.common.PxLoadingRing
import io.pixgo.app.ui.theme.Px
import kotlinx.coroutines.delay

/**
 * Excepção deliberada e única à regra "sem WebView a encapsular a app" —
 * só para o checkout do hub (Assinar Premium), autorizada explicitamente
 * pelo dono do projecto. Objectivo: parecer uma tela da própria app, não
 * um browser.
 *
 * Correções desta versão (spinner descentrado / página "alongada"):
 *  - loadWithOverviewMode = false: com `true`, qualquer elemento mais largo
 *    que o ecrã (iframe do cartão ZumboPay, overlay do Hotmart) fazia a
 *    WebView afastar o zoom para caber — a página ficava esticada e o
 *    `min-height:100vh; align-items:center` do spinner do hub deixava de
 *    coincidir com o centro do ecrã. Agora o layout fica sempre na largura
 *    do ecrã (meta viewport do hub: width=device-width, initial-scale=1);
 *  - textZoom = 100: a escala de fonte do sistema não deforma o layout;
 *  - a WebView ocupa só o espaço abaixo de uma barra nativa (já não há um X
 *    flutuante por cima do conteúdo) e respeita cutout + teclado
 *    (windowInsets safeDrawing). Em modo imersivo, `adjustResize` sozinho não
 *    redimensiona a janela, por isso o teclado empurrava/esticava a página;
 *  - o spinner de carregamento é o MESMO `.loading-ring` (42px/3px) que o hub
 *    desenha no mesmo centro: a passagem do nativo para o da página é contínua;
 *  - falha de rede / HTTP 5xx / processo de render morto / 25s sem carregar
 *    mostram um estado nativo com "Tentar novamente" (antes: página de erro
 *    do Chromium ou spinner eterno).
 *
 * Checkout (Hotmart / ZumboPay) — o que a WebView anterior não tratava:
 *  - window.open (3-D Secure do cartão, janelas do widget Hotmart): sem
 *    setSupportMultipleWindows/onCreateWindow o Android descarta a janela em
 *    silêncio. Agora abre numa WebView-filha (mantém o `opener`, necessário
 *    ao postMessage do 3-D Secure) com botão de fechar;
 *  - `intent://` (abrir a app do banco para o Pix): antes ia por ACTION_VIEW e
 *    falhava; agora usa Intent.parseUri com browser_fallback_url;
 *  - about:/data:/blob: eram tratados como "esquema externo" e a navegação era
 *    cancelada; agora ficam na WebView;
 *  - depois de pagar, CheckoutStatusPage do hub faz
 *    window.location.href = return_to (+px_paid) = o SITE pixgo.qzz.io, que
 *    carregava inteiro dentro da WebView. Agora essa navegação é interceptada
 *    (host do `return_to` do próprio URL de checkout) e fecha a sheet; quem
 *    abriu revalida o plano (invalidateMeCacheAfterPayment).
 *  - só em debug: console.log da página e navegações vão para o logcat
 *    (tag PixGoCheckout) e a WebView fica visível em chrome://inspect.
 */
private const val LOAD_TIMEOUT_MS = 25_000L

@SuppressLint("SetJavaScriptEnabled")
@Composable
fun FastWebViewSheet(url: String, onClose: () -> Unit) {
    val tr = LocalTranslator.current
    var webViewRef by remember { mutableStateOf<WebView?>(null) }
    var attempt by remember { mutableIntStateOf(0) }        // muda => WebView nova (retry / render morto)
    var progress by remember { mutableIntStateOf(0) }
    var pageReady by remember { mutableStateOf(false) }
    var failed by remember { mutableStateOf(false) }
    var popup by remember { mutableStateOf<WebView?>(null) }

    // Host para onde o hub devolve a pessoa depois de pagar (return_to do próprio URL).
    val returnHost = remember(url) {
        runCatching { Uri.parse(url).getQueryParameter("return_to")?.let { Uri.parse(it).host } }.getOrNull()
    }

    fun closePopup() {
        popup?.let { p -> p.stopLoading(); (p.parent as? ViewGroup)?.removeView(p); p.destroy() }
        popup = null
    }

    BackHandler {
        val child = popup
        val wv = webViewRef
        when {
            child != null -> if (child.canGoBack()) child.goBack() else closePopup()
            wv != null && wv.canGoBack() && !failed -> wv.goBack()
            else -> onClose()
        }
    }

    // Sem resposta em LOAD_TIMEOUT_MS => estado de erro com retry (nunca spinner eterno).
    LaunchedEffect(attempt) {
        delay(LOAD_TIMEOUT_MS)
        if (!pageReady) failed = true
    }

    DisposableEffect(Unit) {
        onDispose {
            popup?.let { p -> p.stopLoading(); p.destroy() }
            CookieManager.getInstance().flush()
        }
    }

    Column(
        Modifier
            .fillMaxSize()
            .background(Px.BgDark)
            .windowInsetsPadding(WindowInsets.safeDrawing)
    ) {
        // Barra nativa: fecha + logo (.logo img{height:18px}) + fio de progresso 2px.
        Box(
            Modifier
                .fillMaxWidth()
                .height(48.dp)
                .background(Px.BgDark)
                .drawBehind {
                    drawRect(Px.Border, Offset(0f, size.height - 1.dp.toPx()), Size(size.width, 1.dp.toPx()))
                    if (!pageReady && !failed) {
                        drawRect(
                            Px.Primary,
                            Offset(0f, size.height - 2.dp.toPx()),
                            Size(size.width * (progress.coerceIn(0, 100) / 100f), 2.dp.toPx())
                        )
                    }
                }
        ) {
            Row(Modifier.fillMaxHeight(), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onClose, modifier = Modifier.size(48.dp)) {
                    Icon(Icons.Filled.Close, contentDescription = tr.t("common.close"), tint = Px.TextLight)
                }
                Image(
                    painter = painterResource(id = R.drawable.ic_pixgo_logo),
                    contentDescription = "Pixgo",
                    modifier = Modifier.height(18.dp).aspectRatio(786f / 237f)
                )
            }
        }

        Box(Modifier.weight(1f).fillMaxWidth()) {
            key(attempt) {
                AndroidView(
                    modifier = Modifier.fillMaxSize(),
                    factory = { ctx ->
                        WebView(ctx).apply {
                            if (BuildConfig.DEBUG) WebView.setWebContentsDebuggingEnabled(true)
                            configureCheckoutWebView(this)

                            webChromeClient = object : WebChromeClient() {
                                override fun onProgressChanged(view: WebView?, newProgress: Int) {
                                    progress = newProgress
                                    if (newProgress >= 100) pageReady = true
                                }

                                override fun onConsoleMessage(m: ConsoleMessage): Boolean {
                                    if (BuildConfig.DEBUG) Log.d(TAG, "console[${m.messageLevel()}] ${m.message()} (${m.sourceId()}:${m.lineNumber()})")
                                    return false
                                }

                                // window.open: 3-D Secure / janelas do widget Hotmart.
                                override fun onCreateWindow(view: WebView, isDialog: Boolean, isUserGesture: Boolean, resultMsg: Message): Boolean {
                                    val transport = resultMsg.obj as? WebView.WebViewTransport ?: return false
                                    closePopup()
                                    val child = WebView(view.context)
                                    configureCheckoutWebView(child)
                                    child.webChromeClient = object : WebChromeClient() {
                                        override fun onCloseWindow(window: WebView?) { closePopup() }
                                    }
                                    child.webViewClient = checkoutClient(
                                        returnHost = returnHost,
                                        onReturn = onClose,
                                        onMainFrameError = {},
                                    )
                                    transport.webView = child
                                    resultMsg.sendToTarget()
                                    popup = child
                                    if (BuildConfig.DEBUG) Log.d(TAG, "onCreateWindow gesture=$isUserGesture")
                                    return true
                                }
                            }

                            webViewClient = checkoutClient(
                                returnHost = returnHost,
                                onReturn = onClose,
                                onMainFrameError = { failed = true },
                                onStarted = { failed = false },
                                onFinished = { if (!failed) pageReady = true },
                                onRenderGone = { failed = true; webViewRef = null },
                            )

                            // O CookieManager do WebView é separado do CookieJar do OkHttp:
                            // sincroniza a sessão (pixgo_session) ANTES de carregar.
                            WebViewCookieSync.syncSessionCookies()
                            webViewRef = this
                            loadUrl(url)
                        }
                    },
                    onRelease = { wv ->
                        wv.stopLoading()
                        wv.webViewClient = WebViewClient()
                        wv.webChromeClient = null
                        (wv.parent as? ViewGroup)?.removeView(wv)
                        wv.destroy()
                        if (webViewRef === wv) webViewRef = null
                    }
                )
            }

            // Janela aberta pela página (window.open): por cima da WebView principal.
            popup?.let { child ->
                Column(Modifier.fillMaxSize().background(Px.BgDark)) {
                    Row(
                        Modifier.fillMaxWidth().height(40.dp).background(Px.BgDark),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        IconButton(onClick = { closePopup() }, modifier = Modifier.size(40.dp)) {
                            Icon(Icons.Filled.Close, contentDescription = tr.t("common.close"), tint = Px.TextLight)
                        }
                    }
                    AndroidView(modifier = Modifier.weight(1f).fillMaxWidth(), factory = { child })
                }
            }

            // Carregamento: cobre exatamente a área da WebView e centra o anel no meio dela.
            // Nome totalmente qualificado: dentro de Column > Box, o import resolvia para
            // ColumnScope.AnimatedVisibility, bloqueado pelo BoxScope (@LayoutScopeMarker).
            androidx.compose.animation.AnimatedVisibility(
                visible = !pageReady && !failed,
                exit = fadeOut(),
                modifier = Modifier.fillMaxSize()
            ) {
                Box(Modifier.fillMaxSize().background(Px.BgDark), contentAlignment = Alignment.Center) {
                    PxLoadingRing()
                }
            }

            if (failed) {
                Box(Modifier.fillMaxSize().background(Px.BgDark), contentAlignment = Alignment.Center) {
                    PxEmptyState(
                        icon = Icons.Outlined.ErrorOutline,
                        title = tr.t("common.error"),
                        description = tr.t("errors.networkError"),
                        action = {
                            Row {
                                PxButton(
                                    text = tr.t("common.retry"),
                                    onClick = {
                                        failed = false
                                        pageReady = false
                                        progress = 0
                                        attempt++
                                    }
                                )
                                Box(Modifier.width(10.dp))
                                PxButton(
                                    text = tr.t("common.close"),
                                    onClick = onClose,
                                    variant = PxBtnVariant.Secondary
                                )
                            }
                        }
                    )
                }
            }
        }
    }
}

private const val TAG = "PixGoCheckout"

/** Definições partilhadas pela WebView principal e pelas janelas-filhas. */
@SuppressLint("SetJavaScriptEnabled")
private fun configureCheckoutWebView(wv: WebView) {
    wv.setBackgroundColor(0xFF0A0A0C.toInt()) // sem flash branco
    wv.overScrollMode = View.OVER_SCROLL_NEVER
    wv.isVerticalScrollBarEnabled = false
    wv.isHorizontalScrollBarEnabled = false
    wv.settings.apply {
        javaScriptEnabled = true
        domStorageEnabled = true
        javaScriptCanOpenWindowsAutomatically = true // window.open vindo de um clique
        setSupportMultipleWindows(true)
        useWideViewPort = true          // honra <meta viewport width=device-width>
        loadWithOverviewMode = false    // NUNCA encolher a página para caber conteúdo largo
        setSupportZoom(false)
        builtInZoomControls = false
        displayZoomControls = false
        textZoom = 100
        mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
        allowFileAccess = false
        allowContentAccess = false
    }
    CookieManager.getInstance().also { cm ->
        cm.setAcceptCookie(true)
        cm.setAcceptThirdPartyCookies(wv, true)
    }
}

/** Esquemas que a própria WebView sabe tratar (não são "externos"). */
private val INTERNAL_SCHEMES = setOf("http", "https", "about", "data", "blob", "javascript")

private fun checkoutClient(
    returnHost: String?,
    onReturn: () -> Unit,
    onMainFrameError: () -> Unit,
    onStarted: () -> Unit = {},
    onFinished: () -> Unit = {},
    onRenderGone: () -> Unit = {},
): WebViewClient = object : WebViewClient() {
    override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
        if (BuildConfig.DEBUG) Log.d(TAG, "start $url")
        onStarted()
    }

    override fun onPageFinished(view: WebView?, url: String?) {
        if (BuildConfig.DEBUG) Log.d(TAG, "finish $url")
        onFinished()
        CookieManager.getInstance().flush()
    }

    override fun onReceivedError(view: WebView?, request: WebResourceRequest?, error: WebResourceError?) {
        if (BuildConfig.DEBUG) Log.d(TAG, "error ${request?.url} main=${request?.isForMainFrame} ${error?.errorCode} ${error?.description}")
        if (request?.isForMainFrame == true) onMainFrameError()
    }

    override fun onReceivedHttpError(view: WebView?, request: WebResourceRequest?, errorResponse: WebResourceResponse?) {
        if (BuildConfig.DEBUG) Log.d(TAG, "http ${errorResponse?.statusCode} ${request?.url} main=${request?.isForMainFrame}")
        if (request?.isForMainFrame == true && (errorResponse?.statusCode ?: 0) >= 500) onMainFrameError()
    }

    // Processo de render morto (OOM etc.): sem isto o app inteiro fecha.
    override fun onRenderProcessGone(view: WebView?, detail: RenderProcessGoneDetail?): Boolean {
        onRenderGone()
        return true
    }

    override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
        val uri = request?.url ?: return false
        if (BuildConfig.DEBUG) Log.d(TAG, "navigate $uri main=${request?.isForMainFrame}")

        // Hub devolve a pessoa ao SITE (return_to) depois de pagar: fecha a sheet em vez de
        // carregar o site dentro da WebView. O chamador revalida o plano.
        if (request?.isForMainFrame == true && returnHost != null && uri.host == returnHost) {
            onReturn()
            return true
        }
        val scheme = uri.scheme?.lowercase()
        if (scheme == null || scheme in INTERNAL_SCHEMES) return false

        val ctx: Context = view?.context ?: return true
        if (scheme == "intent") {
            try {
                val intent = Intent.parseUri(uri.toString(), Intent.URI_INTENT_SCHEME).apply {
                    addCategory(Intent.CATEGORY_BROWSABLE)
                    component = null   // nunca deixar a página escolher o componente
                    selector = null
                }
                ctx.startActivity(intent)
            } catch (_: ActivityNotFoundException) {
                // App do banco não instalada: usa o fallback que a própria página indicou.
                val fb = runCatching {
                    Intent.parseUri(uri.toString(), Intent.URI_INTENT_SCHEME).getStringExtra("browser_fallback_url")
                }.getOrNull()
                if (fb != null && fb.startsWith("http")) view?.loadUrl(fb)
            } catch (_: Exception) {
            }
            return true
        }
        try {
            ctx.startActivity(Intent(Intent.ACTION_VIEW, uri).addCategory(Intent.CATEGORY_BROWSABLE))
        } catch (_: ActivityNotFoundException) {
        }
        return true
    }
}
