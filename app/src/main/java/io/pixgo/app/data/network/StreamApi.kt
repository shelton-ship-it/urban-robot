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
    // Conteúdo NÃO cifrado: o servidor devolve drm_key_hex:null (routes/content.js) — com String
    // não nula a desserialização rebentava e o handshake falhava em todas as tentativas.
    @SerialName("drm_key_hex") val drmKeyHex: String? = null,
    @SerialName("seg_ext") val segExt: String? = "bin",
    // playlist.qualities[0] — formato não garantido (string/objeto); não é usado pelo player.
    val quality: kotlinx.serialization.json.JsonElement? = null,
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

    /**
     * GET /api/content/:id/download — routes/content.js. Manifesto real de
     * download offline (license/expires_at/drm_key_hex/manifest com segUrls,
     * noncesUrl, initUrl). Query "episode" confirmada no fetch da watch page.
     */
    @GET("/api/content/{id}/download")
    suspend fun download(
        @Path("id") contentId: String,
        @QueryMap params: Map<String, String>,
    ): Response<io.pixgo.app.data.download.DownloadResponse>

    /**
     * MESMO endpoint, mas devolve o corpo CRU: a desserialização é feita por
     * parseDownloadResponse() (tolerante a tipos). Usado pelo DownloadEngine.
     */
    @GET("/api/content/{id}/download")
    suspend fun downloadRaw(
        @Path("id") contentId: String,
        @QueryMap params: Map<String, String>,
    ): Response<okhttp3.ResponseBody>

    @retrofit2.http.POST("/api/content/{id}/heartbeat")
    suspend fun heartbeat(@Path("id") contentId: String, @Body body: HeartbeatBody): Response<Unit>
}
