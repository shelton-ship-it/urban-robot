package io.pixgo.app.ui.common

import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.pixgo.app.ui.theme.Montserrat
import io.pixgo.app.ui.theme.Poppins
import io.pixgo.app.ui.theme.Px

/*
 * Camada de design partilhada — réplica das classes utilitárias de
 * frontend_web/src/app/globals.css (.content-grid, .page-content,
 * .page-header, .section-*, .empty-*, .btn-*, .skeleton*, .loading-ring).
 * Todos os ecrãs devem usar isto em vez de componentes Material "default",
 * para o app ficar visualmente igual ao frontend_web.
 */

// ───────────────────────── .content-grid (breakpoints do globals.css) ─────────────────────────

/**
 * `.content-grid`:
 *  - default      repeat(auto-fill, minmax(150px,1fr)) gap 12
 *  - >=1920px     minmax(200px,1fr) gap 18
 *  - <=1024px     minmax(135px,1fr)
 *  - <=768px      minmax(112px,1fr) gap 8
 *  - <=480px      repeat(3, minmax(0,1fr)) gap 7
 */
@Immutable
data class GridSpec(val fixedColumns: Int?, val minCell: Dp, val gap: Dp)

fun gridSpecFor(screenWidthDp: Int): GridSpec = when {
    screenWidthDp <= 480 -> GridSpec(3, 0.dp, 7.dp)
    screenWidthDp <= 768 -> GridSpec(null, 112.dp, 8.dp)
    screenWidthDp <= 1024 -> GridSpec(null, 135.dp, 12.dp)
    screenWidthDp >= 1920 -> GridSpec(null, 200.dp, 18.dp)
    else -> GridSpec(null, 150.dp, 12.dp)
}

fun GridSpec.cells(): GridCells =
    if (fixedColumns != null) GridCells.Fixed(fixedColumns) else GridCells.Adaptive(minCell)

/** Nº de colunas que o `auto-fill` do CSS produziria num contentor com [available] de largura. */
fun GridSpec.columnsFor(available: Dp): Int =
    fixedColumns ?: maxOf(1, ((available.value + gap.value) / (minCell.value + gap.value)).toInt())

@Composable
fun rememberGridSpec(): GridSpec {
    val w = LocalConfiguration.current.screenWidthDp
    return remember(w) { gridSpecFor(w) }
}

/** `.page-content` padding: 22px 26px 60px; <=768px: 16px 12px 60px. */
@Composable
fun pagePadding(): PaddingValues {
    val w = LocalConfiguration.current.screenWidthDp
    return if (w <= 768) PaddingValues(start = 12.dp, top = 16.dp, end = 12.dp, bottom = 60.dp)
    else PaddingValues(start = 26.dp, top = 22.dp, end = 26.dp, bottom = 60.dp)
}

/** `.cw-card` / `.cw-thumb`: 200×112; <=768px 130×73; <=480px 110×62. */
@Composable
fun cwCardSize(): Pair<Dp, Dp> {
    val w = LocalConfiguration.current.screenWidthDp
    return when {
        w <= 480 -> 110.dp to 62.dp
        w <= 768 -> 130.dp to 73.dp
        else -> 200.dp to 112.dp
    }
}

// ───────────────────────── tipografia ─────────────────────────

object PxText {
    /** `.section-title` / `.empty-title`: Montserrat 800 1.05rem. */
    val SectionTitle = TextStyle(
        fontFamily = Montserrat, fontWeight = FontWeight.ExtraBold, fontSize = 16.8.sp,
        lineHeight = 25.2.sp, color = Px.TextLight,
    )
    val EmptyTitle = SectionTitle.copy(color = Px.TextTitle)

    /** `.page-title`: Montserrat 800 1.7rem (<=480px 1.3rem; >=1920px 2.2rem), letter-spacing -0.02em. */
    @Composable
    fun pageTitle(): TextStyle {
        val w = LocalConfiguration.current.screenWidthDp
        val size = when { w <= 480 -> 20.8.sp; w >= 1920 -> 35.2.sp; else -> 27.2.sp }
        return TextStyle(
            fontFamily = Montserrat, fontWeight = FontWeight.ExtraBold, fontSize = size,
            letterSpacing = (-0.02f * size.value).sp, color = Px.TextLight,
        )
    }

