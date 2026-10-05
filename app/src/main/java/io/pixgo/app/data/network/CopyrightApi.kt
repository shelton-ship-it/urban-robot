package io.pixgo.app.data.network

import kotlinx.serialization.json.JsonElement
import retrofit2.Response
import retrofit2.http.Body
import retrofit2.http.POST

/**
 * copyrightApi de frontend_web/src/lib/api.ts (copyrightReq): mesmo Worker
 * copyright.pixgo.qzz.io, endpoints PÚBLICOS — quem notifica costuma não ter
 * conta, por isso NÃO envia Authorization nem cookies (cliente sem
 * interceptores: NetworkModule.plainHttpClient()). Sem cache.
 */
interface CopyrightApi {
    @POST("/dmca/reports")
    suspend fun submit(@Body body: JsonElement): Response<JsonElement>

    @POST("/dmca/reports/lookup")
    suspend fun lookup(@Body body: JsonElement): Response<JsonElement>
}
