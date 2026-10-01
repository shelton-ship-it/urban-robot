package io.pixgo.app.data.network

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import retrofit2.Response
import retrofit2.http.Body
import retrofit2.http.GET
import retrofit2.http.Path
import retrofit2.http.QueryMap

@Serializable
data class StreamResponse(
    @SerialName("master_url") val masterUrl: String,
    @SerialName("drm_key_hex") val drmKeyHex: String,
    @SerialName("seg_ext") val segExt: String = "bin",
    val quality: String? = null,
    @SerialName("content_id") val contentId: String? = null,
    @SerialName("episode_id") val episodeId: String? = null
)

@Serializable
data class HeartbeatBody(val position: Int)

@Serializable
data class HeartbeatErrorBody(val message: String? = null, val plans: List<String> = emptyList())

/**
 * routes/content.js — /stream (handshake ECDH + devolve a master_url e a
 * chave) e /heartbeat (crédito de tempo, 409=sessão substituída,
 * 429=tempo grátis esgotado). Ambos em api.pixel_service (pixel_service).
 */
interface StreamApi {
    @GET("/api/content/{id}/stream")
    suspend fun stream(@Path("id") contentId: String, @QueryMap params: Map<String, String>): Response<StreamResponse>

    @retrofit2.http.POST("/api/content/{id}/heartbeat")
    suspend fun heartbeat(@Path("id") contentId: String, @Body body: HeartbeatBody): Response<Unit>
}
