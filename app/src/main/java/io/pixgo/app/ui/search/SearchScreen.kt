package io.pixgo.app.ui.search

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.SearchOff
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.pixgo.app.data.catalog.CatalogRepository
import io.pixgo.app.data.i18n.LocalTranslator
import io.pixgo.app.data.i18n.contentLangFor
import io.pixgo.app.data.model.ContentItem
import io.pixgo.app.ui.common.ContentCardCell
import io.pixgo.app.ui.common.PxEmptyState
import io.pixgo.app.ui.common.PxSpinnerSm
import io.pixgo.app.ui.common.cells
import io.pixgo.app.ui.common.pagePadding
import io.pixgo.app.ui.common.rememberGridSpec
import io.pixgo.app.ui.theme.Poppins
import io.pixgo.app.ui.theme.Px
import kotlinx.coroutines.delay

/**
 * Réplica de app/main/search/page.tsx: sem <h1> (só o espaçamento do
 * page-header), campo `.form-input` (48px, ícone à esquerda, sem placeholder),
 * foco automático ao abrir, debounce de 340ms, nada sugerido antes de a pessoa
 * escrever, "{total} resultados para "{q}"" e empty-state SearchOff.
 */
@Composable
fun SearchScreen(
    catalogRepository: CatalogRepository,
    uiLang: String,
    onOpenContent: (String) -> Unit,
    initialQuery: String = "",
    activeProfileId: String? = null,
) {
    val t = LocalTranslator.current
    val spec = rememberGridSpec()
    var query by remember(initialQuery) { mutableStateOf(initialQuery) }
    var results by remember { mutableStateOf<List<ContentItem>>(emptyList()) }
    var total by remember { mutableStateOf(0) }
    var loading by remember { mutableStateOf(false) }
    // Termo cujos resultados estão realmente no ecrã. O "sem resultados" só vale para ESTE
    // termo: durante o debounce (340 ms) e enquanto o pedido corre, `results` ainda é o da
    // pesquisa anterior (ou vazio) e mostrar o empty-state ali era o falso "sem resultados"
    // que piscava a cada letra. `failed` = pedido falhou (não é o mesmo que "0 resultados").
    var searchedQuery by remember { mutableStateOf<String?>(null) }
    var failed by remember { mutableStateOf(false) }
    val focus = remember { FocusRequester() }
    val myList = io.pixgo.app.ui.common.rememberMyList(catalogRepository, activeProfileId)

    // setTimeout(() => inputRef.current?.focus(), 100)
    LaunchedEffect(Unit) { delay(100); runCatching { focus.requestFocus() } }

    LaunchedEffect(query, uiLang) {
        if (query.isBlank()) {
            results = emptyList(); total = 0; loading = false; failed = false; searchedQuery = null
            return@LaunchedEffect
        }
        // A partir daqui há pesquisa pendente: spinner no campo desde a primeira letra e
        // nenhum empty-state até haver resposta para ESTE termo.
        loading = true
        failed = false
        delay(340)
        // Falha de rede / servidor frio: uma repetição silenciosa antes de dar como falhado
        // (o web fica à espera; nunca transforma uma falha em "sem resultados").
        var page: CatalogRepository.SearchPage? = null
        for (attempt in 0 until 2) {
            if (attempt > 0) delay(600)
            page = runCatching { catalogRepository.searchPageOrNull(query, contentLangFor(uiLang)) }
                .onFailure { if (it is kotlinx.coroutines.CancellationException) throw it }
                .getOrNull()
            if (page != null) break
        }
        if (page != null) {
            results = page.results
            total = page.total
            searchedQuery = query
        } else {
            failed = true
        }
        loading = false
    }

    LazyVerticalGrid(
        columns = spec.cells(),
        contentPadding = pagePadding(),
        horizontalArrangement = Arrangement.spacedBy(spec.gap),
        verticalArrangement = Arrangement.spacedBy(spec.gap),
        modifier = Modifier.fillMaxSize()
    ) {
        // .page-header vazio (margin-bottom:22) + input (maxWidth 560, margin-bottom 28)
        item(span = { GridItemSpan(maxLineSpan) }) {
            Box(Modifier.padding(top = 22.dp, bottom = 28.dp - spec.gap.coerceAtMost(28.dp)).widthIn(max = 560.dp).fillMaxWidth()) {
                val shape = RoundedCornerShape(Px.RadiusSm)
                BasicTextField(
                    value = query,
                    onValueChange = { query = it },
                    singleLine = true,
                    textStyle = TextStyle(fontFamily = Poppins, fontSize = 16.sp, color = Px.TextLight),
                    cursorBrush = SolidColor(Px.TextLight),
                    keyboardOptions = KeyboardOptions(
                        capitalization = KeyboardCapitalization.None,
                        autoCorrect = false,
                        imeAction = ImeAction.Search,
                    ),
                    modifier = Modifier.fillMaxWidth().focusRequester(focus),
                    decorationBox = { inner ->
                        Box(
                            Modifier.fillMaxWidth().height(48.dp).clip(shape)
                                .background(Color(0x0DFFFFFF)).border(1.dp, Px.Border, shape),
                            contentAlignment = Alignment.CenterStart
                        ) {
                            Icon(Icons.Filled.Search, null, Modifier.padding(start = 14.dp).size(20.dp), tint = Px.TextMuted)
                            Box(Modifier.padding(start = 46.dp, end = 40.dp)) { inner() }
                            if (loading) {
                                Box(Modifier.align(Alignment.CenterEnd).padding(end = 12.dp)) { PxSpinnerSm() }
                            }
                        }
                    }
                )
            }
        }

        if (query.isNotBlank() && !loading && !failed && searchedQuery == query && results.isEmpty()) {
            item(span = { GridItemSpan(maxLineSpan) }) {
                PxEmptyState(
                    icon = Icons.Filled.SearchOff,
                    title = "${t.t("search.noResults")} \"$query\"",
                    description = t.t("search.noResultsDesc"),
                )
            }
        }

        if (query.isNotBlank() && !loading && failed) {
            item(span = { GridItemSpan(maxLineSpan) }) {
                Text(
                    t.t("errors.networkError"),
                    color = Px.TextMuted, fontSize = 12.8.sp,
                    modifier = Modifier.padding(bottom = 4.dp)
                )
            }
        }

        if (results.isNotEmpty()) {
            item(span = { GridItemSpan(maxLineSpan) }) {
                Text(
                    "$total ${t.t("search.results")} \"${searchedQuery ?: query}\"",
                    color = Px.TextMuted, fontSize = 12.8.sp,
                    modifier = Modifier.padding(bottom = 4.dp)
                )
            }
            items(results, key = { it.id }) { item ->
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
        }
    }
}
