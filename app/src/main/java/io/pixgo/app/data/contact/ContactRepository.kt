package io.pixgo.app.data.contact

import android.content.Context
import io.pixgo.app.data.auth.TokenManager
import io.pixgo.app.data.network.NetworkModule
import io.pixgo.app.data.network.ChatBody
import io.pixgo.app.data.network.ChatMessageDto
import io.pixgo.app.data.network.ReportAbuseBody
import io.pixgo.app.data.network.SupportBody

class ContactRepository(context: Context) {
    private val tokenManager = TokenManager(context)
    private val api by lazy { NetworkModule.contact(tokenManager) }

    suspend fun reportAbuse(title: String, reason: String): Boolean =
        try { api.reportAbuse(ReportAbuseBody(title, reason)).isSuccessful } catch (e: Exception) { false }

    suspend fun support(email: String, message: String): Boolean =
        try { api.support(SupportBody(email, message)).isSuccessful } catch (e: Exception) { false }

    /** Pixel (PixelChatbot.tsx): devolve o texto da resposta, ou null em qualquer falha. */
    suspend fun chat(message: String, history: List<ChatMessageDto>): String? =
        try {
            val r = api.chat(ChatBody(message, history))
            if (r.isSuccessful) r.body()?.reply?.takeIf { it.isNotBlank() } else null
        } catch (e: Exception) { null }
}
