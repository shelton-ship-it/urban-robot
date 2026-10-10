package io.pixgo.app.data.auth

import android.content.Context
import io.pixgo.app.data.model.AuthResponse
import io.pixgo.app.data.model.MeCache
import io.pixgo.app.data.model.Plan
import io.pixgo.app.data.model.Profile
import io.pixgo.app.data.model.User
import io.pixgo.app.data.network.DeviceActivateBody
import io.pixgo.app.data.network.GoogleCredentialBody
import io.pixgo.app.data.network.LoginBody
import io.pixgo.app.data.network.NetworkModule
import io.pixgo.app.data.network.RefreshBody
import io.pixgo.app.data.network.RegisterBody
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
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

class ApiException(val status: Int, message: String, val error: String? = null) : Exception(message)

/**
 * Réplica 1:1 de pixel/frontend_web/src/store/auth.ts — mesmas chaves,
 * mesmas regras de TTL de cache, mesmo fluxo de refresh com uma única
 * chamada em curso de cada vez (lá era uma Promise partilhada; aqui é um
 * Mutex). A única adaptação real de plataforma: login, registo e Google
 * chamam diretamente os endpoints REST do api-core (o "hub",
 * /api/auth/{login,register,google}) a partir de ecrãs nativos — sem
 * WebView e sem redirect para app.pixgo.qzz.io. fetchMe/refresh/logout continuam no
 * pixel_service (api.pixgo.qzz.io), exactamente como no store original.
 */
class AuthRepository(private val context: Context) {

    private val tokenManager = TokenManager(context)

    // Última vez que o /me foi consultado (rede OU cache). Só serve para limitar o
    // refresh ao voltar ao app (refreshOnResume) — não altera as regras de TTL do cache.
    @Volatile private var lastMeFetchAt = 0L
    // Garante UM só polling de plano em simultâneo (ver pollPlanUntilActive).
    private val planPollActive = java.util.concurrent.atomic.AtomicBoolean(false)
    private val apiCore by lazy { NetworkModule.apiCoreAuth(context, tokenManager) }
    private val pixelService by lazy { NetworkModule.pixelServiceAuth(context, tokenManager) }
    private val uploadApi by lazy { NetworkModule.upload(tokenManager) }
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