    /** `.page-subtitle`: 0.875rem muted. */
    val PageSubtitle = TextStyle(fontFamily = Poppins, fontSize = 14.sp, lineHeight = 21.sp, color = Px.TextMuted)
    val EmptyDesc = TextStyle(fontFamily = Poppins, fontSize = 14.sp, lineHeight = 22.4.sp, color = Px.TextMuted, textAlign = TextAlign.Center)
}

// ───────────────────────── interacção ─────────────────────────

/** Toque sem ripple (o web não tem ripple; hover/focus só em rato/TV). */
fun Modifier.pxTap(enabled: Boolean = true, onClick: () -> Unit): Modifier = composed {
    clickable(
        interactionSource = remember { MutableInteractionSource() },
        indication = null,
        enabled = enabled,
        onClick = onClick,
    )
}

// ───────────────────────── .page-header / .section ─────────────────────────

/** `.page-header` { margin-bottom: 22px } com título/subtítulo opcionais. */
@Composable
fun PxPageHeader(
    modifier: Modifier = Modifier,
    title: String? = null,
    subtitle: String? = null,
    leading: (@Composable () -> Unit)? = null,
) {
    Column(modifier.fillMaxWidth().padding(bottom = 22.dp)) {
        if (leading != null) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                leading()
                if (subtitle != null) Text(subtitle, style = PxText.PageSubtitle)
            }
        } else {
            if (title != null) Text(title, style = PxText.pageTitle())
            if (subtitle != null) Text(subtitle, style = PxText.PageSubtitle, modifier = Modifier.padding(top = 3.dp))
        }
    }
}

/** `.section-header` + `.section-title` (margin-bottom 12). */
@Composable
fun PxSectionHeader(title: String, modifier: Modifier = Modifier, trailing: (@Composable () -> Unit)? = null) {
    Row(
        modifier.fillMaxWidth().padding(bottom = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(title, style = PxText.SectionTitle)
        trailing?.invoke()
    }
}

// ───────────────────────── .empty-state / .loading-ring ─────────────────────────

/** `.empty-state`: padding 64/24, gap 12, ícone 64 circular (borda 2px vermelha 20%). */
@Composable
fun PxEmptyState(
    icon: ImageVector,
    title: String,
    modifier: Modifier = Modifier,
    description: String? = null,
    action: (@Composable () -> Unit)? = null,
) {
    Column(
        modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 64.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp, Alignment.CenterVertically),
    ) {
        Box(
            Modifier.size(64.dp).clip(CircleShape)
                .background(Color(0x14E50914))
                .border(2.dp, Color(0x33E50914), CircleShape),
            contentAlignment = Alignment.Center,
        ) { Icon(icon, null, Modifier.size(28.dp), tint = Px.Primary) }
        Text(title, style = PxText.EmptyTitle.copy(textAlign = TextAlign.Center))
        if (description != null) {
            Text(description, style = PxText.EmptyDesc, modifier = Modifier.widthIn(max = 320.dp))
        }
        if (action != null) Box(Modifier.padding(top = 4.dp)) { action() }
    }
}

/** `.page-loading` + `.loading-ring` (42px, borda 3px, 0.85s linear). */
@Composable
fun PxPageLoading(modifier: Modifier = Modifier) {
    Box(modifier.fillMaxWidth().heightIn(min = 320.dp), contentAlignment = Alignment.Center) { PxLoadingRing() }
}

@Composable
fun PxLoadingRing(size: Dp = 42.dp, stroke: Dp = 3.dp, durationMs: Int = 850) {
    val rot by rememberInfiniteTransition(label = "ring").animateFloat(
        0f, 360f, infiniteRepeatable(tween(durationMs, easing = LinearEasing)), label = "ringRot",
    )
    Canvas(Modifier.size(size).graphicsLayer { rotationZ = rot }) {
        val sw = stroke.toPx()
        val s = Size(this.size.width - sw, this.size.height - sw)
        val tl = Offset(sw / 2, sw / 2)
        drawArc(Color(0x14FFFFFF), 0f, 360f, false, tl, s, style = Stroke(sw))
        drawArc(Px.Primary, -90f, 90f, false, tl, s, style = Stroke(sw))
    }
}

