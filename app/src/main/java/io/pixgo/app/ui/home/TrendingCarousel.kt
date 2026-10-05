package io.pixgo.app.ui.home

import android.provider.Settings
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.animateScrollBy
import androidx.compose.foundation.gestures.snapping.rememberSnapFlingBehavior
import androidx.compose.foundation.interaction.collectIsDraggedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ChevronLeft
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.pixgo.app.data.i18n.LocalTranslator
import io.pixgo.app.data.model.ContentItem
import io.pixgo.app.ui.common.ContentCardCell
import io.pixgo.app.ui.common.PxText
import io.pixgo.app.ui.common.pxTap
import io.pixgo.app.ui.theme.Px
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.roundToInt

private const val AUTOPLAY_MS = 4500L
private const val RESUME_AFTER_TOUCH_MS = 6000L

/**
 * Réplica de components/ui/TrendingCarousel.tsx — secção "Tendências" da home
 * (mini séries de canal do YouTube, cards horizontais 16:9).
 *
 *  • scroller horizontal com snap; auto-avanço a cada 4.5s em "páginas"
 *    (~90% da largura visível), a dar a volta ao chegar ao fim;
 *  • pausa automática: toque/arrasto em curso (retoma 6s depois), foco dentro
 *    da secção, secção fora do ecrã (a lazy list descarta o item → o efeito
 *    cancela-se sozinho);
 *  • SEM botão Pausar/Reproduzir e SEM pontos (pedido explícito): tocar no
 *    carrossel já o pausa; com animações do sistema desligadas
 *    (prefers-reduced-motion) o autoplay nasce parado;
 *  • setas (dão a volta) sempre visíveis.
 * Larguras: --tc-w = clamp(190px, 17vw, 240px); <=600px 46vw.
 */
