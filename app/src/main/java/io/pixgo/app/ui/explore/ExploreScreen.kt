package io.pixgo.app.ui.explore

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import io.pixgo.app.data.catalog.CatalogRepository
import io.pixgo.app.data.i18n.contentLangFor
import io.pixgo.app.ui.common.ContentCardCell
import kotlinx.coroutines.launch

/**
 * Réplica de app/main/catalog/page.tsx. Rótulos reais confirmados em
 * i18n/locales/pt.json (catalog.*) — atenção: "dorama" mostra-se
 * "Animações", não "Doramas". Ordem das abas (TYPES em catalog/page.tsx):
 * all, video, movie, series, anime, documentary, dorama.
 */
private data class TypeTab(val value: String, val label: String)

private val ALL_TABS = listOf(
    TypeTab("all", "Todos"),
    TypeTab("video", "Vídeos"),
    TypeTab("movie", "Filmes"),
    TypeTab("series", "Séries"),
    TypeTab("anime", "Anime"),
    TypeTab("documentary", "Documentários"),
    TypeTab("dorama", "Animações"),
)
private val KID_TABS = ALL_TABS.filter { it.value in setOf("anime", "dorama") }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ExploreScreen(
    catalogRepository: CatalogRepository,
    activeProfileId: String?,
    isKidProfile: Boolean,
    uiLang: String,
    onOpenContent: (String) -> Unit
) {
    val tabs = if (isKidProfile) KID_TABS else ALL_TABS
    var type by remember(isKidProfile) { mutableStateOf(if (isKidProfile) "anime" else "all") }
    var sort by remember { mutableStateOf("recent") }
    var page by remember { mutableStateOf(1) }
    var pages by remember { mutableStateOf(1) }
    var items by remember { mutableStateOf<List<io.pixgo.app.data.model.ContentItem>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }

    LaunchedEffect(type, sort, activeProfileId, uiLang) {
        loading = true
        page = 1
        val result = catalogRepository.loadCatalogPage(type, sort, 1, activeProfileId, contentLangFor(uiLang))
        items = result.items
        pages = result.pages
        loading = false
    }

    suspend fun goToPage(p: Int) {
        loading = true
        val result = catalogRepository.loadCatalogPage(type, sort, p, activeProfileId, contentLangFor(uiLang))
        items = result.items
        pages = result.pages
        page = p
        loading = false
    }

    val scope = rememberCoroutineScope()

    Column(Modifier.fillMaxSize()) {
        ScrollableTabRow(selectedTabIndex = tabs.indexOfFirst { it.value == type }.coerceAtLeast(0)) {
            tabs.forEach { tab ->
                Tab(
                    selected = tab.value == type,
                    onClick = { type = tab.value },
                    text = { Text(tab.label) }
                )
            }
        }

        Row(
            Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.End
        ) {
            FilterChip(
                selected = sort == "recent",
                onClick = { sort = "recent" },
                label = { Text("Recentes") }
            )
            Spacer(Modifier.width(8.dp))
            FilterChip(
                selected = sort == "popular",
                onClick = { sort = "popular" },
                label = { Text("Populares") }
            )
        }

        if (loading) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
            return@Column
        }

        if (items.isEmpty()) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("Nenhum conteúdo encontrado")
                    Text("Tente outros filtros.", style = MaterialTheme.typography.bodySmall)
                }
            }
            return@Column
        }

        LazyVerticalGrid(
            columns = GridCells.Fixed(2),
            contentPadding = PaddingValues(12.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
            modifier = Modifier.weight(1f)
        ) {
            items(items, key = { it.id }) { item ->
                ContentCardCell(item.displayPoster, item.displayTitle, onClick = { onOpenContent(item.id) })
            }
        }

        if (pages > 1) {
            Row(
                Modifier.fillMaxWidth().padding(12.dp),
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically
            ) {
                TextButton(enabled = page > 1, onClick = { scope.launch { goToPage(page - 1) } }) { Text("Anterior") }
                Text("$page / $pages", modifier = Modifier.padding(horizontal = 12.dp))
                TextButton(enabled = page < pages, onClick = { scope.launch { goToPage(page + 1) } }) { Text("Seguinte") }
            }
        }
    }
}
