package io.pixgo.app.ui.channels

import androidx.activity.compose.BackHandler
import androidx.annotation.OptIn
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.size
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.rememberCoroutineScope
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import io.pixgo.app.data.channels.ChannelsRepository
import io.pixgo.app.data.player.HeartbeatEvent
import io.pixgo.app.data.player.PlayerFactory
import io.pixgo.app.data.player.PlayerRepository
import io.pixgo.app.ui.common.PxPlayerSkeleton
import io.pixgo.app.ui.common.rememberFullscreenState
import io.pixgo.app.ui.common.PxButton
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Canais são streams HLS/TS abertos, sem nenhuma camada de DRM (ao
 * contrário do VOD) — o `channel.url` vem directo da playlist.m3u via
 * jsDelivr (ver ChannelsSource), por isso o ExoPlayer nativo trata tudo
 * sozinho, sem BinDecryptDataSource nenhum. Só reaproveita-se a mesma
 * cadência de heartbeat (120s) e o mesmo tratamento de 409/429 que o
 * VOD, porque o rate-limit.js do backend trata os dois endpoints da
 * mesma forma (confirmado em channels/page.tsx).
 *
 * CORREÇÕES: (1) sem botão de ecrã inteiro → agora o botão NATIVO da barra do
 * player roda o ecrã (como o YouTube); (2) virar o ecrã fechava o player para
 * sempre (Activity recriada → estado `watchingChannel` perdido) → a Activity já
 * não é recriada e o MESMO ExoPlayer é só re-dimensionado (16:9 em retrato,
 * ecrã todo em paisagem); (3) spinner nativo de buffering ligado.
 */
