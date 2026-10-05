package io.pixgo.app.ui.home

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items as rowItems
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.TvOff
import androidx.compose.material3.Icon
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
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import io.pixgo.app.data.catalog.CatalogRepository
import io.pixgo.app.data.i18n.LocalTranslator
import io.pixgo.app.data.i18n.contentLangFor
import io.pixgo.app.data.model.ContentItem
import io.pixgo.app.data.model.ContinueItem
import io.pixgo.app.ui.common.ContentCardCell
import io.pixgo.app.ui.common.PxButton
import io.pixgo.app.ui.common.PxContentGridSkeleton
import io.pixgo.app.ui.common.PxEmptyState
import io.pixgo.app.ui.common.PxLoadingRing
import io.pixgo.app.ui.common.PxSectionHeader
import io.pixgo.app.ui.common.PxTrendingSkeleton
import io.pixgo.app.ui.common.cells
import io.pixgo.app.ui.common.cwCardSize
import io.pixgo.app.ui.common.pagePadding
import io.pixgo.app.ui.common.pxTap
import io.pixgo.app.ui.common.rememberGridSpec
import io.pixgo.app.ui.theme.Px
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch

/**
 * Réplica de app/main/page.tsx:
 *  - carrossel "Tendências" (mini séries, cards 16:9) fixo no topo;
 *  - "Continuar assistindo" (.cw-card) quando houver progresso;
 *  - grelha única "feed" (recentes misturados, sem as mini séries), com scroll
 *    infinito por páginas de 24 (feed=1);
 *  - skeletons enquanto carrega e empty-state (ícone TvOff + "Tentar novamente").
 * O tecto MAX_MOUNTED/TRIM_TO do web não é necessário: a LazyVerticalGrid já
 * não mantém tudo montado fora do viewport.
 */
@Composable
fun HomeScreen(
    catalogRepository: CatalogRepository,
    activeProfileId: String?,
    uiLang: String,
    onOpenContent: (String) -> Unit
) {
    val t = LocalTranslator.current
    val spec = rememberGridSpec()

    var feed by remember { mutableStateOf<List<ContentItem>>(emptyList()) }
    var continueW by remember { mutableStateOf<List<ContinueItem>>(emptyList()) }
    var trending by remember { mutableStateOf<List<ContentItem>>(emptyList()) }
    var page by remember { mutableStateOf(1) }
    var hasMore by remember { mutableStateOf(true) }
    var loading by remember { mutableStateOf(true) }
    var loadingMore by remember { mutableStateOf(false) }
    var reloadKey by remember { mutableStateOf(0) }
    val scope = rememberCoroutineScope()
    val myList = io.pixgo.app.ui.common.rememberMyList(catalogRepository, activeProfileId)

    // Carga inicial — reinicia tudo quando o perfil activo / idioma muda.
    LaunchedEffect(activeProfileId, uiLang, reloadKey) {
        loading = true
        page = 1
        hasMore = true
        trending = emptyList()
        continueW = emptyList()
        val contentLang = contentLangFor(uiLang)
        coroutineScope {
            // Carrossel: falha silenciosa — sem ele a home funciona como antes.
            launch {
                trending = runCatching { catalogRepository.loadTrending(activeProfileId, contentLang) }
                    .getOrDefault(emptyList())
            }
            val first = runCatching { catalogRepository.loadHomePage(1, activeProfileId, contentLang) }
                .getOrDefault(emptyList())
            feed = first
            hasMore = first.size == CatalogRepository.ITEMS_LIMIT
            loading = false
            launch {
                continueW = runCatching { catalogRepository.continueWatching() }.getOrDefault(emptyList())
            }
        }
    }

    fun loadMore() {
        if (loadingMore || !hasMore) return
        loadingMore = true
        scope.launch {
            val next = page + 1
            val more = runCatching {
                catalogRepository.loadHomePage(next, activeProfileId, contentLangFor(uiLang))
            }.getOrNull()
            if (more != null) {
                val seen = feed.map { it.id }.toHashSet()
                feed = feed + more.filter { it.id !in seen }
                hasMore = more.size == CatalogRepository.ITEMS_LIMIT
                page = next
            }
            loadingMore = false
        }
    }

    if (loading) {
        // <ContentGridSkeleton count={12} withHeader /> × 2
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(pagePadding())) {
            PxContentGridSkeleton(count = 12, withHeader = true)
            PxContentGridSkeleton(count = 12, withHeader = true)
        }
        return
    }

    // Mini séries saem da grelha misturada (ficam só no carrossel).
    val gridItems = feed.filter { !it.isChannelSeries }

    if (gridItems.isEmpty() && trending.isEmpty()) {
        // .empty-state { minHeight: '60vh' } centrado
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            PxEmptyState(
                icon = Icons.Filled.TvOff,
                title = t.t("home.noContent"),
                description = t.t("home.noContentDesc"),
                action = {
                    PxButton(text = t.t("common.retry"), onClick = { reloadKey++ }, modifier = Modifier.padding(top = 12.dp))
                }
            )
        }
        return
    }

    val sectionGap = (34.dp - spec.gap).coerceAtLeast(0.dp)

    LazyVerticalGrid(
        columns = spec.cells(),
        contentPadding = pagePadding(),
        horizontalArrangement = Arrangement.spacedBy(spec.gap),
        verticalArrangement = Arrangement.spacedBy(spec.gap),
        modifier = Modifier.fillMaxSize()
    ) {
        if (trending.isNotEmpty()) {
            item(span = { GridItemSpan(maxLineSpan) }, key = "trending") {
                TrendingCarousel(
                    items = trending, title = t.t("home.trending"), onOpenContent = onOpenContent, myList = myList,
                    modifier = Modifier.padding(bottom = sectionGap)   // .section { margin-bottom:34px }
                )
            }
        }

        if (continueW.isNotEmpty()) {
            item(span = { GridItemSpan(maxLineSpan) }, key = "continue") {
                ContinueWatchingSection(
                    title = t.t("home.continueWatching"),
                    items = continueW,
                    onOpenContent = onOpenContent,
                    modifier = Modifier.padding(bottom = sectionGap)
                )
            }
        }

        items(gridItems, key = { it.id }) { item ->
            ContentCardCell(
                title = item.displayTitle,
                posterUrl = item.displayPoster,
                year = item.year,
                type = item.type,
                rating = item.displayRating,
                onClick = { onOpenContent(item.id) },
                inList = myList.isIn(item.id),
                onAddToList = { myList.toggle(item.id) },
            )
        }

        // Sentinela do scroll infinito (só é composta quando chega perto do fim).
        item(span = { GridItemSpan(maxLineSpan) }, key = "sentinel") {
            LaunchedEffect(feed.size, hasMore) { if (hasMore) loadMore() }
            if (loadingMore) {
                Box(Modifier.fillMaxWidth().padding(vertical = 22.dp), contentAlignment = Alignment.Center) {
                    PxLoadingRing(size = 20.dp, stroke = 2.dp, durationMs = 700)
                }
            } else {
                Box(Modifier.height(1.dp))
            }
        }
    }
}

