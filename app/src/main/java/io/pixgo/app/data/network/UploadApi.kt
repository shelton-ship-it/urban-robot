package io.pixgo.app.data.network

import kotlinx.serialization.Serializable
import retrofit2.Response
import retrofit2.http.Body
import retrofit2.http.GET
import retrofit2.http.POST
import retrofit2.http.Path

// ── copyright.pixgo.qzz.io — Worker de moderação separado (lib/api.ts: uploadApi) ──
// Contrato real lido em frontend_web/src/lib/api.ts (uploadReq/uploadApi) e no
// formulário frontend_web/src/app/main/upload/page.tsx. Nada inventado.

@Serializable
data class UploadMetadata(
    val title: String,
    val description: String,
    val type: String,
    val year: Int,
    val lang: String,
    val contentId: String? = null,
)

@Serializable
data class UploaderInfo(
    val id: String? = null,
    val username: String? = null,
    val email: String? = null,
)

/**
 * dispatch é polimórfico no JSON real (page.tsx envia objeto com campos
 * diferentes por tipo). Serializado como JsonElement cru para preservar
 * exatamente o formato do original:
 *   série : { type:'manual', season_number, file_indices, seg_duration, max_encode_height, warm_concurrency }
 *   outro : { type:'lote', lote:'A', file_indices, seg_duration, max_encode_height, warm_concurrency }
 */
@Serializable
data class UploadPrecheckRequest(
    val metadata: UploadMetadata,
    val uploader: UploaderInfo,
    val goCreative: Boolean,
    val videoUrl: String,
    val thumbnailUrl: String,
    val dispatch: kotlinx.serialization.json.JsonElement,
)

/** Respostas reais: /precheck → { id, status }; /precheck-status/:id → { id, status, ... } */
@Serializable
data class UploadStatusResponse(
    val id: String? = null,
    val status: String? = null,
)

interface UploadApi {
    @POST("/precheck")
    suspend fun precheck(@Body body: UploadPrecheckRequest): Response<kotlinx.serialization.json.JsonElement>

    @GET("/precheck-status/{id}")
    suspend fun status(@Path("id") id: String): Response<UploadStatusResponse>
}
