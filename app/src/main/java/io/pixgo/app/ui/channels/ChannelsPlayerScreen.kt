package io.pixgo.app.ui.channels

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
import androidx.media3.ui.PlayerView
import io.pixgo.app.data.channels.ChannelsRepository
import io.pixgo.app.data.player.HeartbeatEvent
import io.pixgo.app.data.player.PlayerRepository
import kotlinx.coroutines.delay

/**
 * Canais são streams HLS/TS abertos, sem nenhuma camada de DRM (ao
 * contrário do VOD) — o `channel.url` vem directo da playlist.m3u via
 * jsDelivr (ver ChannelsSource), por isso o ExoPlayer nativo trata tudo
 * sozinho, sem BinDecryptDataSource nenhum. Só reaproveita-se a mesma
 * cadência de heartbeat (120s) e o mesmo tratamento de 409/429 que o
 * VOD, porque o rate-limit.js do backend trata os dois endpoints da
 * mesma forma (confirmado em channels/page.tsx).
 */
@UnstableApi
@Composable
fun ChannelsPlayerScreen(
    channelId: String,
    channelName: String,
    streamUrl: String,
    channelsRepository: ChannelsRepository,
    onClose: () -> Unit
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current

    var loading by remember { mutableStateOf(true) }
    var sessionReplacedMessage by remember { mutableStateOf<String?>(null) }
    var freeTimeMessage by remember { mutableStateOf<String?>(null) }

    val exoPlayer = remember {
        ExoPlayer.Builder(context).build().apply {
            setMediaItem(MediaItem.fromUri(streamUrl))
            prepare()
            playWhenReady = true
        }
    }

    LaunchedEffect(Unit) {
        // Sem handshake aqui — o gate (GET /api/channels/:id) já correu
        // em ChannelsScreen antes de navegar para este ecrã.
        loading = false
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
        AndroidView(
            factory = {
                PlayerView(it).apply {
                    player = exoPlayer
                    useController = true
                }
            },
            modifier = Modifier.fillMaxSize()
        )

        if (loading) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
        }

        IconButton(onClick = onClose, modifier = Modifier.align(Alignment.TopStart).padding(8.dp)) {
            Icon(Icons.Filled.Close, contentDescription = "Fechar", tint = Color.White)
        }

        Text(
            channelName,
            color = Color.White,
            modifier = Modifier.align(Alignment.TopEnd).padding(16.dp)
        )
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
