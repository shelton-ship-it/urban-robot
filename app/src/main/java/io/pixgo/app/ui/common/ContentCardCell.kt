package io.pixgo.app.ui.common

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Movie
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import io.pixgo.app.data.i18n.LocalTranslator
import io.pixgo.app.ui.theme.Px

/**
 * Réplica 1:1 de components/ui/ContentCard.tsx (+ .content-card/.content-thumb/
 * .content-info/.content-type-bar/.content-progress/card-action-btn em
 * globals.css, incluindo as reduções do breakpoint <=768px):
 *  - thumb 2:3 (ou 16:9 quando wide) com poster, placeholder gradiente
 *    (MovieIcon + título), barra de tipo colorida de 3px no topo, badge de
 *    rating dourado top-right, badge de tipo traduzido (t(`catalog.${type}`))
 *    bottom-left, barra de progresso 3px;
 *  - info: padding 8/10/10 (<=768px: 6/8/8; wide: 7/9/8), título 0.8rem/600
 *    duas linhas (<=768px 0.74rem), meta com ano + rating (#ffd700);
 *  - ações: botões 32×32 (<=768px 28×28) radius 6, separador superior 1px —
 *    só aparecem quando os callbacks são passados.
 * Hover-overlay de play NÃO é replicado: no web só existe sob
 * @media (hover:hover) and (pointer:fine) (ver comentário em ContentCard.tsx).
 */

/** TYPE_COLORS literal de ContentCard.tsx. */
private val TYPE_COLORS = mapOf(
    "movie" to Color(0xFFE50914),
    "series" to Color(0xFFFF6B00),
    "anime" to Color(0xFFFF0080),
    "documentary" to Color(0xFF00A8FF),
    "dorama" to Color(0xFF9C27B0),
    "channel" to Color(0xFF1CE783)
)

private fun fmtRating(r: Double?): String? =
    if (r == null || r <= 0.0) null else String.format(java.util.Locale.US, "%.1f", r)

