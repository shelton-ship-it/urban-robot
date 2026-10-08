package io.pixgo.app.data.catalog

import android.content.Context
import io.pixgo.app.data.auth.AuthRepository
import io.pixgo.app.data.auth.TokenManager
import io.pixgo.app.data.model.ContentItem
import io.pixgo.app.data.model.ContentDetail
import io.pixgo.app.data.model.ContinueItem
import io.pixgo.app.data.model.MyListContent
import io.pixgo.app.data.model.MyListEntry
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import io.pixgo.app.data.network.MyListMutationBody
import io.pixgo.app.data.model.ProgressUpdateBody
import io.pixgo.app.data.model.ViewRegisterResponse
import io.pixgo.app.data.network.LoadFailedException
import io.pixgo.app.data.network.NetworkModule
import io.pixgo.app.data.network.Patience
import io.pixgo.app.data.network.patiently
import io.pixgo.app.data.network.runCatchingNonCancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import retrofit2.Response
import java.util.concurrent.ConcurrentHashMap

/**
 * Espelha o loadPage()/progressApi.continue de app/main/page.tsx e o
 * catalogApi/contentApi/myListApi de lib/api.ts do frontend_web.
 *
 * OTIMIZAÇÃO DAS BUSCAS AO pixel_service_v1 (referência: frontend_web):
 *  1. URLs IDÊNTICAS às do web — `lang` sempre primeiro e depois os
 *     parâmetros na MESMA ordem de `new URLSearchParams({ lang, ...p })`.
 *     As rotas /api/catalog* têm cache de borda de 24h POR URL
 *     (s-maxage=86400, ver routes/catalog.js + edgeone.json): ordem ou
 *     parâmetros diferentes = outra chave = cache miss = ida ao Turso.
 *  2. `profile_id` só é enviado quando o perfil activo é infantil (ou ainda
 *     não se sabe se é). O backend só usa esse parâmetro em isKidProfile();
 *     para perfis normais a resposta é igual, mas enviá-lo fragmentava o
 *     cache de borda por perfil e custava um getProfile() no Turso por
 *     pedido.
 *  3. contentApi.get com cache curto de 3 min (contentCache.ts do web);
 *     fileiras repetidas (Tendências, Recentes) com cache de 60 s, o mesmo
 *     max-age que o backend devolve. O OkHttp também respeita o
 *     Cache-Control (ver NetworkModule).
 *  4. "Recomendados" = o mesmo pedido da aba Recentes (GET /catalog?
 *     sort=recent, página 1), partilhando cache com ela.
 */
class CatalogRepository(private val context: Context, private val auth: AuthRepository) {

    private val tokenManager = TokenManager(context)
    private val api by lazy { NetworkModule.catalog(context, tokenManager) }

    companion object {
        const val ITEMS_LIMIT = 24
        const val TRENDING_LIMIT = 60
        private const val CONTENT_TTL_MS = 3 * 60 * 1000L   // contentCache.ts: 3 min
        private const val LIST_TTL_MS = 60 * 1000L          // Cache-Control: max-age=60
        const val RECOMMENDED_LIMIT = 12
    }

    private class Cached<T>(val at: Long, val value: T)

    private val contentCache = ConcurrentHashMap<String, Cached<ContentDetail>>()
    private val listCache = ConcurrentHashMap<String, Cached<List<ContentItem>>>()

    private suspend fun <T> retryOn401(call: suspend () -> Response<T>): Response<T> {
        val resp = call()
        if (resp.code() == 401) {
            if (auth.refreshAccessToken() != null) return call()
        }
        return resp
    }

    /**
     * Pedido PACIENTE: espera/repete em falhas transitórias (rede, 408/429/5xx) até
     * [budgetMs] e só então lança [LoadFailedException]. Um 4xx definitivo é devolvido.
     * A renovação do token (401) acontece dentro de cada tentativa.
     */
    private suspend fun <T> request(budgetMs: Long = Patience.MAIN_BUDGET_MS, call: suspend () -> Response<T>): Response<T> =
        patiently(budgetMs) { retryOn401(call) }

    /** Sucesso ou lança: nunca devolve "lista vazia" para esconder uma falha. */
    private fun <T> Response<T>.bodyOrThrow(): T {
        if (!isSuccessful) throw LoadFailedException("HTTP ${code()}")
        return body() ?: throw LoadFailedException("Resposta vazia (HTTP ${code()})")
    }

