package io.pixgo.app.ui.explore

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.calculateEndPadding
import androidx.compose.foundation.layout.calculateStartPadding
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.TvOff
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.pixgo.app.data.catalog.CatalogRepository
import io.pixgo.app.data.i18n.LocalTranslator
import io.pixgo.app.data.i18n.contentLangFor
import io.pixgo.app.data.model.ContentItem
import io.pixgo.app.data.model.PaymentPlan
import io.pixgo.app.ui.common.ContentCardCell
import io.pixgo.app.ui.common.PxBtnSize
import io.pixgo.app.ui.common.PxBtnVariant
import io.pixgo.app.ui.common.PxButton
import io.pixgo.app.ui.common.PxContentCardSkeleton
import io.pixgo.app.ui.common.PxContentGridSkeleton
import io.pixgo.app.ui.common.PxEmptyState
import io.pixgo.app.ui.common.PxTrendingSkeleton
import io.pixgo.app.ui.common.cells
import io.pixgo.app.ui.common.gridSpecFor
import io.pixgo.app.ui.common.pagePadding
import io.pixgo.app.ui.common.pxTap
import io.pixgo.app.ui.common.rememberGridSpec
import io.pixgo.app.ui.home.TrendingCarousel
import io.pixgo.app.ui.modals.PlansPromoDialog
import io.pixgo.app.ui.theme.Px
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

private const val LIMIT = 24
private const val nTMin = 3   // mínimo de verticais por linha (useRowCounts)

private val TYPES = listOf("all", "video", "movie", "series", "anime", "documentary", "dorama")
private val KID_TYPES = listOf("anime", "dorama")

private enum class RowKind { M, T }
private data class SeriesRow(val kind: RowKind, val items: List<ContentItem>)

/** buildSeriesRows(): 2 linhas de mini séries (16:9), 1 de verticais, repetido. */
private fun buildSeriesRows(list: List<ContentItem>, nM: Int, nT: Int): List<SeriesRow> {
    val minis = list.filter { it.isChannelSeries }
    val talls = list.filter { !it.isChannelSeries }
    val rows = mutableListOf<SeriesRow>()
    var m = 0
    var t = 0
    while (m < minis.size || t < talls.size) {
        var r = 0
        while (r < 2 && m < minis.size) {
            rows += SeriesRow(RowKind.M, minis.subList(m, minOf(m + nM, minis.size))); m += nM; r++
        }
        if (t < talls.size) { rows += SeriesRow(RowKind.T, talls.subList(t, minOf(t + nT, talls.size))); t += nT }
    }
    return rows
}

private fun todayStr(): String =
    SimpleDateFormat("yyyy-MM-dd", Locale.US).apply { timeZone = TimeZone.getTimeZone("UTC") }.format(Date())

