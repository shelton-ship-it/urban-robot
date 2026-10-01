package io.pixgo.app.ui.legal

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import io.pixgo.app.data.legal.LegalRepository
import io.pixgo.app.data.model.LegalResponse

/**
 * Réplica de app/main/legal/page.tsx: o texto vem SEMPRE do backend
 * (GET /api/legal/:lang, sem autenticação) — não há bundle local
 * embutido aqui para usar de fallback (não o temos, e o comentário do
 * próprio ficheiro original diz que o backend já é a fonte de verdade).
 * Se o pedido falhar, mostra-se um erro em vez de texto inventado.
 */
private data class LegalTab(val prefix: String, val label: String, val fallbackCount: Int)

private val LEGAL_TABS = listOf(
    LegalTab("notice", "Aviso Legal", 7),
    LegalTab("upload", "Envio de Conteúdo", 12),
    LegalTab("tos", "Termos de Serviço", 32),
    LegalTab("priv", "Privacidade", 19),
    LegalTab("cookies", "Cookies", 17),
    LegalTab("sec", "Segurança", 16),
    LegalTab("contact", "Contacto", 6),
    LegalTab("ipr", "Direitos de Autor", 22),
    LegalTab("dmca", "Notificação e Remoção", 28),
    LegalTab("counter", "Contra-Notificação", 26),
)

private val SECTION_KEY_RE = Regex("^([a-z]+?)(\\d+)t$")

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LegalScreen(legalRepository: LegalRepository, uiLang: String) {
    var data by remember { mutableStateOf<LegalResponse?>(null) }
    var error by remember { mutableStateOf(false) }
    var loading by remember { mutableStateOf(true) }
    var tab by remember { mutableStateOf(LEGAL_TABS[2]) } // "tos" por defeito, igual ao original

    LaunchedEffect(uiLang) {
        loading = true
        error = false
        val resp = legalRepository.fetch(uiLang)
        if (resp == null) error = true else data = resp
        loading = false
    }

    Column(Modifier.fillMaxSize().padding(16.dp)) {
        Text("Informação Legal", style = MaterialTheme.typography.headlineSmall)
        Spacer(Modifier.height(12.dp))

        ScrollableTabRow(selectedTabIndex = LEGAL_TABS.indexOf(tab)) {
            LEGAL_TABS.forEach { t ->
                Tab(selected = t == tab, onClick = { tab = t }, text = { Text(t.label) })
            }
        }
        Spacer(Modifier.height(12.dp))

        when {
            loading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
            error || data == null -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text("Não foi possível carregar o texto legal. Verifique a ligação.")
            }
            else -> {
                val legal = data!!.legal
                // Conta real de secções pelo padrão "{prefix}{n}t", igual ao
                // useLegalContent() original — nunca inventa um número fixo
                // quando a resposta já diz quantas secções existem.
                val counts = remember(legal) {
                    val map = mutableMapOf<String, Int>()
                    for (key in legal.keys) {
                        val m = SECTION_KEY_RE.find(key) ?: continue
                        val prefix = m.groupValues[1]
                        val n = m.groupValues[2].toIntOrNull() ?: continue
                        map[prefix] = maxOf(map[prefix] ?: 0, n)
                    }
                    map
                }
                val count = counts[tab.prefix] ?: tab.fallbackCount

                Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
                    for (n in 1..count) {
                        val title = legal["${tab.prefix}${n}t"]
                        val body = legal["${tab.prefix}${n}"]
                        if (title != null || body != null) {
                            Text(title ?: "", fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleSmall)
                            Text(body ?: "", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(top = 4.dp, bottom = 16.dp))
                        }
                    }
                    data!!.updatedAt?.let {
                        Text(
                            "Última atualização: $it",
                            style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier.padding(top = 8.dp)
                        )
                    }
                }
            }
        }
    }
}
