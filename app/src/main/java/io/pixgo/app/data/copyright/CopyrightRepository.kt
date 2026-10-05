package io.pixgo.app.data.copyright

import android.content.Context
import io.pixgo.app.data.network.CopyrightApi
import io.pixgo.app.data.network.NetworkModule
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/**
 * Fluxo de notificação de direitos autorais (rotas app/copyright/…). Contrato lido
 * em lib/api.ts (copyrightApi.submit / lookup) e lib/copyright.ts.
 */
class CopyrightRepository(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences("pixgo_copyright", Context.MODE_PRIVATE)
    private val api: CopyrightApi by lazy { NetworkModule.copyright() }
    private val json = Json { ignoreUnknownKeys = true; isLenient = true; explicitNulls = false }

    // ── Protocolos guardados (readStoredReports / saveStoredReport) ──
    fun readStored(): List<StoredReport> = runCatching {
        val raw = prefs.getString(STORE_KEY, null) ?: return emptyList()
        json.decodeFromString(ListSerializer(StoredReport.serializer()), raw)
            .filter { PROTOCOL_RE.matches(it.id) }
    }.getOrDefault(emptyList())

    fun saveStored(entry: StoredReport) {
        runCatching {
            val list = readStored().filter { it.id != entry.id }.toMutableList()
            list.add(0, entry)
            val trimmed = list.take(STORE_MAX)
            prefs.edit().putString(STORE_KEY, json.encodeToString(ListSerializer(StoredReport.serializer()), trimmed)).apply()
        }
    }

    // ── copyrightApi.submit ──
    suspend fun submit(s: ReportSubmission): SubmitResult = withContext(Dispatchers.IO) {
        try {
            val body = buildJsonObject {
                put("claimant_name", s.claimantName)
                put("claimant_email", s.claimantEmail)
                put("relationship", s.relationship)
                put("work_url", s.workUrl)
                put("detection_method", s.detectionMethod)
                put("details", s.details)
                put("items", buildJsonArray {
                    s.items.forEach {
                        add(buildJsonObject {
                            put("url", it.url)
                            put("full", it.full)
                            if (it.full) { put("start", JsonNull); put("end", JsonNull) }
                            else { put("start", it.start); put("end", it.end) }
                        })
                    }
                })
                put("declaration_good_faith", true)
                put("declaration_accuracy", true)
                if (s.lang != null) put("lang", s.lang)
            }
            val resp = api.submit(body)
            when {
                resp.code() == 429 -> SubmitResult.RateLimited
                !resp.isSuccessful -> SubmitResult.Failed
                else -> {
                    val obj = resp.body() as? JsonObject
                    val id = obj?.get("id")?.jsonPrimitive?.contentOrNull
                    if (id.isNullOrBlank()) SubmitResult.Failed
                    else SubmitResult.Ok(id, obj?.get("created_at")?.jsonPrimitive?.contentOrNull ?: "")
                }
            }
        } catch (e: Exception) {
            SubmitResult.Failed
        }
    }

    // ── copyrightApi.lookup ── lança em qualquer falha (a UI mostra copyright.errors.load)
    suspend fun lookup(items: List<Pair<String, String>>): List<PublicReport> = withContext(Dispatchers.IO) {
        val body = buildJsonObject {
            put("items", buildJsonArray {
                items.forEach { (id, email) -> add(buildJsonObject { put("id", id); put("email", email) }) }
            })
        }
        val resp = api.lookup(body)
        if (!resp.isSuccessful) throw java.io.IOException("lookup ${resp.code()}")
        val arr = (resp.body() as? JsonObject)?.get("reports") as? JsonArray ?: return@withContext emptyList()
        json.decodeFromJsonElement(ListSerializer(PublicReport.serializer()), arr)
    }

    private companion object {
        const val STORE_KEY = "pixgo_copyright_reports"
        const val STORE_MAX = 50
    }
}