@Composable
fun ContentCardCell(
    title: String,
    posterUrl: String?,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    year: Int? = null,
    type: String? = null,
    rating: Double? = null,
    progress: Double? = null,
    inList: Boolean? = null,
    wide: Boolean = false,
    typeLabel: String? = null,
    onAddToList: (() -> Unit)? = null,
    onShare: (() -> Unit)? = null
) {
    val t = LocalTranslator.current
    val compact = LocalConfiguration.current.screenWidthDp <= 768
    val typeColor = if (type != null) TYPE_COLORS[type] ?: Px.Primary else Px.Primary
    val ratingStr = fmtRating(rating)
    val shape = RoundedCornerShape(Px.Radius)

    // .content-info padding / tipografia (+ overrides de .content-card--wide e <=768px)
    val infoStart: Int; val infoTop: Int; val infoEnd: Int; val infoBottom: Int
    when {
        wide -> { infoStart = 9; infoTop = 7; infoEnd = 9; infoBottom = 8 }
        compact -> { infoStart = 8; infoTop = 6; infoEnd = 8; infoBottom = 8 }
        else -> { infoStart = 10; infoTop = 8; infoEnd = 10; infoBottom = 10 }
    }
    val titleSize = when { wide -> 12.48.sp; compact -> 11.84.sp; else -> 12.8.sp }
    val titleLine = if (compact) 1.3f else 1.4f
    val titleGap = if (compact) 3.dp else 4.dp
    val metaSize = if (compact) 10.4.sp else 11.2.sp
    val metaGap = if (compact) 5.dp else 7.dp
    val actionTop = if (compact) 5.dp else 7.dp
    val actionBtn = if (compact) 28.dp else 32.dp

    Column(
        modifier
            .clip(shape)
            .background(Px.CardBg)
            .border(1.dp, Px.Border, shape)
            .pxTap(onClick = onClick)
    ) {
        // ── .content-thumb (aspect 2/3; wide → 16/9) ────────────────────────
        Box(
            Modifier
                .fillMaxWidth()
                .aspectRatio(if (wide) 16f / 9f else 2f / 3f)
                .background(Px.BgDarker)
        ) {
            if (!posterUrl.isNullOrBlank()) {
                AsyncImage(
                    model = posterUrl,
                    contentDescription = title,
                    modifier = Modifier.fillMaxSize(),
                    contentScale = ContentScale.Crop
                )
            } else {
                // .content-thumb-placeholder: gradiente 135deg #1a1a20→#0d0d12
                Column(
                    Modifier
                        .fillMaxSize()
                        .background(Brush.linearGradient(listOf(Color(0xFF1A1A20), Color(0xFF0D0D12)))),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center
                ) {
                    Icon(
                        Icons.Filled.Movie, null,
                        tint = Px.TextMuted,
                        modifier = Modifier.size(if (wide) 26.dp else 32.dp)
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(
                        title,
                        fontSize = 11.52.sp,
                        color = Px.TextMuted,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.padding(horizontal = 6.dp)
                    )
                }
            }

            // .content-type-bar — 3px no topo, cor do type
            Box(
                Modifier
                    .fillMaxWidth()
                    .height(3.dp)
                    .align(Alignment.TopStart)
                    .background(typeColor)
            )

            // Badge de rating — top 8 / right 8, rgba(0,0,0,0.78), radius 4, padding 2×6
            // Cards verticais: rating e ano SAEM (pedido explícito; o ano fica na página de
            // Watch). Os cards `wide` (mini séries) mantêm-se exactamente como estavam.
            if (wide && ratingStr != null) {
                Row(
                    Modifier
                        .align(Alignment.TopEnd)
                        .padding(8.dp)
                        .background(Color(0xC7000000), RoundedCornerShape(4.dp))
                        .padding(horizontal = 6.dp, vertical = 2.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(3.dp)
                ) {
                    Icon(Icons.Filled.Star, null, tint = Color(0xFFFFD700), modifier = Modifier.size(11.dp))
                    Text(ratingStr, fontSize = 10.88.sp, color = Color.White, fontWeight = FontWeight.SemiBold)
                }
            }

            // Badge de tipo — t(`catalog.${type}`); bottom 12 se houver progress, senão 8
            if (type != null) {
                val label = typeLabel ?: t.t("catalog.$type").takeIf { it != "catalog.$type" } ?: type
                Text(
                    text = label,
                    fontSize = 9.92.sp,
                    fontWeight = FontWeight.Bold,
                    color = Color.White,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier
                        .align(Alignment.BottomStart)
                        .padding(start = 8.dp, end = 8.dp, bottom = if ((progress ?: 0.0) > 0.0) 12.dp else 8.dp)
                        .background(Color(0xB8000000), RoundedCornerShape(4.dp))
                        .padding(horizontal = 7.dp, vertical = 2.dp)
                )
            }

            // .content-progress — 3px, fundo rgba(255,255,255,0.14), fill primário
            if (progress != null && progress > 0.0) {
                Box(
                    Modifier
                        .fillMaxWidth()
                        .height(3.dp)
                        .align(Alignment.BottomCenter)
                        .background(Color(0x24FFFFFF))
                ) {
                    Box(
                        Modifier
                            .fillMaxWidth(minOf(progress, 100.0).toFloat() / 100f)
                            .height(3.dp)
                            .background(Px.Primary)
                    )
                }
            }
        }

        // ── .content-info ───────────────────────────────────────────────────
        Column(Modifier.padding(start = infoStart.dp, end = infoEnd.dp, top = infoTop.dp, bottom = infoBottom.dp)) {
            // .content-title — 600, clamp 2 linhas
            Text(
                title,
                fontSize = titleSize,
                fontWeight = FontWeight.SemiBold,
                color = Px.TextTitle,
                lineHeight = (titleSize.value * titleLine).sp,
                maxLines = 2,
                // Altura FIXA nos cards verticais: o título reserva sempre 2 linhas,
                // por isso um título curto não encolhe o card (antes, num mesmo
                // separador — Documentários, Filmes... — havia cards de alturas
                // diferentes). Os cards "wide" (mini séries 16:9) ficam EXACTAMENTE
                // como estavam: 1 linha mínima, sem qualquer normalização.
                minLines = if (wide) 1 else 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(bottom = titleGap)
            )

            // .content-meta — ano + rating (#ffd700)
            if (wide && (year != null || ratingStr != null)) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(metaGap)
                ) {
                    if (year != null) Text(year.toString(), fontSize = metaSize, color = Px.TextMuted)
                    if (ratingStr != null) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(3.dp)
                        ) {
                            Icon(Icons.Filled.Star, null, tint = Color(0xFFFFD700), modifier = Modifier.size(11.dp))
                            Text(ratingStr, fontSize = metaSize, color = Color(0xFFFFD700))
                        }
                    }
                }
            }

            // .content-actions — margin-top/padding-top + border-top 1px
            if (onAddToList != null || onShare != null) {
                Row(
                    Modifier
                        .fillMaxWidth()
                        .padding(top = actionTop)
                        .drawBehind {
                            drawLine(Px.Border, Offset(0f, 0.5.dp.toPx()), Offset(size.width, 0.5.dp.toPx()), 1.dp.toPx())
                        }
                        .padding(top = actionTop),
                    horizontalArrangement = Arrangement.spacedBy(2.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    if (onAddToList != null) {
                        CardActionButton(active = inList == true, size = actionBtn, onClick = onAddToList) {
                            if (inList == true) Icon(Icons.Filled.Check, "Remover da lista", Modifier.size(16.dp))
                            else Icon(Icons.Filled.Add, "Adicionar à lista", Modifier.size(16.dp))
                        }
                    }
                    if (onShare != null) {
                        CardActionButton(active = false, size = actionBtn, onClick = onShare) {
                            Icon(Icons.Filled.Share, "Compartilhar", Modifier.size(15.dp))
                        }
                    }
                }
            }
        }
    }
}

/** `.card-action-btn` (+ `.active` → cor primária). A cor chega aos ícones via LocalContentColor. */
@Composable
private fun CardActionButton(
    active: Boolean,
    size: androidx.compose.ui.unit.Dp,
    onClick: () -> Unit,
    content: @Composable () -> Unit
) {
    Box(
        Modifier
            .size(size)
            .clip(RoundedCornerShape(6.dp))
            .pxTap(onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        CompositionLocalProvider(LocalContentColor provides if (active) Px.Primary else Px.TextMuted) {
            content()
        }
    }
}
