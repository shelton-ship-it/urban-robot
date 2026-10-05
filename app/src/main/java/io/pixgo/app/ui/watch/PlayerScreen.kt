package io.pixgo.app.ui.watch

import android.net.Uri
import androidx.annotation.OptIn
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
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
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.hls.HlsMediaSource
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import io.pixgo.app.data.model.UpsellPlan
import io.pixgo.app.data.player.BinDecryptDataSource
import io.pixgo.app.data.player.BinFormat
import io.pixgo.app.data.player.HeartbeatEvent
import io.pixgo.app.data.player.PlayerRepository
import io.pixgo.app.data.player.StreamHandshakeResult
import io.pixgo.app.ui.theme.Px
import kotlinx.coroutines.delay

/**
 * Equivalente ao componente ShakaPlayer.tsx (que, apesar do nome, usa
 * hls.js) + à lógica de handshake/heartbeat da página de watch:
 *  1. handshake() → GET .../stream (ECDH de "portão" + devolve
 *     master_url/drm_key_hex);
 *  2. ExoPlayer com HlsMediaSource usando BinDecryptDataSource — os
 *     .bin são decifrados on-the-fly, tudo o resto (m3u8) passa direto;
 *  3. heartbeat a cada 120s enquanto reproduz, 409=sessão substituída
 *     (pausa + aviso), 429=tempo grátis esgotado (pausa + aviso).
 *
 * A Watch nativa liga-se a este player APENAS pelos callbacks opcionais
 * abaixo (defaults nulos = comportamento antigo intacto para Home/Canais):
 * posição/duração p/ progresso, 429/409 c/ message+plans reais, auto-next
 * e fullscreen controlado por quem o incorpora.
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
    // Fullscreen controlado pela Watch (portrait 16:9 ↔ janela inteira).
    // Default false preserva as chamadas existentes de Home/Canais.
    fullscreen: Boolean = false,
    // Sessão offline (download concluído): quando true, salta handshake/
    // heartbeat remotos e reproduz init+segmentos locais pelo MESMO
    // BinDecryptDataSource (default false = fluxo remoto intacto).
    offline: Boolean = false,
    onToggleFullscreen: (() -> Unit)? = null
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val t = io.pixgo.app.data.i18n.LocalTranslator.current
    val repository = remember { PlayerRepository(context) }

    var loading by remember { mutableStateOf(true) }
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
            httpClient = io.pixgo.app.data.network.NetworkModule.plainHttpClient(),
            keyProvider = { drmKey },
            // Ramo offline: pixgo-offline://{key}/init.bin|seg.bin?i=N →
            // ficheiro cifrado em disco; a decifra chunk-v2 abaixo é idêntica.
            localResolver = { uri -> downloadStore.resolveLocal(uri) }
        )
        val mediaSourceFactory = HlsMediaSource.Factory(dataSourceFactory)
        ExoPlayer.Builder(context)
            .setMediaSourceFactory(mediaSourceFactory)
            .build()
    }

    LaunchedEffect(contentId, episodeId, offline) {
        loading = true
        errorMessage = null
        if (offline) {
            // Sessão offline: playlist sintética local + drm_key_hex do
            // download concluído; handshake/heartbeat remotos são saltados
            // (é o que isOfflineSession representa no repositório).
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
                // da Watch, o modal nativo (RateLimitModal equivalente) é
                // apresentado por ela; sem callback (Home/Canais), mantém-se
                // exatamente o AlertDialog antigo — comportamento intacto.
                if (onRateLimited != null) onRateLimited(result.message, result.plans)
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
    // offline não há heartbeat remoto (sem streaming/licença remota).
    LaunchedEffect(exoPlayer, offline) {
        if (offline) return@LaunchedEffect
        while (true) {
            delay(PlayerRepository.HEARTBEAT_INTERVAL_MS)
            if (!exoPlayer.isPlaying) continue
            val positionSeconds = (exoPlayer.currentPosition / 1000).toInt()
            when (val event = repository.sendHeartbeat(contentId, positionSeconds)) {
                is HeartbeatEvent.SessionReplaced -> {
                    exoPlayer.pause()
                    if (onSessionReplaced != null) onSessionReplaced(event.message)
                    else sessionReplacedMessage = event.message
                }
                is HeartbeatEvent.FreeTimeExhausted -> {
                    exoPlayer.pause()
                    if (onRateLimited != null) onRateLimited(event.message, event.plans)
                    else freeTimeMessage = event.message ?: "Tempo grátis esgotado."
                }
                HeartbeatEvent.Ok -> {}
            }
        }
    }

    // onTimeUpdate: posição/duração reais a cada ~5s enquanto reproduz
    // (equivalente ao listener 'timeupdate' do original); o throttle de
    // 60s e o cálculo de percentual pertencem à Watch, não ao player.
    LaunchedEffect(onTimeUpdate, exoPlayer) {
        if (onTimeUpdate == null) return@LaunchedEffect
        while (true) {
            delay(5_000L)
            if (!exoPlayer.isPlaying) continue
            val durSec = (exoPlayer.duration / 1000).toInt()
            val curSec = (exoPlayer.currentPosition / 1000).toInt()
            if (durSec > 0 && curSec >= 0) onTimeUpdate(curSec, durSec)
        }
    }

    // Auto-next (ShakaPlayer.tsx:770-783): no evento 'ended', se houver
    // onNextEpisode mostra countdown de 5s com "Próximo episódio"/"Cancelar";
    // ao zerar, dispara o próximo. Sem callback, nada acontece (Home/Canais).
    DisposableEffect(exoPlayer) {
        val listener = object : Player.Listener {
            override fun onPlaybackStateChanged(state: Int) {
                if (state == Player.STATE_ENDED && onNextEpisode != null) {
                    autoNextIn = 5
                }
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
            onNextEpisode?.invoke()
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
    // tratamento via callbacks (chamadas antigas Home/Canais/deep-link ficam
    // com exatamente o comportamento anterior).
    sessionReplacedMessage?.let { msg ->
        io.pixgo.app.ui.modals.SessionReplacedModal(message = msg, onClose = { sessionReplacedMessage = null; onClose() })
    }

    freeTimeMessage?.let { msg ->
        io.pixgo.app.ui.modals.RateLimitModal(
            plans = emptyList(), message = msg,
            onClose = { freeTimeMessage = null; onClose() },
            onUpgrade = { freeTimeMessage = null; onClose() },
        )
    }

    Box(Modifier.fillMaxSize().background(Color.Black)) {
        if (drmKey != null) {
            AndroidView(
                factory = {
                    PlayerView(it).apply {
                        player = exoPlayer
                        useController = true
                        resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FIT
                        // Controlos nativos sem anterior/seguinte; o ecrã inteiro é o
                        // botão nativo da própria barra (não um botão solto sobre o vídeo).
                        setShowNextButton(false)
                        setShowPreviousButton(false)
                    }
                },
                update = { pv ->
                    val toggle = onToggleFullscreen
                    if (toggle != null) pv.setFullscreenButtonClickListener { toggle() }
                    else pv.setFullscreenButtonClickListener(null)
                },
                modifier = Modifier.fillMaxSize()
            )
        }

        if (loading) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
        }

        errorMessage?.let {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(it, color = Color.White)
            }
        }

        // Player LIMPO (pedido explícito): sem "X" e sem o botão tipo "next" por cima do
        // vídeo. Só resta o botão Voltar — e apenas em ecrã inteiro, onde a barra de
        // voltar da página Watch não está visível. Sair do ecrã inteiro = voltar.
        if (fullscreen) {
            androidx.activity.compose.BackHandler { onToggleFullscreen?.invoke() ?: onClose() }
            IconButton(
                onClick = { onToggleFullscreen?.invoke() ?: onClose() },
                modifier = Modifier.align(Alignment.TopStart).padding(8.dp)
            ) {
                Icon(Icons.Filled.ArrowBack, contentDescription = "Voltar", tint = Color.White)
            }
        }

        // Countdown auto-next — mesmo card do original (bottom 60 / right 16,
        // fundo preto 0.9, borda rgba(229,9,20,.2), raio 12): número grande
        // vermelho + botões "Próximo episódio"/"Cancelar".
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
                            onNextEpisode()
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

