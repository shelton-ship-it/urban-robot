package io.pixgo.app.data.auth

import android.content.Context
import android.util.Base64
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.first
import org.json.JSONObject

private val Context.authDataStore by preferencesDataStore(name = "pixgo_auth")

/**
 * Espelha 1:1 as chaves/regras de store/auth.ts (pixel/frontend_web):
 *  - pixgo_token / pixgo_refresh
 *  - pixgo_active_profile
 *  - pixgo_me_cache (user/plan/profiles + cachedAt)
 *  - TTL do cache: 30min (plano pago) / 60s (plano free) — ME_CACHE_TTL_MS /
 *    ME_CACHE_FREE_TTL_MS
 *  - token não confiável a <5min de expirar — TOKEN_MIN_TTL_MS
 *
 * Não inventa nenhum valor: os TTLs acima vêm literalmente do ficheiro lido.
 */
class TokenManager(private val context: Context) {

    companion object {
        private val KEY_TOKEN = stringPreferencesKey("pixgo_token")
        private val KEY_REFRESH = stringPreferencesKey("pixgo_refresh")
        private val KEY_ACTIVE_PROFILE = stringPreferencesKey("pixgo_active_profile")
        private val KEY_ME_CACHE = stringPreferencesKey("pixgo_me_cache")

        const val ME_CACHE_TTL_MS = 30 * 60 * 1000L
        const val ME_CACHE_FREE_TTL_MS = 60 * 1000L
        const val TOKEN_MIN_TTL_MS = 5 * 60 * 1000L
    }

    suspend fun getToken(): String? = context.authDataStore.data.first()[KEY_TOKEN]
    suspend fun getRefreshToken(): String? = context.authDataStore.data.first()[KEY_REFRESH]
    suspend fun getActiveProfileId(): String? = context.authDataStore.data.first()[KEY_ACTIVE_PROFILE]
    suspend fun getMeCacheRaw(): String? = context.authDataStore.data.first()[KEY_ME_CACHE]

    suspend fun setToken(token: String) {
        context.authDataStore.edit { it[KEY_TOKEN] = token }
    }

    suspend fun setRefreshToken(refresh: String) {
        context.authDataStore.edit { it[KEY_REFRESH] = refresh }
    }

    suspend fun setActiveProfile(id: String) {
        context.authDataStore.edit { it[KEY_ACTIVE_PROFILE] = id }
    }

    suspend fun writeMeCache(json: String) {
        context.authDataStore.edit { it[KEY_ME_CACHE] = json }
    }

    /** Equivalente a storageDel('pixgo_token','pixgo_refresh','pixgo_active_profile','pixgo_me_cache') */
    suspend fun clearAll() {
        context.authDataStore.edit {
            it.remove(KEY_TOKEN)
            it.remove(KEY_REFRESH)
            it.remove(KEY_ACTIVE_PROFILE)
            it.remove(KEY_ME_CACHE)
        }
    }

    /** Só apaga o cache de /me — usado no equivalente ao fluxo ?px_paid= do pixel */
    suspend fun clearMeCache() {
        context.authDataStore.edit { it.remove(KEY_ME_CACHE) }
    }

    /**
     * Decodifica o `exp` do JWT localmente, sem verificar assinatura — só
     * para decidir se vale a pena confiar no cache sem ir ao servidor
     * (a validação real acontece sempre no backend). Espelha
     * decodeJwtExpMs() em store/auth.ts.
     */
    fun decodeJwtExpMs(token: String): Long? = try {
        var payload = token.split(".")[1].replace('-', '+').replace('_', '/')
        // JWT em base64url normalmente vem sem padding — Base64.decode do
        // Android (ao contrário do atob() do browser) exige-o.
        while (payload.length % 4 != 0) payload += "="
        val decoded = Base64.decode(payload, Base64.DEFAULT)
        val json = JSONObject(String(decoded, Charsets.UTF_8))
        if (json.has("exp")) json.getLong("exp") * 1000L else null
    } catch (e: Exception) {
        null
    }
}
