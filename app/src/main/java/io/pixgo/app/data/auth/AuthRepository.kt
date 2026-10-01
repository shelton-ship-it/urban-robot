package io.pixgo.app.data.auth

import android.content.Context
import io.pixgo.app.data.model.AuthResponse
import io.pixgo.app.data.model.MeCache
import io.pixgo.app.data.model.Plan
import io.pixgo.app.data.model.Profile
import io.pixgo.app.data.model.User
import io.pixgo.app.data.network.DeviceActivateBody
import io.pixgo.app.data.network.LoginBody
import io.pixgo.app.data.network.NetworkModule
import io.pixgo.app.data.network.RefreshBody
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json

data class AuthState(
    val user: User? = null,
    val plan: Plan? = null,
    val profiles: List<Profile> = emptyList(),
    val activeProfileId: String? = null,
    val token: String? = null,
    val hydrated: Boolean = false,
    val loading: Boolean = false
)

class ApiException(val status: Int, message: String) : Exception(message)

/**
 * Réplica 1:1 de pixel/frontend_web/src/store/auth.ts — mesmas chaves,
 * mesmas regras de TTL de cache, mesmo fluxo de refresh com uma única
 * chamada em curso de cada vez (lá era uma Promise partilhada; aqui é um
 * Mutex). A única adaptação real de plataforma: o login por
 * utilizador/senha e por Google passam pelo api-core (o "hub" — ver
 * instrução do dono do projecto: app = hub de login e pagamentos) em vez
 * de um redirect de página para app.pixgo.qzz.io, porque esta app não tem
 * subdomínios separados. fetchMe/refresh/logout continuam no
 * pixel_service (api.pixgo.qzz.io), exactamente como no store original.
 */
class AuthRepository(private val context: Context) {

    private val tokenManager = TokenManager(context)
    private val apiCore by lazy { NetworkModule.apiCoreAuth(context, tokenManager) }
    private val pixelService by lazy { NetworkModule.pixelServiceAuth(context, tokenManager) }
    private val json = Json { ignoreUnknownKeys = true; explicitNulls = false }

    private val refreshMutex = Mutex()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val _state = MutableStateFlow(AuthState())
    val state: StateFlow<AuthState> = _state

    /** Chamado uma vez no arranque — hidrata a partir do cache local, tal
     * como a hidratação síncrona no topo de store/auth.ts. */
    fun bootstrap() {
        scope.launch {
            val token = tokenManager.getToken()
            val cacheRaw = tokenManager.getMeCacheRaw()
            val cache = cacheRaw?.let { runCatching { json.decodeFromString<MeCache>(it) }.getOrNull() }
            val tokenExp = token?.let { tokenManager.decodeJwtExpMs(it) }
            val tokenLooksValid = tokenExp != null && tokenExp > System.currentTimeMillis()

            if (cache != null && token != null && tokenLooksValid) {
                val activeId = resolveActiveProfileId(cache.profiles)
                _state.value = AuthState(
                    user = cache.user, plan = cache.plan, profiles = cache.profiles,
                    activeProfileId = activeId, token = token, hydrated = true
                )
            } else {
                _state.value = AuthState(hydrated = false)
            }
            fetchMe()
        }
    }

    private suspend fun resolveActiveProfileId(profiles: List<Profile>): String? {
        if (profiles.isEmpty()) return null
        val stored = tokenManager.getActiveProfileId()
        return if (stored != null && profiles.any { it.id == stored }) stored else profiles.first().id
    }

    suspend fun setActiveProfile(id: String) {
        val exists = _state.value.profiles.any { it.id == id }
        if (!exists) return
        tokenManager.setActiveProfile(id)
        _state.value = _state.value.copy(activeProfileId = id)
    }

    private suspend fun writeMeCache(user: User?, plan: Plan?, profiles: List<Profile>) {
        val cache = MeCache(user, plan, profiles, System.currentTimeMillis())
        tokenManager.writeMeCache(json.encodeToString(MeCache.serializer(), cache))
    }

