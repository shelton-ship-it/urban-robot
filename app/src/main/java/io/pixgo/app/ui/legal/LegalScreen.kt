package io.pixgo.app.ui.legal

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Size
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.foundation.text.ClickableText
import androidx.compose.ui.platform.LocalContext
import android.content.Intent
import android.net.Uri
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.pixgo.app.data.i18n.LocalTranslator
import io.pixgo.app.data.legal.LegalRepository
import io.pixgo.app.data.model.LegalResponse
import io.pixgo.app.ui.common.PxBtnSize
import io.pixgo.app.ui.common.PxButton
import io.pixgo.app.ui.common.PxCard
import io.pixgo.app.ui.common.PxPageHeader
import io.pixgo.app.ui.common.PxPageLoading
import io.pixgo.app.ui.common.PxTabs
import io.pixgo.app.ui.common.pagePadding
import io.pixgo.app.ui.theme.Montserrat
import io.pixgo.app.ui.theme.Px

/**
 * Réplica de app/main/legal/page.tsx: título, abas (.tabs), cartão com o texto.
 * O texto vem de GET /api/legal/:lang; se falhar, usa-se o bundle embutido
 * (legal.* nos mesmos pt/en/es.json), exactamente como o fallback do web.
 * Os e-mails do texto são destacados e abrem o cliente de e-mail.
 */
private data class LegalTab(val prefix: String, val labelKey: String, val titleKey: String, val count: Int)

private val LEGAL_TABS = listOf(
    LegalTab("notice", "legal.tabNotice", "legal.noticeT", 7),
    LegalTab("upload", "legal.tabUpload", "legal.uploadT", 12),
    LegalTab("tos", "legal.tabTos", "legal.tosT", 32),
    LegalTab("priv", "legal.tabPrivacy", "legal.privacyT", 19),
    LegalTab("cookies", "legal.tabCookies", "legal.cookiesT", 17),
    LegalTab("sec", "legal.tabSecurity", "legal.securityT", 16),
    LegalTab("contact", "legal.tabContact", "legal.contactT", 6),
    LegalTab("ipr", "legal.tabIpr", "legal.iprT", 22),
    LegalTab("dmca", "legal.tabDmca", "legal.dmcaT", 28),
    LegalTab("counter", "legal.tabCounter", "legal.counterT", 26),
)

/** Chaves de aba do web (?tab=): privacy→priv, security→sec, o resto igual ao prefixo. */
private fun legalTabIndexForKey(key: String?): Int? {
    if (key == null) return null
    val prefix = when (key) { "privacy" -> "priv"; "security" -> "sec"; else -> key }
    return LEGAL_TABS.indexOfFirst { it.prefix == prefix }.takeIf { it >= 0 }
}

private val SECTION_KEY_RE = Regex("^([a-z]+?)(\\d+)t$")
private val EMAIL_RE = Regex("[a-zA-Z0-9._%+-]+@[a-zA-Z0-9.-]+\\.[a-zA-Z]{2,}")

private fun legalBody(text: String): AnnotatedString = buildAnnotatedString {
    var last = 0
    for (m in EMAIL_RE.findAll(text)) {
        append(text.substring(last, m.range.first))
        pushStringAnnotation("email", m.value)
        withStyle(SpanStyle(fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold, color = Px.Primary)) { append(m.value) }
        pop()
        last = m.range.last + 1
    }
    append(text.substring(last))
}

@Composable
fun LegalScreen(
    legalRepository: LegalRepository,
    uiLang: String,
    initialTab: String? = null,
    onReportCopyright: () -> Unit = {},
) {
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(pagePadding())
    ) {
        LegalContent(legalRepository, uiLang, initialTab, onReportCopyright)
    }
}

/**
 * Conteúdo da página legal (sem scroll próprio), reutilizado dentro do
 * CopyrightShell na versão pública de /legal (app/legal/page.tsx).
 * [initialTab] = ?tab= do web; [onReportCopyright] = botão do banner de
 * denúncia (abas notice/ipr/dmca → /copyright).
 */