/** `.spinner.spinner-sm` (inline em botões/inputs). */
@Composable
fun PxSpinnerSm(size: Dp = 15.dp) = PxLoadingRing(size = size, stroke = 2.dp, durationMs = 700)

// ───────────────────────── .btn ─────────────────────────

enum class PxBtnVariant { Primary, Secondary, Ghost, Danger }
enum class PxBtnSize { Sm, Md, Lg }

/** `.btn .btn-{primary|secondary|ghost|danger} [.btn-sm|.btn-lg]`. */
@Composable
fun PxButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    variant: PxBtnVariant = PxBtnVariant.Primary,
    size: PxBtnSize = PxBtnSize.Md,
    enabled: Boolean = true,
    icon: ImageVector? = null,
) {
    val shape = RoundedCornerShape(Px.RadiusSm)
    val (bg, fg, borderColor) = when (variant) {
        PxBtnVariant.Primary -> Triple(Px.Primary, Color.White, null as Color?)
        PxBtnVariant.Secondary -> Triple(Color(0x12FFFFFF), Px.TextLight, Px.Border as Color?)
        PxBtnVariant.Ghost -> Triple(Color.Transparent, Px.TextMuted, null as Color?)
        PxBtnVariant.Danger -> Triple(Color(0x1AE50914), Px.Primary, Color(0x33E50914) as Color?)
    }
    val fontSize = when (size) {
        PxBtnSize.Sm -> 12.8.sp
        PxBtnSize.Md -> 14.sp
        PxBtnSize.Lg -> 16.sp
    }
    val (hPad, vPad, minH) = when (size) {
        PxBtnSize.Sm -> Triple(13.dp, 6.dp, 34.dp)
        PxBtnSize.Md -> Triple(18.dp, 9.dp, 40.dp)
        PxBtnSize.Lg -> Triple(26.dp, 13.dp, 50.dp)
    }
    Row(
        modifier
            .alpha(if (enabled) 1f else 0.42f)
            .then(
                if (variant == PxBtnVariant.Primary && enabled)
                    Modifier.shadow(6.dp, shape, ambientColor = Px.Primary.copy(alpha = 0.28f), spotColor = Px.Primary.copy(alpha = 0.28f))
                else Modifier
            )
            .heightIn(min = minH)
            .clip(shape)
            .background(bg)
            .then(if (borderColor != null) Modifier.border(1.dp, borderColor, shape) else Modifier)
            .pxTap(enabled = enabled, onClick = onClick)
            .padding(horizontal = hPad, vertical = vPad),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(7.dp, Alignment.CenterHorizontally),
    ) {
        if (icon != null) Icon(icon, null, Modifier.size(18.dp), tint = fg)
        Text(text, color = fg, fontSize = fontSize, fontWeight = FontWeight.SemiBold, maxLines = 1)
    }
}

// ───────────────────────── .badge ─────────────────────────

enum class PxBadgeKind { Red, Green, Purple, Gray, Amber }

/** `.badge .badge-*`. */
@Composable
fun PxBadge(text: String, kind: PxBadgeKind = PxBadgeKind.Gray, modifier: Modifier = Modifier) {
    val (bg, fg) = when (kind) {
        PxBadgeKind.Red -> Color(0x24E50914) to Color(0xFFFF6B6B)
        PxBadgeKind.Green -> Color(0x1C1CE783) to Px.Secondary
        PxBadgeKind.Purple -> Color(0x248C3BFF) to Color(0xFFB06EF3)
        PxBadgeKind.Gray -> Color(0x12FFFFFF) to Px.TextMuted
        PxBadgeKind.Amber -> Color(0x1CFFB830) to Color(0xFFFFB830)
    }
    Text(
        text, color = fg, fontSize = 10.72.sp, fontWeight = FontWeight.Bold, letterSpacing = 0.21.sp,
        modifier = modifier.clip(RoundedCornerShape(99.dp)).background(bg).padding(horizontal = 9.dp, vertical = 3.dp),
    )
}