    suspend fun login(username: String, password: String) {
        _state.value = _state.value.copy(loading = true)
        try {
            val resp = apiCore.login(LoginBody(username, password))
            if (!resp.isSuccessful) throw ApiException(resp.code(), parseErrorMessage(resp) ?: "Login failed")
            val data = resp.body() ?: throw ApiException(resp.code(), "Empty response")
            applyAuthResponse(data)
            // Réplica de fetchMe() logo a seguir ao login — o hub não devolve
            // perfis; api.pixgo.qzz.io/api/auth/me devolve perfis + plano.
            fetchMe(force = true)
        } finally {
            _state.value = _state.value.copy(loading = false)
        }
    }

    suspend fun loginWithDeviceCode(code: String) {
        _state.value = _state.value.copy(loading = true)
        try {
            val resp = apiCore.activateDeviceCode(DeviceActivateBody(code))
            if (!resp.isSuccessful) throw ApiException(resp.code(), parseErrorMessage(resp) ?: "Activation failed")
            val data = resp.body() ?: throw ApiException(resp.code(), "Empty response")
            applyAuthResponse(data)
            fetchMe(force = true)
        } finally {
            _state.value = _state.value.copy(loading = false)
        }
    }

    private suspend fun applyAuthResponse(data: AuthResponse) {
        data.token?.let { tokenManager.setToken(it) }
        data.refreshToken?.let { tokenManager.setRefreshToken(it) }
        val activeId = resolveActiveProfileId(data.profiles)
        activeId?.let { tokenManager.setActiveProfile(it) }
        writeMeCache(data.user, data.plan, data.profiles)
        _state.value = _state.value.copy(
            token = data.token ?: _state.value.token,
            user = data.user, plan = data.plan, profiles = data.profiles,
            activeProfileId = activeId
        )
    }

    suspend fun fetchMe(force: Boolean = false) {
        val cacheRaw = tokenManager.getMeCacheRaw()
        val cache = cacheRaw?.let { runCatching { json.decodeFromString<MeCache>(it) }.getOrNull() }
        val token = tokenManager.getToken()
        val tokenExp = token?.let { tokenManager.decodeJwtExpMs(it) }
        val tokenFresh = tokenExp != null && (tokenExp - System.currentTimeMillis() > TokenManager.TOKEN_MIN_TTL_MS)
        val isFreeCached = cache?.plan == null || cache.plan.id == "free" || cache.plan.isActive == false
        val cacheTtl = if (isFreeCached) TokenManager.ME_CACHE_FREE_TTL_MS else TokenManager.ME_CACHE_TTL_MS
        val cacheFresh = cache != null && (System.currentTimeMillis() - cache.cachedAt < cacheTtl)

        if (!force && cache != null && token != null && tokenFresh && cacheFresh) {
            _state.value = _state.value.copy(hydrated = true)
            return
        }

        try {
            val resp = authedMe()
            if (resp == null || !resp.isSuccessful) {
                tokenManager.clearAll()
                _state.value = AuthState(hydrated = true)
                return
            }
            val data = resp.body() ?: AuthResponse()
            val activeId = resolveActiveProfileId(data.profiles)
            data.token?.let { tokenManager.setToken(it) }
            writeMeCache(data.user, data.plan, data.profiles)
            _state.value = _state.value.copy(
                user = data.user, plan = data.plan, profiles = data.profiles,
                activeProfileId = activeId,
                token = data.token ?: tokenManager.getToken(),
                hydrated = true
            )
        } catch (e: Exception) {
            // Falha de rede (não uma resposta negativa real do servidor) —
            // mantém o token local intacto, tal como o catch em fetchMe()
            // do store original (o offline continua a funcionar).
            _state.value = _state.value.copy(hydrated = true, token = tokenManager.getToken())
        }
    }

    /** GET /api/auth/me com retry automático de 401 via refresh — equivalente a authedFetch(). */
    private suspend fun authedMe(): retrofit2.Response<AuthResponse>? {
        val resp = pixelService.me()
        if (resp.code() == 401) {
            val newToken = refreshAccessToken()
            if (newToken == null) return resp
            return pixelService.me()
        }
        return resp
    }

