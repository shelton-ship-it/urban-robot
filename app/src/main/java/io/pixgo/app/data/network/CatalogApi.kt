package io.pixgo.app.data.network

import io.pixgo.app.data.model.CatalogResponse
import io.pixgo.app.data.model.ContinueItem
import io.pixgo.app.data.model.LegalResponse
import io.pixgo.app.data.model.MyListResponse
import io.pixgo.app.data.model.SearchResponse
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import retrofit2.Response
import retrofit2.http.Body
import retrofit2.http.DELETE
import retrofit2.http.GET
import retrofit2.http.POST
import retrofit2.http.Path
import retrofit2.http.QueryMap

/**
 * routes/channels.js — só o "gate" anti-abuso; os dados dos canais em si
 * vêm 100% client-side do jsDelivr (ver ChannelsSource), não daqui.
 */
interface ChannelsGateApi {
    @GET("/api/channels/{id}")
    suspend fun gate(@Path("id") id: String): Response<Unit>

    @POST("/api/channels/{id}/heartbeat")
    suspend fun heartbeat(@Path("id") id: String): Response<Unit>
}

/**
 * pixel_service_v1 (api.pixgo.qzz.io) — catálogo/progresso, tal como
 * chamados por app/main/page.tsx via lib/api.ts (catalogApi.list,
 * progressApi.continue). Paths e nomes de parâmetro confirmados no
 * ficheiro, não inventados.
 */
interface CatalogApi {
    @GET("/api/catalog")
    suspend fun list(@QueryMap params: Map<String, String>): Response<CatalogResponse>

    @GET("/api/progress/continue")
    suspend fun continueWatching(@QueryMap params: Map<String, String>): Response<List<ContinueItem>>

    @GET("/api/search")
    suspend fun search(@QueryMap params: Map<String, String>): Response<SearchResponse>

    /** routes/mylist.js — atenção: parâmetro é "profileId" (camelCase), diferente de profile_id no catálogo. */
    @GET("/api/mylist")
    suspend fun myList(@QueryMap params: Map<String, String>): Response<MyListResponse>

    @POST("/api/mylist/add")
    suspend fun addToMyList(@Body body: MyListMutationBody): Response<Unit>

    @POST("/api/mylist/remove")
    suspend fun removeFromMyList(@Body body: MyListMutationBody): Response<Unit>

    /** routes/legal.js — público, sem sessão necessária. */
    @GET("/api/legal/{lang}")
    suspend fun legal(@Path("lang") lang: String): Response<LegalResponse>
}

@Serializable
data class MyListMutationBody(
    @SerialName("profileId") val profileId: String,
    @SerialName("contentId") val contentId: String
)