@Composable
fun LegalContent(
    legalRepository: LegalRepository,
    uiLang: String,
    initialTab: String? = null,
    onReportCopyright: () -> Unit = {},
) {
    val t = LocalTranslator.current
    val ctx = LocalContext.current
    var data by remember { mutableStateOf<LegalResponse?>(null) }
    var loading by remember { mutableStateOf(true) }
    var tabIdx by remember(initialTab) { mutableStateOf(legalTabIndexForKey(initialTab) ?: 2) } // "tos" por defeito, igual ao original
    val tab = LEGAL_TABS[tabIdx]

    LaunchedEffect(uiLang) {
        loading = true
        data = runCatching { legalRepository.fetch(uiLang) }.getOrNull()
        loading = false
    }

    val legal = data?.legal.orEmpty()
    // Contagem real de secções pelo padrão "{prefix}{n}t" (useLegalContent do original).
    val counts = remember(legal) {
        val map = mutableMapOf<String, Int>()
        for (key in legal.keys) {
            val m = SECTION_KEY_RE.find(key) ?: continue
            val n = m.groupValues[2].toIntOrNull() ?: continue
            map[m.groupValues[1]] = maxOf(map[m.groupValues[1]] ?: 0, n)
        }
        map
    }
    fun text(key: String): String = legal[key.removePrefix("legal.")] ?: t.t(key)
    val count = counts[tab.prefix] ?: tab.count

    run {
        Column(Modifier.widthIn(max = 760.dp)) {
            PxPageHeader(title = t.t("legal.title"))
            PxTabs(
                labels = LEGAL_TABS.map { t.t(it.labelKey) },
                selected = tabIdx,
                onSelect = { tabIdx = it },
                bottomGap = 16.dp,
            )
            val showBanner = tab.prefix == "notice" || tab.prefix == "ipr" || tab.prefix == "dmca"
            if (showBanner) {
                CopyrightBanner(
                    title = t.t("copyright.legalBannerTitle"),
                    body = t.t("copyright.legalBannerBody"),
                    button = t.t("copyright.legalBannerButton"),
                    onClick = onReportCopyright,
                )
                Spacer(Modifier.height(16.dp))
            }
            if (loading && data == null) {
                PxPageLoading()
            } else {
                PxCard {
                    Text(
                        text(tab.titleKey),
                        fontFamily = Montserrat, fontWeight = FontWeight.ExtraBold, fontSize = 16.8.sp,
                        color = Px.TextLight, modifier = Modifier.padding(bottom = 18.dp),
                    )
                    for (n in 1..count) {
                        Column(Modifier.fillMaxWidth().padding(bottom = 22.dp)) {
                            Text(
                                text("legal.${tab.prefix}${n}t"),
                                fontFamily = Montserrat, fontWeight = FontWeight.ExtraBold, fontSize = 14.08.sp,
                                color = Px.TextTitle, modifier = Modifier.padding(bottom = 7.dp),
                            )
                            val body = legalBody(text("legal.${tab.prefix}$n"))
                            ClickableText(
                                body,
                                style = TextStyle(fontSize = 13.44.sp, lineHeight = 23.52.sp, color = Px.TextMuted),
                                onClick = { off ->
                                    body.getStringAnnotations("email", off, off).firstOrNull()?.let {
                                        runCatching { ctx.startActivity(Intent(Intent.ACTION_SENDTO, Uri.parse("mailto:${it.item}"))) }
                                    }
                                },
                            )
                        }
                    }
                    data?.updatedAt?.let {
                        Text(
                            "${t.t("legal.lastUpdated").takeIf { s -> s != "legal.lastUpdated" } ?: "Última atualização"}: $it",
                            fontSize = 11.2.sp, color = Px.TextMuted.copy(alpha = 0.7f),
                            modifier = Modifier.padding(top = 18.dp),
                        )
                    }
                }
            }
        }
    }
}

/** `.cr-banner`: cartão com barra primária à esquerda, título, texto e botão (quebra de linha em ecrãs estreitos). */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun CopyrightBanner(title: String, body: String, button: String, onClick: () -> Unit) {
    val shape = RoundedCornerShape(Px.RadiusSm)
    FlowRow(
        Modifier.fillMaxWidth()
            .clip(shape).background(Px.CardBg).border(1.dp, Px.Border, shape)
            .drawBehind { drawRect(Px.Primary, size = Size(3.dp.toPx(), size.height)) }
            .padding(horizontal = 16.dp, vertical = 14.dp),
        horizontalArrangement = Arrangement.spacedBy(14.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Column(Modifier.widthIn(max = 520.dp)) {
            Text(title, color = Px.TextTitle, fontSize = 13.44.sp, fontWeight = FontWeight.Bold)
            Text(body, color = Px.TextMuted, fontSize = 12.32.sp, lineHeight = 19.7.sp, modifier = Modifier.padding(top = 2.dp))
        }
        PxButton(button, onClick = onClick, size = PxBtnSize.Sm)
    }
}
