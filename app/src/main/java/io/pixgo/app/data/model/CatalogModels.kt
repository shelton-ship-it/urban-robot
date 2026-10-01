package io.pixgo.app.data.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Espelha exactamente o que app/main/page.tsx lê da resposta de
 * GET /api/catalog?feed=1 (routes/catalog.js): `meta.title/poster/rating`
 * quando presentes, senão os campos soltos `title/poster/rating`. Nada
 * inventado — os dois caminhos (meta.* vs solto) vêm literalmente do
 * `item.meta?.title || item.title` do frontend original.
 */
@Serializable
data class ContentMeta(
    val title: String? = null,
    val poster: String? = null,
    val rating: Double? = null
)

@Serializable
data class ContentItem(
    val id: String,
    val type: String? = null,
    val year: Int? = null,
    val title: String? = null,
    val poster: String? = null,
    val rating: Double? = null,
    val meta: ContentMeta? = null
) {
    val displayTitle: String get() = meta?.title ?: title ?: "—"
    val displayPoster: String? get() = meta?.poster ?: poster
    val displayRating: Double? get() = meta?.rating ?: rating
}

@Serializable
data class Pagination(
    val page: Int? = null,
    val limit: Int? = null,
    val total: Int? = null,
    val pages: Int? = null
)

@Serializable
data class CatalogResponse(
    val items: List<ContentItem> = emptyList(),
    val pagination: Pagination? = null
)

/** GET /api/mylist — routes/mylist.js: entry.content_id + entry.content{id,title,type,poster,year,duration} */
@Serializable
data class MyListEntry(
    @SerialName("content_id") val contentId: String,
    val content: MyListContent? = null
)

@Serializable
data class MyListContent(
    val id: String,
    val title: String? = null,
    val type: String? = null,
    val poster: String? = null,
    val year: Int? = null
)

@Serializable
data class MyListResponse(
    val items: List<MyListEntry> = emptyList(),
    val pagination: Pagination? = null
)

/** GET /api/search — routes/search.js: campo é "results", NÃO "items". */
@Serializable
data class SearchResponse(
    val results: List<ContentItem> = emptyList(),
    val pagination: Pagination? = null
)

/** Item de GET /api/progress/continue — content_id + content.{poster,title} embutidos. */
@Serializable
data class ContinueItem(
    @SerialName("content_id") val contentId: String,
    val progress: Double? = null,
    val content: ContinueContentSummary? = null
)

@Serializable
data class ContinueContentSummary(
    val title: String? = null,
    val poster: String? = null
)
