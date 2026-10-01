package io.pixgo.app.ui.search

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import io.pixgo.app.data.catalog.CatalogRepository
import io.pixgo.app.data.i18n.contentLangFor
import io.pixgo.app.data.model.ContentItem
import io.pixgo.app.ui.common.ContentCardCell
import kotlinx.coroutines.delay

/**
 * Réplica de app/main/search/page.tsx: debounce de 340ms, sem nada
 * sugerido antes de a pessoa escrever (a secção "Em alta"/popular foi
 * removida no original — não reintroduzir só porque a chave de tradução
 * search.popularSearches ainda existe no ficheiro pt.json).
 */
@Composable
fun SearchScreen(catalogRepository: CatalogRepository, uiLang: String, onOpenContent: (String) -> Unit) {
    var query by remember { mutableStateOf("") }
    var results by remember { mutableStateOf<List<ContentItem>>(emptyList()) }
    var loading by remember { mutableStateOf(false) }

    LaunchedEffect(query, uiLang) {
        if (query.isBlank()) {
            results = emptyList()
            loading = false
            return@LaunchedEffect
        }
        loading = true
        delay(340)
        results = catalogRepository.search(query, contentLangFor(uiLang))
        loading = false
    }

    Column(Modifier.fillMaxSize().padding(12.dp)) {
        OutlinedTextField(
            value = query,
            onValueChange = { query = it },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
            leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
            trailingIcon = { if (loading) CircularProgressIndicator(modifier = Modifier.size(18.dp)) },
            keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(
                capitalization = KeyboardCapitalization.None,
                autoCorrect = false
            )
        )

        Spacer(Modifier.height(12.dp))

        when {
            query.isBlank() -> {}
            !loading && results.isEmpty() -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("Sem resultados para \"$query\"")
                    Text("Tente outro termo de busca.", style = MaterialTheme.typography.bodySmall)
                }
            }
            results.isNotEmpty() -> {
                Text(
                    "${results.size} resultados para \"$query\"",
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(bottom = 8.dp)
                )
                LazyVerticalGrid(
                    columns = GridCells.Fixed(2),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                    modifier = Modifier.fillMaxSize()
                ) {
                    items(results, key = { it.id }) { item ->
                        ContentCardCell(item.displayPoster, item.displayTitle, onClick = { onOpenContent(item.id) })
                    }
                }
            }
        }
    }
}