@OptIn(ExperimentalFoundationApi::class, ExperimentalLayoutApi::class)
@Composable
fun TrendingCarousel(
    items: List<ContentItem>,
    title: String,
    onOpenContent: (String) -> Unit,
    modifier: Modifier = Modifier,
    myList: io.pixgo.app.ui.common.MyListUi? = null,
) {
    if (items.isEmpty()) return

    val t = LocalTranslator.current
    val ctx = LocalContext.current
    val density = LocalDensity.current
    val screenW = LocalConfiguration.current.screenWidthDp
    val compact = screenW <= 600

    val slideW = if (compact) (screenW * 0.46f).dp else (screenW * 0.17f).coerceIn(190f, 240f).dp
    val gap = if (compact) 8.dp else 14.dp
    val arrowSize = if (compact) 30.dp else 36.dp

    val state = rememberLazyListState()
    val scope = rememberCoroutineScope()
    val n = items.size

    // Sem botão de pausa: tocar/arrastar o carrossel já o pausa (touchHold abaixo).
    // Só o 'reduce motion' do sistema desliga o auto-avanço.
    var playing by remember { mutableStateOf(true) }
    LaunchedEffect(Unit) {
        // prefers-reduced-motion: reduce → autoplay nasce desligado.
        val scale = runCatching {
            Settings.Global.getFloat(ctx.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f)
        }.getOrDefault(1f)
        if (scale == 0f) playing = false
    }

    // ── Medição (nº de páginas + página actual) a partir do scroll real ──────
    var viewportPx by remember { mutableStateOf(0) }
    val slideWPx = with(density) { slideW.toPx() }
    val gapPx = with(density) { gap.toPx() }
    val padPx = with(density) { 2.dp.toPx() }
    val contentPx = n * slideWPx + (n - 1) * gapPx + 2f * padPx          // scrollWidth
    val maxScroll = max(0f, contentPx - viewportPx)
    val pageCount = if (viewportPx <= 0) 1 else max(1, ceil(contentPx / viewportPx - 0.05f).toInt())

    fun currentScroll(): Float =
        state.firstVisibleItemIndex * (slideWPx + gapPx) + state.firstVisibleItemScrollOffset

    // move(dir): dá a volta (início ↔ fim) em vez de ficar inerte.
    val minStepPx = with(density) { 200.dp.toPx() }
    suspend fun move(dir: Int) {
        if (maxScroll <= 1f) return
        val cur = currentScroll()
        val amount = when {
            dir == 1 && cur >= maxScroll - 4f -> -cur
            dir == -1 && cur <= 4f -> maxScroll
            else -> dir * max(viewportPx * 0.9f, minStepPx)
        }
        state.animateScrollBy(amount)
    }

    // ── Pausas que não alteram a intenção do utilizador (`playing`) ──────────
    val dragged by state.interactionSource.collectIsDraggedAsState()
    var touchHold by remember { mutableStateOf(false) }
    LaunchedEffect(dragged) {
        if (dragged) touchHold = true
        else if (touchHold) { delay(RESUME_AFTER_TOUCH_MS); touchHold = false }
    }
    var focusHold by remember { mutableStateOf(false) }

    LaunchedEffect(playing, n, touchHold, focusHold, maxScroll, viewportPx) {
        if (!playing || n < 2 || touchHold || focusHold) return@LaunchedEffect
        while (true) {
            delay(AUTOPLAY_MS)
            move(1)
        }
    }

    // ── UI ───────────────────────────────────────────────────────────────────
    Column(
        modifier
            .fillMaxWidth()
            .onFocusChanged { focusHold = it.hasFocus }
    ) {
        // .tc-header
        Row(
            Modifier.fillMaxWidth().padding(bottom = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(title, style = PxText.SectionTitle)
        }

        // .tc-viewport
        Box(Modifier.fillMaxWidth().onSizeChanged { viewportPx = it.width }) {
            LazyRow(
                state = state,
                contentPadding = PaddingValues(start = 2.dp, end = 2.dp, top = 2.dp, bottom = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(gap),
                flingBehavior = rememberSnapFlingBehavior(state),
            ) {
                itemsIndexed(items, key = { _, it -> it.id }) { _, item ->
                    Box(Modifier.width(slideW)) {
                        ContentCardCell(
                            title = item.displayTitle,
                            posterUrl = item.displayPoster,
                            year = item.year,
                            type = item.type,
                            rating = item.displayRating,
                            wide = true,
                            modifier = Modifier.fillMaxWidth(),
                            onClick = { onOpenContent(item.id) },
                            inList = myList?.isIn(item.id),
                            onAddToList = myList?.let { m -> { m.toggle(item.id) } },
                        )
                    }
                }
            }

            // .tc-arrow: top = tc-w × 0.28125 + 2px (centro da thumb 16:9), translateY(-50%)
            val arrowY = slideW * 0.28125f + 2.dp - arrowSize / 2
            CarouselArrow(
                modifier = Modifier.align(Alignment.TopStart).offset(x = 4.dp, y = arrowY),
                size = arrowSize, dimmed = pageCount <= 1,
                description = t.t("home.trendingPrev"), left = true,
            ) { scope.launch { move(-1) } }
            CarouselArrow(
                modifier = Modifier.align(Alignment.TopEnd).offset(x = (-4).dp, y = arrowY),
                size = arrowSize, dimmed = pageCount <= 1,
                description = t.t("home.trendingNext"), left = false,
            ) { scope.launch { move(1) } }
        }
    }
}

@Composable
private fun CarouselArrow(
    modifier: Modifier,
    size: androidx.compose.ui.unit.Dp,
    dimmed: Boolean,
    description: String,
    left: Boolean,
    onClick: () -> Unit,
) {
    Box(
        modifier
            .size(size)
            .clip(CircleShape)
            .background(Color(0xC70C0C10))
            .border(1.dp, Color(0x33FFFFFF), CircleShape)
            .pxTap(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            if (left) Icons.Filled.ChevronLeft else Icons.Filled.ChevronRight,
            description,
            Modifier.size(if (size < 36.dp) 24.dp else 28.dp),
            tint = if (dimmed) Color(0x73FFFFFF) else Color.White,
        )
    }
}