/**
 * Réplica de app/main/catalog/page.tsx:
 *  - chips de tipo (Todos/Vídeos/Filmes/Séries/Anime/Documentários/Animações) e de
 *    ordenação (Recentes/Populares) em dois grupos (`.filter-bar`, empilham <=768px);
 *  - "Todos": carrossel Tendências no topo (skeleton enquanto carrega; não em perfil kid);
 *  - "Todos" e "Séries": scroll infinito; "Séries": linhas fixas 2×mini série + 1×vertical;
 *  - restantes tipos: paginação numerada com reticências;
 *  - botão "+" nos cartões (myListApi.add, silencioso como no original);
 *  - PlansModal (só free, no máx. 1x/dia).
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ExploreScreen(
    catalogRepository: CatalogRepository,
    activeProfileId: String?,
    isKidProfile: Boolean,
    uiLang: String,
    onOpenContent: (String) -> Unit,
    isFreeUser: Boolean = false,
    loadPlans: (suspend () -> List<PaymentPlan>)? = null,
    plansModalLastSeen: (suspend () -> String?)? = null,
    markPlansModalSeen: (suspend (String) -> Unit)? = null,
    onSeePlans: () -> Unit = {},
) {
    val t = LocalTranslator.current
    val scope = rememberCoroutineScope()
    val gridSpec = rememberGridSpec()
    val contentLang = contentLangFor(uiLang)
    val visibleTypes = if (isKidProfile) KID_TYPES else TYPES
    val myList = io.pixgo.app.ui.common.rememberMyList(catalogRepository, activeProfileId)

    var type by remember(isKidProfile) { mutableStateOf(if (isKidProfile) "anime" else "all") }
    var sort by remember { mutableStateOf("recent") }
    var items by remember { mutableStateOf<List<ContentItem>>(emptyList()) }
    var page by remember { mutableStateOf(1) }
    var pages by remember { mutableStateOf(1) }
    var loading by remember { mutableStateOf(true) }
    var hasMore by remember { mutableStateOf(true) }
    var loadingMore by remember { mutableStateOf(false) }
    var trending by remember { mutableStateOf<List<ContentItem>>(emptyList()) }
    var trendingLoading by remember { mutableStateOf(false) }
    val gridState = rememberLazyGridState()

    val infinite = type == "all" || type == "series"

    // ── PlansModal: só free, 1x/dia; só marca "visto" quando abre COM dados ──
    var promoPlans by remember { mutableStateOf<List<PaymentPlan>?>(null) }
    LaunchedEffect(isFreeUser) {
        if (!isFreeUser || loadPlans == null) return@LaunchedEffect
        val last = runCatching { plansModalLastSeen?.invoke() }.getOrNull()
        if (last == todayStr()) return@LaunchedEffect
        val data = runCatching { loadPlans() }.getOrNull()
        if (!data.isNullOrEmpty()) {
            promoPlans = data
            runCatching { markPlansModalSeen?.invoke(todayStr()) }
        }
    }
    promoPlans?.let { plans ->
        PlansPromoDialog(
            plans = plans,
            onSeePlans = { promoPlans = null; onSeePlans() },
            onDismiss = { promoPlans = null },
        )
    }

    suspend fun fetch(p: Int) = catalogRepository.loadCatalogPage(type, sort, p, activeProfileId, contentLang)

    // load(1) sempre que tipo/ordem/perfil/idioma mudam; load(n) para a paginação numerada.
    suspend fun load(p: Int) {
        loading = true
        val typeAtStart = type
        val res = runCatching { fetch(p) }.getOrNull()
        if (res != null) {
            var all = res.items
            var pg = p
            var pgs = res.pages
            var more = if (res.pages > 0) p < res.pages else res.items.size == LIMIT
            // "Séries": o padrão é 2 linhas de mini séries + 1 de verticais. Se a 1.ª página
            // vier quase só com mini séries, as verticais chegariam depois (e entrariam
            // numa linha ACIMA do que se vê, só aparecendo ao voltar ao topo). Por isso,
            // enquanto estiver no skeleton, pede mais páginas (máx. 4) até haver verticais
            // suficientes para as primeiras linhas.
            if (typeAtStart == "series" && p == 1) {
                var extra = 0
                while (more && extra < 4 && all.count { !it.isChannelSeries } < nTMin * 2) {
                    val nx = runCatching { fetch(pg + 1) }.getOrNull() ?: break
                    val seen = all.map { it.id }.toHashSet()
                    all = all + nx.items.filter { it.id !in seen }
                    pg += 1; pgs = nx.pages; extra++
                    more = if (nx.pages > 0) pg < nx.pages else nx.items.size == LIMIT
                }
            }
            items = all
            pages = pgs
            page = pg
            hasMore = more
        } else {
            items = emptyList()
        }
        loading = false
        runCatching { gridState.scrollToItem(0) }
    }

    LaunchedEffect(type, sort, activeProfileId, uiLang) { load(1) }

    LaunchedEffect(type, sort, isKidProfile, activeProfileId, uiLang) {
        if (type != "all" || isKidProfile) { trending = emptyList(); trendingLoading = false; return@LaunchedEffect }
        trendingLoading = true
        trending = runCatching { catalogRepository.loadTrending(activeProfileId, contentLang, sort) }.getOrDefault(emptyList())
        trendingLoading = false
    }

    fun loadMore() {
        if (loadingMore || !hasMore || !infinite) return
        loadingMore = true
        val typeAtStart = type
        val sortAtStart = sort
        scope.launch {
            val next = page + 1
            val res = runCatching { fetch(next) }.getOrNull()
            // descarta respostas de um filtro que entretanto mudou
            if (typeAtStart != type || sortAtStart != sort) { loadingMore = false; return@launch }
            if (res != null) {
                val seen = items.map { it.id }.toHashSet()
                items = items + res.items.filter { it.id !in seen }
                page = next
                pages = res.pages
                hasMore = if (res.pages > 0) next < res.pages else res.items.size == LIMIT
            }
            loadingMore = false
        }
    }

    BoxWithConstraints(Modifier.fillMaxSize()) {
        val pad = pagePadding()
        val layoutDir = androidx.compose.ui.platform.LocalLayoutDirection.current
        val innerW: Dp = maxWidth - pad.calculateStartPadding(layoutDir) - pad.calculateEndPadding(layoutDir)
        val narrow = LocalConfiguration.current.screenWidthDp <= 768
        // useRowCounts(): gap < 600 → 7 senão 10; nM = clamp(⌊(w+gap)/(180+gap)⌋, 2..6); nT = clamp(⌊(w+gap)/(140+gap)⌋, 3..9)
        val rowGap = if (innerW.value < 600f) 7.dp else 10.dp
        val nM = ((innerW.value + rowGap.value) / (180f + rowGap.value)).toInt().coerceIn(2, 6)
        val nT = ((innerW.value + rowGap.value) / (140f + rowGap.value)).toInt().coerceIn(3, 9)

        LazyVerticalGrid(
            state = gridState,
            columns = gridSpec.cells(),
            contentPadding = pad,
            horizontalArrangement = Arrangement.spacedBy(gridSpec.gap),
            verticalArrangement = Arrangement.spacedBy(gridSpec.gap),
            modifier = Modifier.fillMaxSize()
        ) {
            // .page-header vazio (margin-bottom:22) + .filter-bar (margin-bottom:18)
            item(span = { GridItemSpan(maxLineSpan) }, key = "filters") {
                Column(Modifier.padding(top = 22.dp, bottom = 6.dp)) {
                    if (narrow) {
                        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                            ChipGroup {
                                visibleTypes.forEach { tp ->
                                    FilterChip(t.t("catalog.${if (tp == "all") "allTypes" else tp}"), type == tp) { type = tp }
                                }
                            }
                            Column(
                                Modifier.fillMaxWidth().drawBehind {
                                    drawLine(Px.Border, Offset(0f, 0.5.dp.toPx()), Offset(size.width, 0.5.dp.toPx()), 1.dp.toPx())
                                }.padding(top = 10.dp)
                            ) { SortChips(sort, { sort = it }) }
                        }
                    } else {
                        // justify-content: space-between; gap 12px 18px
                        FlowRow(
                            Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalArrangement = Arrangement.spacedBy(12.dp),
                        ) {
                            Box(Modifier.padding(end = 18.dp)) {
                                ChipGroup {
                                    visibleTypes.forEach { tp ->
                                        FilterChip(t.t("catalog.${if (tp == "all") "allTypes" else tp}"), type == tp) { type = tp }
                                    }
                                }
                            }
                            SortChips(sort, { sort = it })
                        }
                    }
                }
            }

            // "Todos": carrossel fixo no topo (skeleton enquanto carrega)
            if (type == "all" && !isKidProfile) {
                if (trendingLoading) {
                    item(span = { GridItemSpan(maxLineSpan) }, key = "trendingSkeleton") { PxTrendingSkeleton() }
                } else if (trending.isNotEmpty()) {
                    item(span = { GridItemSpan(maxLineSpan) }, key = "trending") {
                        TrendingCarousel(
                            items = trending, title = t.t("home.trending"), onOpenContent = onOpenContent, myList = myList,
                            modifier = Modifier.padding(bottom = (34.dp - gridSpec.gap).coerceAtLeast(0.dp))
                        )
                    }
                }
            }

            when {
                loading -> item(span = { GridItemSpan(maxLineSpan) }, key = "loading") {
                    if (type == "series") SeriesRowsSkeleton(nM, nT, rowGap)
                    else PxContentGridSkeleton(count = 18)
                }
                items.isEmpty() -> item(span = { GridItemSpan(maxLineSpan) }, key = "empty") {
                    PxEmptyState(
                        icon = Icons.Filled.TvOff,
                        title = t.t("catalog.noContent"),
                        description = t.t("catalog.noContentDesc"),
                    )
                }
                type == "series" -> {
                    val rows = buildSeriesRows(items, nM, nT)
                    items(rows.size, key = { "row$it" }, span = { GridItemSpan(maxLineSpan) }) { ri ->
                        val row = rows[ri]
                        val n = if (row.kind == RowKind.M) nM else nT
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(rowGap)) {
                            for (c in 0 until n) {
                                Box(Modifier.weight(1f)) {
                                    row.items.getOrNull(c)?.let { item ->
                                        CatalogCard(item, wide = row.kind == RowKind.M, onOpenContent, myList)
                                    }
                                }
                            }
                        }
                    }
                }
                else -> {
                    val shown = if (type == "all") items.filter { !it.isChannelSeries } else items
                    items(shown, key = { it.id }) { item ->
                        CatalogCard(item, wide = false, onOpenContent, myList)
                    }
                }
            }

            // sentinela do scroll infinito + skeleton de "carregar mais"
            if (!loading && infinite) {
                item(span = { GridItemSpan(maxLineSpan) }, key = "sentinel") {
                    LaunchedEffect(items.size, hasMore, page) { if (hasMore) loadMore() }
                    if (loadingMore) {
                        if (type == "series") {
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(rowGap)) {
                                repeat(nM) { Box(Modifier.weight(1f)) { PxContentCardSkeleton(wide = true) } }
                            }
                        } else PxContentGridSkeleton(count = maxOf(6, nT))
                    } else Box(Modifier.heightIn(min = 1.dp))
                }
            }

            // paginação numerada (tipos não-infinitos)
            if (!loading && !infinite && pages > 1) {
                item(span = { GridItemSpan(maxLineSpan) }, key = "pager") {
                    Pager(page, pages, loading) { p -> scope.launch { load(p) } }
                }
            }
        }
    }
}

@Composable
private fun CatalogCard(
    item: ContentItem,
    wide: Boolean,
    onOpenContent: (String) -> Unit,
    myList: io.pixgo.app.ui.common.MyListUi,
) {
    ContentCardCell(
        title = item.displayTitle,
        posterUrl = item.displayPoster,
        year = item.year,
        type = item.type,
        rating = item.displayRating,
        wide = wide,
        modifier = Modifier.fillMaxWidth(),
        onClick = { onOpenContent(item.id) },
        // Botão "+" / "✓": igual em TODOS os cards (ver MyListUi).
        inList = myList.isIn(item.id),
        onAddToList = { myList.toggle(item.id) },
    )
}

/** `.filter-bar-group`: flex-wrap, gap 7. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ChipGroup(content: @Composable () -> Unit) {
    FlowRow(
        horizontalArrangement = Arrangement.spacedBy(7.dp),
        verticalArrangement = Arrangement.spacedBy(7.dp),
    ) { content() }
}

@Composable
private fun SortChips(sort: String, onSort: (String) -> Unit) {
    val t = LocalTranslator.current
    ChipGroup {
        FilterChip(t.t("catalog.sortRecent"), sort == "recent") { onSort("recent") }
        FilterChip(t.t("catalog.sortPopular"), sort == "popular") { onSort("popular") }
    }
}

/** `.filter-chip` (+ `.active`): pill, 0.81rem/600, min-height 36; >=1920px 42 / 0.9rem. */
@Composable
private fun FilterChip(label: String, active: Boolean, minWidth: Dp = 0.dp, onClick: () -> Unit) {
    val xl = LocalConfiguration.current.screenWidthDp >= 1920
    val shape = RoundedCornerShape(99.dp)
    Box(
        Modifier
            .heightIn(min = if (xl) 42.dp else 36.dp)
            .widthIn(min = minWidth)
            .clip(shape)
            .background(if (active) Color(0x24E50914) else Color(0x0AFFFFFF))
            .border(1.dp, if (active) Px.Primary else Px.Border, shape)
            .pxTap(onClick = onClick)
            .padding(horizontal = if (xl) 18.dp else 14.dp, vertical = if (xl) 9.dp else 7.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            label,
            color = if (active) Color.White else Px.TextMuted,
            fontSize = if (xl) 14.4.sp else 12.96.sp,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1,
            textAlign = TextAlign.Center,
        )
    }
}

