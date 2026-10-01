package io.pixgo.app.ui.watch

import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
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
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.media3.common.MediaItem
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.hls.HlsMediaSource
import androidx.media3.ui.PlayerView
import io.pixgo.app.data.player.BinDecryptDataSource
import io.pixgo.app.data.player.BinFormat
import io.pixgo.app.data.player.HeartbeatEvent
import io.pixgo.app.data.player.PlayerRepository
import io.pixgo.app.data.player.StreamHandshakeResult
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
 * Ainda por fazer nesta tela (deixado de fora deliberadamente, não
 * inventado): guardar progresso periodicamente, próximo episódio,
 * reprodução offline via IndexedDB-equivalente, fullscreen/orientação.
 */
@UnstableApi
@Composable
fun PlayerScreen(contentId: String, episodeId: String?, onClose: () -> Unit) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val repository = remember { PlayerRepository(context) }

    var loading by remember { mutableStateOf(true) }
    var errorMessage by remember { mutableStateOf<String?>(null) }
    var sessionReplacedMessage by remember { mutableStateOf<String?>(null) }
    var freeTimeMessage by remember { mutableStateOf<String?>(null) }
    var drmKey by remember { mutableStateOf<ByteArray?>(null) }

    val exoPlayer = remember {
        val dataSourceFactory = BinDecryptDataSource.Factory(
            httpClient = io.pixgo.app.data.network.NetworkModule.plainHttpClient(),
            keyProvider = { drmKey }
        )
        val mediaSourceFactory = HlsMediaSource.Factory(dataSourceFactory)
        ExoPlayer.Builder(context)
            .setMediaSourceFactory(mediaSourceFactory)
            .build()
    }

    LaunchedEffect(contentId, episodeId) {
        loading = true
        errorMessage = null
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
                freeTimeMessage = result.message ?: "Tempo grátis esgotado."
            }
            is StreamHandshakeResult.Error -> {
                loading = false
                errorMessage = result.message
            }
        }
    }

    // Heartbeat — só corre enquanto isPlaying, tal como o listener
    // play/pause do original que liga/desliga o setInterval.
    LaunchedEffect(exoPlayer) {
        while (true) {
            delay(PlayerRepository.HEARTBEAT_INTERVAL_MS)
            if (!exoPlayer.isPlaying) continue
            val positionSeconds = (exoPlayer.currentPosition / 1000).toInt()
            when (val event = repository.sendHeartbeat(contentId, positionSeconds)) {
                is HeartbeatEvent.SessionReplaced -> {
                    exoPlayer.pause()
                    sessionReplacedMessage = event.message
                }
                is HeartbeatEvent.FreeTimeExhausted -> {
                    exoPlayer.pause()
                    freeTimeMessage = event.message ?: "Tempo grátis esgotado."
                }
                HeartbeatEvent.Ok -> {}
            }
        }
    }

    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_PAUSE) exoPlayer.pause()
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            exoPlayer.release()
        }
    }

    Box(Modifier.fillMaxSize().background(Color.Black)) {
        if (drmKey != null) {
            AndroidView(
                factory = {
                    PlayerView(it).apply {
                        player = exoPlayer
                        useController = true
                    }
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

        IconButton(onClick = onClose, modifier = Modifier.align(Alignment.TopStart).padding(8.dp)) {
            Icon(Icons.Filled.Close, contentDescription = "Fechar", tint = Color.White)
        }
    }

    sessionReplacedMessage?.let { msg ->
        AlertDialog(
            onDismissRequest = { sessionReplacedMessage = null; onClose() },
            title = { Text("Sessão encerrada") },
            text = { Text(msg) },
            confirmButton = { TextButton(onClick = { sessionReplacedMessage = null; onClose() }) { Text("OK") } }
        )
    }

    freeTimeMessage?.let { msg ->
        AlertDialog(
            onDismissRequest = { freeTimeMessage = null; onClose() },
            title = { Text("Tempo grátis esgotado") },
            text = { Text(msg) },
            confirmButton = { TextButton(onClick = { freeTimeMessage = null; onClose() }) { Text("OK") } }
        )
    }
}