    /** `new URLSearchParams({ lang, ...p })`: lang primeiro, restantes pela ordem dada, nulos omitidos. */
    private fun query(lang: String, vararg pairs: Pair<String, String?>): Map<String, String> {
        val m = LinkedHashMap<String, String>()
        m["lang"] = lang
        pairs.forEach { (k, v) -> if (v != null) m[k] = v }
        return m
    }

    /**
     * profile_id só interessa ao backend para perfis infantis (isKidProfile).
     * Perfil conhecido e normal → null (URL partilhada com todos). Perfil
     * ainda desconhecido → enviado, para nunca falhar em modo "seguro".
     */
    private fun profileParam(activeProfileId: String?): String? {
        if (activeProfileId == null) return null
        val p = auth.state.value.profiles.firstOrNull { it.id == activeProfileId }
        return if (p != null && p.isKid != true) null else activeProfileId
    }

    // ── Minha Lista: estado partilhado por todos os cards ───────────────────
    private val _myListIds = MutableStateFlow<Set<String>>(emptySet())
    val myListIds: StateFlow<Set<String>> = _myListIds.asStateFlow()
    private var myListProfileId: String? = null

    /** Ao trocar de perfil, o conjunto de ids conhecidos recomeça vazio. */
    fun bindMyListProfile(profileId: String?) {
        if (myListProfileId != profileId) {
            myListProfileId = profileId
            _myListIds.value = emptySet()
        }
    }

    /** Semeia ids que já se sabe estarem na lista (ex.: ecrã Minha Coleção, check da Watch). */
    fun seedMyList(ids: Collection<String>, inList: Boolean = true) {
        _myListIds.update { cur -> if (inList) cur + ids else cur - ids.toSet() }
    }

    /**
     * Alterna o estado na lista com actualização optimista (o ícone muda já)
     * e reversão se o servidor falhar. O "add" do backend é INSERT OR IGNORE,
     * portanto tocar num card cujo estado ainda é desconhecido adiciona — e o
     * resultado (item na lista) fica sempre correcto.
     * Devolve o novo estado, ou null se o pedido falhou.
     */
    suspend fun toggleMyList(profileId: String, contentId: String): Boolean? {
        bindMyListProfile(profileId)
        val wasIn = contentId in _myListIds.value
        seedMyList(listOf(contentId), inList = !wasIn)
        val ok = runCatching {
            if (wasIn) removeFromMyList(profileId, contentId) else addToMyList(profileId, contentId)
        }.getOrDefault(false)
        if (!ok) {
            seedMyList(listOf(contentId), inList = wasIn)
            return null
        }
        return !wasIn
    }

    suspend fun loadHomePage(page: Int, activeProfileId: String?, lang: String): List<ContentItem> {
        val params = query(
            lang,
            "limit" to ITEMS_LIMIT.toString(),
            "page" to page.toString(),
            "sort" to "recent",
            "feed" to "1",
            "profile_id" to profileParam(activeProfileId),
        )
        val resp = request { api.list(params) }
        return resp.bodyOrThrow().items ?: emptyList()
    }

    /**
     * Carrossel "Tendências" de app/main/page.tsx: mesma rota do catálogo
     * (type=series, sort=recent, limit=60), filtrada no cliente pelo marcador
     * de mini série (isChannelSeries). Falha silenciosa → lista vazia.
     * Cache de 60 s: Home e Explorar pedem exactamente o mesmo.
     */
    suspend fun loadTrending(activeProfileId: String?, lang: String, sort: String = "recent"): List<ContentItem> {
        val pid = profileParam(activeProfileId)
        val key = "trend|$lang|$sort|${pid ?: "-"}"
        listCache[key]?.let { if (System.currentTimeMillis() - it.at < LIST_TTL_MS) return it.value }
        val params = query(
            lang,
            "limit" to TRENDING_LIMIT.toString(),
            "page" to "1",
            "sort" to sort,
            "type" to "series",
            "profile_id" to pid,
        )
        val resp = request(Patience.OPTIONAL_BUDGET_MS) { api.list(params) }
        val list = (resp.bodyOrThrow().items ?: emptyList()).filter { it.isChannelSeries }
        listCache[key] = Cached(System.currentTimeMillis(), list)
        return list
    }

