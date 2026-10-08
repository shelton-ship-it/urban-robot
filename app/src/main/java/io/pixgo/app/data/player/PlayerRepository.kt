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
