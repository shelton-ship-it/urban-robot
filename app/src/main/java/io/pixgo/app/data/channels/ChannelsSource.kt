package io.pixgo.app.data.channels

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import okhttp3.OkHttpClient
import okhttp3.Request

/**
 * Réplica 1:1 de lib/channels-source.ts: 100% client-side, sem passar pelo
 * nosso backend. Busca playlist.m3u + logos.json directamente do jsDelivr
 * (mirror público do repo shelton-ship-it/assets-main), faz o parsing do
 * M3U aqui e trata categoria/paginação/pesquisa localmente. O backend
 * (routes/channels.js) só serve de "gate" anti-abuso antes de reproduzir —
 * ver ChannelsGateApi, não isto.
 */
data class RawChannel(
    val id: String,
    val name: String,
    val url: String,
    val logo: String,
    val group: String,
    val country: String,
    val language: String,
    val tvgId: String
)

data class ChannelListItem(
    val id: String,
    val name: String,
    val logo: String,
    val group: String,
    val country: String,
    val locked: Boolean,
    val hasAccess: Boolean,
    val url: String?
)

data class ChannelsPage(val channels: List<ChannelListItem>, val pages: Int, val grandTotal: Int)
data class ChannelCategory(val name: String, val slug: String, val count: Int)

object ChannelsSource {
    private const val PLAYLIST_URL = "https://cdn.jsdelivr.net/gh/shelton-ship-it/assets-main@main/playlist.m3u"
    private const val LOGOS_URL = "https://cdn.jsdelivr.net/gh/shelton-ship-it/assets-main@main/logos.json"
    private const val CACHE_TTL_MS = 22 * 60 * 60 * 1000L
    private const val ITEMS_LIMIT = 24

    private val client = OkHttpClient()
    private val mutex = Mutex()
    private var cache: List<RawChannel>? = null
    private var cacheTime = 0L

    private val attrRegex = mapOf(
        "logo" to Regex("tvg-logo=\"([^\"]*)\""),
        "group" to Regex("group-title=\"([^\"]*)\""),
        "tvgId" to Regex("tvg-id=\"([^\"]*)\""),
        "country" to Regex("tvg-country=\"([^\"]*)\""),
        "language" to Regex("tvg-language=\"([^\"]*)\"")
    )

    private fun parseM3U(text: String, logos: Map<String, String>): List<RawChannel> {
        val channels = mutableListOf<RawChannel>()
        val lines = text.split("\n").map { it.trim() }.filter { it.isNotEmpty() }
        var index = 0
        var pending: RawChannelBuilder? = null

        for (line in lines) {
            if (line.startsWith("#EXTINF")) {
                index++
                val ci = line.lastIndexOf(',')
                val name = if (ci != -1) line.substring(ci + 1).trim() else ""
                pending = RawChannelBuilder(
                    id = "ch_$index",
                    name = name,
                    logo = attrRegex.getValue("logo").find(line)?.groupValues?.get(1) ?: "",
                    group = attrRegex.getValue("group").find(line)?.groupValues?.get(1)?.ifBlank { "outros" } ?: "outros",
                    tvgId = attrRegex.getValue("tvgId").find(line)?.groupValues?.get(1) ?: "",
                    country = attrRegex.getValue("country").find(line)?.groupValues?.get(1) ?: "",
                    language = attrRegex.getValue("language").find(line)?.groupValues?.get(1) ?: ""
                )
            } else if (pending != null && !line.startsWith("#")) {
                val p = pending
                val logo = p.logo.ifBlank { if (p.tvgId.isNotBlank()) logos[p.tvgId] ?: "" else "" }
                if (p.name.isNotBlank() && line.isNotBlank()) {
                    channels.add(
                        RawChannel(p.id, p.name, line, logo, p.group, p.country, p.language, p.tvgId)
                    )
                }
                pending = null
            }
        }
        return channels
    }

    private data class RawChannelBuilder(
        val id: String, val name: String, val logo: String, val group: String,
        val tvgId: String, val country: String, val language: String
    )

    private suspend fun fetchText(url: String): String = withContext(Dispatchers.IO) {
        client.newCall(Request.Builder().url(url).build()).execute().use { resp ->
            if (!resp.isSuccessful) throw java.io.IOException("HTTP ${resp.code}")
            resp.body?.string() ?: ""
        }
    }

    private suspend fun fetchAndParse(): List<RawChannel> {
        val playlistText = fetchText(PLAYLIST_URL)
        val logosMap: Map<String, String> = try {
            val logosText = fetchText(LOGOS_URL)
            val json = Json.parseToJsonElement(logosText) as? JsonObject ?: JsonObject(emptyMap())
            json.mapValues { (_, v) -> (v as? JsonPrimitive)?.content ?: "" }
        } catch (e: Exception) {
            emptyMap()
        }
        return parseM3U(playlistText, logosMap)
    }

    private suspend fun getRawChannels(): List<RawChannel> {
        val now = System.currentTimeMillis()
        cache?.let { if (now - cacheTime < CACHE_TTL_MS) return it }
        return mutex.withLock {
            val c = cache
            if (c != null && System.currentTimeMillis() - cacheTime < CACHE_TTL_MS) return@withLock c
            val fetched = fetchAndParse()
            cache = fetched
            cacheTime = System.currentTimeMillis()
            fetched
        }
    }

    /** Deduplicação por nome (case-insensitive) — mesma razão do original: a playlist agrega várias fontes. */
    private fun dedupe(channels: List<RawChannel>): List<RawChannel> {
        val seen = HashSet<String>()
        return channels.filter {
            val key = it.name.trim().lowercase()
            if (key.isBlank() || key in seen) false else { seen.add(key); true }
        }
    }

    private fun mapForList(ch: RawChannel, hasUser: Boolean) = ChannelListItem(
        id = ch.id, name = ch.name, logo = ch.logo, group = ch.group, country = ch.country,
        locked = !hasUser, hasAccess = hasUser, url = if (hasUser) ch.url else null
    )

    suspend fun listChannels(page: Int, category: String?, hasUser: Boolean): ChannelsPage {
        val all = dedupe(getRawChannels())
        val filtered = if (category != null) all.filter { it.group.equals(category, ignoreCase = true) } else all
        val total = filtered.size
        val offset = (page - 1) * ITEMS_LIMIT
        val paginated = filtered.drop(offset).take(ITEMS_LIMIT).map { mapForList(it, hasUser) }
        return ChannelsPage(paginated, maxOf(1, (total + ITEMS_LIMIT - 1) / ITEMS_LIMIT), all.size)
    }

    suspend fun getCategories(): List<ChannelCategory> {
        val all = getRawChannels()
        val counts = LinkedHashMap<String, Int>()
        for (ch in all) {
            val g = ch.group.ifBlank { "outros" }
            counts[g] = (counts[g] ?: 0) + 1
        }
        return counts.entries.sortedBy { it.key }.map { ChannelCategory(it.key, it.key.lowercase(), it.value) }
    }

    suspend fun searchChannels(query: String, hasUser: Boolean): List<ChannelListItem> {
        val all = getRawChannels()
        val q = query.lowercase().trim()
        val filtered = if (q.isBlank()) all else all.filter {
            it.name.lowercase().contains(q) || it.group.lowercase().contains(q) ||
                it.country.lowercase().contains(q) || it.language.lowercase().contains(q)
        }
        return filtered.take(200).map { mapForList(it, hasUser) }
    }
}
