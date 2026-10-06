package io.pixgo.app.data.prefs

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Preferências LOCAIS de interface (SharedPreferences, leitura síncrona — não
 * precisa de DataStore/coroutines). Hoje guarda só se o ícone do assistente
 * Pixel (IA) está oculto, para a escolha do utilizador sobreviver a rotações,
 * fecho da app e novos arranques.
 */
object UiPrefs {
    private const val FILE = "pixgo_ui_prefs"
    private const val KEY_AI_HIDDEN = "pixel_ai_hidden"

    private val _aiHidden = MutableStateFlow(false)
    @Volatile private var loaded = false

    /** `true` quando o utilizador arrastou o ícone da IA para o "X" (ou o ocultou na Conta). */
    val aiHidden: StateFlow<Boolean> = _aiHidden.asStateFlow()

    private fun ensureLoaded(context: Context) {
        if (loaded) return
        synchronized(this) {
            if (loaded) return
            val sp = context.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE)
            _aiHidden.value = sp.getBoolean(KEY_AI_HIDDEN, false)
            loaded = true
        }
    }

    /** Chamar cedo (ex.: onCreate) para o primeiro frame já refletir a preferência guardada. */
    fun init(context: Context) = ensureLoaded(context)

    fun setAiHidden(context: Context, hidden: Boolean) {
        ensureLoaded(context)
        _aiHidden.value = hidden
        context.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE)
            .edit().putBoolean(KEY_AI_HIDDEN, hidden).apply()
    }
}

/**
 * Flags de SESSÃO (vivem enquanto o processo existir; reiniciam num arranque
 * novo). Equivalem ao estado `useState` das páginas web, que só se perde num
 * reload real — NÃO numa rotação. Evitam que um modal já tratado volte a abrir
 * se a Activity/composição for recriada por qualquer motivo.
 */
object SessionFlags {
    /** DisclaimerModal já aceite/recusado nesta sessão. */
    @Volatile var disclaimerHandled: Boolean = false
}