@OptIn(UnstableApi::class)
@Composable
fun ChannelsPlayerScreen(
    channelId: String,
    channelName: String,
    streamUrl: String,
    channelsRepository: ChannelsRepository,
    onClose: () -> Unit,
    onUpgrade: (planId: String) -> Unit = {},
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val fs = rememberFullscreenState()
    val syncingFsIcon = remember { booleanArrayOf(false) }
    val fsS by rememberUpdatedState(fs)

    var prepared by remember { mutableStateOf(false) }
    var sessionReplacedMessage by remember { mutableStateOf<String?>(null) }
    // channels/page.tsx: onFreeTimeExhausted(plans, message) → RateLimitModal; onSessionReplaced(message) → SessionReplacedModal
    var freeTime by remember { mutableStateOf<Pair<String?, List<io.pixgo.app.data.model.UpsellPlan>>?>(null) }

    val exoPlayer = remember(streamUrl) {
        PlayerFactory.createLive(context).apply {
            setMediaItem(MediaItem.fromUri(streamUrl))
            prepare()
            playWhenReady = true
        }
    }

    // Sem handshake aqui — o gate (GET /api/channels/:id) já correu em
    // ChannelsScreen antes de navegar para este ecrã.
    LaunchedEffect(exoPlayer) { prepared = true }

    // ── Recuperação silenciosa (spinner) antes de qualquer erro ────────────────────────────────
    // Um erro fatal do ExoPlayer (CDN lento/indisponível um instante) deixava o ecrã preso, sem
    // spinner nem mensagem, "como se o canal não existisse" — e ao reabrir tocava. Agora re-prepara
    // sozinho com espera crescente, com o spinner visível, e só mostra erro (com "Tentar novamente")
    // depois de ~1 min sem conseguir tocar. O contador volta a zero assim que o vídeo toca.
    val scope = rememberCoroutineScope()
    var errorCount by remember(exoPlayer) { mutableIntStateOf(0) }
    var recovering by remember(exoPlayer) { mutableStateOf(false) }
    var buffering by remember(exoPlayer) { mutableStateOf(false) }
    var errorMessage by remember(exoPlayer) { mutableStateOf<String?>(null) }

    fun waitingNow(): Boolean {
        val st = exoPlayer.playbackState
        if (st == Player.STATE_BUFFERING) return true
        return st == Player.STATE_READY && exoPlayer.playWhenReady && !exoPlayer.isPlaying &&
            exoPlayer.playbackSuppressionReason == Player.PLAYBACK_SUPPRESSION_REASON_NONE
    }
    LaunchedEffect(exoPlayer) {
        while (true) {
            val w = waitingNow()
            if (buffering != w) buffering = w
            delay(250L)
        }
    }

    DisposableEffect(exoPlayer) {
        var recoverJob: Job? = null
        val listener = object : Player.Listener {
            override fun onPlaybackStateChanged(state: Int) { buffering = waitingNow() }
            override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) { buffering = waitingNow() }
            override fun onIsPlayingChanged(isPlaying: Boolean) {
                if (isPlaying) { errorCount = 0; recovering = false; errorMessage = null }
            }
            override fun onPlayerError(error: PlaybackException) {
                android.util.Log.e("PixGoChannels", "erro do player: ${error.errorCodeName}", error)
                errorCount += 1
                if (errorCount > LIVE_MAX_RECOVERIES) {
                    recovering = false
                    errorMessage = "Não foi possível carregar o canal. Verifique a ligação."
                    return
                }
                recovering = true
                recoverJob?.cancel()
                recoverJob = scope.launch {
                    delay(LIVE_RECOVER_DELAYS_MS[(errorCount - 1).coerceIn(0, LIVE_RECOVER_DELAYS_MS.lastIndex)])
                    exoPlayer.prepare()
                    exoPlayer.playWhenReady = true
                }
            }
        }
        buffering = waitingNow()
        exoPlayer.addListener(listener)
        onDispose {
            recoverJob?.cancel()
            exoPlayer.removeListener(listener)
        }
    }

    LaunchedEffect(exoPlayer) {
        while (true) {
            delay(PlayerRepository.HEARTBEAT_INTERVAL_MS)
            if (!exoPlayer.isPlaying) continue
            when (val event = channelsRepository.heartbeat(channelId)) {
                is HeartbeatEvent.SessionReplaced -> {
                    exoPlayer.pause()
                    sessionReplacedMessage = event.message
                }
                is HeartbeatEvent.FreeTimeExhausted -> {
                    exoPlayer.pause()
                    freeTime = event.message to event.plans
                }
                HeartbeatEvent.Ok -> {}
            }
        }
    }

    DisposableEffect(lifecycleOwner, exoPlayer) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_PAUSE) exoPlayer.pause()
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            exoPlayer.release()
        }
    }

    // Voltar: em ecrã inteiro sai do ecrã inteiro (retrato); senão fecha o canal.
    BackHandler { if (fsS.isFullscreen) fsS.toggle() else onClose() }

    Column(Modifier.fillMaxSize().background(Color.Black)) {
        // Barra superior só em retrato (em paisagem o vídeo ocupa tudo).
        if (!fs.isFullscreen) {
            Row(
                Modifier.fillMaxWidth().windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Top))
                    .padding(horizontal = 4.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(onClick = onClose) {
                    Icon(Icons.Filled.ArrowBack, contentDescription = "Voltar", tint = Color.White)
                }
                Text(
                    channelName, color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.SemiBold,
                    maxLines = 1, overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f).padding(end = 12.dp),
                )
            }
        }

        // UM só ponto na árvore: só o tamanho muda → o stream não reinicia ao rodar.
        Box(
            if (fs.isFullscreen) Modifier.weight(1f).fillMaxWidth()
            else Modifier.fillMaxWidth().aspectRatio(16f / 9f)
        ) {
            AndroidView(
                factory = { ctx ->
                    PlayerView(ctx).apply {
                        player = exoPlayer
                        useController = true
                        resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FIT
                        setShowBuffering(PlayerView.SHOW_BUFFERING_NEVER) // spinner único (Compose)
                    }
                },
                update = { pv ->
                    if (pv.player !== exoPlayer) pv.player = exoPlayer
                    // Botão NATIVO de ecrã inteiro (aparece quando há listener).
                    // Guarda anti-loop: sincronizar o ícone também notifica este listener (Media3) e
                    // fazia o ecrã virar e desvirar sozinho.
                    pv.setFullscreenButtonClickListener { if (!syncingFsIcon[0]) fsS.toggle() }
                    syncingFsIcon[0] = true
                    try {
                        runCatching {
                            pv.javaClass.getMethod("setFullscreenButtonState", java.lang.Boolean.TYPE)
                                .invoke(pv, fs.isFullscreen)
                        }
                    } finally { syncingFsIcon[0] = false }
                },
                onRelease = { pv -> pv.player = null },
                modifier = Modifier.fillMaxSize()
            )

            if (!prepared) PxPlayerSkeleton()

            // Spinner persistente: buffering real + recuperação automática (antes ficava um ecrã preto parado).
            if (errorMessage == null && (buffering || recovering)) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(
                        color = Color.White,
                        trackColor = Color(0x33FFFFFF),
                        strokeWidth = 3.dp,
                        modifier = Modifier.size(44.dp),
                    )
                }
            }

            errorMessage?.let { msg ->
                Column(
                    Modifier.fillMaxSize().background(Color(0xCC000000)).padding(24.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center,
                ) {
                    Text(msg, color = Color.White, fontSize = 13.sp)
                    PxButton(
                        text = "Tentar novamente",
                        onClick = {
                            errorMessage = null
                            errorCount = 0
                            recovering = true
                            exoPlayer.prepare()
                            exoPlayer.playWhenReady = true
                        },
                        modifier = Modifier.padding(top = 12.dp),
                    )
                }
            }

            if (fs.isFullscreen) {
                IconButton(onClick = { fsS.toggle() }, modifier = Modifier.align(Alignment.TopStart).padding(8.dp)) {
                    Icon(Icons.Filled.ArrowBack, contentDescription = "Voltar", tint = Color.White)
                }
                Text(
                    channelName, color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.align(Alignment.TopEnd).padding(16.dp)
                )
            }
        }
    }

    sessionReplacedMessage?.let { msg ->
        io.pixgo.app.ui.modals.SessionReplacedModal(
            message = msg,
            onClose = { sessionReplacedMessage = null; onClose() },
        )
    }

    freeTime?.let { (message, plans) ->
        io.pixgo.app.ui.modals.RateLimitModal(
            plans = plans,
            message = message,
            onClose = { freeTime = null; onClose() },
            onUpgrade = { planId -> freeTime = null; onUpgrade(planId) },
        )
    }
}

private const val LIVE_MAX_RECOVERIES = 12
private val LIVE_RECOVER_DELAYS_MS = longArrayOf(800L, 1_500L, 3_000L, 5_000L)