// ───────────────────────── .skeleton* ─────────────────────────

/**
 * `.skeleton`: fundo --color-card-bg + brilho (transparent → rgba(255,255,255,.06) →
 * transparent) a varrer da esquerda para a direita, 1.6s ease-in-out infinito.
 */
fun Modifier.pxShimmer(shape: Shape = RoundedCornerShape(Px.RadiusSm)): Modifier = composed {
    val p by rememberInfiniteTransition(label = "sk").animateFloat(
        0f, 1f,
        infiniteRepeatable(tween(1600, easing = CubicBezierEasing(0.42f, 0f, 0.58f, 1f)), RepeatMode.Restart),
        label = "skProgress",
    )
    this
        .clip(shape)
        .background(Px.CardBg)
        .drawWithContent {
            drawContent()
            val w = size.width
            val x = -w + 2f * w * p            // translateX(-100%) → translateX(100%)
            drawRect(
                Brush.horizontalGradient(
                    listOf(Color.Transparent, Color(0x0FFFFFFF), Color.Transparent),
                    startX = x, endX = x + w,
                )
            )
        }
}

/** `.skeleton-card` com `.skeleton-thumb` 2:3 (ou 16:9 se [wide]) + 2 linhas. */
@Composable
fun PxContentCardSkeleton(modifier: Modifier = Modifier, wide: Boolean = false) {
    val shape = RoundedCornerShape(Px.Radius)
    Column(modifier.clip(shape).background(Px.CardBg).border(1.dp, Px.Border, shape)) {
        Box(Modifier.fillMaxWidth().aspectRatio(if (wide) 16f / 9f else 2f / 3f).pxShimmer(RectangleShape))
        Column(
            Modifier.padding(start = 11.dp, end = 11.dp, top = 9.dp, bottom = 11.dp),
            verticalArrangement = Arrangement.spacedBy(7.dp),
        ) {
            Box(Modifier.fillMaxWidth(if (wide) 0.80f else 0.85f).height(10.dp).pxShimmer(RoundedCornerShape(4.dp)))
            Box(Modifier.fillMaxWidth(if (wide) 0.35f else 0.40f).height(10.dp).pxShimmer(RoundedCornerShape(4.dp)))
        }
    }
}

/** Grelha não-lazy (usa os mesmos breakpoints do `.content-grid`) para skeletons. */
@Composable
fun PxFlowGrid(
    count: Int,
    modifier: Modifier = Modifier,
    spec: GridSpec = rememberGridSpec(),
    cell: @Composable (Int) -> Unit,
) {
    BoxWithConstraints(modifier.fillMaxWidth()) {
        val cols = spec.columnsFor(maxWidth)
        val rows = (count + cols - 1) / cols
        Column(verticalArrangement = Arrangement.spacedBy(spec.gap)) {
            repeat(rows) { r ->
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(spec.gap)) {
                    for (c in 0 until cols) {
                        val idx = r * cols + c
                        Box(Modifier.weight(1f)) { if (idx < count) cell(idx) }
                    }
                }
            }
        }
    }
}

/** `ContentGridSkeleton` de components/ui/Skeleton.tsx. */
@Composable
fun PxContentGridSkeleton(count: Int = 12, withHeader: Boolean = false, modifier: Modifier = Modifier) {
    Column(modifier.then(if (withHeader) Modifier.padding(bottom = 34.dp) else Modifier)) {
        if (withHeader) {
            Box(Modifier.padding(bottom = 12.dp).size(width = 140.dp, height = 18.dp).pxShimmer(RoundedCornerShape(4.dp)))
        }
        PxFlowGrid(count) { PxContentCardSkeleton() }
    }
}