/** `.section` + `.section-header` + linha de `.cw-card` (largura fixa, thumb, progress-bar, título). */
@Composable
private fun ContinueWatchingSection(
    title: String,
    items: List<ContinueItem>,
    onOpenContent: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val (cardW, thumbH) = cwCardSize()
    Column(modifier.fillMaxWidth()) {
        PxSectionHeader(title)
        LazyRow(
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            contentPadding = PaddingValues(bottom = 6.dp),
        ) {
            rowItems(items, key = { it.contentId }) { cw ->
                Column(Modifier.width(cardW).pxTap { onOpenContent(cw.contentId) }) {
                    val thumbShape = RoundedCornerShape(8.dp)
                    val poster = cw.content?.poster
                    if (!poster.isNullOrBlank()) {
                        AsyncImage(
                            model = poster,
                            contentDescription = cw.content?.title,
                            modifier = Modifier.fillMaxWidth().height(thumbH).clip(thumbShape),
                            contentScale = ContentScale.Crop
                        )
                    } else {
                        Box(
                            Modifier.fillMaxWidth().height(thumbH).clip(thumbShape).background(Px.CardBg),
                            contentAlignment = Alignment.Center
                        ) { Icon(Icons.Filled.PlayArrow, null, tint = Px.TextMuted) }
                    }
                    // .progress-bar { height:3px; margin-top:5px } / .progress-fill
                    val pct = ((cw.progress ?: 0.0) / 100.0).toFloat().coerceIn(0f, 1f)
                    Box(
                        Modifier.padding(top = 5.dp).fillMaxWidth().height(3.dp)
                            .clip(RoundedCornerShape(99.dp)).background(Color1A)
                    ) {
                        Box(Modifier.fillMaxWidth(pct).height(3.dp).background(Px.Primary))
                    }
                    Text(
                        cw.content?.title ?: "",
                        color = Px.TextMuted, fontSize = 12.48.sp,
                        maxLines = 1, overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(top = 4.dp)
                    )
                }
            }
        }
    }
}

private val Color1A = androidx.compose.ui.graphics.Color(0x1AFFFFFF)