    /** Único refresh em curso de cada vez — equivalente a _refreshPromise em store/auth.ts. */
    suspend fun refreshAccessToken(): String? = refreshMutex.withLock {
        val refreshToken = tokenManager.getRefreshToken() ?: return@withLock null
        try {
            val resp = pixelService.refresh(RefreshBody(refreshToken))
            if (!resp.isSuccessful) {
                tokenManager.clearAll()
                return@withLock null
            }
            val data = resp.body() ?: return@withLock null
            data.token?.let { tokenManager.setToken(it) }
            data.refreshToken?.let { tokenManager.setRefreshToken(it) }
            _state.value = _state.value.copy(token = data.token)
            data.token
        } catch (e: Exception) {
            null
        }
    }

    suspend fun logout() {
        val refresh = tokenManager.getRefreshToken()
        tokenManager.clearAll()
        NetworkModule.clearCookies()
        _state.value = AuthState(hydrated = true)
        // Melhor esforço, em paralelo — a UI já reagiu (mesmo padrão do original).
        scope.launch {
            runCatching { pixelService.logout(RefreshBody(refresh)) }
        }
    }

    /** Chamado quando a pessoa volta do checkout com px_paid (ver CheckoutStatusPage/ZumboPay). */
    suspend fun invalidateMeCacheAfterPayment() {
        tokenManager.clearMeCache()
        fetchMe(force = true)
    }

    fun isAdmin(): Boolean = _state.value.user?.role == "admin"

    /** Best-effort — espelha authApi.setLanguage(code) chamado por handleLangChange() no AppShell original. */
    suspend fun setLanguageServerSide(lang: String) {
        runCatching { pixelService.setLanguage(io.pixgo.app.data.network.LanguageBody(lang)) }
    }

    /** Retry-uma-vez-em-401 genérico, igual ao authedFetch() — reutilizado pelas mutações abaixo. */
    private suspend fun <T> retryOn401(call: suspend () -> retrofit2.Response<T>): retrofit2.Response<T> {
        val resp = call()
        if (resp.code() == 401 && refreshAccessToken() != null) return call()
        return resp
    }

    /** PUT /api/auth/me — authApi.update() no original (account/page.tsx: saveProfile). */
    suspend fun updateMe(body: io.pixgo.app.data.network.UpdateMeBody) {
        val resp = retryOn401 { pixelService.updateMe(body) }
        if (!resp.isSuccessful) throw ApiException(resp.code(), parseErrorMessage(resp) ?: "Update failed")
        fetchMe(force = true)
    }

    /** POST /api/auth/change-password — mín. 8 caracteres validado na UI, tal como o original. */
    suspend fun changePassword(current: String, new: String) {
        val resp = retryOn401 {
            pixelService.changePassword(
                io.pixgo.app.data.network.ChangePasswordBody(currentPassword = current, newPassword = new)
            )
        }
        if (!resp.isSuccessful) throw ApiException(resp.code(), parseErrorMessage(resp) ?: "Change password failed")
    }

    suspend fun createProfile(body: io.pixgo.app.data.network.ProfileBody) {
        val resp = retryOn401 { pixelService.createProfile(body) }
        if (!resp.isSuccessful) throw ApiException(resp.code(), parseErrorMessage(resp) ?: "Create profile failed")
        fetchMe(force = true)
    }

    suspend fun updateProfile(id: String, body: io.pixgo.app.data.network.ProfileBody) {
        val resp = retryOn401 { pixelService.updateProfile(id, body) }
        if (!resp.isSuccessful) throw ApiException(resp.code(), parseErrorMessage(resp) ?: "Update profile failed")
        fetchMe(force = true)
    }

    suspend fun deleteProfile(id: String) {
        val resp = retryOn401 { pixelService.deleteProfile(id) }
        if (!resp.isSuccessful) throw ApiException(resp.code(), parseErrorMessage(resp) ?: "Delete profile failed")
        fetchMe(force = true)
    }

    private fun parseErrorMessage(resp: retrofit2.Response<*>): String? = try {
        val body = resp.errorBody()?.string()
        body?.let { json.decodeFromString<io.pixgo.app.data.model.ApiErrorBody>(it).message }
    } catch (e: Exception) {
        null
    }
}
