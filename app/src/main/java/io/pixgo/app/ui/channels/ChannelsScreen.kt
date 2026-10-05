package io.pixgo.app.ui.channels

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ChevronLeft
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.filled.LiveTv
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
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
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import coil.compose.AsyncImage
import io.pixgo.app.data.channels.ChannelCategory
import io.pixgo.app.data.channels.ChannelGateResult
import io.pixgo.app.data.channels.ChannelListItem
import io.pixgo.app.data.channels.ChannelsRepository
import io.pixgo.app.data.i18n.LocalTranslator
import io.pixgo.app.ui.common.PxBtnSize
import io.pixgo.app.ui.common.PxBtnVariant
import io.pixgo.app.ui.common.PxButton
import io.pixgo.app.ui.common.PxEmptyState
import io.pixgo.app.ui.common.PxLoadingRing
import io.pixgo.app.ui.common.PxSpinnerSm
import io.pixgo.app.ui.common.PxText
import io.pixgo.app.ui.common.pagePadding
import io.pixgo.app.ui.common.pxShimmer
import io.pixgo.app.ui.common.pxTap
import io.pixgo.app.ui.theme.Montserrat
import io.pixgo.app.ui.theme.Poppins
import io.pixgo.app.ui.theme.Px
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.text.Normalizer

private const val LIMIT = 30
private const val COMMUNITY_REPO_URL = "https://github.com/iptv-org/iptv"

private fun normalizeStr(s: String): String =
    Normalizer.normalize(s, Normalizer.Form.NFD).replace(Regex("\\p{M}+"), "").lowercase().trim()

