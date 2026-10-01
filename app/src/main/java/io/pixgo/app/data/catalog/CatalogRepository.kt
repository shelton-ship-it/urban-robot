package io.pixgo.app.data.catalog

import android.content.Context
import io.pixgo.app.data.auth.AuthRepository
import io.pixgo.app.data.auth.TokenManager
import io.pixgo.app.data.model.ContentItem
import io.pixgo.app.data.model.ContinueItem
import io.pixgo.app.data.model.MyListEntry
import io.pixgo.app.data.network.MyListMutationBody
import io.pixgo.app.data.network.NetworkModule
import retrofit2.Response

/**
 * Espelha o loadPage()/progressApi.continue de app/main/page.tsx:
 *  - GET /api/catalog?limit=24&page=N&sort=recent&feed=1(&profile_id=)
 *  - GET /api/progress/continue?limit=6
 * `lang` fixo em "pt" por agora — a UI original manda o idioma activo do
 * i18next; a escolha de idioma na app nativa ainda não foi implementada,
 * por isso não finjo uma lógica que não existe aqui ainda.
 */
class CatalogRepository(private val context: Context, private val auth: AuthRepository) {

    private val tokenManager = TokenManager(context)
    private val api by lazy { NetworkModule.catalog(context, tokenManager) }

    companion object {
        const val ITEMS_LIMIT = 24
    }

    private suspend fun <T> retryOn401(call: suspend () -> Response<T>): Response<T> {
        val resp = call()
        if (resp.code() == 401) {
            if (auth.refreshAccessToken() != null) return call()
        }
        return resp
    }

    suspend fun loadHomePage(page: Int, activeProfileId: String?, lang: String): List<ContentItem> {
        val params = mutableMapOf(
            "limit" to ITEMS_LIMIT.toString(),
            "page" to page.toString(),
            "sort" to "recent",
            "feed" to "1",
            "lang" to lang
        )
        activeProfileId?.let { params["profile_id"] = it }
        val resp = retryOn401 { api.list(params) }
        if (!resp.isSuccessful) return emptyList()
        return resp.body()?.items ?: emptyList()
    }

    suspend fun continueWatching(): List<ContinueItem> {
        val resp = retryOn401 { api.continueWatching(mapOf("limit" to "6")) }
        if (!resp.isSuccessful) return emptyList()
        return resp.body() ?: emptyList()
    }

    /** Espelha app/main/catalog/page.tsx (paginação numerada, não infinita). */
    data class CatalogPage(val items: List<ContentItem>, val pages: Int)

    suspend fun loadCatalogPage(
        type: String,
        sort: String,
        page: Int,
        activeProfileId: String?,
        lang: String
    ): CatalogPage {
        val params = mutableMapOf(
            "limit" to ITEMS_LIMIT.toString(),
            "page" to page.toString(),
            "sort" to sort,
            "lang" to lang
        )
        if (type != "all") params["type"] = type
        activeProfileId?.let { params["profile_id"] = it }
        val resp = retryOn401 { api.list(params) }
        if (!resp.isSuccessful) return CatalogPage(emptyList(), 1)
        val body = resp.body() ?: return CatalogPage(emptyList(), 1)
        return CatalogPage(body.items, body.pagination?.pages ?: 1)
    }

    /** Espelha app/main/search/page.tsx — sem sugestões/popular (removidas do original). */
    suspend fun search(query: String, lang: String): List<ContentItem> {
        val resp = retryOn401 { api.search(mapOf("q" to query, "limit" to "24", "lang" to lang)) }
        if (!resp.isSuccessful) return emptyList()
        return resp.body()?.results ?: emptyList()
    }

    /** Espelha app/main/mylist/page.tsx — profileId em camelCase, não profile_id. */
    suspend fun myList(activeProfileId: String): List<MyListEntry> {
        val resp = retryOn401 { api.myList(mapOf("profileId" to activeProfileId, "limit" to "100")) }
        if (!resp.isSuccessful) return emptyList()
        return resp.body()?.items ?: emptyList()
    }

    suspend fun addToMyList(profileId: String, contentId: String): Boolean =
        retryOn401 { api.addToMyList(MyListMutationBody(profileId, contentId)) }.isSuccessful

    suspend fun removeFromMyList(profileId: String, contentId: String): Boolean =
        retryOn401 { api.removeFromMyList(MyListMutationBody(profileId, contentId)) }.isSuccessful
}
