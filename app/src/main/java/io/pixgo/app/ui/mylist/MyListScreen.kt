package io.pixgo.app.ui.mylist

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bookmark
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material3.Icon
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import io.pixgo.app.data.catalog.CatalogRepository
import io.pixgo.app.data.i18n.LocalTranslator
import io.pixgo.app.data.model.MyListEntry
import io.pixgo.app.ui.common.ContentCardCell
import io.pixgo.app.ui.common.PxButton
import io.pixgo.app.ui.common.PxEmptyState
import io.pixgo.app.ui.common.PxPageHeader
import io.pixgo.app.ui.common.PxPageLoading
import io.pixgo.app.ui.common.cells
import io.pixgo.app.ui.common.pagePadding
import io.pixgo.app.ui.common.pxTap
import io.pixgo.app.ui.common.rememberGridSpec
import io.pixgo.app.ui.theme.Px
import kotlinx.coroutines.launch

/**
 * Réplica de app/main/mylist/page.tsx:
 *  - cabeçalho só com ícone Bookmark + "{n} {myList.saved}" (sem <h1>);
 *  - remoção otimista (tira da grelha já; reverte + avisa se o servidor falhar);
 *  - botão remover 32×32 no canto sup. direito (em ecrã táctil fica sempre visível);
 *  - estado vazio com ícone, textos e "Explorar catálogo".
 */
@Composable
fun MyListScreen(
    catalogRepository: CatalogRepository,
    activeProfileId: String?,
    onOpenContent: (String) -> Unit,
    onBrowseCatalog: () -> Unit
) {
    val t = LocalTranslator.current
    val spec = rememberGridSpec()
    var items by remember { mutableStateOf<List<MyListEntry>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }
    val scope = rememberCoroutineScope()
    val snackbarHostState = remember { SnackbarHostState() }

    LaunchedEffect(activeProfileId) {
        if (activeProfileId == null) { loading = false; return@LaunchedEffect }
        loading = true
        items = runCatching { catalogRepository.myList(activeProfileId) }.getOrDefault(emptyList())
        catalogRepository.bindMyListProfile(activeProfileId)
        catalogRepository.seedMyList(items.map { it.contentId })
        loading = false
    }

    fun remove(entry: MyListEntry) {
        if (activeProfileId == null) return
        val before = items
        items = items.filter { it.contentId != entry.contentId }
        catalogRepository.seedMyList(listOf(entry.contentId), inList = false)
        scope.launch {
            snackbarHostState.currentSnackbarData?.dismiss()
            snackbarHostState.showSnackbar("\uD83D\uDDD1 " + t.t("myList.removed"))
        }
        scope.launch {
            val ok = runCatching { catalogRepository.removeFromMyList(activeProfileId, entry.contentId) }.getOrDefault(false)
            if (!ok) {
                items = before
                catalogRepository.seedMyList(listOf(entry.contentId))
                snackbarHostState.currentSnackbarData?.dismiss()
                snackbarHostState.showSnackbar(t.t("errors.networkError"))
            }
        }
    }

    Box(Modifier.fillMaxSize()) {
        LazyVerticalGrid(
            columns = spec.cells(),
            contentPadding = pagePadding(),
            horizontalArrangement = Arrangement.spacedBy(spec.gap),
            verticalArrangement = Arrangement.spacedBy(spec.gap),
            modifier = Modifier.fillMaxSize()
        ) {
            item(span = { GridItemSpan(maxLineSpan) }) {
                PxPageHeader(
                    subtitle = "${items.size} ${t.t("myList.saved")}",
                    leading = { Icon(Icons.Filled.Bookmark, null, Modifier.size(24.dp), tint = Px.Primary) },
                )
            }
            when {
                loading -> item(span = { GridItemSpan(maxLineSpan) }) { PxPageLoading() }
                items.isEmpty() -> item(span = { GridItemSpan(maxLineSpan) }) {
                    PxEmptyState(
                        icon = Icons.Filled.Bookmark,
                        title = t.t("myList.empty"),
                        action = {
                            PxButton(
                                text = t.t("myList.browse"), onClick = onBrowseCatalog,
                                modifier = Modifier.padding(top = 12.dp),
                            )
                        }
                    )
                }
                else -> items(items, key = { it.contentId }) { entry ->
                    val content = entry.content
                    if (content != null) {
                        Box {
                            ContentCardCell(
                                title = content.title ?: "—",
                                posterUrl = content.poster,
                                year = content.year,
                                type = content.type,
                                onClick = { onOpenContent(content.id) }
                            )
                            // Botão remover — absolute top:8 right:8, 32×32, radius 6
                            Box(
                                Modifier
                                    .align(Alignment.TopEnd)
                                    .padding(8.dp)
                                    .size(32.dp)
                                    .clip(RoundedCornerShape(6.dp))
                                    .background(Color(0xCC000000))
                                    .border(1.dp, Color(0x66E50914), RoundedCornerShape(6.dp))
                                    .pxTap { remove(entry) },
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(Icons.Filled.DeleteOutline, t.t("myList.removed"), Modifier.size(16.dp), tint = Px.Primary)
                            }
                        }
                    }
                }
            }
        }
        SnackbarHost(hostState = snackbarHostState, modifier = Modifier.align(Alignment.BottomCenter).fillMaxWidth())
    }
}
