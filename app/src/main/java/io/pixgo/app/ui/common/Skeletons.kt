package io.pixgo.app.ui.common

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.unit.dp
import io.pixgo.app.ui.theme.Px

/*
 * Skeletons partilhados (mesmo shimmer `.skeleton` do globals.css). Substituem
 * os spinners nas páginas — pedido explícito: "spinners na página de player são
 * horríveis". O ÚNICO spinner que fica é o de buffering do próprio player.
 */

/** Área do vídeo enquanto o handshake/stream ainda não chegou (preenche o pai). */
@Composable
fun PxPlayerSkeleton(modifier: Modifier = Modifier) {
    Box(modifier.fillMaxSize().pxShimmer(RectangleShape))
}

/** Barra de texto/forma genérica com shimmer. */
@Composable
fun PxSkeletonBar(modifier: Modifier = Modifier, height: androidx.compose.ui.unit.Dp = 12.dp, radius: androidx.compose.ui.unit.Dp = 4.dp) {
    Box(modifier.height(height).pxShimmer(RoundedCornerShape(radius)))
}

/**
 * Esqueleto da página Watch (tudo o que fica ABAIXO do player): título, chips,
 * botões, descrição, cartão de episódios e recomendados — mesma geometria da
 * página real para não haver "salto" de layout quando o conteúdo chega.
 */
@Composable
fun PxWatchDetailsSkeleton(modifier: Modifier = Modifier) {
    Column(modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
        PxSkeletonBar(Modifier.fillMaxWidth(0.62f), height = 13.dp)
        Box(Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(7.dp)) {
            PxSkeletonBar(Modifier.width(46.dp), height = 18.dp, radius = 99.dp)
            PxSkeletonBar(Modifier.width(52.dp), height = 18.dp, radius = 99.dp)
        }
        Box(Modifier.height(14.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(7.dp)) {
            PxSkeletonBar(Modifier.width(118.dp), height = 34.dp, radius = 8.dp)
            PxSkeletonBar(Modifier.width(92.dp), height = 34.dp, radius = 8.dp)
            PxSkeletonBar(Modifier.width(38.dp), height = 34.dp, radius = 8.dp)
        }
        Box(Modifier.height(14.dp))
        val shape = RoundedCornerShape(12.dp)
        Column(
            Modifier.fillMaxWidth().clip(shape).background(Px.CardBg).border(1.dp, Px.Border, shape)
                .padding(horizontal = 14.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            PxSkeletonBar(Modifier.fillMaxWidth(), height = 10.dp)
            PxSkeletonBar(Modifier.fillMaxWidth(0.78f), height = 10.dp)
        }
        Box(Modifier.height(18.dp))
        PxSkeletonBar(Modifier.width(110.dp), height = 14.dp)
        Box(Modifier.height(12.dp))
        repeat(3) {
            Row(Modifier.padding(vertical = 6.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Box(Modifier.size(width = 120.dp, height = 68.dp).pxShimmer(RoundedCornerShape(6.dp)))
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    PxSkeletonBar(Modifier.fillMaxWidth(0.9f), height = 11.dp)
                    PxSkeletonBar(Modifier.fillMaxWidth(0.55f), height = 11.dp)
                    PxSkeletonBar(Modifier.fillMaxWidth(0.3f), height = 9.dp)
                }
            }
        }
    }
}

/** Cartões de planos em esqueleto (mesma altura aproximada dos reais). */
@Composable
fun PxPlanCardSkeleton(modifier: Modifier = Modifier) {
    val shape = RoundedCornerShape(Px.Radius)
    Column(
        modifier.clip(shape).background(Px.CardBg).border(2.dp, Px.Border, shape).padding(22.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        PxSkeletonBar(Modifier.width(110.dp), height = 16.dp)
        PxSkeletonBar(Modifier.width(150.dp), height = 30.dp, radius = 6.dp)
        Box(Modifier.height(2.dp))
        repeat(4) { PxSkeletonBar(Modifier.fillMaxWidth(if (it % 2 == 0) 0.9f else 0.7f), height = 11.dp) }
        Box(Modifier.height(4.dp))
        PxSkeletonBar(Modifier.fillMaxWidth(), height = 40.dp, radius = Px.RadiusSm)
    }
}

@Composable
fun PxFullSkeleton(modifier: Modifier = Modifier) {
    Box(modifier.fillMaxSize().pxShimmer(RectangleShape))
}