    suspend fun continueWatching(): List<ContinueItem> {
        val resp = request(Patience.OPTIONAL_BUDGET_MS) { api.continueWatching(mapOf("limit" to "6")) }
        return resp.bodyOrThrow()
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
        // catalog/page.tsx: { limit: 24, page, sort, [type], [profile_id] }
        val params = query(
            lang,
            "limit" to ITEMS_LIMIT.toString(),
            "page" to page.toString(),
            "sort" to sort,
            "type" to (if (type != "all") type else null),
            "profile_id" to profileParam(activeProfileId),
        )
        val body = request { api.list(params) }.bodyOrThrow()
        return CatalogPage(body.items, body.pagination?.pages ?: 1)
    }

    /**
     * Recomendados = conteúdos de /recent (pedido explícito, em toda a
     * plataforma): o MESMO pedido da aba Recentes do Explorar
     * (GET /api/catalog?lang&limit=24&page=1&sort=recent), sem tipo, com o
     * conteúdo actual retirado. Não usa sort=recommended.
     */
    suspend fun recommended(excludeId: String, activeProfileId: String?, lang: String): List<ContentItem> {
        val pid = profileParam(activeProfileId)
        val key = "recent|$lang|${pid ?: "-"}"
        val cached = listCache[key]
        val recent = if (cached != null && System.currentTimeMillis() - cached.at < LIST_TTL_MS) {
            cached.value
        } else {
            val params = query(
                lang,
                "limit" to ITEMS_LIMIT.toString(),
                "page" to "1",
                "sort" to "recent",
                "profile_id" to pid,
            )
            val resp = request(Patience.OPTIONAL_BUDGET_MS) { api.list(params) }
            val list = resp.bodyOrThrow().items ?: emptyList()
            listCache[key] = Cached(System.currentTimeMillis(), list)
            list
        }
        return recent.filter { it.id != excludeId }.take(RECOMMENDED_LIMIT)
    }

    /** Espelha app/main/search/page.tsx — sem sugestões/popular (removidas do original). */
    suspend fun search(query: String, lang: String, limit: Int = 24): List<ContentItem> {
        val resp = request(Patience.SEARCH_BUDGET_MS) { api.search(mapOf("q" to query, "limit" to limit.toString(), "lang" to lang)) }
        return resp.bodyOrThrow().results ?: emptyList()
    }

    /** search/page.tsx mostra `res.pagination.total` ("{total} resultados para"). */
    data class SearchPage(val results: List<ContentItem>, val total: Int)

    /**
     * Como [searchPage], mas devolve null quando o pedido FALHA (HTTP != 2xx / corpo vazio),
     * em vez de fingir "0 resultados": a UI distingue "sem resultados" de "erro de rede".
     */
    suspend fun searchPageOrNull(query: String, lang: String, limit: Int = 24): SearchPage? {
        val body = try {
            request(Patience.SEARCH_BUDGET_MS) { api.search(mapOf("q" to query, "limit" to limit.toString(), "lang" to lang)) }
                .bodyOrThrow()
        } catch (e: LoadFailedException) { return null }
        return SearchPage(body.results, body.pagination?.total ?: 0)
    }

    suspend fun searchPage(query: String, lang: String, limit: Int = 24): SearchPage {
        val body = request(Patience.SEARCH_BUDGET_MS) { api.search(mapOf("q" to query, "limit" to limit.toString(), "lang" to lang)) }
            .bodyOrThrow()
        return SearchPage(body.results, body.pagination?.total ?: 0)
    }

    /**
     * Espelha app/main/mylist/page.tsx — profileId em camelCase, não profile_id.
     *
     * BUG CORRIGIDO ("minha coleção envia lang errado, metadados não aparecem"):
     * GET /api/mylist não recebia `lang` e o backend usa `req.language`
     * (detectado por IP/cookie, tipicamente "en"). Como todo o conteúdo é gravado
     * em PT, não havia tradução EN nem fallback → título/poster vinham nulos.
     *  1. Agora envia sempre `lang=pt` (e o patch opcional de routes/mylist.js passa
     *     a respeitá-lo);
     *  2. COMPATÍVEL com o backend atual (sem deploy): qualquer entrada que ainda
     *     venha sem título é completada por GET /api/content/:id?lang=pt (cache de
     *     3 min partilhado com a Watch), em paralelo.
     */
    suspend fun myList(activeProfileId: String): List<MyListEntry> {
        val resp = request {
            api.myList(mapOf("profileId" to activeProfileId, "limit" to "100", "lang" to io.pixgo.app.data.i18n.CONTENT_LANG))
        }
        val entries = resp.bodyOrThrow().items ?: emptyList()
        return kotlinx.coroutines.coroutineScope {
            entries.map { e ->
                async {
                    val c = e.content
                    if (c != null && !c.title.isNullOrBlank() && !c.poster.isNullOrBlank()) return@async e
                    val d = runCatchingNonCancel { content(e.contentId, io.pixgo.app.data.i18n.CONTENT_LANG, null) }.getOrNull()
                        ?: return@async e
                    e.copy(
                        content = MyListContent(
                            id = e.contentId,
                            title = c?.title?.takeIf { it.isNotBlank() } ?: d.displayTitle,
                            type = c?.type ?: d.type,
                            poster = c?.poster?.takeIf { it.isNotBlank() } ?: d.displayPoster,
                            year = c?.year ?: d.year,
                        )
                    )
                }
            }.awaitAll()
        }
    }

