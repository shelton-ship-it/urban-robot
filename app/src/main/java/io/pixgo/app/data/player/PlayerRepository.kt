package io.pixgo.app.data.player

import android.content.Context
import android.util.Base64
import io.pixgo.app.data.auth.TokenManager
import io.pixgo.app.data.download.DownloadEngine
import io.pixgo.app.data.download.DownloadStore
import io.pixgo.app.data.download.DownloadStatus
import io.pixgo.app.data.model.StreamLimitErrorBody
import io.pixgo.app.data.model.UpsellPlan
import io.pixgo.app.data.network.HeartbeatBody
import io.pixgo.app.data.network.NetworkModule
import io.pixgo.app.data.network.StreamResponse
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
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
    }

    suspend fun handshake(contentId: String, episodeId: String?): StreamHandshakeResult {
        val clientPubKey = EcdhKeyExchange.generateClientPubKeyBase64()
        val params = mutableMapOf("clientPubKey" to clientPubKey)
        episodeId?.let { params["episode"] = it }
        return try {
            val resp = api.stream(contentId, params)
            when {
                resp.isSuccessful -> resp.body()?.let { StreamHandshakeResult.Ok(it) }
                    ?: StreamHandshakeResult.Error("Resposta vazia do servidor.")
                resp.code() == 429 -> {
                    val err = parseErrorBody(resp.errorBody()?.string())
                    StreamHandshakeResult.FreeTimeExhausted(err?.message, err?.plans ?: emptyList())
                }
                else -> StreamHandshakeResult.Error(
                    parseErrorBody(resp.errorBody()?.string())?.message ?: "Erro ${resp.code()} ao iniciar reprodução."
                )
            }
        } catch (e: Exception) {
            StreamHandshakeResult.Error("Falha de rede.")
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
    fun startLocal(contentId: String, episodeId: String?): Boolean {
        val key = DownloadStore.keyFor(contentId, episodeId)
        val meta = runBlocking { store.get(key) } ?: return false
        if (meta.status != DownloadStatus.COMPLETED || meta.keyHex.isNullOrBlank()) return false

        val total = meta.segCount
        if (total <= 0) return false
        val sb = StringBuilder()
        sb.append("#EXTM3U\n")
        sb.append("#EXT-X-VERSION:7\n")
        sb.append("#EXT-X-TARGETDURATION:${DownloadEngine.OFFLINE_SEG_DURATION.toInt()}\n")
        sb.append("#EXT-X-PLAYLIST-TYPE:VOD\n")
        sb.append("#EXT-X-MAP:URI=\"${OfflineLocal.SCHEME}://$key/init.bin\"\n")
        repeat(total) {
            sb.append("#EXTINF:%.3f,\n".format(DownloadEngine.OFFLINE_SEG_DURATION))
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
