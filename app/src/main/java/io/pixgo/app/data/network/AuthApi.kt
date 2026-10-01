package io.pixgo.app.data.network

import io.pixgo.app.data.model.AuthResponse
import io.pixgo.app.data.model.Plan
import io.pixgo.app.data.model.Profile
import io.pixgo.app.data.model.User
import retrofit2.Response
import retrofit2.http.Body
import retrofit2.http.DELETE
import retrofit2.http.GET
import retrofit2.http.POST
import retrofit2.http.PUT
import retrofit2.http.Path
import kotlinx.serialization.Serializable

@Serializable data class LoginBody(val username: String, val password: String)
@Serializable data class RefreshBody(@kotlinx.serialization.SerialName("refresh_token") val refreshToken: String? = null)
@Serializable data class DeviceActivateBody(val code: String)
@Serializable data class UpdateMeBody(val name: String? = null, val email: String? = null)
@Serializable data class ChangePasswordBody(
    @kotlinx.serialization.SerialName("current_password") val currentPassword: String,
    @kotlinx.serialization.SerialName("new_password") val newPassword: String
)
@Serializable data class ProfileBody(val name: String, @kotlinx.serialization.SerialName("is_kid") val isKid: Boolean)
@Serializable data class LanguageBody(val lang: String)

/**
 * api-core (pixel.pixgo.qzz.io) — o "hub": responsável por login/registo/
 * Google/pareamento de TV e (ver AuthApi de pagamentos, à parte) planos e
 * ZumboPay/Hotmart. Rotas confirmadas em
 * api-core/node-functions/api/routes/{auth,device}.js — nada inventado.
 */
interface ApiCoreAuthApi {
    @POST("/api/auth/login")
    suspend fun login(@Body body: LoginBody): Response<AuthResponse>

    @POST("/api/auth/register")
    suspend fun register(@Body body: Map<String, String>): Response<AuthResponse>

    @POST("/api/auth/device/activate")
    suspend fun activateDeviceCode(@Body body: DeviceActivateBody): Response<AuthResponse>
}

/**
 * pixel_service_v1 (api.pixgo.qzz.io) — backend do próprio app de vídeo:
 * catálogo/conteúdo/stream + a sessão do dia a dia (me/refresh/logout/
 * perfis). Rotas confirmadas em
 * pixel_service_v1/node-functions/api/routes/auth.js.
 */
interface PixelServiceAuthApi {
    @GET("/api/auth/me")
    suspend fun me(): Response<AuthResponse>

    @PUT("/api/auth/me")
    suspend fun updateMe(@Body body: UpdateMeBody): Response<AuthResponse>

    @POST("/api/auth/refresh")
    suspend fun refresh(@Body body: RefreshBody): Response<AuthResponse>

    @POST("/api/auth/logout")
    suspend fun logout(@Body body: RefreshBody): Response<Unit>

    @POST("/api/auth/change-password")
    suspend fun changePassword(@Body body: ChangePasswordBody): Response<Unit>

    /** routes/auth.js — POST /api/auth/language, chamado pelo seletor (ver AppShell.handleLangChange). */
    @POST("/api/auth/language")
    suspend fun setLanguage(@Body body: LanguageBody): Response<Unit>

    @POST("/api/auth/profiles")
    suspend fun createProfile(@Body body: ProfileBody): Response<Profile>

    @PUT("/api/auth/profiles/{id}")
    suspend fun updateProfile(@Path("id") id: String, @Body body: ProfileBody): Response<Profile>

    @DELETE("/api/auth/profiles/{id}")
    suspend fun deleteProfile(@Path("id") id: String): Response<Unit>
}