/** `TrendingSkeleton` — cabeçalho + linha de mini séries (flex 0 0 clamp(190px,17vw,240px); <=600px 46vw). */
@Composable
fun PxTrendingSkeleton(count: Int = 8, modifier: Modifier = Modifier) {
    val w = LocalConfiguration.current.screenWidthDp
    val compact = w <= 600
    val slideW = if (compact) (w * 0.46f).dp else (w * 0.17f).coerceIn(190f, 240f).dp
    Column(modifier.fillMaxWidth().padding(bottom = 34.dp)) {
        Box(Modifier.padding(bottom = 12.dp).size(width = 120.dp, height = 18.dp).pxShimmer(RoundedCornerShape(4.dp)))
        Row(
            Modifier.fillMaxWidth().graphicsLayer { clip = true },
            horizontalArrangement = Arrangement.spacedBy(if (compact) 8.dp else 12.dp),
        ) {
            repeat(count) { PxContentCardSkeleton(Modifier.width(slideW), wide = true) }
        }
    }
}

// ───────────────────────── .tabs / .tab / .card ─────────────────────────

/** `.tabs` + `.tab(.active)`: sublinhado de 2px, min-height 42, scroll horizontal, margin-bottom 22 (via [bottomGap]). */
@Composable
fun PxTabs(
    labels: List<String>,
    selected: Int,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier,
    bottomGap: Dp = 22.dp,
) {
    val scroll = androidx.compose.foundation.rememberScrollState()
    androidx.compose.foundation.layout.Row(
        modifier
            .fillMaxWidth()
            .padding(bottom = bottomGap)
            .drawBehind {
                drawLine(Px.Border, Offset(0f, size.height - 0.5.dp.toPx()), Offset(size.width, size.height - 0.5.dp.toPx()), 1.dp.toPx())
            }
            .horizontalScroll(scroll),
        horizontalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        labels.forEachIndexed { i, label ->
            val active = i == selected
            Box(
                Modifier
                    .heightIn(min = 42.dp)
                    .pxTap { onSelect(i) }
                    .drawBehind {
                        if (active) drawLine(Px.Primary, Offset(0f, size.height - 1.dp.toPx()), Offset(size.width, size.height - 1.dp.toPx()), 2.dp.toPx())
                    }
                    .padding(horizontal = 17.dp, vertical = 9.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(label, color = if (active) Px.Primary else Px.TextMuted, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, maxLines = 1)
            }
        }
    }
}

/** `.card`: fundo --color-card-bg, borda, raio 12. */
@Composable
fun PxCard(modifier: Modifier = Modifier, padding: Dp = 22.dp, content: @Composable androidx.compose.foundation.layout.ColumnScope.() -> Unit) {
    val shape = RoundedCornerShape(Px.Radius)
    Column(modifier.fillMaxWidth().clip(shape).background(Px.CardBg).border(1.dp, Px.Border, shape).padding(padding), content = content)
}

// ───────────────────────── formulários / alertas ─────────────────────────

/** `.form-group` + `.form-label` + `.form-input` (44px) / textarea; [helper] = `.form-helper`. */
@Composable
fun PxField(
    label: String,
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    labelSuffix: String? = null,
    helper: String? = null,
    placeholder: String? = null,
    enabled: Boolean = true,
    password: Boolean = false,
    multiline: Boolean = false,
    keyboardType: androidx.compose.ui.text.input.KeyboardType = androidx.compose.ui.text.input.KeyboardType.Text,
    maxLength: Int? = null,
) {
    val shape = RoundedCornerShape(Px.RadiusSm)
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(5.dp)) {
        Row {
            Text(label, color = Px.TextMuted, fontSize = 12.32.sp, fontWeight = FontWeight.SemiBold)
            if (labelSuffix != null) Text(" ($labelSuffix)", color = Px.TextMuted, fontSize = 12.32.sp)
        }
        androidx.compose.foundation.text.BasicTextField(
            value = value,
            onValueChange = { v -> onValueChange(if (maxLength != null) v.take(maxLength) else v) },
            enabled = enabled,
            singleLine = !multiline,
            minLines = if (multiline) 3 else 1,
            textStyle = TextStyle(fontFamily = Poppins, fontSize = 14.4.sp, color = Px.TextLight),
            cursorBrush = androidx.compose.ui.graphics.SolidColor(Px.TextLight),
            visualTransformation = if (password) androidx.compose.ui.text.input.PasswordVisualTransformation()
            else androidx.compose.ui.text.input.VisualTransformation.None,
            keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(
                keyboardType = if (password) androidx.compose.ui.text.input.KeyboardType.Password else keyboardType,
                autoCorrect = false,
            ),
            modifier = Modifier.fillMaxWidth().alpha(if (enabled) 1f else 0.45f),
            decorationBox = { inner ->
                Box(
                    Modifier.fillMaxWidth()
                        .then(if (multiline) Modifier.heightIn(min = 90.dp) else Modifier.height(44.dp))
                        .clip(shape).background(Color(0x0DFFFFFF)).border(1.dp, Px.Border, shape)
                        .padding(horizontal = 14.dp, vertical = if (multiline) 10.dp else 0.dp),
                    contentAlignment = if (multiline) Alignment.TopStart else Alignment.CenterStart,
                ) {
                    if (value.isEmpty() && placeholder != null) {
                        Text(placeholder, color = Px.TextMuted.copy(alpha = 0.6f), fontSize = 14.4.sp, maxLines = 1)
                    }
                    inner()
                }
            },
        )
        if (helper != null) Text(helper, color = Px.TextMuted, fontSize = 11.36.sp)
    }
}

