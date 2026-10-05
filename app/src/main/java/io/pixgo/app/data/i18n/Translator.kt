package io.pixgo.app.data.i18n

import android.content.Context
import androidx.compose.runtime.staticCompositionLocalOf
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * Equivalente a i18n/index.ts: os MESMOS ficheiros pt/en/es.json do
 * frontend_web (copiados sem alteração para assets/locales), fallbackLng
 * 'pt', interpolação {{var}} (escapeValue:false).
 */
class Translator private constructor(
    val code: String,
    private val primary: JsonObject,
    private val fallback: JsonObject,
) {
    fun t(key: String, vars: Map<String, String> = emptyMap()): String {
        val raw = lookup(primary, key) ?: lookup(fallback, key) ?: key
        if (vars.isEmpty()) return raw
        var out = raw
        vars.forEach { (k, v) -> out = out.replace("{{$k}}", v) }
        return out
    }

    private fun lookup(root: JsonObject, key: String): String? {
        var cur: JsonElement = root
        for (part in key.split('.')) {
            cur = (cur as? JsonObject)?.get(part) ?: return null
        }
        return (cur as? JsonPrimitive)?.takeIf { it.isString }?.content
    }

    companion object {
        private val json = Json { ignoreUnknownKeys = true }
        private val cache = mutableMapOf<String, JsonObject>()

        private fun bundle(context: Context, code: String): JsonObject = synchronized(cache) {
            cache.getOrPut(code) {
                context.assets.open("locales/$code.json").bufferedReader().use {
                    json.parseToJsonElement(it.readText()) as JsonObject
                }
            }
        }

        fun create(context: Context, code: String): Translator {
            val c = if (code in listOf("pt", "en", "es")) code else "pt"
            return Translator(c, bundle(context, c), bundle(context, "pt"))
        }
    }
}

val LocalTranslator = staticCompositionLocalOf<Translator> { error("Translator não fornecido") }
