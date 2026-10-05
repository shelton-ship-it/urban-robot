package io.pixgo.app.data.network

import kotlinx.serialization.Serializable
import retrofit2.Response
import retrofit2.http.Body
import retrofit2.http.POST

@Serializable data class ReportAbuseBody(val contentTitle: String, val reason: String)
@Serializable data class SupportBody(val email: String, val message: String)

/** chatApi.send (lib/api.ts) — POST /chat { message, history[{role,content}] } → { reply } */
@Serializable data class ChatMessageDto(val role: String, val content: String)
@Serializable data class ChatBody(val message: String, val history: List<ChatMessageDto>)
@Serializable data class ChatReply(val reply: String? = null)

/**
 * copyright.pixgo.qzz.io — Worker de moderação separado (lib/api.ts:
 * uploadReq/contactApi), fora da API principal. Só Bearer token, sem
 * cookies — o original também usa fetch() simples ali, não
 * credentials:'include'. Ver NetworkModule.contactClient() para o
 * OkHttpClient isolado (sem o CookieJar partilhado).
 */
interface ContactApi {
    @POST("/report-abuse")
    suspend fun reportAbuse(@Body body: ReportAbuseBody): Response<Unit>

    @POST("/support")
    suspend fun support(@Body body: SupportBody): Response<Unit>

    @POST("/chat")
    suspend fun chat(@Body body: ChatBody): Response<ChatReply>
}
