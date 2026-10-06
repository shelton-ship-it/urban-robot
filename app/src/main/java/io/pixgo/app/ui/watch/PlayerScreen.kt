package io.pixgo.app.ui.watch

import android.net.Uri
import androidx.annotation.OptIn
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import io.pixgo.app.data.model.UpsellPlan
import io.pixgo.app.data.player.BinDecryptDataSource
import io.pixgo.app.data.player.BinFormat
import io.pixgo.app.data.player.HeartbeatEvent
import io.pixgo.app.data.player.PlayerFactory
import io.pixgo.app.data.player.PlayerRepository
import io.pixgo.app.data.player.StreamHandshakeResult
import io.pixgo.app.ui.common.PxButton
import io.pixgo.app.ui.common.PxPlayerSkeleton
import io.pixgo.app.ui.theme.Px
import kotlinx.coroutines.delay

/**
 * Equivalente ao componente ShakaPlayer.tsx (que, apesar do nome, usa
 * hls.js) + à lógica de handshake/heartbeat da página de watch:
 *  1. handshake() → GET .../stream (ECDH de "portão" + devolve
 *     master_url/drm_key_hex);
 *  2. ExoPlayer com HlsMediaSource usando BinDecryptDataSource — os
 *     .bin são decifrados em streaming, tudo o resto (m3u8) passa direto;
 *  3. heartbeat a cada 120s enquanto reproduz, 409=sessão substituída
 *     (pausa + aviso), 429=tempo grátis esgotado (pausa + aviso).
 *
 * CORREÇÕES desta versão:
 *  - SPINNER: o PlayerView tinha o indicador de buffering desligado (default
 *    SHOW_BUFFERING_NEVER) e só existia um spinner Compose que desaparecia
 *    quando o handshake terminava — depois disso, em rede lenta, o vídeo
 *    parecia "encravado". Agora o spinner NATIVO do player fica em
 *    SHOW_BUFFERING_ALWAYS (aparece em QUALQUER buffering/rebuffer/seek) e
 *    durante o handshake mostra-se um skeleton, não um spinner.
 *  - ECRÃ INTEIRO: este composable é UM ÚNICO ponto na árvore — a Watch só
 *    muda o tamanho do contentor (16:9 ↔ tudo). Antes eram dois PlayerScreen
 *    diferentes (um por ramo do if), por isso entrar/sair do ecrã inteiro
 *    destruía o ExoPlayer e recomeçava o vídeo.
 *  - Callbacks (onTimeUpdate/onNextEpisode/...) lidos via rememberUpdatedState:
 *    antes cada recomposição da Watch reiniciava o LaunchedEffect e o `delay`
 *    de 5 s nunca chegava ao fim (progresso nunca guardado).
 *  - Player configurado como o hls.js do frontend (ver PlayerFactory).
 */
