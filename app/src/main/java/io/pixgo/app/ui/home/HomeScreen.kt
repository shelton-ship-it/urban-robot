package io.pixgo.app.ui.home

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items as lazyRowItems
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import io.pixgo.app.data.catalog.CatalogRepository
import io.pixgo.app.data.model.ContentItem
import io.pixgo.app.data.model.ContinueItem
import io.pixgo.app.ui.common.ContentCardCell
import kotlinx.coroutines.launch

/**
 * Réplica do comportamento de app/main/page.tsx:
 *  - "Continuar assistindo" (linha horizontal) se houver itens
 *  - grelha única "feed" (recent+mix de tipos), scroll infinito por
 *    páginas de 24 (ITEMS_LIMIT), sem separação por tipo (é assim no
 *    original — feed=1)
 * Sem equivalente aqui: skeleton loaders exactos e o tecto MAX_MOUNTED/
 * TRIM_TO do array em JS — LazyVerticalGrid já não mantém tudo montado
 * fora do viewport, por isso esse tecto específico não é necessário.
 */
@Composable
fun HomeScreen(
    catalogRepository: CatalogRepository,
    activeProfileId: String?,
    uiLang: String,
    onOpenContent: (String) -> Unit
) {
    var items by remember { mutableStateOf<List<ContentItem>>(emptyList()) }
    var continueW by remember { mutableStateOf<List<ContinueItem>>(emptyList()) }
    var page by remember { mutableStateOf(1) }
    var hasMore by remember { mutableStateOf(true) }
    var loading by remember { mutableStateOf(true) }
    var loadingMore by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    LaunchedEffect(activeProfileId, uiLang) {
        loading = true
        page = 1
        hasMore = true
        val contentLang = io.pixgo.app.data.i18n.contentLangFor(uiLang)
        val first = catalogRepository.loadHomePage(1, activeProfileId, contentLang)
        items = first
        hasMore = first.size == CatalogRepository.ITEMS_LIMIT
        loading = false
        continueW = catalogRepository.continueWatching()
    }

    fun loadMore() {
        if (loadingMore || !hasMore) return
        loadingMore = true
        scope.launch {
            val next = page + 1
            val contentLang = io.pixgo.app.data.i18n.contentLangFor(uiLang)
            val more = catalogRepository.loadHomePage(next, activeProfileId, contentLang)
            val seen = items.map { it.id }.toHashSet()
            items = items + more.filter { it.id !in seen }
            hasMore = more.size == CatalogRepository.ITEMS_LIMIT
            page = next
            loadingMore = false
        }
    }

    if (loading) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            CircularProgressIndicator()
        }
        return
    }

    if (items.isEmpty()) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text("Sem conteúdo por agora.")
        }
        return
    }

    LazyVerticalGrid(
        columns = GridCells.Fixed(2),
        contentPadding = PaddingValues(12.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
        modifier = Modifier.fillMaxSize()
    ) {
        if (continueW.isNotEmpty()) {
            item(span = { GridItemSpan(maxLineSpan) }) {
                ContinueWatchingRow(continueW, onOpenContent)
            }
        }

        items(items, key = { it.id }) { item ->
            ContentCardCell(item.displayPoster, item.displayTitle, onClick = { onOpenContent(item.id) })
        }

        item(span = { GridItemSpan(maxLineSpan) }) {
            LaunchedEffect(items.size, hasMore) { if (hasMore) loadMore() }
            if (loadingMore) {
                Box(Modifier.fillMaxWidth().padding(16.dp), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(modifier = Modifier.size(20.dp))
                }
            }
        }
    }
}

@Composable
private fun ContinueWatchingRow(items: List<ContinueItem>, onOpenContent: (String) -> Unit) {
    Column {
        Text(
            "Continuar assistindo",
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.padding(bottom = 8.dp)
        )
        LazyRow(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            lazyRowItems(items, key = { it.contentId }) { cw ->
                Column(Modifier.width(130.dp)) {
                    AsyncImage(
                        model = cw.content?.poster,
                        contentDescription = cw.content?.title,
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(80.dp)
                            .clip(RoundedCornerShape(8.dp))
                            .clickable { onOpenContent(cw.contentId) },
                        contentScale = ContentScale.Crop
                    )
                    LinearProgressIndicator(
                        progress = { ((cw.progress ?: 0.0) / 100.0).toFloat().coerceIn(0f, 1f) },
                        modifier = Modifier.fillMaxWidth().padding(top = 4.dp)
                    )
                }
            }
        }
    }
}