/**
 * Réplica de app/main/channels/page.tsx (lista; o player vive em ChannelsPlayerScreen):
 *  - modal informativo "Sobre os canais" ao entrar (fecha ao toque fora / botão);
 *  - cabeçalho: ícone LiveTv + "{n} canais disponíveis" (+ categoria / "· Anime") + botão info;
 *  - barra de filtros: pesquisa (38px), dropdown de categorias, alternador Anime/Todos;
 *  - filtro por defeito = categoria "Animation" só com canais com logo (animeOnly);
 *  - grelha `.channels-grid` de cartões 16:9 com gradiente, nome, grupo e selo (Assistir/Premium/…);
 *  - paginação "Pág. x / y", estado vazio com "Limpar filtros".
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ChannelsScreen(
    repository: ChannelsRepository,
    hasUser: Boolean,
    onOpenChannel: (ChannelListItem) -> Unit,
    onUpgrade: (planId: String) -> Unit = {},
) {
    val t = LocalTranslator.current
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }
    val w = LocalConfiguration.current.screenWidthDp

    var categories by remember { mutableStateOf<List<ChannelCategory>>(emptyList()) }
    var categoriesLoaded by remember { mutableStateOf(false) }
    var selectedCategory by remember { mutableStateOf<String?>(null) }   // slug
    var animeOnly by remember { mutableStateOf(true) }
    var query by remember { mutableStateOf("") }
    var items by remember { mutableStateOf<List<ChannelListItem>>(emptyList()) }
    var page by remember { mutableStateOf(1) }
    var pages by remember { mutableStateOf(1) }
    var grandTotal by remember { mutableStateOf(0) }
    var loading by remember { mutableStateOf(true) }
    var searching by remember { mutableStateOf(false) }
    var checkingId by remember { mutableStateOf<String?>(null) }
    // channels/page.tsx: 429 do gate → RateLimitModal com err.data.plans
    var rateLimitPlans by remember { mutableStateOf<List<io.pixgo.app.data.model.UpsellPlan>?>(null) }
    var showInfo by remember { mutableStateOf(true) }

    val animeCategory = categories.firstOrNull { normalizeStr(it.name) == normalizeStr("Animation") }
    val defaultFilter = animeOnly && query.isBlank() && selectedCategory == null
    val effectiveCategory = if (defaultFilter) animeCategory?.slug else selectedCategory

    LaunchedEffect(Unit) {
        categories = runCatching { repository.categories() }.getOrDefault(emptyList())
        categoriesLoaded = true
    }

    suspend fun loadPage(pg: Int) {
        val res = runCatching { repository.list(pg, effectiveCategory, hasUser, LIMIT) }.getOrNull()
        if (res == null) { snackbar.showSnackbar(t.t("errors.networkError")); return }
        items = if (defaultFilter) res.channels.filter { it.logo.isNotBlank() } else res.channels
        pages = res.pages
        grandTotal = res.grandTotal
        page = pg
    }

    // Recarrega a página 1 quando muda a categoria efectiva (só depois das categorias, como no web).
    LaunchedEffect(effectiveCategory, categoriesLoaded) {
        if (query.isNotBlank() || !categoriesLoaded) return@LaunchedEffect
        loading = true
        loadPage(1)
        loading = false
    }

    // Pesquisa com debounce de 500ms (searchChannels).
    LaunchedEffect(query) {
        if (query.isBlank()) {
            if (categoriesLoaded) { loading = true; loadPage(1); loading = false }
            return@LaunchedEffect
        }
        delay(500)
        searching = true
        val res = runCatching { repository.search(query.trim(), hasUser) }.getOrNull()
        if (res != null) { items = res; pages = 1 } else snackbar.showSnackbar(t.t("errors.networkError"))
        searching = false
    }

    fun openChannel(ch: ChannelListItem) {
        if (ch.locked || !ch.hasAccess) {
            scope.launch { snackbar.showSnackbar("\uD83D\uDD12 Canal premium. Assine para assistir.") }
            return
        }
        if (ch.url == null) { scope.launch { snackbar.showSnackbar("Stream indisponível.") }; return }
        checkingId = ch.id
        scope.launch {
            when (val r = repository.checkGate(ch.id)) {
                is ChannelGateResult.Ok -> onOpenChannel(ch)
                is ChannelGateResult.Denied -> snackbar.showSnackbar(r.message)
                is ChannelGateResult.RateLimited -> rateLimitPlans = r.plans
            }
            checkingId = null
        }
    }

    fun goPage(p: Int) { scope.launch { loading = true; loadPage(p); loading = false } }

    // `.channels-grid`: minmax(200px) gap 12 · <=768px minmax(150px) gap 9 · <=480px 2 colunas
    val cols = when { w <= 480 -> GridCells.Fixed(2); w <= 768 -> GridCells.Adaptive(150.dp); else -> GridCells.Adaptive(200.dp) }
    val gap = if (w <= 768) 9.dp else 12.dp

    if (showInfo) ChannelsInfoDialog(
        onClose = { showInfo = false },
        onMore = { runCatching { ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(COMMUNITY_REPO_URL))) } },
    )

    Box(Modifier.fillMaxSize()) {
        LazyVerticalGrid(
            columns = cols,
            contentPadding = pagePadding(),
            horizontalArrangement = Arrangement.spacedBy(gap),
            verticalArrangement = Arrangement.spacedBy(gap),
            modifier = Modifier.fillMaxSize(),
        ) {
            // ── .page-header
            item(span = { GridItemSpan(maxLineSpan) }) {
                Row(
                    Modifier.fillMaxWidth().padding(bottom = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    Icon(Icons.Filled.LiveTv, null, Modifier.size(24.dp), tint = Px.Primary)
                    FlowRow(verticalArrangement = Arrangement.Center, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text("%,d %s".format(grandTotal, t.t("channels.available")), style = PxText.PageSubtitle)
                        selectedCategory?.let {
                            Text("· ${categories.firstOrNull { c -> c.slug == it }?.name ?: it}", style = PxText.PageSubtitle.copy(color = Px.Primary))
                        }
                        if (animeOnly && query.isBlank() && selectedCategory == null) {
                            Text("· ${t.t("channels.filterAnime")}", style = PxText.PageSubtitle.copy(color = Px.Primary))
                        }
                    }
                    Icon(
                        Icons.Outlined.Info, t.t("channels.infoTitle"),
                        Modifier.size(16.dp).pxTap { showInfo = true }, tint = Px.TextMuted,
                    )
                }
            }

            // ── barra de filtros
            item(span = { GridItemSpan(maxLineSpan) }) {
                FlowRow(
                    Modifier.fillMaxWidth().padding(bottom = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    ChannelSearchField(query, searching, t.t("search.placeholder"), { query = it }, Modifier.widthIn(min = 220.dp, max = 380.dp).fillMaxWidth())
                    if (categories.isNotEmpty()) {
                        CategoryDropdown(
                            categories = categories,
                            selected = selectedCategory,
                            onChange = { slug -> query = ""; selectedCategory = slug; animeOnly = false },
                        )
                    }
                    if (query.isBlank()) {
                        val on = animeOnly && selectedCategory == null
                        Box(
                            Modifier.height(38.dp).clip(RoundedCornerShape(8.dp))
                                .background(if (on) Color(0x1AE50914) else Px.CardBg)
                                .border(1.dp, if (on) Px.Primary else Px.Border, RoundedCornerShape(8.dp))
                                .pxTap { query = ""; animeOnly = !animeOnly; selectedCategory = null }
                                .padding(horizontal = 12.dp),
                            contentAlignment = Alignment.Center,
                        ) {
                            Text(
                                if (on) t.t("channels.filterAnime") else t.t("channels.filterAll"),
                                color = if (on) Px.Primary else Px.TextLight, fontSize = 13.2.sp, fontWeight = FontWeight.SemiBold, maxLines = 1,
                            )
                        }
                    }
                }
            }

            when {
                loading -> items(12) { Box(Modifier.fillMaxWidth().aspectRatio(16f / 9f).pxShimmer(RoundedCornerShape(Px.Radius))) }
                items.isEmpty() -> item(span = { GridItemSpan(maxLineSpan) }) {
                    val clearAction: (@Composable () -> Unit)? =
                        if (query.isNotBlank() || selectedCategory != null || animeOnly) {
                            {
                                PxButton(
                                    "Limpar filtros",
                                    onClick = { query = ""; selectedCategory = null; animeOnly = false },
                                    variant = PxBtnVariant.Secondary, size = PxBtnSize.Sm,
                                    modifier = Modifier.padding(top = 12.dp),
                                )
                            }
                        } else null
                    PxEmptyState(
                        icon = Icons.Filled.LiveTv,
                        title = if (query.isNotBlank()) "Nenhum canal encontrado" else t.t("channels.noChannels"),
                        action = clearAction,
                    )
                }
                else -> {
                    items(items, key = { it.id }) { ch ->
                        ChannelCard(ch, loading = checkingId == ch.id, watchLabel = t.t("channels.watch"), onClick = { openChannel(ch) })
                    }
                    if (query.isBlank() && pages > 1) {
                        item(span = { GridItemSpan(maxLineSpan) }) {
                            Row(
                                Modifier.fillMaxWidth().padding(top = 12.dp),
                                horizontalArrangement = Arrangement.spacedBy(6.dp, Alignment.CenterHorizontally),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                PageBtn(Icons.Filled.ChevronLeft, enabled = page > 1) { goPage(page - 1) }
                                Text("Pág. $page / $pages", color = Px.TextMuted, fontSize = 13.sp, modifier = Modifier.padding(horizontal = 8.dp))
                                PageBtn(Icons.Filled.ChevronRight, enabled = page < pages) { goPage(page + 1) }
                            }
                        }
                    }
                }
            }
        }
        SnackbarHost(snackbar, Modifier.align(Alignment.BottomCenter))
    }

    rateLimitPlans?.let { plans ->
        io.pixgo.app.ui.modals.RateLimitModal(
            plans = plans,
            message = null,
            onClose = { rateLimitPlans = null },
            onUpgrade = { planId -> rateLimitPlans = null; onUpgrade(planId) },
        )
    }
}

/** `.form-input` 38px com ícone à esquerda, spinner / limpar à direita. */
@Composable
private fun ChannelSearchField(value: String, searching: Boolean, placeholder: String, onChange: (String) -> Unit, modifier: Modifier) {
    val shape = RoundedCornerShape(Px.RadiusSm)
    BasicTextField(
        value = value, onValueChange = onChange, singleLine = true,
        textStyle = TextStyle(fontFamily = Poppins, fontSize = 14.4.sp, color = Px.TextLight),
        cursorBrush = SolidColor(Px.TextLight),
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
        modifier = modifier,
        decorationBox = { inner ->
            Box(
                Modifier.fillMaxWidth().height(38.dp).clip(shape).background(Color(0x0DFFFFFF)).border(1.dp, Px.Border, shape),
                contentAlignment = Alignment.CenterStart,
            ) {
                Icon(Icons.Filled.Search, null, Modifier.padding(start = 11.dp).size(16.dp), tint = Px.TextMuted)
                Box(Modifier.padding(start = 34.dp, end = 34.dp)) {
                    if (value.isEmpty() && placeholder.isNotEmpty()) Text(placeholder, color = Px.TextMuted.copy(alpha = 0.6f), fontSize = 14.4.sp, maxLines = 1)
                    inner()
                }
                Box(Modifier.align(Alignment.CenterEnd).padding(end = 10.dp)) {
                    when {
                        searching -> PxSpinnerSm()
                        value.isNotEmpty() -> Icon(Icons.Filled.Close, null, Modifier.size(16.dp).pxTap { onChange("") }, tint = Px.TextMuted)
                    }
                }
            }
        },
    )
}

