package io.pixgo.app.data.legal

import android.content.Context
import io.pixgo.app.data.auth.TokenManager
import io.pixgo.app.data.model.LegalResponse
import io.pixgo.app.data.network.NetworkModule

/** GET /api/legal/:lang — público (routes/legal.js: "sem autenticação de propósito"). */
class LegalRepository(private val context: Context) {
    private val tokenManager = TokenManager(context)
    private val api by lazy { NetworkModule.catalog(context, tokenManager) }

    suspend fun fetch(lang: String): LegalResponse? {
        val resp = api.legal(lang)
        return if (resp.isSuccessful) resp.body() else null
    }
}