    suspend fun addToMyList(profileId: String, contentId: String): Boolean {
        invalidateContent(contentId)
        return retryOn401 { api.addToMyList(MyListMutationBody(profileId, contentId)) }.isSuccessful
    }

    suspend fun removeFromMyList(profileId: String, contentId: String): Boolean {
        invalidateContent(contentId)
        return retryOn401 { api.removeFromMyList(MyListMutationBody(profileId, contentId)) }.isSuccessful
    }

    /**
     * Espelha contentApi.get de lib/api.ts:
     * GET /api/content/:id?lang=(profile_id opcional). O backend embute
     * `in_list` quando profile_id vem junto (routes/content.js) — o ecrã
     * usa-o antes de cair no check separado, tal como watch/[id]/page.tsx.
     */
    suspend fun content(id: String, lang: String, activeProfileId: String?): ContentDetail? {
        val key = "$id|$lang|${activeProfileId ?: "none"}"
        contentCache[key]?.let { if (System.currentTimeMillis() - it.at < CONTENT_TTL_MS) return it.value }
        // contentApi.get: { lang, [profile_id] } — aqui o profile_id é sempre
        // necessário (traz in_list embutido), ao contrário do catálogo.
        val params = query(lang, "profile_id" to activeProfileId)
        val resp = request { api.content(id, params) }
        // 404 definitivo = conteúdo inexistente (único caso de "não encontrado" legítimo);
        // qualquer outra falha lança LoadFailedException e a UI mostra "Tentar novamente".
        if (resp.code() == 404) return null
        val body = resp.bodyOrThrow()
        contentCache[key] = Cached(System.currentTimeMillis(), body)
        return body
    }

    /** invalidateContentCache(): depois de mexer na lista, a próxima abertura já vem certa. */
    private fun invalidateContent(contentId: String) {
        val prefix = "$contentId|"
        contentCache.keys.filter { it.startsWith(prefix) }.forEach { contentCache.remove(it) }
    }

    /**
     * contentApi.registerView (lib/api.ts): POST /api/content/:id/view e
     * invalida o cache curto do conteúdo para a próxima abertura já trazer o
     * total actualizado. null = falhou (o ecrã tenta de novo, como o .catch do original).
     */
    suspend fun registerView(contentId: String): ViewRegisterResponse? {
        return try {
            val resp = retryOn401 { api.registerView(contentId) }
            if (!resp.isSuccessful) return null
            invalidateContent(contentId)
            resp.body() ?: ViewRegisterResponse()
        } catch (_: Exception) { null }
    }

    /** myListApi.check(contentId, profileId) — fallback quando in_list não vem embutido. */
    suspend fun checkMyList(contentId: String, activeProfileId: String?): Boolean {
        val params = activeProfileId?.let { mapOf("profileId" to it) } ?: emptyMap()
        val resp = retryOn401 { api.checkMyList(contentId, params) }
        return resp.body()?.inList ?: false
    }

    /**
     * progressApi.update do heartbeat de progresso de watch/[id]/page.tsx
     * (POST /api/progress/update, body camelCase; falha silenciosa como o
     * .catch(() => {}) original).
     */
    suspend fun updateProgress(
        profileId: String,
        contentId: String,
        episodeId: String?,
        progress: Int,
        durationSeconds: Int
    ) {
        try {
            retryOn401 {
                api.updateProgress(ProgressUpdateBody(profileId, contentId, episodeId, "en", progress, durationSeconds))
            }
        } catch (_: Exception) { /* silencioso — igual ao original */ }
    }
}
