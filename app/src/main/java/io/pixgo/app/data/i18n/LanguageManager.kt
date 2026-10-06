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

    /**
     * Equivalente ao gate de Providers.tsx no web: `localStorage.getItem('pixgo_lang')`
     * só existe depois da primeira escolha no LanguageModal. Aqui, ausente = modal
     * de idioma deve aparecer uma única vez antes de qualquer conteúdo.
     */
    val langChosen: Flow<Boolean> = context.langDataStore.data.map { prefs -> prefs[KEY_LANG] != null }

    suspend fun setLanguage(code: String) {
        context.langDataStore.edit { it[KEY_LANG] = code }
    }
}

/**
 * Idioma dos METADADOS (título/descrição) pedidos ao backend.
 *
 * Todo o conteúdo é gravado em PORTUGUÊS no upload (não se usa en/es para
 * metadados). O backend faz `COALESCE(tradução_no_idioma, tradução_en)`: se
 * pedirmos "en" (ou deixarmos o servidor adivinhar pelo IP/cabeçalhos) não há
 * tradução nem fallback e os metadados vêm vazios. Por isso o catálogo, a
 * pesquisa, a Watch e a Minha Coleção pedem SEMPRE "pt". O idioma da INTERFACE
 * (pt/en/es) continua a ser independente — só afecta os textos da app.
 */
const val CONTENT_LANG = "pt"

@Suppress("UNUSED_PARAMETER")
fun contentLangFor(uiLangCode: String): String = CONTENT_LANG
