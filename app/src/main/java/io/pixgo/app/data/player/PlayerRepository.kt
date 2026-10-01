package io.pixgo.app.data.player

import android.content.Context
import io.pixgo.app.data.auth.TokenManager
import io.pixgo.app.data.network.HeartbeatBody
import io.pixgo.app.data.network.HeartbeatErrorBody
import io.pixgo.app.data.network.NetworkModule
import io.pixgo.app.data.network.StreamResponse
import kotlinx.coroutines.delay
import kotlinx.serialization.json.Json

sealed class StreamHandshakeResult {
    data class Ok(val info: StreamResponse) : StreamHandshakeResult()
    data class FreeTimeExhausted(val message: String?) : StreamHandshakeResult()
    data class Error(val message: String) : StreamHandshakeResult()
}

sealed class HeartbeatEvent {
    object Ok : HeartbeatEvent()
    data class SessionReplaced(val message: String) : HeartbeatEvent()
    data class FreeTimeExhausted(val message: String?, val plans: List<String>) : HeartbeatEvent()
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
class PlayerRepository(context: Context) {
    private val tokenManager = TokenManager(context)
    private val api by lazy { NetworkModule.stream(context, tokenManager) }
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
                    StreamHandshakeResult.FreeTimeExhausted(err?.message)
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

    private fun parseErrorBody(raw: String?): HeartbeatErrorBody? {
        if (raw.isNullOrBlank()) return null
        return try { json.decodeFromString(HeartbeatErrorBody.serializer(), raw) } catch (e: Exception) { null }
    }
}
