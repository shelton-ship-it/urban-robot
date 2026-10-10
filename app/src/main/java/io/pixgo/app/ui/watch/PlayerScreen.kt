package io.pixgo.app.ui.watch

import io.pixgo.app.ui.common.KeepScreenOn

import android.net.Uri
import android.view.LayoutInflater
import android.view.View
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
import androidx.compose.foundation.layout.size
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
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
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
import io.pixgo.app.R
import io.pixgo.app.data.model.UpsellPlan
import io.pixgo.app.data.player.BinDecryptDataSource
import io.pixgo.app.data.player.BinFormat
import io.pixgo.app.data.player.HeartbeatEvent
import io.pixgo.app.data.player.runHeartbeatClock
import io.pixgo.app.data.player.PlayerFactory
import io.pixgo.app.data.player.PlayerRepository
import io.pixgo.app.data.player.StreamHandshakeResult
import io.pixgo.app.ui.common.PxButton
import io.pixgo.app.ui.common.PxPlayerSkeleton
import io.pixgo.app.ui.theme.Px
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

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
    KeepScreenOn()
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

    // O Media3 notifica o listener do botão de ecrã inteiro TAMBÉM quando o estado do ícone é
    // mudado por código (setFullscreenButtonState). Sem esta guarda, sincronizar o ícone depois
    // de rodar o ecrã disparava "alternar" outra vez e o ecrã virava e DESVIRAVA sozinho.
    val syncingFsIcon = remember { booleanArrayOf(false) }
    var loading by remember { mutableStateOf(true) }
    // Visibilidade dos controlos NATIVOS do player (toque mostra/esconde, auto-esconde a tocar).
    // O botão Voltar do ecrã inteiro acompanha-os.
    var controlsVisible by remember { mutableStateOf(true) }
    var attempt by remember { mutableIntStateOf(0) }
    var errorMessage by remember { mutableStateOf<String?>(null) }
    var sessionReplacedMessage by remember { mutableStateOf<String?>(null) }
    var freeTimeMessage by remember { mutableStateOf<String?>(null) }
    var drmKey by remember { mutableStateOf<ByteArray?>(null) }
    // Auto-next com contagem de 5s após 'ended' — mesmo comportamento do
    // ShakaPlayer.tsx original (setAutoNextIn(5) + setInterval 1000ms).
    var autoNextIn by remember { mutableStateOf<Int?>(null) }
    // Spinner persistente: buffering real do ExoPlayer + recuperação automática de erros.
    // (O web nunca mostra erro em falha de rede do hls.js: faz startLoad()/recoverMediaError()
    // em silêncio e o <video> mostra o seu spinner — é isto que se replica aqui.)
    var buffering by remember { mutableStateOf(false) }
    var recovering by remember { mutableStateOf(false) }
    var errorCount by remember { mutableIntStateOf(0) }
    val scope = rememberCoroutineScope()

    val downloadStore = remember { io.pixgo.app.data.download.DownloadStore(context) }
    val exoPlayer = remember(contentId, episodeId, offline) {
        val dataSourceFactory = BinDecryptDataSource.Factory(
            httpClient = io.pixgo.app.data.network.NetworkModule.playerHttp(context),
            keyProvider = { drmKey },
            // Ramo offline: pixgo-offline://{key}/init.bin|seg.bin?i=N →
            // ficheiro cifrado em disco; a decifra chunk-v2 é idêntica.
            localResolver = { uri -> downloadStore.resolveLocal(uri) }
        )
        PlayerFactory.createVod(context, dataSourceFactory, patient = !offline)
    }

    LaunchedEffect(contentId, episodeId, offline, attempt) {
        loading = true
        errorMessage = null
        recovering = false
        errorCount = 0
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
                drmKey = result.info.drmKeyHex?.takeIf { it.isNotBlank() }?.let { BinFormat.keyFromHex(it) }
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

    // Heartbeat — réplica do play/pause/ended do ShakaPlayer.tsx: crédito imediato ao dar
    // play, depois a cada 120 s enquanto NÃO estiver pausado (buffering conta como a reproduzir).
    // Em sessões offline não há heartbeat remoto.
    LaunchedEffect(exoPlayer, offline) {
        if (offline) return@LaunchedEffect
        runHeartbeatClock(
            player = exoPlayer,
            immediate = true,
            sendNow = {
                val positionSeconds = (exoPlayer.currentPosition / 1000).toInt()
                repository.sendHeartbeat(contentId, positionSeconds)
            },
            onTerminal = { event ->
                exoPlayer.pause()
                when (event) {
                    is HeartbeatEvent.SessionReplaced -> {
                        val cb = onSessionReplacedS
                        if (cb != null) cb(event.message) else sessionReplacedMessage = event.message
                    }
                    is HeartbeatEvent.FreeTimeExhausted -> {
                        val cb = onRateLimitedS
                        if (cb != null) cb(event.message, event.plans)
                        else freeTimeMessage = event.message ?: "Tempo grátis esgotado."
                    }
                    HeartbeatEvent.Ok -> {}
                }
            },
        )
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

    // SPINNER CONSISTENTE (como o YouTube): em vez de depender de um único evento
    // (onPlaybackStateChanged), o estado "à espera de dados" é RELIDO do player em cada evento
    // relevante e por um relógio de 250 ms. Assim carregar num botão, seek, pausa/play, rodar o
    // ecrã ou um segmento lento nunca deixam o spinner "perdido" nem o vídeo parecer partido.
    // `stalled`: o ExoPlayer diz "a tocar" mas a posição não anda (segmento lento/rede presa,
    // rotação do ecrã a re-criar a superfície…). O YouTube mostra spinner aqui; nós também.
    val stalled = remember(exoPlayer) { booleanArrayOf(false) }
    fun waitingNow(): Boolean =
        exoPlayer.playbackState == Player.STATE_BUFFERING || stalled[0]
    LaunchedEffect(exoPlayer) {
        var lastPos = -1L
        var lastMove = System.currentTimeMillis()
        while (true) {
            val now = System.currentTimeMillis()
            val pos = exoPlayer.currentPosition
            if (pos != lastPos || !exoPlayer.isPlaying) { lastPos = pos; lastMove = now; stalled[0] = false }
            else if (now - lastMove >= 900L) stalled[0] = true
            val w = waitingNow()
            if (buffering != w) buffering = w
            delay(250L)
        }
    }

    // Auto-next (ShakaPlayer.tsx:770-783) + estado de buffering + recuperação de erros.
    DisposableEffect(exoPlayer) {
        buffering = waitingNow()
        var recoverJob: Job? = null
        val listener = object : Player.Listener {
            override fun onPlaybackStateChanged(state: Int) {
                buffering = waitingNow()
                if (state == Player.STATE_ENDED && onNextEpisodeS != null) autoNextIn = 5
            }
            override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) { buffering = waitingNow() }
            override fun onIsLoadingChanged(isLoading: Boolean) { buffering = waitingNow() }
            override fun onPositionDiscontinuity(
                oldPosition: Player.PositionInfo, newPosition: Player.PositionInfo, reason: Int,
            ) { buffering = waitingNow() }

            override fun onIsPlayingChanged(isPlaying: Boolean) {
                if (isPlaying) { errorCount = 0; recovering = false }
                buffering = waitingNow()
            }

            override fun onPlayerError(error: PlaybackException) {
                android.util.Log.e("PixGoPlayer", "erro do player: ${error.errorCodeName}", error)
                // Mesma filosofia do web: erro fatal de rede/media → tenta recuperar
                // sozinho (re-prepara na posição actual) com o spinner visível. Só depois
                // de várias tentativas seguidas sem conseguir reproduzir é que mostra o
                // erro com "Tentar novamente". Cobre também o falso erro ao virar o ecrã
                // (superfície/codec recriados) e rede que cai a meio de um segmento.
                errorCount += 1
                if (errorCount > MAX_AUTO_RECOVERIES) {
                    recovering = false
                    errorMessage = if (offline) {
                        // Sessão local: "verifique a ligação" não faz sentido — mostra a causa real.
                        val cause = error.cause?.message?.takeIf { it.isNotBlank() }?.let { " — $it" } ?: ""
                        "Não foi possível reproduzir este download (${error.errorCodeName})$cause"
                    } else "Falha ao reproduzir o vídeo. Verifique a ligação."
                    return
                }
                recovering = true
                val pos = exoPlayer.currentPosition
                recoverJob?.cancel()
                recoverJob = scope.launch {
                    delay(RECOVER_DELAYS_MS[(errorCount - 1).coerceIn(0, RECOVER_DELAYS_MS.lastIndex)])
                    if (pos > 0L) exoPlayer.seekTo(pos)
                    exoPlayer.prepare()
                    exoPlayer.playWhenReady = true
                }
            }
        }
        exoPlayer.addListener(listener)
        onDispose {
            recoverJob?.cancel()
            exoPlayer.removeListener(listener)
        }
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

    val spinnerVisible = errorMessage == null && (loading || buffering || recovering)
    // Estado "spinner visível" acessível a callbacks nativos + referência ao PlayerView, para
    // re-aplicar a ocultação dos botões centrais SEMPRE que o controlador nativo os volte a
    // mostrar (tocar num botão fazia-os reaparecer por cima do spinner → "player partido").
    val spinnerNow = remember { booleanArrayOf(false) }
    spinnerNow[0] = spinnerVisible
    val pvRef = remember { arrayOfNulls<PlayerView>(1) }
    LaunchedEffect(spinnerVisible) { pvRef[0]?.let { setCenterControlsHidden(it, spinnerVisible) } }

    Box(Modifier.fillMaxSize().background(Color.Black)) {
        // O PlayerView existe SEMPRE (mesmo durante o handshake): a superfície
        // fica pronta e o spinner nativo de buffering funciona desde o 1.º frame.
        AndroidView(
            factory = { ctx ->
                (LayoutInflater.from(ctx).inflate(R.layout.px_player_view, null, false) as PlayerView).apply {
                    player = exoPlayer
                    useController = true
                    resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FIT
                    // O spinner é do Compose (abaixo); o nativo fica desligado.
                    setShowBuffering(PlayerView.SHOW_BUFFERING_NEVER)
                    // Controlos nativos sem anterior/seguinte; o ecrã inteiro é o
                    // botão nativo da própria barra.
                    setShowNextButton(false)
                    setShowPreviousButton(false)
                    pvRef[0] = this
                    setControllerVisibilityListener(PlayerView.ControllerVisibilityListener { visibility ->
                        controlsVisible = visibility == View.VISIBLE
                        setCenterControlsHidden(this, spinnerNow[0])
                    })
                }
            },
            update = { pv ->
                if (pv.player !== exoPlayer) pv.player = exoPlayer
                if (onToggleFullscreenS != null) {
                    pv.setFullscreenButtonClickListener {
                        // só toques REAIS do utilizador alternam; sincronizações do ícone são ignoradas
                        if (!syncingFsIcon[0]) onToggleFullscreenS?.invoke()
                    }
                } else {
                    pv.setFullscreenButtonClickListener(null)
                }
                syncingFsIcon[0] = true
                try { syncFullscreenIcon(pv, fullscreen) } finally { syncingFsIcon[0] = false }
                // Enquanto o spinner está visível, os botões pause/«/» do centro saem de
                // cima dele: davam a impressão de player quebrado. O spinner manda.
                setCenterControlsHidden(pv, hidden = spinnerVisible)
            },
            onRelease = { pv -> pv.player = null; pvRef[0] = null },
            modifier = Modifier.fillMaxSize()
        )

        // Handshake em curso: skeleton por baixo (mesma geometria do vídeo).
        if (loading && errorMessage == null) PxPlayerSkeleton()

        // Spinner BRANCO persistente, centrado, por cima de tudo (menos do erro): handshake e
        // tentativas, buffering, rede lenta/caída, recuperação automática. Em ecrã inteiro
        // também (antes só o spinner nativo, que não aparecia de forma fiável).
        if (spinnerVisible) {
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
        // Só em ecrã inteiro e junto dos controlos nativos: some sozinho com eles e volta com o
        // toque. Com erro visível fica sempre, para haver saída. Escondido não existe na árvore,
        // logo não rouba toques ao player.
        androidx.compose.animation.AnimatedVisibility(
            visible = fullscreen && (controlsVisible || errorMessage != null),
            modifier = Modifier.align(Alignment.TopStart),
            enter = androidx.compose.animation.fadeIn(),
            exit = androidx.compose.animation.fadeOut(),
        ) {
            IconButton(
                onClick = { onToggleFullscreenS?.invoke() ?: onCloseS() },
                modifier = Modifier.padding(8.dp)
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

// Recuperação silenciosa (com spinner) durante ~1 min antes de mostrar qualquer erro; o contador
// só volta a zero quando o vídeo efetivamente toca (onIsPlayingChanged).
private const val MAX_AUTO_RECOVERIES = 12
private val RECOVER_DELAYS_MS = longArrayOf(800L, 1_500L, 3_000L, 5_000L)

/**
 * Esconde/mostra o bloco central do controlador nativo (pause, «, »). O contentor
 * `exo_center_controls` não é tocado pelo PlayerControlView (ao contrário dos botões
 * individuais, cuja visibilidade ele reescreve a cada actualização). INVISIBLE também
 * impede toques acidentais enquanto o vídeo está a carregar. Por id em runtime: se esta
 * versão do Media3 não tiver o id, cai para os botões e, no pior caso, é um no-op.
 */
private fun setCenterControlsHidden(pv: PlayerView, hidden: Boolean) {
    val res = pv.resources
    val pkg = pv.context.packageName
    fun find(name: String): View? {
        val id = res.getIdentifier(name, "id", pkg)
        return if (id != 0) pv.findViewById<View>(id) else null
    }
    val container = find("exo_center_controls")
    if (container != null) {
        container.visibility = if (hidden) View.INVISIBLE else View.VISIBLE
        return
    }
    listOf("exo_play_pause", "exo_rew", "exo_ffwd", "exo_rew_with_amount", "exo_ffwd_with_amount")
        .forEach { name -> find(name)?.alpha = if (hidden) 0f else 1f }
}