/** Paginação do original: Anterior · 1 … n-2..n+2 … último · Seguinte, com reticências. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun Pager(page: Int, pages: Int, busy: Boolean, go: (Int) -> Unit) {
    val t = LocalTranslator.current
    val nums = (1..pages).filter { it == 1 || it == pages || kotlin.math.abs(it - page) <= 2 }
    val entries = mutableListOf<Int?>()          // null = reticências
    nums.forEachIndexed { idx, n ->
        if (idx > 0 && n - nums[idx - 1] > 1) entries.add(null)
        entries.add(n)
    }
    FlowRow(
        Modifier.fillMaxWidth().padding(top = 26.dp - 12.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp, Alignment.CenterHorizontally),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        PxButton(t.t("common.previous"), onClick = { go(page - 1) }, variant = PxBtnVariant.Secondary, size = PxBtnSize.Sm, enabled = page > 1 && !busy)
        entries.forEach { n ->
            if (n == null) Text("…", color = Px.TextMuted, modifier = Modifier.padding(horizontal = 4.dp))
            else FilterChip(n.toString(), n == page, minWidth = 34.dp) { if (!busy) go(n) }
        }
        PxButton(t.t("common.next"), onClick = { go(page + 1) }, variant = PxBtnVariant.Secondary, size = PxBtnSize.Sm, enabled = page < pages && !busy)
    }
}

/** SeriesRowsSkeleton(blocks=2): por bloco 2×mini + 1×vertical. */
@Composable
private fun SeriesRowsSkeleton(nM: Int, nT: Int, gap: Dp) {
    Column(verticalArrangement = Arrangement.spacedBy(gap)) {
        repeat(2) {
            listOf(RowKind.M, RowKind.M, RowKind.T).forEach { k ->
                val n = if (k == RowKind.M) nM else nT
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(gap)) {
                    repeat(n) { Box(Modifier.weight(1f)) { PxContentCardSkeleton(wide = k == RowKind.M) } }
                }
            }
        }
    }
}
