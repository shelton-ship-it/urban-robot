package io.pixgo.app.ui.auth

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import androidx.credentials.CredentialManager
import androidx.credentials.CustomCredential
import androidx.credentials.GetCredentialRequest
import androidx.credentials.exceptions.GetCredentialCancellationException
import androidx.credentials.exceptions.GetCredentialException
import android.util.Log
import com.google.android.libraries.identity.googleid.GetSignInWithGoogleOption
import com.google.android.libraries.identity.googleid.GoogleIdTokenCredential
import com.google.android.libraries.identity.googleid.GoogleIdTokenParsingException
import io.pixgo.app.BuildConfig

sealed class GoogleResult {
    /** ID token (JWT) do Google — é o `credential` que POST /api/auth/google espera. */
    data class Token(val idToken: String) : GoogleResult()
    object Cancelled : GoogleResult()
    data class Failure(val cause: Throwable?) : GoogleResult()
}

/**
 * "Continuar com Google" nativo (Credential Manager) — equivalente Android do
 * GoogleAuthButton.tsx do hub (Google Identity Services). O resultado é o MESMO
 * artefacto que o botão web entrega ao callback: um ID token, enviado depois a
 * POST /api/auth/google { credential }.
 *
 * Tal como no web (`if (!GOOGLE_CLIENT_ID) return null`), sem client id
 * configurado o botão nem aparece. O id é o MESMO GOOGLE_CLIENT_ID do backend
 * (tipo "Web application"): o backend valida `audience` contra ele.
 */
object GoogleSignIn {
    val enabled: Boolean get() = BuildConfig.GOOGLE_WEB_CLIENT_ID.isNotBlank()

    suspend fun requestIdToken(context: Context): GoogleResult {
        val activity = context.findActivity() ?: return GoogleResult.Failure(IllegalStateException("sem Activity"))
        // FLUXO OFICIAL DO BOTÃO "Sign in with Google" (Credential Manager).
        // Antes usava GetGoogleIdOption, pensado para "login automático" com contas já
        // autorizadas: falhava com NoCredentialException em primeiro uso/sem conta
        // previamente autorizada. GetSignInWithGoogleOption mostra sempre o seletor
        // de contas do Google e devolve o mesmo ID token (audience = Web client id).
        val option = GetSignInWithGoogleOption.Builder(BuildConfig.GOOGLE_WEB_CLIENT_ID).build()
        val request = GetCredentialRequest.Builder().addCredentialOption(option).build()
        return try {
            val result = CredentialManager.create(activity).getCredential(activity, request)
            val cred = result.credential
            if (cred is CustomCredential && cred.type == GoogleIdTokenCredential.TYPE_GOOGLE_ID_TOKEN_CREDENTIAL) {
                GoogleResult.Token(GoogleIdTokenCredential.createFrom(cred.data).idToken)
            } else {
                GoogleResult.Failure(IllegalStateException("credencial inesperada: ${cred.type}"))
            }
        } catch (e: GetCredentialCancellationException) {
            GoogleResult.Cancelled
        } catch (e: GetCredentialException) {
            Log.e(TAG, "GetCredentialException type=${e.type} msg=${e.errorMessage}", e)
            GoogleResult.Failure(e)
        } catch (e: GoogleIdTokenParsingException) {
            Log.e(TAG, "GoogleIdTokenParsingException", e)
            GoogleResult.Failure(e)
        } catch (e: Exception) {
            Log.e(TAG, "Falha inesperada no login Google", e)
            GoogleResult.Failure(e)
        }
    }

    private const val TAG = "PixGoGoogle"

    /**
     * Motivo técnico curto, SÓ para builds de debug (diagnóstico: distingue falha do
     * Google [SHA-1/pacote/conta] de recusa do backend [audience/credencial]).
     */
    fun debugReason(cause: Throwable?): String {
        if (!BuildConfig.DEBUG || cause == null) return ""
        val detail = when (cause) {
            is GetCredentialException -> "Google: ${cause.type.substringAfterLast('.')} ${cause.errorMessage ?: ""}".trim()
            is io.pixgo.app.data.auth.ApiException -> "Servidor: HTTP ${cause.status} ${cause.error ?: ""} ${cause.message ?: ""}".trim()
            else -> "${cause.javaClass.simpleName}: ${cause.message ?: ""}".trim()
        }
        return "\n[debug] $detail"
    }
}

private fun Context.findActivity(): Activity? {
    var c: Context? = this
    while (c is ContextWrapper) {
        if (c is Activity) return c
        c = c.baseContext
    }
    return null
}