enum class PxAlertKind { Error, Success, Info, Warning }

/** `.alert .alert-*` (padding 11/14, radius 6, margin-bottom 14). */
@Composable
fun PxAlert(kind: PxAlertKind, modifier: Modifier = Modifier, content: @Composable androidx.compose.foundation.layout.ColumnScope.() -> Unit) {
    val (bg, fg, bd) = when (kind) {
        PxAlertKind.Error -> Triple(Color(0x14E50914), Color(0xFFFF7070), Color(0x2EE50914))
        PxAlertKind.Success -> Triple(Color(0x141CE783), Px.Secondary, Color(0x2E1CE783))
        PxAlertKind.Info -> Triple(Color(0x1400A8FF), Color(0xFF5CC4F8), Color(0x2E00A8FF))
        PxAlertKind.Warning -> Triple(Color(0x14FFB830), Color(0xFFFFB830), Color(0x2EFFB830))
    }
    val shape = RoundedCornerShape(Px.RadiusSm)
    Column(
        modifier.fillMaxWidth().clip(shape).background(bg).border(1.dp, bd, shape).padding(horizontal = 14.dp, vertical = 11.dp),
    ) {
        androidx.compose.runtime.CompositionLocalProvider(androidx.compose.material3.LocalContentColor provides fg) { content() }
    }
}

/** `.card-header` + `.card-title` + `.card-body` (card com cabeçalho). */
@Composable
fun PxCardWithHeader(title: String, modifier: Modifier = Modifier, content: @Composable androidx.compose.foundation.layout.ColumnScope.() -> Unit) {
    val shape = RoundedCornerShape(Px.Radius)
    Column(modifier.fillMaxWidth().clip(shape).background(Px.CardBg).border(1.dp, Px.Border, shape)) {
        Box(
            Modifier.fillMaxWidth().drawBehind {
                drawLine(Px.Border, Offset(0f, size.height - 0.5.dp.toPx()), Offset(size.width, size.height - 0.5.dp.toPx()), 1.dp.toPx())
            }.padding(horizontal = 17.dp, vertical = 13.dp)
        ) { Text(title, fontFamily = Montserrat, fontWeight = FontWeight.ExtraBold, fontSize = 14.72.sp, color = Px.TextLight) }
        Column(Modifier.padding(17.dp), content = content)
    }
}

/** Avatar circular: fundo branco com a(s) inicial(is) a preto (pedido explícito). */
@Composable
fun PxAvatar(text: String, size: Dp, fontSize: androidx.compose.ui.unit.TextUnit, modifier: Modifier = Modifier, display: Boolean = true) {
    Box(
        modifier.size(size).clip(CircleShape)
            .background(Color.White),
        contentAlignment = Alignment.Center,
    ) {
        Text(text, color = Color.Black, fontFamily = if (display) Montserrat else Poppins, fontWeight = FontWeight.Black, fontSize = fontSize)
    }
}
