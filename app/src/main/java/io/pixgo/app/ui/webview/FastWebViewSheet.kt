package io.pixgo.app.ui.webview

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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import io.pixgo.app.data.network.WebViewCookieSync

/**
 * Excepção deliberada e única à regra "sem WebView a encapsular a app" —
 * só para o checkout do hub (Assinar Premium), autorizada explicitamente
 * pelo dono do projecto. Objectivo: parecer uma tela da própria app, não
 * um browser — por isso:
 *  - sem barra de endereço, sem chrome de browser nenhum;
 *  - fundo escuro (a cor do tema, --color-bg-dark #0a0a0c) em vez do
 *    branco por defeito da WebView, para não haver "flash" ao carregar;
 *  - só um botão de fechar (X) — nada que pareça "abrir noutro app";
 *  - sincroniza o cookie de sessão partilhado ANTES de carregar, para a
 *    pessoa não ter de fazer login outra vez no hub.
 * Volta atrás dentro da própria página (goBack) quando possível; senão o
 * botão físico/gesto de recuar fecha a sheet, não sai da app.
 */
@SuppressLint("SetJavaScriptEnabled")
@Composable
fun FastWebViewSheet(url: String, onClose: () -> Unit) {
    var loading by remember { mutableStateOf(true) }
    var webViewRef by remember { mutableStateOf<WebView?>(null) }

    BackHandler {
        val wv = webViewRef
        if (wv != null && wv.canGoBack()) wv.goBack() else onClose()
    }

    DisposableEffect(Unit) {
        onDispose { webViewRef?.destroy() }
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
                    android.webkit.CookieManager.getInstance().setAcceptThirdPartyCookies(this, true)
                    webViewClient = object : WebViewClient() {
                        override fun onPageFinished(view: WebView?, finishedUrl: String?) {
                            loading = false
                        }
                    }
                    WebViewCookieSync.syncSessionCookies()
                    webViewRef = this
                    loadUrl(url)
                }
            },
            modifier = Modifier.fillMaxSize()
        )

        if (loading) {
            Box(
                Modifier.fillMaxSize().background(Color(0xFF0A0A0C)),
                contentAlignment = Alignment.Center
            ) {
                CircularProgressIndicator()
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