/** CategoryDropdown: botão 38px + menu escuro (#0f0f13, borda, 10px, max 340px). */
@Composable
private fun CategoryDropdown(categories: List<ChannelCategory>, selected: String?, onChange: (String?) -> Unit) {
    var open by remember { mutableStateOf(false) }
    val label = if (selected != null) categories.firstOrNull { it.slug == selected }?.name ?: selected else "Todas as categorias"
    Box {
        Row(
            Modifier.height(38.dp).clip(RoundedCornerShape(8.dp)).background(Px.CardBg)
                .border(1.dp, Px.Border, RoundedCornerShape(8.dp)).pxTap { open = !open }.padding(horizontal = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Icon(Icons.Filled.LiveTv, null, Modifier.size(14.dp), tint = Px.TextMuted)
            Text(label.replaceFirstChar { it.uppercase() }, color = Px.TextLight, fontSize = 13.2.sp, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.widthIn(max = 140.dp))
            Icon(Icons.Filled.ExpandMore, null, Modifier.size(16.dp), tint = Px.TextMuted)
        }
        DropdownMenu(
            expanded = open, onDismissRequest = { open = false },
            modifier = Modifier.widthIn(min = 220.dp).heightIn(max = 340.dp).background(Color(0xFF0F0F13)).border(1.dp, Px.Border, RoundedCornerShape(10.dp)),
        ) {
            DropdownMenuItem(
                text = { Text("Todas as categorias", color = if (selected == null) Px.Primary else Px.TextLight, fontSize = 13.sp) },
                onClick = { onChange(null); open = false },
            )
            categories.forEach { c ->
                DropdownMenuItem(
                    text = {
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            Text(c.name.replaceFirstChar { it.uppercase() }, color = if (selected == c.slug) Px.Primary else Px.TextLight, fontSize = 13.sp, maxLines = 1, modifier = Modifier.weight(1f, fill = false))
                            Text(c.count.toString(), color = Px.TextMuted, fontSize = 11.sp, modifier = Modifier.padding(start = 12.dp))
                        }
                    },
                    onClick = { onChange(c.slug); open = false },
                )
            }
        }
    }
}

