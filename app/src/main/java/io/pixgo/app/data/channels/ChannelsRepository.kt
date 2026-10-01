package io.pixgo.app.data.channels

import android.content.Context
import io.pixgo.app.data.auth.AuthRepository
import io.pixgo.app.data.auth.TokenManager
import io.pixgo.app.data.network.HeartbeatErrorBody
import io.pixgo.app.data.network.NetworkModule
import io.pixgo.app.data.player.HeartbeatEvent
import kotlinx.serialization.json.Json
import retrofit2.Response

sealed class ChannelGateResult {
    object Ok : ChannelGateResult()
    data class Denied(val message: String) : ChannelGateResult()
}

class ChannelsRepository(context: Context, private val auth: AuthRepository) {
    private val tokenManager = TokenManager(context)
    private val api by lazy { NetworkModule.channelsGate(context, tokenManager) }
    private val json = Json { ignoreUnknownKeys = true }

    suspend fun categories() = ChannelsSource.getCategories()
    suspend fun list(page: Int, category: String?, hasUser: Boolean) = ChannelsSource.listChannels(page, category, hasUser)
    suspend fun search(query: String, hasUser: Boolean) = ChannelsSource.searchChannels(query, hasUser)

    private suspend fun <T> retryOn401(call: suspend () -> Response<T>): Response<T> {
        val resp = call()
        if (resp.code() == 401 && auth.refreshAccessToken() != null) return call()
        return resp
    }

    /** GET /api/channels/:id — gate anti-abuso antes de "reproduzir" (ver routes/channels.js). */
    suspend fun checkGate(channelId: String): ChannelGateResult {
        return try {
            val resp = retryOn401 { api.gate(channelId) }
            if (resp.isSuccessful) ChannelGateResult.Ok
            else ChannelGateResult.Denied(
                when (resp.code()) {
                    429 -> "Limite diário de streaming atingido."
                    409 -> "Sessão substituída noutro ecrã."
                    401 -> "Faça login para aceder ao canal."
                    else -> "Não foi possível abrir o canal."
                }
            )
        } catch (e: Exception) {
            ChannelGateResult.Denied("Falha de rede.")
        }
    }

    /**
     * Mesma quota/formato de erro do heartbeat de VOD (rate-limit.js trata
     * /api/channels/:id/heartbeat exactamente como /api/content/:id/
     * heartbeat) — 409=sessão substituída, 429=tempo grátis esgotado.
     * Chamado a cada 120s enquanto o canal reproduz (ver ChannelsPlayerScreen).
     */
    suspend fun heartbeat(channelId: String): HeartbeatEvent {
        return try {
            val resp = retryOn401 { api.heartbeat(channelId) }
            when (resp.code()) {
                409 -> HeartbeatEvent.SessionReplaced(
                    parseErrorMessage(resp)?.message ?: "A sua sessão foi encerrada neste dispositivo."
                )
                429 -> {
                    val err = parseErrorMessage(resp)
                    HeartbeatEvent.FreeTimeExhausted(err?.message, err?.plans ?: emptyList())
                }
                else -> HeartbeatEvent.Ok
            }
        } catch (e: Exception) {
            HeartbeatEvent.Ok // falha de rede pontual — ignora, tenta de novo no próximo tick (igual ao original)
        }
    }

    private fun parseErrorMessage(resp: Response<*>): HeartbeatErrorBody? {
        val raw = resp.errorBody()?.string() ?: return null
        return try { json.decodeFromString(HeartbeatErrorBody.serializer(), raw) } catch (e: Exception) { null }
    }
}
