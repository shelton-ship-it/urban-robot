package io.pixgo.app.data.i18n

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.langDataStore by preferencesDataStore(name = "pixgo_lang_store")
private val KEY_LANG = stringPreferencesKey("pixgo_lang")

/** Espelha LANGUAGES em i18n/index.ts. Só pt/en/es existem — nada inventado. */
data class Language(val code: String, val label: String, val flag: String, val native: String)

val SUPPORTED_LANGUAGES = listOf(
    Language("pt", "Português", "🇧🇷", "Português"),
    Language("en", "English", "🇺🇸", "English"),
    Language("es", "Español", "🇪🇸", "Español")
)

/**
 * A app nativa mantém português como idioma por defeito (pedido explícito),
 * mas expõe o mesmo seletor pt/en/es do original — sem forçar nenhum modal
 * na entrada. Persistido localmente (equivalente a localStorage
 * 'pixgo_lang') e, quando há sessão, também mandado ao backend via
 * POST /api/auth/language (routes/auth.js do pixel_service), tal como o
 * handleLangChange() do AppShell original.
 */
class LanguageManager(private val context: Context) {
    val languageCode: Flow<String> = context.langDataStore.data.map { it[KEY_LANG] ?: "pt" }

    suspend fun setLanguage(code: String) {
        context.langDataStore.edit { it[KEY_LANG] = code }
    }
}

/**
 * O backend do catálogo só serve conteúdo em pt/en (SUPPORTED_LANGUAGES em
 * lib/geoip.js) — 'es' da UI cai em 'en', exactamente como contentLang()
 * em lib/api.ts.
 */
fun contentLangFor(uiLangCode: String): String = if (uiLangCode == "pt") "pt" else "en"
