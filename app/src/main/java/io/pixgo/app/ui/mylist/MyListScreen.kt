package io.pixgo.app.ui.mylist

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
import io.pixgo.app.data.model.MyListEntry
import io.pixgo.app.ui.common.ContentCardCell
import kotlinx.coroutines.launch

/**
 * Réplica de app/main/mylist/page.tsx: remove optimisticamente da UI e só
 * reverte + avisa se o pedido ao servidor falhar — não espera o
 * round-trip antes de tirar o item da grelha.
 */
@Composable
fun MyListScreen(
    catalogRepository: CatalogRepository,
    activeProfileId: String?,
    onOpenContent: (String) -> Unit,
    onBrowseCatalog: () -> Unit
) {
    var items by remember { mutableStateOf<List<MyListEntry>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }
    val scope = rememberCoroutineScope()
    val snackbarHostState = remember { SnackbarHostState() }

    LaunchedEffect(activeProfileId) {
        if (activeProfileId == null) { loading = false; return@LaunchedEffect }
        loading = true
        items = catalogRepository.myList(activeProfileId)
        loading = false
    }

    fun remove(entry: MyListEntry) {
        if (activeProfileId == null) return
        val before = items
        items = items.filter { it.contentId != entry.contentId }
        scope.launch {
            val ok = catalogRepository.removeFromMyList(activeProfileId, entry.contentId)
            if (!ok) {
                items = before
                snackbarHostState.showSnackbar("Erro de rede.")
            }
        }
    }

    Box(Modifier.fillMaxSize()) {
        when {
            loading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
            items.isEmpty() -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("Sua lista está vazia")
                    Text(
                        "Adicione filmes e séries para assistir mais tarde.",
                        style = MaterialTheme.typography.bodySmall
                    )
                    Spacer(Modifier.height(12.dp))
                    Button(onClick = onBrowseCatalog) { Text("Explorar catálogo") }
                }
            }
            else -> LazyVerticalGrid(
                columns = GridCells.Fixed(2),
                contentPadding = PaddingValues(12.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
                modifier = Modifier.fillMaxSize()
            ) {
                items(items, key = { it.contentId }) { entry ->
                    val content = entry.content
                    if (content != null) {
                        Column {
                            ContentCardCell(
                                posterUrl = content.poster,
                                title = content.title ?: "—",
                                onClick = { onOpenContent(content.id) }
                            )
                            TextButton(onClick = { remove(entry) }) { Text("Remover") }
                        }
                    }
                }
            }
        }
        SnackbarHost(hostState = snackbarHostState, modifier = Modifier.align(Alignment.BottomCenter))
    }
}