@Composable
private fun ChannelCard(ch: ChannelListItem, loading: Boolean, watchLabel: String, onClick: () -> Unit) {
    val shape = RoundedCornerShape(Px.Radius)
    val premium = ch.locked || !ch.hasAccess
    Box(
        Modifier.fillMaxWidth().aspectRatio(16f / 9f).clip(shape)
            .background(Px.CardBg).border(1.dp, Px.Border, shape).pxTap(onClick = onClick)
    ) {
        if (ch.logo.isNotBlank()) {
            AsyncImage(ch.logo, ch.name, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
        } else {
            Box(Modifier.fillMaxSize().background(Px.CardHover), contentAlignment = Alignment.Center) {
                Icon(Icons.Filled.LiveTv, null, Modifier.size(28.dp), tint = Px.TextMuted)
            }
        }
        Box(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(Color.Transparent, Color(0x33000000), Color(0xD9000000)))))
        Column(Modifier.align(Alignment.BottomStart).fillMaxWidth().padding(horizontal = 10.dp, vertical = 8.dp)) {
            Text(ch.name, color = Color.White, fontSize = 12.48.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (ch.group.isNotBlank()) {
                Text(ch.group.replaceFirstChar { it.uppercase() }, color = Color(0xA6FFFFFF), fontSize = 9.92.sp, modifier = Modifier.padding(top = 2.dp), maxLines = 1)
            }
        }
        Row(
            Modifier.align(Alignment.TopEnd).padding(8.dp).clip(RoundedCornerShape(4.dp))
                .background(if (premium) Color(0xB3000000) else Color(0x99000000))
                .padding(horizontal = 7.dp, vertical = 3.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            when {
                loading -> PxSpinnerSm(12.dp)
                premium -> {
                    Icon(Icons.Filled.Lock, null, Modifier.size(11.dp), tint = Px.TextMuted)
                    Text("Premium", color = Px.TextMuted, fontSize = 9.92.sp, fontWeight = FontWeight.Bold)
                }
                else -> {
                    Icon(Icons.Filled.PlayArrow, null, Modifier.size(12.dp), tint = Color.White)
                    Text(watchLabel, color = Color.White, fontSize = 9.92.sp, fontWeight = FontWeight.Bold)
                }
            }
        }
    }
}

@Composable
private fun PageBtn(icon: androidx.compose.ui.graphics.vector.ImageVector, enabled: Boolean, onClick: () -> Unit) {
    val shape = RoundedCornerShape(Px.RadiusSm)
    Box(
        Modifier.size(34.dp).clip(shape).border(1.dp, Px.Border, shape)
            .then(if (enabled) Modifier.pxTap(onClick = onClick) else Modifier),
        contentAlignment = Alignment.Center,
    ) { Icon(icon, null, Modifier.size(15.dp), tint = if (enabled) Px.TextLight else Px.TextMuted.copy(alpha = 0.4f)) }
}

/** Modal "Sobre os canais" (overlay rgba(0,0,0,.82), card 14px, maxWidth 460, padding 24). */
@Composable
private fun ChannelsInfoDialog(onClose: () -> Unit, onMore: () -> Unit) {
    val t = LocalTranslator.current
    Dialog(onDismissRequest = onClose) {
        Column(
            Modifier.widthIn(max = 460.dp).fillMaxWidth().clip(RoundedCornerShape(14.dp))
                .background(Px.CardBg).border(1.dp, Px.Border, RoundedCornerShape(14.dp)).padding(24.dp)
        ) {
            Text(t.t("channels.infoTitle"), fontFamily = Montserrat, fontWeight = FontWeight.ExtraBold, fontSize = 16.8.sp, color = Px.TextLight, modifier = Modifier.padding(bottom = 12.dp))
            Text(t.t("channels.infoBody1"), fontSize = 13.6.sp, lineHeight = 23.1.sp, color = Px.TextMuted, modifier = Modifier.padding(bottom = 12.dp))
            Text(t.t("channels.infoBody2"), fontSize = 13.6.sp, lineHeight = 23.1.sp, color = Px.TextMuted)
            Text(
                t.t("channels.infoMore"), fontSize = 13.6.sp, fontWeight = FontWeight.SemiBold, color = Px.Primary,
                modifier = Modifier.padding(top = 4.dp).pxTap(onClick = onMore),
            )
            PxButton(t.t("common.close"), onClick = onClose, modifier = Modifier.padding(top = 18.dp).fillMaxWidth())
        }
    }
}
