package io.pixgo.app.data.player

import android.content.Context
import android.os.SystemClock
import android.util.Base64
import androidx.media3.common.Player
import io.pixgo.app.data.auth.TokenManager
import io.pixgo.app.data.download.DownloadEngine
import io.pixgo.app.data.download.DownloadStore
import io.pixgo.app.data.download.DownloadStatus
import io.pixgo.app.data.model.StreamLimitErrorBody
import io.pixgo.app.data.model.UpsellPlan
import io.pixgo.app.data.network.HeartbeatBody
import io.pixgo.app.data.network.NetworkModule
import io.pixgo.app.data.network.StreamResponse
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.serialization.json.Json

sealed class StreamHandshakeResult {
    data class Ok(val info: StreamResponse) : StreamHandshakeResult()
    /** 429 no /stream — leva message + plans do body (RateLimitModal do frontend). */
    data class FreeTimeExhausted(val message: String?, val plans: List<UpsellPlan> = emptyList()) : StreamHandshakeResult()
    data class Error(val message: String) : StreamHandshakeResult()
}

sealed class HeartbeatEvent {
    object Ok : HeartbeatEvent()
    data class SessionReplaced(val message: String) : HeartbeatEvent()
    data class FreeTimeExhausted(val message: String?, val plans: List<UpsellPlan> = emptyList()) : HeartbeatEvent()
}

/**
 * Equivalente Android de `!video.paused && !video.ended` (ShakaPlayer.tsx / channels/page.tsx).
 *
 * IMPORTANTE: buffering NÃO é pausa. Antes o heartbeat usava `isPlaying`, que é false enquanto o
 * ExoPlayer faz buffering — em redes lentas o tick caía quase sempre num instante de buffering,
 * era saltado e o tempo grátis quase não era creditado (o modal de limite praticamente nunca
 * disparava). No web o <video> em buffering continua `!paused` e o tick conta.
 */
fun Player.isHeartbeatActive(): Boolean =
    playWhenReady && playbackState != Player.STATE_IDLE && playbackState != Player.STATE_ENDED

private const val HEARTBEAT_POLL_MS = 500L

/**
 * Relógio do heartbeat, réplica do `useEffect` play/pause/ended do ShakaPlayer.tsx:
 *  - ao passar a "a reproduzir" ('play'): [immediate] = true envia logo um heartbeat (VOD) e
 *    depois repete a cada HEARTBEAT_INTERVAL_MS;
 *  - ao pausar/terminar ('pause'/'ended'): o relógio pára (e o 'play' seguinte recomeça com
 *    crédito imediato — o dedup de 100 s do servidor impede duplo crédito);
 *  - 409/429: [onTerminal] é chamado (o chamador pausa o player e abre o modal) e o relógio só
 *    volta a armar no próximo 'play', tal como o web faz clearInterval + novo 'play'.
 * Antes o loop era `delay(120 s)` fixo, sem crédito no início: sessões < 120 s nunca eram
 * contadas e qualquer tick em buffering era perdido.
 */
suspend fun runHeartbeatClock(
    player: Player,
    immediate: Boolean,
    sendNow: suspend () -> HeartbeatEvent,
    onTerminal: (HeartbeatEvent) -> Unit,
) {
    while (currentCoroutineContext().isActive) {
        while (!player.isHeartbeatActive()) delay(HEARTBEAT_POLL_MS)
        var lastBeatAt = SystemClock.elapsedRealtime()
        var first = immediate
        while (currentCoroutineContext().isActive && player.isHeartbeatActive()) {
            val due = first || SystemClock.elapsedRealtime() - lastBeatAt >= PlayerRepository.HEARTBEAT_INTERVAL_MS
            if (due) {
                first = false
                val event = sendNow()
                lastBeatAt = SystemClock.elapsedRealtime()
                if (event !is HeartbeatEvent.Ok) {
                    onTerminal(event)
                    // Se por algum motivo o chamador não pausou, espera pela pausa em vez de martelar o servidor.
                    while (currentCoroutineContext().isActive && player.isHeartbeatActive()) delay(HEARTBEAT_POLL_MS)
                    break
                }
            }
            delay(HEARTBEAT_POLL_MS)
        }
    }
}

/**
 * Réplica de performECDH() e sendHeartbeat() em ShakaPlayer.tsx (o
 * componente chama-se "Shaka" mas usa hls.js por baixo — nome mantido no
 * ficheiro original, não é Shaka Player de facto).
 *
 * NÃO envia os "network/device hints" nem o X-Device-Fp (FingerprintJS)
 * do handshake original — são descritos no próprio código como sinais
 * opcionais/de recuperação, não essenciais ("falha silenciosamente" se
 * indisponíveis), por isso ficam de fora aqui em vez de fabricar um
 * fingerprint que não existe no Android.
 */
