package io.pixgo.app.ui.downloads

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.Movie
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.WarningAmber
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import io.pixgo.app.data.auth.AuthState
import io.pixgo.app.data.download.DownloadEngine
import io.pixgo.app.data.download.DownloadMeta
import io.pixgo.app.data.download.DownloadStatus
import io.pixgo.app.data.download.DownloadStore
import io.pixgo.app.ui.theme.Px
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Réplica fiel de app/main/downloads/page.tsx (o page.tsx ATIVO — o
 * ficheiro "pages.tsx" ao lado é a versão antiga que nunca era roteada,
 * e o retryActive dela aponta para /main/content/{id}, rota banida; aqui
 * o retry segue pelo motor real). 100% local: lê DownloadStore (equivalente
 * do IndexedDB), sem pedidos de rede. Polling de 1,5s como o original.
 */
@Composable
fun DownloadsScreen(
    authState: AuthState,
    onOpenDownload: (contentId: String, episodeId: String?) -> Unit,
    onUpgrade: () -> Unit,
    onBrowseCatalog: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val store = remember { DownloadStore(context) }
    val engine = remember { DownloadEngine(context) }

    var items by remember { mutableStateOf<List<DownloadMeta>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }

    // Gate idêntico ao original: !!(plan && plan.id !== 'free' && plan.is_active)
    val canDownload = authState.plan?.let { it.id != "free" && it.isActive == true } ?: false

    LaunchedEffect(Unit) {
        while (true) {
            // load() + loadActive() do original num só: allOnce() já reconcilia
            // com o disco e remove expirados (mesma regra expiresAt > now).
            items = store.allOnce()
            loading = false
            delay(1_500L)
        }
    }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 24.dp, vertical = 16.dp)) {
        // page-header: ícone vermelho 24 + título + subtítulo "{n} títulos guardados"
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Icon(Icons.Filled.Download, null, Modifier.size(24.dp), tint = Px.Primary)
            Column {
                Text("Downloads", color = Px.TextTitle, fontWeight = FontWeight.Bold, fontSize = 20.sp)
                Text(
                    "${items.size} ${if (items.size == 1) "título guardado" else "títulos guardados"}",
                    color = Px.TextMuted, fontSize = 13.sp
                )
            }
        }
        Spacer(Modifier.height(16.dp))

        if (!canDownload) {
            Box(
                Modifier.fillMaxWidth()
                    .clip(RoundedCornerShape(10.dp))
                    .background(Color(0x14E50914)) // rgba(229,9,20,0.08)
                    .border(1.dp, Color(0x33E50914), RoundedCornerShape(10.dp)) // 0.2 alpha
                    .padding(horizontal = 18.dp, vertical = 14.dp)
            ) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Icon(Icons.Filled.WarningAmber, null, Modifier.size(20.dp), tint = Px.Primary)
                    Column(Modifier.weight(1f)) {
                        Text("Download disponível nos planos Mensal e Anual", color = Px.TextTitle, fontWeight = FontWeight.Bold, fontSize = 14.sp)
                        Text("Faça upgrade para descarregar conteúdo para assistir offline.", color = Px.TextMuted, fontSize = 13.sp, modifier = Modifier.padding(top = 3.dp))
                    }
                    TextButtonSmall("Ver planos") { onUpgrade() }
                }
            }
            Spacer(Modifier.height(20.dp))
        }

        // Downloads em curso/falhou — barra por item (progress 0-100 real do engine)
        val active = items.filter { it.status != DownloadStatus.COMPLETED }
        if (active.isNotEmpty()) {
            Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                active.forEach { d ->
                    ActiveRow(
                        d = d,
                        onCancel = {
                            // cancelActive(): aborta o motor e apaga meta+bytes
                            engine.cancel(d.key)
                            scope.launch { store.remove(d.key) }
                        },
                        onRetry = {
                            // startDownload novamente: o motor reobtém manifesto
                            // fresco e continua do primeiro segmento em falta.
                            scope.launch { engine.start(d.contentId, d.episodeId, d.title, d.poster) }
                        }
                    )
                }
            }
            Spacer(Modifier.height(22.dp))
        }

        when {
            loading -> Box(Modifier.fillMaxWidth().height(220.dp), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(color = Px.Primary, strokeWidth = 3.dp, modifier = Modifier.size(34.dp))
            }
            items.none { it.status == DownloadStatus.COMPLETED } && active.isEmpty() -> {
                // empty-state idêntico
                Column(Modifier.fillMaxWidth().padding(top = 48.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    Box(
                        Modifier.size(64.dp).clip(CircleShape)
                            .background(Color(0x1FFFFFFF)).border(1.dp, Px.Border, CircleShape),
                        contentAlignment = Alignment.Center
                    ) { Icon(Icons.Filled.Download, null, Modifier.size(28.dp), tint = Px.TextMuted) }
                    Spacer(Modifier.height(14.dp))
                    Text("Sem downloads", color = Px.TextTitle, fontWeight = FontWeight.Bold, fontSize = 16.sp)
                    Spacer(Modifier.height(6.dp))
                    Text(
                        if (canDownload) "Abre um filme ou série e clica em \"Baixar\" para assistir offline."
                        else "Disponível nos planos Mensal e Anual.",
                        color = Px.TextMuted, fontSize = 13.sp
                    )
                    if (canDownload) {
                        Spacer(Modifier.height(16.dp))
                        TextButtonSmall("Explorar catálogo", big = true) { onBrowseCatalog() }
                    }
                }
            }
            else -> {
                val completed = items.filter { it.status == DownloadStatus.COMPLETED }
                // Grid dentro de Column com verticalScroll: altura fixa por linhas
                // (2 colunas adaptativas; ~170dp por linha + folga).
                val rows = (completed.size + 1) / 2
                LazyVerticalGrid(
                    columns = GridCells.Adaptive(minSize = 168.dp),
                    verticalArrangement = Arrangement.spacedBy(14.dp),
                    horizontalArrangement = Arrangement.spacedBy(14.dp),
                    userScrollEnabled = false,
                    modifier = Modifier.fillMaxWidth().height((rows * 190 + 20).dp)
                ) {
                    items(completed, key = { it.key }) { d ->
                        CompletedCard(
                            d = d,
                            onPlay = { onOpenDownload(d.contentId, d.episodeId) },
                            onRemove = { scope.launch { store.remove(d.key) } }
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun ActiveRow(d: DownloadMeta, onCancel: () -> Unit, onRetry: () -> Unit) {
    val isError = d.status == DownloadStatus.ERROR
    Row(
        Modifier.fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(Px.CardBg)
            .border(1.dp, Px.Border, RoundedCornerShape(10.dp))
            .padding(horizontal = 14.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        if (d.poster.isNotBlank()) {
            AsyncImage(model = d.poster, contentDescription = null, modifier = Modifier.width(56.dp).height(32.dp).clip(RoundedCornerShape(6.dp)), contentScale = androidx.compose.ui.layout.ContentScale.Crop)
        } else {
            Box(Modifier.width(56.dp).height(32.dp).clip(RoundedCornerShape(6.dp)).background(Px.BgDarker), contentAlignment = Alignment.Center) {
                Icon(Icons.Filled.Movie, null, Modifier.size(16.dp), tint = Px.TextMuted)
            }
        }
        Column(Modifier.weight(1f)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(d.title, color = Px.TextTitle, fontWeight = FontWeight.Bold, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
                Text(
                    if (isError) "Falhou" else "${d.progress}%",
                    color = if (isError) Color(0xFFFF7070) else Px.TextMuted,
                    fontSize = 12.sp, fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace
                )
            }
            Spacer(Modifier.height(5.dp))
            Box(Modifier.fillMaxWidth().height(4.dp).clip(RoundedCornerShape(2.dp)).background(Color(0x14FFFFFF))) {
                Box(
                    Modifier.fillMaxWidth((d.progress.coerceIn(0, 100)) / 100f).height(4.dp)
                        .clip(RoundedCornerShape(2.dp))
                        .background(if (isError) Color(0xFFFF7070) else Px.Primary)
                )
            }
        }
        if (isError) TextButtonSmall("Repetir") { onRetry() }
        Box(
            Modifier.size(28.dp).clip(RoundedCornerShape(6.dp))
                .background(Color(0x4D000000)).border(1.dp, Px.Border, RoundedCornerShape(6.dp))
                .clickable { onCancel() },
            contentAlignment = Alignment.Center
        ) { Icon(Icons.Filled.Close, "Cancelar download", Modifier.size(15.dp), tint = Px.TextMuted) }
    }
}

@Composable
private fun CompletedCard(d: DownloadMeta, onPlay: () -> Unit, onRemove: () -> Unit) {
    val days = daysLeft(d.expiresAt)
    val expiringSoon = days <= 3
    Box(Modifier.fillMaxWidth()) {
        Column(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp))
                .background(Px.CardBg).border(1.dp, Px.Border, RoundedCornerShape(10.dp))
                .clickable { onPlay() }
        ) {
            Box(Modifier.fillMaxWidth().aspectRatio(16f / 9f).background(Px.BgDarker)) {
                if (d.poster.isNotBlank()) {
                    AsyncImage(model = d.poster, contentDescription = null, modifier = Modifier.fillMaxSize(), contentScale = androidx.compose.ui.layout.ContentScale.Crop)
                } else {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Icon(Icons.Filled.Movie, null, Modifier.size(28.dp), tint = Px.TextMuted)
                    }
                }
                // play overlay sempre visível no toque (media hover:none do original)
                Box(Modifier.matchParentSize().background(Color(0x4D000000)), contentAlignment = Alignment.Center) {
                    Icon(Icons.Filled.PlayArrow, null, Modifier.size(36.dp), tint = Color.White)
                }
                // badge dias restantes (monospace; ⚠ quando <=3)
                Box(
                    Modifier.align(Alignment.BottomEnd).padding(6.dp)
                        .clip(RoundedCornerShape(4.dp)).background(Color(0xC0000000))
                        .padding(horizontal = 6.dp, vertical = 2.dp)
                ) {
                    Text(
                        if (expiringSoon) "⚠ ${days}d" else "${days}d",
                        color = if (expiringSoon) Color(0xFFFF7070) else Color(0xB3FFFFFF),
                        fontSize = 10.sp, fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace
                    )
                }
            }
            Column(Modifier.padding(horizontal = 12.dp, vertical = 10.dp)) {
                Text(d.title, color = Px.TextTitle, fontWeight = FontWeight.Bold, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Row(Modifier.padding(top = 3.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(d.quality.uppercase(), color = Px.TextMuted, fontSize = 11.sp, fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace)
                    Text("Expira ${formatDate(d.expiresAt)}", color = Px.TextMuted, fontSize = 11.sp)
                }
            }
        }
        // remover — sempre visível em toque (dl-remove-btn)
        Box(
            Modifier.align(Alignment.TopEnd).padding(8.dp).size(32.dp)
                .clip(RoundedCornerShape(6.dp)).background(Color(0xCC000000))
                .border(1.dp, Color(0x66E50914), RoundedCornerShape(6.dp))
                .clickable { onRemove() },
            contentAlignment = Alignment.Center
        ) { Icon(Icons.Filled.DeleteOutline, "Remover download", Modifier.size(16.dp), tint = Px.Primary) }
    }
}

@Composable
private fun TextButtonSmall(text: String, big: Boolean = false, onClick: () -> Unit) {
    Box(
        Modifier.clip(RoundedCornerShape(if (big) 8.dp else 6.dp))
            .background(Px.Primary)
            .clickable { onClick() }
            .padding(horizontal = if (big) 18.dp else 12.dp, vertical = if (big) 10.dp else 7.dp)
    ) {
        Text(text, color = Color.White, fontWeight = FontWeight.Bold, fontSize = if (big) 14.sp else 12.sp)
    }
}

private fun daysLeft(expiresAt: String?): Int {
    val iso = expiresAt ?: return 0
    val ms = try { java.time.Instant.parse(iso).toEpochMilli() } catch (e: Exception) { return 0 }
    val diff = ms - System.currentTimeMillis()
    return maxOf(0, Math.ceil(diff / (1000.0 * 60 * 60 * 24)).toInt())
}

private fun formatDate(iso: String?): String {
    val s = iso ?: return "-"
    return try {
        val d = java.time.Instant.parse(s).atZone(java.time.ZoneId.systemDefault()).toLocalDate()
        "%02d/%02d/%04d".format(d.dayOfMonth, d.monthValue, d.year) // toLocaleDateString('pt-BR')
    } catch (e: Exception) { s }
}