@OptIn(UnstableApi::class)
@Composable
fun PlayerScreen(
    contentId: String,
    episodeId: String?,
    onClose: () -> Unit,
    // Ligações mínimas para a Watch nativa (defaults nulos preservam as
    // chamadas existentes de Home/Canais/deep-link sem qualquer mudança).
    onTimeUpdate: ((curSec: Int, durSec: Int) -> Unit)? = null,
    onRateLimited: ((message: String?, plans: List<UpsellPlan>) -> Unit)? = null,
    onSessionReplaced: ((message: String) -> Unit)? = null,
    onNextEpisode: (() -> Unit)? = null,
    // Estado de ecrã inteiro (derivado da orientação) — só decide o botão Voltar.
    fullscreen: Boolean = false,
    // Sessão offline (download concluído): salta handshake/heartbeat remotos.
    offline: Boolean = false,
    onToggleFullscreen: (() -> Unit)? = null
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val t = io.pixgo.app.data.i18n.LocalTranslator.current
    val repository = remember { PlayerRepository(context) }

    val onTimeUpdateS by rememberUpdatedState(onTimeUpdate)
    val onRateLimitedS by rememberUpdatedState(onRateLimited)
    val onSessionReplacedS by rememberUpdatedState(onSessionReplaced)
    val onNextEpisodeS by rememberUpdatedState(onNextEpisode)
    val onToggleFullscreenS by rememberUpdatedState(onToggleFullscreen)
    val onCloseS by rememberUpdatedState(onClose)

    var loading by remember { mutableStateOf(true) }
    var attempt by remember { mutableIntStateOf(0) }
    var errorMessage by remember { mutableStateOf<String?>(null) }
    var sessionReplacedMessage by remember { mutableStateOf<String?>(null) }
    var freeTimeMessage by remember { mutableStateOf<String?>(null) }
    var drmKey by remember { mutableStateOf<ByteArray?>(null) }
    // Auto-next com contagem de 5s após 'ended' — mesmo comportamento do
    // ShakaPlayer.tsx original (setAutoNextIn(5) + setInterval 1000ms).
    var autoNextIn by remember { mutableStateOf<Int?>(null) }

    val downloadStore = remember { io.pixgo.app.data.download.DownloadStore(context) }
    val exoPlayer = remember(contentId, episodeId) {
        val dataSourceFactory = BinDecryptDataSource.Factory(
            httpClient = io.pixgo.app.data.network.NetworkModule.playerHttp(context),
            keyProvider = { drmKey },
            // Ramo offline: pixgo-offline://{key}/init.bin|seg.bin?i=N →
            // ficheiro cifrado em disco; a decifra chunk-v2 é idêntica.
            localResolver = { uri -> downloadStore.resolveLocal(uri) }
        )
        PlayerFactory.createVod(context, dataSourceFactory)
    }

    LaunchedEffect(contentId, episodeId, offline, attempt) {
        loading = true
        errorMessage = null
        if (offline) {
            if (repository.startLocal(contentId, episodeId)) {
                drmKey = repository.offlineKey()
                val url = repository.offlineStreamUrl
                if (url != null) {
                    exoPlayer.setMediaItem(MediaItem.fromUri(Uri.parse(url)))
                    exoPlayer.prepare()
                    exoPlayer.playWhenReady = true
                    loading = false
                } else {
                    loading = false
                    errorMessage = "Download indisponível."
                }
            } else {
                loading = false
                errorMessage = "Download incompleto ou expirado."
            }
            return@LaunchedEffect
        }
        when (val result = repository.handshake(contentId, episodeId)) {
            is StreamHandshakeResult.Ok -> {
                drmKey = BinFormat.keyFromHex(result.info.drmKeyHex)
                exoPlayer.setMediaItem(MediaItem.fromUri(Uri.parse(result.info.masterUrl)))
                exoPlayer.prepare()
                exoPlayer.playWhenReady = true
                loading = false
            }
            is StreamHandshakeResult.FreeTimeExhausted -> {
                loading = false
                // 429 real: message + plans do body do backend. Com callback
                // da Watch, o modal nativo é apresentado por ela; sem callback
                // (Home/Canais) mantém-se o AlertDialog antigo.
                val cb = onRateLimitedS
                if (cb != null) cb(result.message, result.plans)
                else freeTimeMessage = result.message ?: "Tempo grátis esgotado."
            }
            is StreamHandshakeResult.Error -> {
                loading = false
                errorMessage = result.message
            }
        }
    }

    // Heartbeat — só corre enquanto isPlaying, tal como o listener
    // play/pause do original que liga/desliga o setInterval. Em sessões
    // offline não há heartbeat remoto.
    LaunchedEffect(exoPlayer, offline) {
        if (offline) return@LaunchedEffect
        while (true) {
            delay(PlayerRepository.HEARTBEAT_INTERVAL_MS)
            if (!exoPlayer.isPlaying) continue
            val positionSeconds = (exoPlayer.currentPosition / 1000).toInt()
            when (val event = repository.sendHeartbeat(contentId, positionSeconds)) {
                is HeartbeatEvent.SessionReplaced -> {
                    exoPlayer.pause()
                    val cb = onSessionReplacedS
                    if (cb != null) cb(event.message) else sessionReplacedMessage = event.message
                }
                is HeartbeatEvent.FreeTimeExhausted -> {
                    exoPlayer.pause()
                    val cb = onRateLimitedS
                    if (cb != null) cb(event.message, event.plans)
                    else freeTimeMessage = event.message ?: "Tempo grátis esgotado."
                }
                HeartbeatEvent.Ok -> {}
            }
        }
    }

    // onTimeUpdate: posição/duração reais a cada ~5s enquanto reproduz
    // (equivalente ao listener 'timeupdate' do original).
    LaunchedEffect(exoPlayer) {
        while (true) {
            delay(5_000L)
            val cb = onTimeUpdateS ?: continue
            if (!exoPlayer.isPlaying) continue
            val dur = exoPlayer.duration
            if (dur == C.TIME_UNSET || dur <= 0L) continue
            val durSec = (dur / 1000).toInt()
            val curSec = (exoPlayer.currentPosition / 1000).toInt()
            if (durSec > 0 && curSec >= 0) cb(curSec, durSec)
        }
    }

    // Auto-next (ShakaPlayer.tsx:770-783) + erros de reprodução com "Tentar novamente".
    DisposableEffect(exoPlayer) {
        val listener = object : Player.Listener {
            override fun onPlaybackStateChanged(state: Int) {
                if (state == Player.STATE_ENDED && onNextEpisodeS != null) autoNextIn = 5
            }

            override fun onPlayerError(error: PlaybackException) {
                errorMessage = "Falha ao reproduzir o vídeo. Verifique a ligação."
            }
        }
        exoPlayer.addListener(listener)
        onDispose { exoPlayer.removeListener(listener) }
    }
    LaunchedEffect(autoNextIn) {
        var n = autoNextIn
        while (n != null && n > 0) {
            delay(1000L)
            n = n - 1
            autoNextIn = n
        }
        if (autoNextIn == 0) {
            autoNextIn = null
            onNextEpisodeS?.invoke()
        }
    }

    DisposableEffect(lifecycleOwner, exoPlayer) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_PAUSE) exoPlayer.pause()
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            // Libera o ExoPlayer também quando o episódio muda (exoPlayer é
            // relembrado por contentId/episodeId) — evita 2 players ativos.
            exoPlayer.release()
        }
    }

    // Modais de sessão/limite: exibidos apenas quando a Watch não assumiu o
    // tratamento via callbacks (Home/Canais/deep-link).
    sessionReplacedMessage?.let { msg ->
        io.pixgo.app.ui.modals.SessionReplacedModal(message = msg, onClose = { sessionReplacedMessage = null; onCloseS() })
    }

    freeTimeMessage?.let { msg ->
        io.pixgo.app.ui.modals.RateLimitModal(
            plans = emptyList(), message = msg,
            onClose = { freeTimeMessage = null; onCloseS() },
            onUpgrade = { freeTimeMessage = null; onCloseS() },
        )
    }

    Box(Modifier.fillMaxSize().background(Color.Black)) {
        // O PlayerView existe SEMPRE (mesmo durante o handshake): a superfície
        // fica pronta e o spinner nativo de buffering funciona desde o 1.º frame.
        AndroidView(
            factory = { ctx ->
                PlayerView(ctx).apply {
                    player = exoPlayer
                    useController = true
                    resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FIT
                    // Spinner PADRÃO do player em todo o buffering/rebuffer/seek.
                    setShowBuffering(PlayerView.SHOW_BUFFERING_ALWAYS)
                    // Controlos nativos sem anterior/seguinte; o ecrã inteiro é o
                    // botão nativo da própria barra.
                    setShowNextButton(false)
                    setShowPreviousButton(false)
                }
            },
            update = { pv ->
                if (pv.player !== exoPlayer) pv.player = exoPlayer
                if (onToggleFullscreenS != null) {
                    pv.setFullscreenButtonClickListener { onToggleFullscreenS?.invoke() }
                } else {
                    pv.setFullscreenButtonClickListener(null)
                }
                syncFullscreenIcon(pv, fullscreen)
            },
            onRelease = { pv -> pv.player = null },
            modifier = Modifier.fillMaxSize()
        )

        // Handshake em curso: skeleton (não spinner).
        if (loading && errorMessage == null) PxPlayerSkeleton()

        errorMessage?.let { msg ->
            Column(
                Modifier.fillMaxSize().background(Color(0xCC000000)).padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
            ) {
                Text(msg, color = Color.White, fontSize = 13.sp)
                if (!offline) {
                    PxButton(
                        text = "Tentar novamente",
                        onClick = { attempt++ },
                        modifier = Modifier.padding(top = 12.dp),
                    )
                }
            }
        }

        // Player LIMPO: sem "X" nem botão tipo "next" por cima do vídeo. Só o
        // Voltar — e apenas em ecrã inteiro, onde a barra da Watch não aparece.
        // Sair do ecrã inteiro = voltar a retrato (o BackHandler vive na Watch).
        if (fullscreen) {
            IconButton(
                onClick = { onToggleFullscreenS?.invoke() ?: onCloseS() },
                modifier = Modifier.align(Alignment.TopStart).padding(8.dp)
            ) {
                Icon(Icons.Filled.ArrowBack, contentDescription = "Voltar", tint = Color.White)
            }
        }

        // Countdown auto-next — mesmo card do original.
        autoNextIn?.let { n ->
            if (n > 0 && onNextEpisode != null) {
                Row(
                    Modifier
                        .align(Alignment.BottomEnd)
                        .padding(end = 16.dp, bottom = 60.dp)
                        .background(Color(0xE6000000), RoundedCornerShape(12.dp))
                        .border(1.dp, Color(0x33E50914), RoundedCornerShape(12.dp))
                        .padding(horizontal = 18.dp, vertical = 14.dp),
                    horizontalArrangement = Arrangement.spacedBy(14.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column {
                        Text(t.t("player.autoNext"), color = Px.TextMuted, fontSize = 11.5.sp)
                        Text(
                            n.toString(),
                            fontFamily = MaterialTheme.typography.displaySmall.fontFamily,
                            fontWeight = FontWeight.Black,
                            fontSize = 32.sp,
                            color = Px.Primary,
                            lineHeight = 34.sp
                        )
                    }
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        TextButton(onClick = {
                            autoNextIn = null
                            onNextEpisodeS?.invoke()
                        }) { Text(t.t("player.nextEpisode"), color = Px.PrimaryGlow) }
                        TextButton(onClick = { autoNextIn = null }) {
                            Text(t.t("common.cancel"))
                        }
                    }
                }
            }
        }
    }
}

/**
 * Mantém o ícone do botão NATIVO (expandir/recolher) coerente com o estado real
 * (a orientação pode mudar fisicamente, sem o botão ser tocado). Por reflexão:
 * se esta versão do Media3 não expõe o método, é um no-op silencioso.
 */
@OptIn(UnstableApi::class)
private fun syncFullscreenIcon(pv: PlayerView, fullscreen: Boolean) {
    runCatching {
        pv.javaClass.getMethod("setFullscreenButtonState", java.lang.Boolean.TYPE).invoke(pv, fullscreen)
    }
}