class PlayerRepository(private val context: Context) {
    private val tokenManager = TokenManager(context)
    private val api by lazy { NetworkModule.stream(context, tokenManager) }
    private val store by lazy { DownloadStore(context) }
    private val json = Json { ignoreUnknownKeys = true }

    companion object {
        const val HEARTBEAT_INTERVAL_MS = 120_000L
        private const val GENERIC_FAIL = "Não foi possível iniciar a reprodução. Verifique a ligação e tente novamente."
    }

    suspend fun handshake(contentId: String, episodeId: String?): StreamHandshakeResult {
        val clientPubKey = EcdhKeyExchange.generateClientPubKeyBase64()
        val params = mutableMapOf("clientPubKey" to clientPubKey)
        episodeId?.let { params["episode"] = it }

        // Resiliência (redes lentas / edge functions "frias"): a rota /stream é
        // só-leitura (não acumula tempo de visualização), logo repetir é seguro.
        //
        // O web (performECDH) nunca mostra o texto cru do servidor ao utilizador e o
        // hls.js recupera sozinho de falhas de rede. Aqui, qualquer falha que NÃO seja
        // definitiva (HTTP 404/408/5xx, corpo sem master_url, erro de rede, timeout)
        // é tratada como transitória: repete durante até PLAYER_BUDGET_MS (90 s) com
        // espera crescente (0,4 → 5 s), mantendo o spinner na UI — antes eram só 4
        // tentativas (~5 s) e o erro saía cedo demais (o KV/Turso "frio" do backend
        // responde 500/404 transitório e à segunda tentativa já funciona). Só 429
        // (limite de plano) e 401/403 (resposta definitiva) saem logo. A mensagem final é
        // SEMPRE a genérica — nunca "stream metadata not found" / "failed to get stream url".
        var last: StreamHandshakeResult = StreamHandshakeResult.Error(GENERIC_FAIL)
        val deadline = System.currentTimeMillis() + io.pixgo.app.data.network.Patience.PLAYER_BUDGET_MS
        var attempt = 0
        while (true) {
            if (attempt > 0) delay(io.pixgo.app.data.network.Patience.delayFor(attempt - 1))
            try {
                val resp = api.stream(contentId, params)
                val code = resp.code()
                when {
                    resp.isSuccessful -> {
                        val body = resp.body()
                        if (body != null && body.masterUrl.isNotBlank()) return StreamHandshakeResult.Ok(body)
                        // Equivalente ao `throw new Error('No stream URL')` do web — aqui tentamos de novo.
                        android.util.Log.e("PixGoPlayer", "stream sem master_url (tentativa ${attempt + 1})")
                        last = StreamHandshakeResult.Error(GENERIC_FAIL)
                    }
                    code == 429 -> {
                        val err = parseErrorBody(resp.errorBody()?.string())
                        return StreamHandshakeResult.FreeTimeExhausted(err?.message, err?.plans ?: emptyList())
                    }
                    code == 401 || code == 403 -> {
                        val msg = parseErrorBody(resp.errorBody()?.string())?.message
                        return StreamHandshakeResult.Error(msg ?: GENERIC_FAIL)
                    }
                    else -> {
                        val serverMsg = parseErrorBody(resp.errorBody()?.string())?.message
                        android.util.Log.e("PixGoPlayer", "stream HTTP $code (tentativa ${attempt + 1}): $serverMsg")
                        last = StreamHandshakeResult.Error(
                            GENERIC_FAIL +
                                if (io.pixgo.app.BuildConfig.DEBUG) "\n[debug] HTTP $code: ${serverMsg ?: "-"}" else ""
                        )
                    }
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e   // o ecrã saiu / mudou de episódio: não é uma falha
            } catch (e: Exception) {
                android.util.Log.e("PixGoPlayer", "stream falhou (tentativa ${attempt + 1})", e)
                last = StreamHandshakeResult.Error(
                    GENERIC_FAIL +
                        if (io.pixgo.app.BuildConfig.DEBUG) "\n[debug] ${e.javaClass.simpleName}: ${e.message ?: ""}" else ""
                )
            }
            attempt++
            if (System.currentTimeMillis() >= deadline) return last
        }
    }

    /**
     * Um único pulso de heartbeat — POST .../heartbeat com a posição
     * actual (em segundos). O chamador (ecrã do player) é responsável
     * por repetir isto a cada HEARTBEAT_INTERVAL_MS enquanto o vídeo
     * está a reproduzir, tal como o setInterval do original.
     */
    suspend fun sendHeartbeat(contentId: String, positionSeconds: Int): HeartbeatEvent {
        return try {
            val resp = api.heartbeat(contentId, HeartbeatBody(positionSeconds))
            when (resp.code()) {
                409 -> {
                    val err = parseErrorBody(resp.errorBody()?.string())
                    HeartbeatEvent.SessionReplaced(err?.message ?: "A sua sessão foi encerrada neste dispositivo.")
                }
                429 -> {
                    val err = parseErrorBody(resp.errorBody()?.string())
                    HeartbeatEvent.FreeTimeExhausted(err?.message, err?.plans ?: emptyList())
                }
                else -> HeartbeatEvent.Ok
            }
        } catch (e: Exception) {
            HeartbeatEvent.Ok // falha de rede pontual — não interrompe a reprodução, igual ao catch{} silencioso do original
        }
    }

    /**
     * Playlist HLS sintética para reprodução offline — réplica literal de
     * buildOfflinePlaylist() em ShakaPlayer.tsx (linhas 383-400), com o
     * esquema "idb://" trocado por "pixgo-offline://" (mesma ideia: URL
     * local resolvida pelo loader, não pela rede). Duração estimada por
     * segmento porque o pipeline não grava durações reais — o player corrige
     * a duração real ao decodificar os fMP4, exactamente como no web.
     */
    suspend fun startLocal(contentId: String, episodeId: String?): Boolean {
        val key = DownloadStore.keyFor(contentId, episodeId)
        val meta = store.get(key) ?: return false
        if (meta.status != DownloadStatus.COMPLETED || meta.keyHex.isNullOrBlank()) return false

        val total = meta.segCount
        if (total <= 0) return false

        // Durações REAIS (#EXTINF do index.m3u8, guardadas pelo DownloadEngine). Com
        // `-c:v copy` no process.yml os segmentos seguem os keyframes (4, 5, 10 s…),
        // não um valor fixo; o ExoPlayer monta a timeline e o seek a partir destas
        // durações, por isso 6 s fixos desalinham duração/seek/buffer. Downloads antigos
        // (sem durations.txt) caem na duração de reserva, como antes.
        val durations = store.readDurations(key)?.takeIf { it.size == total }
        val fallback = DownloadEngine.OFFLINE_SEG_DURATION
        val target = Math.ceil(durations?.maxOrNull() ?: fallback).toInt().coerceAtLeast(1)

        val sb = StringBuilder()
        sb.append("#EXTM3U\n")
        sb.append("#EXT-X-VERSION:7\n")
        sb.append("#EXT-X-TARGETDURATION:$target\n")
        sb.append("#EXT-X-PLAYLIST-TYPE:VOD\n")
        sb.append("#EXT-X-INDEPENDENT-SEGMENTS\n")
        sb.append("#EXT-X-MAP:URI=\"${OfflineLocal.SCHEME}://$key/init.bin\"\n")
        repeat(total) {
            val d = durations?.get(it) ?: fallback
            // Locale.US: com locale pt o format() escreveria "6,000" (vírgula).
            sb.append(java.lang.String.format(java.util.Locale.US, "#EXTINF:%.6f,\n", d))
            sb.append("${OfflineLocal.SCHEME}://$key/seg.bin?i=$it\n")
        }
        sb.append("#EXT-X-ENDLIST\n")

        offlineMasterUrl = "data:application/x-mpegURL;base64," +
            Base64.encodeToString(sb.toString().toByteArray(), Base64.NO_WRAP)
        offlineKeyHex = meta.keyHex
        return true
    }

    /** true quando startLocal() foi chamado — o chamador deve saltar handshake/heartbeat. */
    val isOfflineSession: Boolean get() = offlineMasterUrl != null
    val offlineStreamUrl: String? get() = offlineMasterUrl

    /** Chave da sessão offline (drm_key_hex do download concluído); nula em sessões online. */
    fun offlineKey(): ByteArray? = offlineKeyHex?.let { BinFormat.keyFromHex(it) }

    private var offlineMasterUrl: String? = null
    private var offlineKeyHex: String? = null

    private fun parseErrorBody(raw: String?): StreamLimitErrorBody? {
        if (raw.isNullOrBlank()) return null
        return try { json.decodeFromString(StreamLimitErrorBody.serializer(), raw) } catch (e: Exception) { null }
    }
}
