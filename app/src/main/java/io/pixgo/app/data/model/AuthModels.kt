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
    val gateway: String? = null
)

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
    val message: String? = null
)