            // Offline-first: com token + cache locais mantém a sessão MESMO que o access
            // token já tenha expirado (o refresh só é possível com rede). Antes exigia
            // token ainda válido: token expirado + sem rede ⇒ estado vazio (user/plano
            // null) ⇒ Downloads bloqueados offline. Se a sessão estiver mesmo inválida,
            // o fetchMe() com rede limpa-a (401/403) e volta ao login.
            if (cache != null && token != null) {
                val activeId = resolveActiveProfileId(cache.profiles)
                _state.value = AuthState(
                    user = cache.user, plan = effectivePlan(cache.plan), profiles = cache.profiles,
                    activeProfileId = activeId, token = token, hydrated = true
                )
            } else {
                _state.value = AuthState(hydrated = false)
            }
            fetchMe()
        }
    }

    /**
     * Plano a mostrar quando o valor vem do CACHE local (sem rede para o servidor
     * confirmar): se o plano pago já passou do `expires_at` pelo relógio do aparelho,
     * trata-o como inativo — o servidor faria o mesmo (syncPlanExpiry). Valores vindos
     * do /me online são autoritativos e não passam por aqui.
     */
    private fun effectivePlan(plan: Plan?): Plan? {
        if (plan == null || plan.id == "free") return plan
        val exp = plan.expiresAt?.let { runCatching { java.time.Instant.parse(it).toEpochMilli() }.getOrNull() }
        return if (exp != null && exp <= System.currentTimeMillis()) plan.copy(isActive = false) else plan
    }

    /**
     * Falha de rede / resposta não-401 no /me: mantém a sessão. Se por algum motivo o
     * estado ficou sem utilizador mas há cache local, repõe-no a partir do cache (rede de
     * segurança do modo offline — Downloads têm de continuar acessíveis).
     */
    private suspend fun keepSession(cache: MeCache?) {
        val cur = _state.value
        val token = tokenManager.getToken()
        _state.value = if (cur.user == null && cache != null && token != null) {
            cur.copy(
                user = cache.user, plan = effectivePlan(cache.plan), profiles = cache.profiles,
                activeProfileId = resolveActiveProfileId(cache.profiles), token = token, hydrated = true
            )
        } else cur.copy(hydrated = true, token = token)
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

    /**
     * "Continuar com Google" — POST /api/auth/google no hub com o ID token
     * (credential) obtido na WebView de login. Mesmo tratamento de sucesso
     * de login(): token + refresh_token gravados, depois fetchMe(force) para
     * trazer perfis/plano do pixel_service (padrão confirmado em
     * api-core/node-functions/api/routes/auth.js).
     */
    suspend fun loginWithGoogleCredential(credential: String) {
        _state.value = _state.value.copy(loading = true)
        try {
            val resp = apiCore.loginWithGoogle(GoogleCredentialBody(credential))
            if (!resp.isSuccessful) {
                val e = parseErrorBody(resp)
                throw ApiException(resp.code(), e?.message ?: "Google login failed", e?.error)
            }
            val data = resp.body() ?: throw ApiException(resp.code(), "Empty response")
            applyAuthResponse(data)
            fetchMe(force = true)
        } finally {
            _state.value = _state.value.copy(loading = false)
        }
    }

    /**
     * Registo nativo — POST /api/auth/register no hub (api-core). Mesmo payload
     * de useAuthStore.register() em app/_shared/store/auth.ts: email só vai se
     * preenchido. A resposta (201) traz { user, plan, token, refresh_token },
     * tratada exatamente como o login; depois fetchMe(force) traz perfis/plano.
     */
    suspend fun register(name: String, username: String, email: String?, password: String) {
        _state.value = _state.value.copy(loading = true)
        try {
            val resp = apiCore.register(
                RegisterBody(
                    name = name.trim(),
                    username = username.trim(),
                    email = email?.trim()?.takeIf { it.isNotEmpty() },
                    password = password
                )
            )
            if (!resp.isSuccessful) {
                val e = parseErrorBody(resp)
                throw ApiException(resp.code(), e?.message ?: "Register failed", e?.error)
            }
            val data = resp.body() ?: throw ApiException(resp.code(), "Empty response")
            applyAuthResponse(data)
            fetchMe(force = true)
        } finally {
            _state.value = _state.value.copy(loading = false)
        }
    }

    // ── Plans: GET /api/plans do api-core (plansApi.list() do hub) ──────────────
    /**
     * Mesma fonte da página de planos do hub (PlansPage.tsx → plansApi.list()): preço do env
     * do api-core (BRL), convertido para MZN quando o IP é de Moçambique. NÃO usa o
     * /api/payments/plans do pixel_service, que tem preços fixos no código e não segue o hub.
     * `label` sai de formatPlanPrice (port de lib/planPrice.ts), igual ao texto do hub.
     */
    suspend fun paymentPlans(): List<io.pixgo.app.data.model.PaymentPlan> {
        val resp = retryOn401 { apiCore.plans() }
        if (!resp.isSuccessful) throw ApiException(resp.code(), parseErrorMessage(resp) ?: "Failed to load plans")
        val plans = resp.body()?.plans.orEmpty()
        val listCurrency = plans.firstNotNullOfOrNull { it.currency }
        return plans.map { p ->
            val free = p.isFree == true || p.id == "free"
            val value = p.price?.content?.toDoubleOrNull() ?: 0.0
            io.pixgo.app.data.model.PaymentPlan(
                id = p.id,
                name = p.name,
                price = value,
                label = io.pixgo.app.data.model.formatPlanPrice(value, p.currency, free, listCurrency),
                billingCycle = io.pixgo.app.data.model.planCycle(p.id),
                currency = p.currency,
                gateway = p.gateway,
                processor = p.processor,
                methods = p.methods,
            )
        }
    }

    // ── Upload (uploadApi real em lib/api.ts → copyright.pixgo.qzz.io) ──────

    /** POST /precheck — payload idêntico ao formulário web. */
    suspend fun uploadPrecheck(body: io.pixgo.app.data.network.UploadPrecheckRequest): UploadResult2 {
        val resp = uploadApi.precheck(body)
        if (!resp.isSuccessful) {
            val raw = runCatching { resp.errorBody()?.string() }.getOrNull()
            val msg = raw?.let { runCatching { json.decodeFromString<kotlinx.serialization.json.JsonObject>(it) }
                .getOrNull()?.get("error")?.let { e -> (e as? kotlinx.serialization.json.JsonPrimitive)?.content } }
            throw ApiException(resp.code(), msg ?: "Upload request failed")
        }
        val obj = resp.body() as? kotlinx.serialization.json.JsonObject ?: throw ApiException(resp.code(), "Empty response")
        fun str(k: String): String? = (obj[k] as? kotlinx.serialization.json.JsonPrimitive)
            ?.takeIf { !it.isString || it.content != "null" }?.content
        return UploadResult2(id = str("id") ?: "", status = str("status") ?: "pending")
    }

    /** GET /precheck-status/:id — polling de 15s enquanto pending no original. */
    suspend fun uploadStatus(id: String): UploadResult2 {
        val resp = uploadApi.status(id)
        if (!resp.isSuccessful) throw ApiException(resp.code(), "Upload status failed")
        val b = resp.body() ?: throw ApiException(resp.code(), "Empty response")
        return UploadResult2(id = b.id ?: id, status = b.status ?: "pending")
    }

    data class UploadResult2(val id: String, val status: String)

    suspend fun getUploadTermsAccepted(): Boolean = tokenManager.getUploadTermsAccepted()
    suspend fun setUploadTermsAccepted(accepted: Boolean) = tokenManager.setUploadTermsAccepted(accepted)

    /**
     * Equivalente ao localStorage 'pixgo_disclaimer_dismissed' do gate real em
     * Providers.tsx (DisclaimerGate). Reutiliza o mesmo DataStore do TokenManager;
     * chave nova, nada existente alterado.
     */
    suspend fun isDisclaimerDismissed(): Boolean = tokenManager.isDisclaimerDismissed()
    suspend fun setDisclaimerDismissed(dismissed: Boolean) = tokenManager.setDisclaimerDismissed(dismissed)
    suspend fun isPixelGreeted(): Boolean = tokenManager.isPixelGreeted()
    suspend fun markPixelGreeted() = tokenManager.setPixelGreeted()
    suspend fun plansModalLastSeen(): String? = tokenManager.getPlansModalLastSeen()
    suspend fun markPlansModalSeen(day: String) = tokenManager.setPlansModalLastSeen(day)

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

    /**
     * Sessão importada do WebView do hub (HubLoginSheet): guarda pixgo_token /
     * pixgo_refresh (mesmas chaves de store/auth.ts) e reflete o token no estado.
     * Só com refresh (sem access token), troca-o por um access token novo via
     * refreshAccessToken() — o mesmo fluxo do refresh automático em 401.
     */
    suspend fun storeHubTokens(token: String?, refresh: String?) {
        refresh?.let { tokenManager.setRefreshToken(it) }
        if (token != null) {
            tokenManager.setToken(token)
            _state.value = _state.value.copy(token = token)
        } else if (refresh != null && tokenManager.getToken() == null) {
            refreshAccessToken()
        }
    }

    /** Token Bearer entregue pela bridge JS (PixGoNative.onToken) do HubLoginSheet. */
    suspend fun storeExternalToken(token: String) {
        tokenManager.setToken(token)
        _state.value = _state.value.copy(token = token)
    }

    suspend fun fetchMe(force: Boolean = false) {
        lastMeFetchAt = System.currentTimeMillis()
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
            if (resp == null) {
                keepSession(cache)
                return
            }
            if (!resp.isSuccessful) {
                android.util.Log.w(TAG, "/me falhou: HTTP ${resp.code()}")
                // Só 401/403 = credencial realmente inválida. O backend devolve 503
                // para falha de infraestrutura (routes/auth.js GET /me: "o cliente
                // mantém a sessão e tenta de novo") — apagar o token aqui expulsava
                // a pessoa logo após um login bem-sucedido.
                if (resp.code() == 401 || resp.code() == 403) {
                    tokenManager.clearAll()
                    _state.value = AuthState(hydrated = true)
                } else {
                    keepSession(cache)
                }
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
            android.util.Log.w(TAG, "/me sem sucesso (rede ou resposta ilegível): ${e.javaClass.simpleName}: ${e.message}")
            // Falha de rede (não uma resposta negativa real do servidor) —
            // mantém o token local intacto, tal como o catch em fetchMe()
            // do store original (o offline continua a funcionar).
            keepSession(cache)
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

    private fun isPaidActive(plan: Plan?): Boolean =
        plan != null && plan.id != "free" && plan.isActive != false

    /**
     * Depois de um pagamento concluído no checkout: o plano é ativado por webhook
     * (assíncrono). O hub só espera ~15 s no estado "approved" e nem espera em
     * pendente/análise (boleto, Pix), por isso o /me pode ainda dizer "free". Repete
     * o /me (sempre à rede) a cada [intervalMs] até o plano aparecer ativo, a sessão
     * cair ou passar [timeoutMs]. Devolve true se o plano ficou ativo.
     */
    suspend fun pollPlanUntilActive(timeoutMs: Long = 60_000L, intervalMs: Long = 3_000L): Boolean {
        if (!planPollActive.compareAndSet(false, true)) return isPaidActive(_state.value.plan)
        try {
            val deadline = System.currentTimeMillis() + timeoutMs
            while (true) {
                fetchMe(force = true)
                if (_state.value.user == null) return false          // sessão terminou (401/403)
                if (isPaidActive(_state.value.plan)) return true
                if (System.currentTimeMillis() + intervalMs > deadline) return false
                delay(intervalMs)
            }
        } finally {
            planPollActive.set(false)
        }
    }

    /**
     * Ao voltar ao app (ex.: Pix pago na app do banco, boleto compensado, webhook que
     * atrasou): revalida o plano à rede. Intervalo mínimo entre pedidos: 5 s (free) / 60 s (pago)
     * e nunca durante um polling de plano em curso. Sem sessão não faz nada.
     */
    suspend fun refreshOnResume() {
        val cur = _state.value
        if (cur.user == null) { android.util.Log.d(TAG, "resume: sem sessão — ignorado"); return }
        if (planPollActive.get()) { android.util.Log.d(TAG, "resume: polling de plano em curso — ignorado"); return }
        // Utilizador free = pode ter acabado de pagar noutro sítio → intervalo curto.
        // Plano pago = nada a esperar → intervalo longo.
        val isFree = cur.plan == null || cur.plan.id == "free" || cur.plan.isActive == false
        val minGap = if (isFree) RESUME_REFRESH_FREE_MS else RESUME_REFRESH_PAID_MS
        val since = System.currentTimeMillis() - lastMeFetchAt
        if (since < minGap) {
            android.util.Log.d(TAG, "resume: ignorado (último /me há ${since} ms < ${minGap} ms)")
            return
        }
        android.util.Log.d(TAG, "resume: a revalidar /me (plano atual=${cur.plan?.id})")
        fetchMe(force = true)
        android.util.Log.d(TAG, "resume: plano agora=${_state.value.plan?.id} ativo=${_state.value.plan?.isActive}")
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

    private fun parseErrorBody(resp: retrofit2.Response<*>): io.pixgo.app.data.model.ApiErrorBody? = try {
        val body = resp.errorBody()?.string()
        body?.let { json.decodeFromString<io.pixgo.app.data.model.ApiErrorBody>(it) }
    } catch (e: Exception) {
        null
    }

    private fun parseErrorMessage(resp: retrofit2.Response<*>): String? = parseErrorBody(resp)?.message
}

private const val TAG = "PixGoAuth"
private const val RESUME_REFRESH_FREE_MS = 5_000L
private const val RESUME_REFRESH_PAID_MS = 60_000L
