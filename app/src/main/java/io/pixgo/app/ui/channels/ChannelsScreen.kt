package io.pixgo.app.ui.channels

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items as lazyRowItems
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import io.pixgo.app.data.channels.ChannelGateResult
import io.pixgo.app.data.channels.ChannelListItem
import io.pixgo.app.data.channels.ChannelCategory
import io.pixgo.app.data.channels.ChannelsRepository
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Réplica de app/main/channels/page.tsx: categorias (group-title do M3U,
 * ordem alfabética), pesquisa com debounce, grelha de canais. Os dados em
 * si vêm 100% client-side do jsDelivr (ChannelsSource) — o backend só
 * entra no toque (gate anti-abuso, GET /api/channels/:id). Abrir o
 * player em si fica para a fase seguinte (watch/player); aqui só se
 * confirma que o gate deixaria passar.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChannelsScreen(repository: ChannelsRepository, hasUser: Boolean, onOpenChannel: (ChannelListItem) -> Unit) {
    var categories by remember { mutableStateOf<List<ChannelCategory>>(emptyList()) }
    var selectedCategory by remember { mutableStateOf<String?>(null) }
    var query by remember { mutableStateOf("") }
    var items by remember { mutableStateOf<List<ChannelListItem>>(emptyList()) }
    var page by remember { mutableStateOf(1) }
    var pages by remember { mutableStateOf(1) }
    var loading by remember { mutableStateOf(true) }
    var checkingId by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    val snackbarHostState = remember { SnackbarHostState() }

    LaunchedEffect(Unit) {
        categories = repository.categories()
    }

    LaunchedEffect(selectedCategory, query) {
        if (query.isNotBlank()) {
            loading = true
            delay(340)
            items = repository.search(query, hasUser)
            pages = 1
            loading = false
        } else {
            loading = true
            page = 1
            val result = repository.list(1, selectedCategory, hasUser)
            items = result.channels
            pages = result.pages
            loading = false
        }
    }

    fun openChannel(ch: ChannelListItem) {
        if (ch.locked || !ch.hasAccess) {
            scope.launch { snackbarHostState.showSnackbar("Faça login para aceder ao canal.") }
            return
        }
        checkingId = ch.id
        scope.launch {
            when (val result = repository.checkGate(ch.id)) {
                is ChannelGateResult.Ok -> onOpenChannel(ch)
                is ChannelGateResult.Denied -> snackbarHostState.showSnackbar(result.message)
            }
            checkingId = null
        }
    }

    Box(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize()) {
            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                modifier = Modifier.fillMaxWidth().padding(12.dp),
                singleLine = true,
                placeholder = { Text("Pesquisar canais") },
                leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) }
            )

            if (query.isBlank() && categories.isNotEmpty()) {
                LazyRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    contentPadding = PaddingValues(horizontal = 12.dp),
                    modifier = Modifier.padding(bottom = 8.dp)
                ) {
                    lazyRowItems(categories, key = { it.slug }) { cat ->
                        FilterChip(
                            selected = selectedCategory == cat.name,
                            onClick = { selectedCategory = if (selectedCategory == cat.name) null else cat.name },
                            label = { Text("${cat.name} (${cat.count})") }
                        )
                    }
                }
            }

            if (loading) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
            } else if (items.isEmpty()) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text("Nenhum canal encontrado")
                }
            } else {
                LazyVerticalGrid(
                    columns = GridCells.Fixed(3),
                    contentPadding = PaddingValues(12.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.weight(1f)
                ) {
                    items(items, key = { it.id }) { ch ->
                        ChannelCell(ch, loading = checkingId == ch.id, onClick = { openChannel(ch) })
                    }
                }

                if (query.isBlank() && pages > 1) {
                    Row(
                        Modifier.fillMaxWidth().padding(12.dp),
                        horizontalArrangement = Arrangement.Center,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        TextButton(
                            enabled = page > 1,
                            onClick = {
                                scope.launch {
                                    loading = true
                                    val result = repository.list(page - 1, selectedCategory, hasUser)
                                    items = result.channels; pages = result.pages; page -= 1; loading = false
                                }
                            }
                        ) { Text("Anterior") }
                        Text("$page / $pages", modifier = Modifier.padding(horizontal = 12.dp))
                        TextButton(
                            enabled = page < pages,
                            onClick = {
                                scope.launch {
                                    loading = true
                                    val result = repository.list(page + 1, selectedCategory, hasUser)
                                    items = result.channels; pages = result.pages; page += 1; loading = false
                                }
                            }
                        ) { Text("Seguinte") }
                    }
                }
            }
        }
        SnackbarHost(hostState = snackbarHostState, modifier = Modifier.align(Alignment.BottomCenter))
    }
}

@Composable
private fun ChannelCell(ch: ChannelListItem, loading: Boolean, onClick: () -> Unit) {
    Column(
        Modifier
            .clip(RoundedCornerShape(8.dp))
            .background(Color(0xFF1A1A1E))
            .clickable(onClick = onClick)
            .padding(8.dp)
    ) {
        Box(Modifier.fillMaxWidth().aspectRatio(1.6f), contentAlignment = Alignment.Center) {
            AsyncImage(
                model = ch.logo.ifBlank { null },
                contentDescription = ch.name,
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Fit
            )
            if (ch.locked || !ch.hasAccess) {
                Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.5f)), contentAlignment = Alignment.Center) {
                    Icon(Icons.Filled.Lock, contentDescription = "Bloqueado", tint = Color.White)
                }
            }
            if (loading) {
                CircularProgressIndicator(modifier = Modifier.size(20.dp))
            }
        }
        Text(ch.name, style = MaterialTheme.typography.bodySmall, maxLines = 1, modifier = Modifier.padding(top = 4.dp))
    }
}
