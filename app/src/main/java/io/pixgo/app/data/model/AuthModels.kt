package io.pixgo.app.data.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

// Campos e nomes espelham exatamente interface User/Plan/Profile em
// pixel/frontend_web/src/store/auth.ts. Nada aqui foi inventado —
// qualquer campo devolvido pelo backend e não usado ainda fica de fora
// (kotlinx.serialization ignora campos desconhecidos por definição no
// Json { ignoreUnknownKeys = true } configurado em NetworkModule).

@Serializable
data class User(
    val id: String,
    val username: String,
    val name: String,
    val email: String? = null,
    val role: String? = null,
    @SerialName("plan_id") val planId: String? = null
)

@Serializable
data class Plan(
    val id: String,
    val name: String,
    @SerialName("price_brl") val priceBrl: Double? = null,
    @SerialName("price_usdt") val priceUsdt: Double? = null,
    @SerialName("is_active") val isActive: Boolean? = null,
    @SerialName("expires_at") val expiresAt: String? = null,
    @SerialName("duration_days") val durationDays: Int? = null,
    @SerialName("max_profiles") val maxProfiles: Int? = null,
    @SerialName("max_downloads") val maxDownloads: Int? = null,
    val currency: String? = null,
    val gateway: String? = null,
    /** Marca visível da processadora em MZ (NET-H | PAY-T) e métodos (mpesa | mpesa+emola) — decididos pelo backend (env MZ_GATEWAY). */
    val processor: String? = null,
    val methods: List<String>? = null
)

/**
 * Item de GET /api/payments/plans (pixel_service_v1 routes/payments.js —
 * fonte: PLANS em lib/edgeone.js + override de país em plan-pricing-read).
 * Campos e nomes espelham exatamente o type Plan em
 * frontend_web/src/app/main/plans/page.tsx. Nada inventado.
 */
@Serializable
data class PaymentPlan(
    val id: String,
    val name: String,
    val price: Double? = null,
    val label: String? = null,
    @SerialName("billing_cycle") val billingCycle: String? = null,
    @SerialName("max_profiles") val maxProfiles: Int? = null,
    @SerialName("max_downloads") val maxDownloads: Int? = null,
    val features: List<String> = emptyList(),
    val currency: String? = null,
    val gateway: String? = null
)

/**
 * GET /api/plans do api-core (hub) — routes/plans.js: { plans, current }.
 * O preço vem de PLANS (lib/edgeone.js: PLAN_*_PRICE do env, base BRL) e, para IPs de
 * Moçambique, já vem convertido (currency 'MZN'). `price` pode vir como número ou texto
 * (o hub faz Number(p.price)), por isso é lido como JsonPrimitive.
 */
@Serializable
data class HubPlan(
    val id: String,
    val name: String = "",
    val price: kotlinx.serialization.json.JsonPrimitive? = null,
    @SerialName("is_free") val isFree: Boolean? = null,
    val currency: String? = null,
    val gateway: String? = null,
    val processor: String? = null,
    val methods: List<String>? = null
)

@Serializable
data class HubPlansResponse(val plans: List<HubPlan> = emptyList())

@Serializable
data class Profile(
    val id: String,
    val name: String,
    val avatar: String? = null,
    val language: String? = null,
    @SerialName("is_kid") val isKid: Boolean? = null
)

/** Resposta de POST /api/auth/login, /api/auth/device/activate, /api/auth/me */
@Serializable
data class AuthResponse(
    val token: String? = null,
    @SerialName("refresh_token") val refreshToken: String? = null,
    val user: User? = null,
    val plan: Plan? = null,
    val profiles: List<Profile> = emptyList()
)

/** Espelha MeCache (user/plan/profiles + cachedAt) para persistência local */
@Serializable
data class MeCache(
    val user: User? = null,
    val plan: Plan? = null,
    val profiles: List<Profile> = emptyList(),
    val cachedAt: Long = 0L
)

@Serializable
data class ApiErrorBody(
    val message: String? = null,
    /** Código curto do backend (ex.: "AccountExistsUnlinked" em POST /api/auth/google). */
    val error: String? = null
)
