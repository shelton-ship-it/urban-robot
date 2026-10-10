package io.pixgo.app.data.download

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject

/**
 * Modelos do contrato REAL de download — pixel_service_v1
 * routes/content.js, GET /api/content/:id/download (lido integralmente;
 * nada aqui foi inventado). Resposta:
 *
 *   { license, expires_in, expires_at, drm_key_hex,
 *     manifest: { contentId, segmentCount, noncesUrl, masterUrl, initUrl,
 *                 segUrls[], encrypted, segExt },
 *     content:  { id, title, type, poster, duration },
 *     plan, downloads_remaining, downloads_max }
 */
@Serializable
data class DownloadManifestJson(
    val contentId: String = "",
    val segmentCount: Int = 0,
    val noncesUrl: String? = null,
    val masterUrl: String? = null,
    val initUrl: String? = null,
    val segUrls: List<String> = emptyList(),
    val encrypted: Boolean = true,
    val segExt: String = "bin",
)

@Serializable
data class DownloadContentInfo(
    val id: String = "",
    val title: String = "",
    val type: String? = null,
    val poster: String? = null,
    val duration: Int? = null,
)

@Serializable
data class DownloadResponse(
    val license: String = "",
    @SerialName("expires_in") val expiresIn: Long = 0,
    @SerialName("expires_at") val expiresAt: String = "",
    @SerialName("drm_key_hex") val drmKeyHex: String? = null,
    val manifest: DownloadManifestJson,
    val content: DownloadContentInfo? = null,
    val plan: String? = null,
    @SerialName("downloads_remaining") val downloadsRemaining: Int? = null,
    @SerialName("downloads_max") val downloadsMax: Int? = null,
)

/** Body 403 real do gate de download (canDownload em edgeone.js). */
@Serializable
data class DownloadGateError(
    val message: String? = null,
    @SerialName("current_plan") val currentPlan: String? = null,
    val exhausted: Boolean = false,
)

/**
 * Metadado persistido de um download — equivalente Android do registo
 * STORE_META de lib/downloads.ts (ActiveDownloadMeta + meta concluído),
 * com os mesmos campos de estado usados pela página Downloads.
 *
 * `key` é a chave local: contentId puro para filme/post, e
 * "{contentId}_{episodeId}" quando o manifesto veio para um episódio
 * (o endpoint usa contentId = episode || id — cada episódio é um
 * download separado, exatamente como no web, onde o id da rota é o
 * próprio episódio selecionado).
 */
enum class DownloadStatus { DOWNLOADING, ERROR, COMPLETED }

@Serializable
data class DownloadMeta(
    val key: String,
    val contentId: String,
    val episodeId: String? = null,
    val title: String,
    val poster: String,
    val status: DownloadStatus = DownloadStatus.DOWNLOADING,
    /** 0-100, mesmo campo lido pelo polling da página Downloads original. */
    val progress: Int = 0,
    val segCount: Int = 0,
    val quality: String = "",
    val error: String? = null,
    val startedAt: String,
    /** Preenchidos só na conclusão (meta 'completed' do original). */
    val expiresAt: String? = null,
    val downloadedAt: String? = null,
    /** Chave ChaCha20 (drm_key_hex) — só existe em downloads concluídos. */
    val keyHex: String? = null,
    /** true se init.bin está gravado (sem ele o MSE nunca reproduz). */
    val hasInit: Boolean = false,
    val noncesUrl: String? = null,
)


// ─────────────────────────────────────────────────────────────────────────────
// Parser TOLERANTE da resposta de GET /api/content/:id/download.
//
// Antes a resposta era desserializada directamente para DownloadResponse. Um único
// escalar com tipo diferente do esperado (ex.: content.duration fraccionário num campo
// Int, um null num campo String não-nulo, um id numérico) deitava abaixo o download
// inteiro com "Unexpected JSON token at offset N" — a licença e os ~500 segmentos já
// estavam validos, só um campo acessório falhava. O frontend web não sofre disto porque
// JS não tipa os campos. Aqui lê-se a árvore JSON e extrai-se cada campo de forma
// defensiva; só a falta do manifesto/segmentos (o que torna o download impossível) é erro.
// ─────────────────────────────────────────────────────────────────────────────
private val downloadJson = Json { ignoreUnknownKeys = true; isLenient = true }

private fun JsonElement?.str(): String? =
    (this as? JsonPrimitive)?.takeIf { it !is JsonNull }?.content

private fun JsonElement?.num(): Double? = str()?.toDoubleOrNull()

fun parseDownloadResponse(raw: String): DownloadResponse {
    val root: JsonObject = try {
        downloadJson.parseToJsonElement(raw).jsonObject
    } catch (e: Exception) {
        android.util.Log.e("PixGoDownload", "JSON inválido (len=${raw.length}): ${e.message}")
        throw Exception("Resposta de download inválida (${raw.length} bytes): ${e.message?.take(140)}")
    }

    val m = root["manifest"] as? JsonObject
        ?: throw Exception("Resposta de download sem manifesto")
    val segUrls = (m["segUrls"] as? JsonArray)?.mapNotNull { it.str() }.orEmpty()

    val manifest = DownloadManifestJson(
        contentId = m["contentId"].str() ?: "",
        segmentCount = m["segmentCount"].num()?.toInt() ?: segUrls.size,
        noncesUrl = m["noncesUrl"].str()?.takeIf { it.isNotBlank() },
        masterUrl = m["masterUrl"].str()?.takeIf { it.isNotBlank() },
        initUrl = m["initUrl"].str()?.takeIf { it.isNotBlank() },
        segUrls = segUrls,
        encrypted = m["encrypted"].str()?.let { it == "true" } ?: true,
        segExt = m["segExt"].str()?.takeIf { it.isNotBlank() } ?: "bin",
    )

    val c = root["content"] as? JsonObject
    val content = c?.let {
        DownloadContentInfo(
            id = it["id"].str() ?: "",
            title = it["title"].str() ?: "",
            type = it["type"].str(),
            poster = it["poster"].str(),
            duration = it["duration"].num()?.toInt(),   // aceita 2512 e 2512.64
        )
    }

    return DownloadResponse(
        license = root["license"].str() ?: "",
        expiresIn = root["expires_in"].num()?.toLong() ?: 0L,
        expiresAt = root["expires_at"].str() ?: "",
        drmKeyHex = root["drm_key_hex"].str(),
        manifest = manifest,
        content = content,
        plan = root["plan"].str(),
        downloadsRemaining = root["downloads_remaining"].num()?.toInt(),
        downloadsMax = root["downloads_max"].num()?.toInt(),
    )
}
