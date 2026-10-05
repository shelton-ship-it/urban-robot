package io.pixgo.app.ui.copyright

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.ArrowForward
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Translate
import androidx.compose.material.icons.outlined.ReportProblem
import androidx.compose.material.icons.outlined.Shield
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.pixgo.app.R
import io.pixgo.app.data.copyright.CopyrightRepository
import io.pixgo.app.data.copyright.DETECTION_KEYS
import io.pixgo.app.data.copyright.DETECTION_METHODS
import io.pixgo.app.data.copyright.EMAIL_RE
import io.pixgo.app.data.copyright.MAX_ITEMS
import io.pixgo.app.data.copyright.PROTOCOL_RE
import io.pixgo.app.data.copyright.PublicReport
import io.pixgo.app.data.copyright.RELATIONSHIPS
import io.pixgo.app.data.copyright.RELATIONSHIP_KEYS
import io.pixgo.app.data.copyright.ReportItemInput
import io.pixgo.app.data.copyright.ReportSubmission
import io.pixgo.app.data.copyright.StoredReport
import io.pixgo.app.data.copyright.SubmitResult
import io.pixgo.app.data.copyright.formatClock
import io.pixgo.app.data.copyright.isHttpUrl
import io.pixgo.app.data.i18n.LocalTranslator
import io.pixgo.app.data.i18n.SUPPORTED_LANGUAGES
import io.pixgo.app.data.legal.LegalRepository
import io.pixgo.app.ui.common.PxBadge
import io.pixgo.app.ui.common.PxBadgeKind
import io.pixgo.app.ui.common.PxBtnSize
import io.pixgo.app.ui.common.PxBtnVariant
import io.pixgo.app.ui.common.PxButton
import io.pixgo.app.ui.common.PxCard
import io.pixgo.app.ui.common.PxField
import io.pixgo.app.ui.common.PxText
import io.pixgo.app.ui.common.pxTap
import io.pixgo.app.ui.legal.LegalContent
import io.pixgo.app.ui.theme.Montserrat
import io.pixgo.app.ui.theme.Px
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.Locale

/**
 * Central de direitos autorais — réplica de app/copyright/{page,response/page,portal/page}.tsx,
 * app/legal/page.tsx e components/copyright/{CopyrightShell,TimeField,StatusBadge}.tsx.
 * Pilha de rotas interna; Voltar do Android desfaz a rota e, na raiz, fecha a central.
 */
sealed class CrRoute {
    object Form : CrRoute()
    data class Response(val id: String?) : CrRoute()
    object Portal : CrRoute()
    data class Legal(val tab: String?) : CrRoute()
}

@Composable
fun CopyrightHost(
    repository: CopyrightRepository,
    legalRepository: LegalRepository,
    langCode: String,
    onSelectLanguage: (String) -> Unit,
    onClose: () -> Unit,
) {
    val stack = remember { mutableStateListOf<CrRoute>(CrRoute.Form) }
    val route = stack.last()
    BackHandler { if (stack.size > 1) stack.removeAt(stack.lastIndex) else onClose() }

    // O web usa router.push entre as páginas: ir para a rota já ativa não empilha de novo.
    fun go(r: CrRoute) {
        if (stack.last()::class == r::class && r !is CrRoute.Legal) return
        stack.add(r)
    }

    val narrow = route is CrRoute.Response || route is CrRoute.Legal
    Shell(
        route = route,
        narrow = narrow,
        langCode = langCode,
        onSelectLanguage = onSelectLanguage,
        onGo = { go(it) },
        onClose = onClose,
    ) {
        when (route) {
            is CrRoute.Form -> FormPage(repository, langCode, onSubmitted = { id -> go(CrRoute.Response(id)) }, onGo = { go(it) })
            is CrRoute.Response -> ResponsePage(repository, route.id, langCode, onGo = { go(it) })
            is CrRoute.Portal -> PortalPage(repository, langCode, onGo = { go(it) })
            is CrRoute.Legal -> LegalContent(
                legalRepository = legalRepository,
                uiLang = langCode,
                initialTab = route.tab,
                onReportCopyright = { go(CrRoute.Form) },
            )
        }
    }
}

// ───────────────────────── Shell (header + container + footer) ─────────────────────────

private val LEGAL_LINKS = listOf(
    "notice" to "legal.tabNotice", "tos" to "legal.tabTos", "privacy" to "legal.tabPrivacy",
    "cookies" to "legal.tabCookies", "upload" to "legal.tabUpload", "security" to "legal.tabSecurity",
    "contact" to "legal.tabContact",
)
private val COPYRIGHT_LEGAL_LINKS = listOf("ipr" to "legal.tabIpr", "dmca" to "legal.tabDmca", "counter" to "legal.tabCounter")
private val BOTTOM_STRIP = listOf("tos", "privacy", "cookies", "notice", "contact")

@Composable
private fun Shell(
    route: CrRoute,
    narrow: Boolean,
    langCode: String,
    onSelectLanguage: (String) -> Unit,
    onGo: (CrRoute) -> Unit,
    onClose: () -> Unit,
    content: @Composable () -> Unit,
) {
    val w = LocalConfiguration.current.screenWidthDp
    Column(Modifier.fillMaxSize().background(Px.BgDark).windowInsetsPadding(WindowInsets.safeDrawing)) {
        CrHeader(route, langCode, onSelectLanguage, onGo, onClose, w)
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState())) {
            // .cr-container: max 1120 (760 narrow); padding 34/24/64 · <=760: 24/16/48
            val hPad = if (w <= 760) 16.dp else 24.dp
            Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.TopCenter) {
                Column(
                    Modifier.widthIn(max = if (narrow) 760.dp else 1120.dp).fillMaxWidth()
                        .padding(start = hPad, end = hPad, top = if (w <= 760) 24.dp else 34.dp, bottom = if (w <= 760) 48.dp else 64.dp)
                ) { content() }
            }
            CrFooter(onGo, w)
        }
    }
}

@Composable
private fun CrHeader(
    route: CrRoute, langCode: String, onSelectLanguage: (String) -> Unit,
    onGo: (CrRoute) -> Unit, onClose: () -> Unit, w: Int,
) {
    val t = LocalTranslator.current
    var langOpen by remember { mutableStateOf(false) }
    val isPortal = route is CrRoute.Portal
    val isReport = !isPortal && (route is CrRoute.Form || route is CrRoute.Response)
    Row(
        Modifier.fillMaxWidth().height(58.dp)
            .background(Brush.horizontalGradient(listOf(Color(0xD9000000), Color(0xF20A0A0C))))
            .drawBehind { drawLine(Color(0x33E50914), Offset(0f, size.height - 0.5.dp.toPx()), Offset(size.width, size.height - 0.5.dp.toPx()), 1.dp.toPx()) }
            .padding(horizontal = if (w <= 560) 12.dp else 16.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(if (w <= 560) 6.dp else 10.dp),
    ) {
        Icon(
            painterResource(R.drawable.ic_pixgo_logo), "Pixgo", tint = Color.Unspecified,
            modifier = Modifier.height(18.dp).width((18f * 786f / 237f).dp).pxTap(onClick = onClose),
        )
        if (w > 1024) {
            Text(
                t.t("copyright.shell.center").uppercase(), color = Px.TextMuted, fontSize = 11.52.sp, fontWeight = FontWeight.SemiBold,
                letterSpacing = 0.46.sp, maxLines = 1,
                modifier = Modifier.padding(start = 14.dp)
                    .drawBehind { drawLine(Px.Border, Offset(-14.dp.toPx(), 0f), Offset(-14.dp.toPx(), size.height), 1.dp.toPx()) },
            )
        }
        Row(Modifier.padding(start = if (w <= 760) 0.dp else 8.dp), horizontalArrangement = Arrangement.spacedBy(2.dp)) {
            NavLink(t.t("copyright.shell.navReport"), isReport, w) { onGo(CrRoute.Form) }
            NavLink(t.t("copyright.shell.navPortal"), isPortal, w) { onGo(CrRoute.Portal) }
        }
        Spacer(Modifier.weight(1f))
        Box {
            val shape = RoundedCornerShape(Px.RadiusSm)
            Row(
                Modifier.height(38.dp).clip(shape).background(Color(0x0AFFFFFF)).border(1.dp, Px.Border, shape)
                    .pxTap { langOpen = true }.padding(horizontal = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Icon(Icons.Filled.Translate, null, Modifier.size(16.dp), tint = Px.TextMuted)
                Text(langCode.uppercase(), color = Px.TextMuted, fontSize = 12.8.sp, fontWeight = FontWeight.SemiBold)
            }
            DropdownMenu(expanded = langOpen, onDismissRequest = { langOpen = false }, modifier = Modifier.background(Px.CardBg)) {
                SUPPORTED_LANGUAGES.forEach { l ->
                    DropdownMenuItem(
                        text = { Text(l.native, color = if (l.code == langCode) Px.TextLight else Px.TextMuted, fontSize = 14.sp) },
                        onClick = { langOpen = false; onSelectLanguage(l.code) },
                    )
                }
            }
        }
        // .cr-back: texto some <=760, botão some <=560
        if (w > 560) {
            val shape = RoundedCornerShape(Px.RadiusSm)
            Row(
                Modifier.height(38.dp).clip(shape).background(Color(0x12FFFFFF)).border(1.dp, Px.Border, shape)
                    .pxTap(onClick = onClose).padding(horizontal = if (w <= 760) 11.dp else 14.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Icon(Icons.Filled.ArrowBack, null, Modifier.size(16.dp), tint = Px.TextLight)
                if (w > 760) Text(t.t("copyright.shell.backToPlatform"), color = Px.TextLight, fontSize = 12.8.sp, fontWeight = FontWeight.SemiBold, maxLines = 1)
            }
        }
    }
}

@Composable
private fun NavLink(label: String, active: Boolean, w: Int, onClick: () -> Unit) {
    val shape = RoundedCornerShape(Px.RadiusSm)
    Text(
        label, color = if (active) Px.TextLight else Px.TextMuted,
        fontSize = if (w <= 760) 12.8.sp else 13.6.sp, fontWeight = FontWeight.SemiBold, maxLines = 1,
        modifier = Modifier.clip(shape).background(if (active) Color(0x1FE50914) else Color.Transparent)
            .pxTap(onClick = onClick)
            .padding(horizontal = if (w <= 560) 9.dp else if (w <= 760) 10.dp else 14.dp, vertical = 8.dp),
    )
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun CrFooter(onGo: (CrRoute) -> Unit, w: Int) {
    val t = LocalTranslator.current
    val year = remember { java.util.Calendar.getInstance().get(java.util.Calendar.YEAR) }
    val navBottom = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
    Column(
        Modifier.fillMaxWidth().background(Px.BgDarker)
            .drawBehind { drawLine(Px.Border, Offset(0f, 0.5.dp.toPx()), Offset(size.width, 0.5.dp.toPx()), 1.dp.toPx()) }
            .padding(start = 24.dp, end = 24.dp, top = 42.dp, bottom = navBottom),
    ) {
        Column(Modifier.padding(bottom = 34.dp), verticalArrangement = Arrangement.spacedBy(28.dp)) {
            Column {
                Icon(painterResource(R.drawable.ic_pixgo_logo), "Pixgo", tint = Color.Unspecified,
                    modifier = Modifier.height(18.dp).width((18f * 786f / 237f).dp))
                Text(t.t("copyright.footer.tagline"), color = Px.TextMuted, fontSize = 12.48.sp, lineHeight = 21.2.sp,
                    modifier = Modifier.padding(top = 14.dp).widthIn(max = 340.dp))
            }
            Column {
                FooterHeading(t.t("copyright.footer.colCopyright"))
                Column(verticalArrangement = Arrangement.spacedBy(9.dp)) {
                    FooterLink(t.t("copyright.shell.navReport")) { onGo(CrRoute.Form) }
                    FooterLink(t.t("copyright.shell.navPortal")) { onGo(CrRoute.Portal) }
                    COPYRIGHT_LEGAL_LINKS.forEach { (tab, key) -> FooterLink(t.t(key)) { onGo(CrRoute.Legal(tab)) } }
                }
            }
            Column {
                FooterHeading(t.t("copyright.footer.colLegal"))
                // ul.two: 2 colunas
                val rows = LEGAL_LINKS.chunked(2)
                Column(verticalArrangement = Arrangement.spacedBy(9.dp)) {
                    rows.forEach { pair ->
                        Row(Modifier.fillMaxWidth()) {
                            pair.forEach { (tab, key) ->
                                Box(Modifier.weight(1f)) { FooterLink(t.t(key)) { onGo(CrRoute.Legal(tab)) } }
                            }
                            if (pair.size == 1) Spacer(Modifier.weight(1f))
                        }
                    }
                }
            }
        }
        Column(
            Modifier.fillMaxWidth()
                .drawBehind { drawLine(Px.Border, Offset(0f, 0.5.dp.toPx()), Offset(size.width, 0.5.dp.toPx()), 1.dp.toPx()) }
                .padding(top = 18.dp, bottom = 22.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text("© $year Pixgo. ${t.t("copyright.footer.rights")}", color = Px.TextMuted, fontSize = 11.84.sp)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(0.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                BOTTOM_STRIP.forEachIndexed { i, tab ->
                    val key = (LEGAL_LINKS + COPYRIGHT_LEGAL_LINKS).first { it.first == tab }.second
                    Text(
                        t.t(key), color = Px.TextMuted, fontSize = 11.84.sp,
                        modifier = Modifier
                            .then(if (i > 0) Modifier.drawBehind { drawLine(Px.Border, Offset(0f, 0f), Offset(0f, size.height), 1.dp.toPx()) } else Modifier)
                            .pxTap { onGo(CrRoute.Legal(tab)) }
                            .padding(start = if (i == 0) 0.dp else 12.dp, end = 12.dp, top = 2.dp, bottom = 2.dp),
                    )
                }
            }
        }
    }
}

@Composable
private fun FooterHeading(text: String) {
    Text(text.uppercase(), fontFamily = Montserrat, fontWeight = FontWeight.ExtraBold, fontSize = 11.2.sp,
        letterSpacing = 0.9.sp, color = Px.TextTitle, modifier = Modifier.padding(bottom = 14.dp))
}

@Composable
private fun FooterLink(text: String, onClick: () -> Unit) {
    Text(text, color = Px.TextMuted, fontSize = 12.8.sp, modifier = Modifier.pxTap(onClick = onClick))
}

// ───────────────────────── blocos reutilizáveis (.cr-*) ─────────────────────────

@Composable
private fun CrHero(title: String, subtitle: String, bottom: Dp = 26.dp) {
    Column(Modifier.fillMaxWidth().padding(bottom = bottom)) {
        Text(title, style = PxText.pageTitle(), modifier = Modifier.padding(bottom = 8.dp))
        Text(subtitle, color = Px.TextMuted, fontSize = 14.72.sp, lineHeight = 25.sp, modifier = Modifier.widthIn(max = 720.dp))
    }
}

@Composable
private fun CrNum(n: Int, bg: Color = Color(0x1AE50914)) {
    Box(
        Modifier.size(28.dp).clip(CircleShape).background(bg).border(1.dp, Color(0x47E50914), CircleShape),
        contentAlignment = Alignment.Center,
    ) { Text(n.toString(), color = Px.Primary, fontSize = 12.48.sp, fontWeight = FontWeight.ExtraBold) }
}

@Composable
private fun CardHeader(title: String, subtitle: String? = null, trailing: String? = null) {
    Row(
        Modifier.fillMaxWidth()
            .drawBehind { drawLine(Px.Border, Offset(0f, size.height - 0.5.dp.toPx()), Offset(size.width, size.height - 0.5.dp.toPx()), 1.dp.toPx()) }
            .padding(horizontal = 17.dp, vertical = 13.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, fontFamily = Montserrat, fontWeight = FontWeight.ExtraBold, fontSize = 14.72.sp, color = Px.TextLight)
            if (subtitle != null) Text(subtitle, color = Px.TextMuted, fontSize = 11.36.sp, modifier = Modifier.padding(top = 2.dp))
        }
        if (trailing != null) Text(trailing, color = Px.TextMuted, fontSize = 11.36.sp)
    }
}

@Composable
private fun CrError(text: String) {
    val shape = RoundedCornerShape(Px.RadiusSm)
    Row(
        Modifier.fillMaxWidth().clip(shape).background(Color(0x14E50914)).border(1.dp, Color(0x40E50914), shape)
            .padding(horizontal = 14.dp, vertical = 11.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Icon(Icons.Filled.ErrorOutline, null, Modifier.size(18.dp), tint = Color(0xFFFF8A8A))
        Text(text, color = Color(0xFFFF8A8A), fontSize = 13.12.sp, lineHeight = 19.7.sp, modifier = Modifier.weight(1f))
    }
}

@Composable
private fun CrSection(num: Int, title: String, desc: String, last: Boolean = false, content: @Composable () -> Unit) {
    val w = LocalConfiguration.current.screenWidthDp
    Column(
        Modifier.fillMaxWidth()
            .then(if (!last) Modifier.drawBehind { drawLine(Px.Border, Offset(0f, size.height - 0.5.dp.toPx()), Offset(size.width, size.height - 0.5.dp.toPx()), 1.dp.toPx()) } else Modifier)
            .padding(horizontal = if (w <= 760) 16.dp else 24.dp, vertical = if (w <= 760) 18.dp else 22.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.Top) {
            CrNum(num)
            Column {
                Text(title, fontFamily = Montserrat, fontWeight = FontWeight.ExtraBold, fontSize = 15.2.sp, color = Px.TextTitle)
                Text(desc, color = Px.TextMuted, fontSize = 12.48.sp, lineHeight = 19.4.sp, modifier = Modifier.padding(top = 2.dp))
            }
        }
        content()
    }
}

@Composable
private fun CrLabel(text: String, required: Boolean = false, optional: String? = null) {
    Row {
        Text(text, color = Px.TextMuted, fontSize = 12.32.sp, fontWeight = FontWeight.SemiBold)
        if (required) Text(" *", color = Px.Primary, fontSize = 12.32.sp, fontWeight = FontWeight.SemiBold)
        if (optional != null) Text(" ($optional)", color = Px.TextMuted.copy(alpha = 0.7f), fontSize = 12.32.sp)
    }
}

@Composable
private fun CrSelect(
    value: String, placeholder: String, options: List<Pair<String, String>>, onSelect: (String) -> Unit,
) {
    var open by remember { mutableStateOf(false) }
    val shape = RoundedCornerShape(Px.RadiusSm)
    val current = options.firstOrNull { it.first == value }?.second
    Box {
        Row(
            Modifier.fillMaxWidth().height(44.dp).clip(shape).background(Color(0x0DFFFFFF)).border(1.dp, Px.Border, shape)
                .pxTap { open = true }.padding(start = 14.dp, end = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(current ?: placeholder, color = if (current != null) Px.TextLight else Px.TextLight, fontSize = 14.4.sp, maxLines = 1, modifier = Modifier.weight(1f))
            Icon(Icons.Filled.KeyboardArrowDown, null, Modifier.size(16.dp), tint = Px.TextMuted)
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }, modifier = Modifier.background(Px.CardBg)) {
            DropdownMenuItem(text = { Text(placeholder, color = Px.TextMuted, fontSize = 14.sp) }, onClick = { open = false; onSelect("") })
            options.forEach { (k, label) ->
                DropdownMenuItem(text = { Text(label, color = Px.TextLight, fontSize = 14.sp) }, onClick = { open = false; onSelect(k) })
            }
        }
    }
}

@Composable
private fun CrCheck(checked: Boolean, onChange: (Boolean) -> Unit, inline: Boolean = false, content: @Composable () -> Unit) {
    val shape = RoundedCornerShape(Px.RadiusSm)
    Row(
        Modifier.fillMaxWidth()
            .then(if (inline) Modifier else Modifier.clip(shape).background(Color(0x05FFFFFF)).border(1.dp, Px.Border, shape))
            .pxTap { onChange(!checked) }
            .then(if (inline) Modifier else Modifier.padding(horizontal = 14.dp, vertical = 12.dp)),
        horizontalArrangement = Arrangement.spacedBy(if (inline) 8.dp else 11.dp),
        verticalAlignment = if (inline) Alignment.CenterVertically else Alignment.Top,
    ) {
        Checkbox(
            checked = checked, onCheckedChange = null,
            colors = CheckboxDefaults.colors(checkedColor = Px.Primary, uncheckedColor = Px.TextMuted, checkmarkColor = Color.White),
            modifier = Modifier.size(18.dp),
        )
        Box(Modifier.weight(1f)) { content() }
    }
}

/** TimeField.tsx: HH:MM:SS em segundos; dígitos entram pela direita, máx 23/59/59. */
@Composable
private fun TimeField(value: Int, onChange: (Int) -> Unit) {
    val maxes = intArrayOf(23, 59, 59)
    val total = value.coerceIn(0, 86399)
    val parts = intArrayOf(total / 3600, (total % 3600) / 60, total % 60)
    val shape = RoundedCornerShape(Px.RadiusSm)
    Row(
        Modifier.fillMaxWidth().height(44.dp).clip(shape).background(Color(0x0DFFFFFF)).border(1.dp, Px.Border, shape).padding(horizontal = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(2.dp, Alignment.CenterHorizontally),
    ) {
        for (idx in 0..2) {
            if (idx > 0) Text(":", color = Px.TextMuted, fontWeight = FontWeight.Bold)
            BasicTextField(
                value = parts[idx].toString().padStart(2, '0'),
                onValueChange = { raw ->
                    val digits = raw.filter { it.isDigit() }.takeLast(2)
                    val next = (digits.toIntOrNull() ?: 0).coerceIn(0, maxes[idx])
                    val copy = parts.copyOf(); copy[idx] = next
                    onChange(copy[0] * 3600 + copy[1] * 60 + copy[2])
                },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                textStyle = TextStyle(fontFamily = io.pixgo.app.ui.theme.Poppins, fontSize = 15.2.sp, fontWeight = FontWeight.SemiBold, color = Px.TextLight, textAlign = TextAlign.Center),
                cursorBrush = androidx.compose.ui.graphics.SolidColor(Px.TextLight),
                modifier = Modifier.width(36.dp),
            )
        }
    }
}

@Composable
private fun StatusBadge(status: String?) {
    val t = LocalTranslator.current
    val s = if (status == "rejected" || status == "removed") status else "pending"
    val kind = when (s) { "rejected" -> PxBadgeKind.Red; "removed" -> PxBadgeKind.Green; else -> PxBadgeKind.Amber }
    PxBadge(t.t("copyright.status.$s"), kind)
}

@Composable
private fun CrNote(title: String, text: String) {
    val shape = RoundedCornerShape(Px.RadiusSm)
    Column(
        Modifier.fillMaxWidth().clip(shape).background(Px.CardBg).border(1.dp, Px.Border, shape)
            .drawBehind { drawRect(Px.Primary, size = Size(3.dp.toPx(), size.height)) }
            .padding(horizontal = 16.dp, vertical = 14.dp),
    ) {
        Text(title, color = Px.TextTitle, fontSize = 13.12.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(bottom = 5.dp))
        Text(text, color = Px.TextMuted, fontSize = 12.32.sp, lineHeight = 20.3.sp)
    }
}

@Composable
private fun CrSteps(items: List<Pair<String, String>>) {
    Column(Modifier.padding(vertical = 6.dp)) {
        items.forEachIndexed { i, (title, text) ->
            val last = i == items.lastIndex
            Row(
                Modifier.fillMaxWidth()
                    .then(if (!last) Modifier.drawBehind { drawLine(Px.Border, Offset(31.dp.toPx(), 42.dp.toPx()), Offset(31.dp.toPx(), size.height + 12.dp.toPx()), 1.dp.toPx()) } else Modifier)
                    .padding(horizontal = 18.dp, vertical = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                CrNum(i + 1, bg = Px.CardBg)
                Column {
                    Text(title, color = Px.TextTitle, fontSize = 13.44.sp, fontWeight = FontWeight.Bold)
                    Text(text, color = Px.TextMuted, fontSize = 12.32.sp, lineHeight = 19.7.sp, modifier = Modifier.padding(top = 2.dp))
                }
            }
        }
    }
}

private fun fmtDate(iso: String?, lang: String): String {
    if (iso.isNullOrBlank()) return ""
    return runCatching {
        val instant = runCatching { Instant.parse(iso) }.getOrElse { OffsetDateTime.parse(iso).toInstant() }
        val locale = if (lang == "pt") Locale("pt", "BR") else Locale(lang)
        DateTimeFormatter.ofLocalizedDateTime(FormatStyle.MEDIUM, FormatStyle.SHORT).withLocale(locale)
            .format(instant.atZone(ZoneId.systemDefault()))
    }.getOrDefault(iso)
}

// ───────────────────────── /copyright ─────────────────────────

private data class ItemState(val key: Int, val url: String = "", val full: Boolean = true, val start: Int = 0, val end: Int = 0)

@Composable
private fun FormPage(repository: CopyrightRepository, langCode: String, onSubmitted: (String) -> Unit, onGo: (CrRoute) -> Unit) {
    val t = LocalTranslator.current
    val scope = rememberCoroutineScope()
    val w = LocalConfiguration.current.screenWidthDp
    var keySeq by remember { mutableIntStateOf(1) }
    var name by remember { mutableStateOf("") }
    var email by remember { mutableStateOf("") }
    var relationship by remember { mutableStateOf("") }
    var workUrl by remember { mutableStateOf("") }
    val items = remember { mutableStateListOf(ItemState(key = 0)) }
    var detection by remember { mutableStateOf("") }
    var details by remember { mutableStateOf("") }
    var goodFaith by remember { mutableStateOf(false) }
    var accuracy by remember { mutableStateOf(false) }
    var submitting by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf("") }

    fun update(idx: Int, f: (ItemState) -> ItemState) { items[idx] = f(items[idx]) }

    fun validate(): String {
        if (name.trim().length < 2 || email.isBlank() || workUrl.isBlank() || items.any { it.url.isBlank() }) return t.t("copyright.errors.required")
        if (relationship.isEmpty()) return t.t("copyright.errors.relationship")
        if (!EMAIL_RE.matches(email.trim())) return t.t("copyright.errors.email")
        if (!isHttpUrl(workUrl) || items.any { !isHttpUrl(it.url) }) return t.t("copyright.errors.url")
        if (items.any { !it.full && it.end <= it.start }) return t.t("copyright.errors.range")
        if (!goodFaith || !accuracy) return t.t("copyright.errors.declarations")
        return ""
    }

    fun submit() {
        if (submitting) return
        val problem = validate()
        if (problem.isNotEmpty()) { error = problem; return }
        error = ""
        submitting = true
        scope.launch {
            val cleanEmail = email.trim().lowercase()
            val res = repository.submit(
                ReportSubmission(
                    claimantName = name.trim(), claimantEmail = cleanEmail, relationship = relationship,
                    workUrl = workUrl.trim(), detectionMethod = detection, details = details.trim(),
                    items = items.map { ReportItemInput(it.url.trim(), it.full, it.start, it.end) },
                    lang = langCode.takeIf { it in listOf("pt", "en", "es") },
                )
            )
            when (res) {
                is SubmitResult.Ok -> {
                    repository.saveStored(StoredReport(res.id, cleanEmail, res.createdAt))
                    onSubmitted(res.id)
                }
                SubmitResult.RateLimited -> { error = t.t("copyright.errors.rateLimit"); submitting = false }
                SubmitResult.Failed -> { error = t.t("copyright.errors.generic"); submitting = false }
            }
        }
    }

    CrHero(t.t("copyright.form.title"), t.t("copyright.form.subtitle"))

    @Composable
    fun FormCard() {
        PxCard(padding = 0.dp) {
            CardHeader(t.t("copyright.form.cardTitle"), t.t("copyright.form.cardSubtitle"), t.t("copyright.form.requiredNote"))

            CrSection(1, t.t("copyright.form.s1Title"), t.t("copyright.form.s1Desc")) {
                PxField(t.t("copyright.form.name") + " *", name, { name = it }, maxLength = 200)
                PxField(t.t("copyright.form.email") + " *", email, { email = it }, maxLength = 254, keyboardType = KeyboardType.Email)
                Column(verticalArrangement = Arrangement.spacedBy(5.dp)) {
                    CrLabel(t.t("copyright.form.relationship"), required = true)
                    CrSelect(
                        relationship, t.t("copyright.form.relationshipSelect"),
                        RELATIONSHIPS.map { it to t.t(RELATIONSHIP_KEYS.getValue(it)) },
                    ) { relationship = it }
                }
            }

            CrSection(2, t.t("copyright.form.s2Title"), t.t("copyright.form.s2Desc")) {
                PxField(
                    t.t("copyright.form.workUrl") + " *", workUrl, { workUrl = it },
                    helper = t.t("copyright.form.workUrlHint"), maxLength = 2000, keyboardType = KeyboardType.Uri,
                )
                Column(verticalArrangement = Arrangement.spacedBy(5.dp)) {
                    CrLabel(t.t("copyright.form.itemsLabel"), required = true)
                    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        items.forEachIndexed { idx, it ->
                            val shape = RoundedCornerShape(10.dp)
                            Column(
                                Modifier.fillMaxWidth().clip(shape).background(Color(0x05FFFFFF)).border(1.dp, Px.Border, shape).padding(horizontal = 16.dp, vertical = 14.dp),
                                verticalArrangement = Arrangement.spacedBy(12.dp),
                            ) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Text(t.t("copyright.form.itemLabel", mapOf("n" to (idx + 1).toString())), color = Px.TextTitle, fontSize = 12.48.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                                    if (items.size > 1) {
                                        Box(Modifier.size(30.dp).clip(RoundedCornerShape(6.dp)).pxTap { items.removeAt(idx) }, contentAlignment = Alignment.Center) {
                                            Icon(Icons.Filled.Close, t.t("copyright.form.removeItem"), Modifier.size(18.dp), tint = Px.TextMuted)
                                        }
                                    }
                                }
                                PxField(t.t("copyright.form.itemUrl"), it.url, { v -> update(idx) { s -> s.copy(url = v) } }, maxLength = 2000, keyboardType = KeyboardType.Uri)
                                CrCheck(it.full, { v -> update(idx) { s -> s.copy(full = v) } }, inline = true) {
                                    Text(t.t("copyright.form.fullContent"), color = Px.TextLight, fontSize = 13.44.sp, fontWeight = FontWeight.Medium)
                                }
                                if (!it.full) {
                                    Column(verticalArrangement = Arrangement.spacedBy(5.dp)) {
                                        CrLabel(t.t("copyright.form.affected"))
                                        Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
                                            Column(verticalArrangement = Arrangement.spacedBy(5.dp)) {
                                                Text(t.t("copyright.form.timeStart"), color = Px.TextMuted, fontSize = 11.36.sp)
                                                TimeField(it.start) { v -> update(idx) { s -> s.copy(start = v) } }
                                            }
                                            Column(verticalArrangement = Arrangement.spacedBy(5.dp)) {
                                                Text(t.t("copyright.form.timeEnd"), color = Px.TextMuted, fontSize = 11.36.sp)
                                                TimeField(it.end) { v -> update(idx) { s -> s.copy(end = v) } }
                                            }
                                        }
                                        Text(t.t("copyright.form.timeHint"), color = Px.TextMuted, fontSize = 11.36.sp)
                                    }
                                }
                            }
                        }
                    }
                    if (items.size < MAX_ITEMS) {
                        PxButton(
                            t.t("copyright.form.addItem"), onClick = { items.add(ItemState(key = keySeq++)) },
                            variant = PxBtnVariant.Secondary, size = PxBtnSize.Sm, icon = Icons.Filled.Add,
                            modifier = Modifier.padding(top = 4.dp),
                        )
                    } else {
                        Text(t.t("copyright.form.itemsLimit", mapOf("max" to MAX_ITEMS.toString())), color = Px.TextMuted, fontSize = 11.36.sp)
                    }
                }
                Column(verticalArrangement = Arrangement.spacedBy(5.dp)) {
                    CrLabel(t.t("copyright.form.detection"), optional = t.t("copyright.form.optional"))
                    CrSelect(
                        detection, t.t("copyright.form.detectionNone"),
                        DETECTION_METHODS.map { it to t.t(DETECTION_KEYS.getValue(it)) },
                    ) { detection = it }
                }
                PxField(
                    t.t("copyright.form.details"), details, { details = it },
                    labelSuffix = t.t("copyright.form.optional"), helper = t.t("copyright.form.detailsHint"),
                    multiline = true, maxLength = 4000,
                )
            }

            CrSection(3, t.t("copyright.form.s3Title"), t.t("copyright.form.s3Desc"), last = true) {
                CrCheck(goodFaith, { goodFaith = it }) {
                    Text(
                        androidx.compose.ui.text.buildAnnotatedString {
                            pushStyle(androidx.compose.ui.text.SpanStyle(color = Px.TextTitle, fontWeight = FontWeight.Bold))
                            append(t.t("copyright.form.declGoodFaithTitle")); pop()
                            append(" " + t.t("copyright.form.declGoodFaith"))
                        },
                        color = Px.TextMuted, fontSize = 13.12.sp, lineHeight = 21.sp,
                    )
                }
                CrCheck(accuracy, { accuracy = it }) {
                    Text(
                        androidx.compose.ui.text.buildAnnotatedString {
                            pushStyle(androidx.compose.ui.text.SpanStyle(color = Px.TextTitle, fontWeight = FontWeight.Bold))
                            append(t.t("copyright.form.declAccuracyTitle")); pop()
                            append(" " + t.t("copyright.form.declAccuracy"))
                        },
                        color = Px.TextMuted, fontSize = 13.12.sp, lineHeight = 21.sp,
                    )
                }
            }

            Column(
                Modifier.fillMaxWidth().background(Color(0x04FFFFFF))
                    .drawBehind { drawLine(Px.Border, Offset(0f, 0.5.dp.toPx()), Offset(size.width, 0.5.dp.toPx()), 1.dp.toPx()) }
                    .padding(horizontal = if (w <= 760) 16.dp else 24.dp, vertical = 20.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                if (error.isNotEmpty()) CrError(error)
                PxButton(
                    if (submitting) t.t("copyright.form.submitting") else t.t("copyright.form.submit"),
                    onClick = { submit() }, size = PxBtnSize.Lg, enabled = !submitting, modifier = Modifier.fillMaxWidth(),
                )
                Text(t.t("copyright.form.submitNote"), color = Px.TextMuted, fontSize = 11.84.sp, lineHeight = 19.sp)
            }
        }
    }

    @Composable
    fun Aside() {
        Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
            PxCard(padding = 0.dp) {
                CardHeader(t.t("copyright.form.howTitle"))
                CrSteps((1..4).map { n -> t.t("copyright.form.step${n}Title") to t.t("copyright.form.step$n") })
            }
            CrNote(t.t("copyright.form.noticeTitle"), t.t("copyright.form.noticeBody"))
            CrNote(t.t("copyright.form.beforeTitle"), t.t("copyright.form.beforeBody"))
            val shape = RoundedCornerShape(Px.Radius)
            Row(
                Modifier.fillMaxWidth().clip(shape).background(Px.CardBg).border(1.dp, Px.Border, shape)
                    .pxTap { onGo(CrRoute.Portal) }.padding(horizontal = 16.dp, vertical = 14.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Column(Modifier.weight(1f)) {
                    Text(t.t("copyright.form.portalCtaTitle"), color = Px.TextTitle, fontSize = 13.12.sp, fontWeight = FontWeight.Bold)
                    Text(t.t("copyright.form.portalCta"), color = Px.TextMuted, fontSize = 12.32.sp, fontWeight = FontWeight.Medium)
                }
                Icon(Icons.Filled.ArrowForward, null, Modifier.size(20.dp), tint = Px.Primary)
            }
        }
    }

    // .cr-layout: 2 colunas (1fr + 340px) acima de 1024; uma coluna abaixo
    if (w > 1024) {
        Row(horizontalArrangement = Arrangement.spacedBy(22.dp), verticalAlignment = Alignment.Top) {
            Box(Modifier.weight(1f)) { FormCard() }
            Box(Modifier.width(340.dp)) { Aside() }
        }
    } else {
        Column(verticalArrangement = Arrangement.spacedBy(22.dp)) { FormCard(); Aside() }
    }
}

// ───────────────────────── /copyright/response ─────────────────────────

@Composable
private fun ResponsePage(repository: CopyrightRepository, id: String?, langCode: String, onGo: (CrRoute) -> Unit) {
    val t = LocalTranslator.current
    val clipboard = LocalClipboardManager.current
    val w = LocalConfiguration.current.screenWidthDp
    val valid = id != null && PROTOCOL_RE.matches(id)
    var report by remember(id) { mutableStateOf<PublicReport?>(null) }
    var copied by remember { mutableStateOf(false) }

    LaunchedEffect(id) {
        if (!valid || id == null) return@LaunchedEffect
        val stored = repository.readStored().firstOrNull { it.id == id } ?: return@LaunchedEffect
        runCatching { repository.lookup(listOf(id to stored.email)).firstOrNull() }
            .getOrNull()?.takeIf { it.found }?.let { report = it }
    }
    LaunchedEffect(copied) { if (copied) { kotlinx.coroutines.delay(2000); copied = false } }

    if (!valid) {
        PxCard(padding = if (w <= 760) 18.dp else 34.dp) {
            Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
                SuccessIcon(Icons.Outlined.ReportProblem, warn = true)
                Text(t.t("copyright.response.invalidTitle"), fontFamily = Montserrat, fontWeight = FontWeight.ExtraBold, fontSize = 25.6.sp, color = Px.TextLight, textAlign = TextAlign.Center)
                Text(t.t("copyright.response.invalidBody"), color = Px.TextMuted, fontSize = 14.08.sp, lineHeight = 23.9.sp, textAlign = TextAlign.Center)
                ResponseActions(onGo)
            }
        }
        return
    }

    PxCard(padding = if (w <= 760) 18.dp else 34.dp) {
        Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
            SuccessIcon(Icons.Filled.Check, warn = false)
            Text(t.t("copyright.response.title"), fontFamily = Montserrat, fontWeight = FontWeight.ExtraBold, fontSize = 25.6.sp, color = Px.TextLight, textAlign = TextAlign.Center, letterSpacing = (-0.5).sp)
            Text(t.t("copyright.response.subtitle"), color = Px.TextMuted, fontSize = 14.4.sp, textAlign = TextAlign.Center)

            val shape = RoundedCornerShape(Px.RadiusSm)
            Row(
                Modifier.padding(top = 6.dp, bottom = 2.dp).clip(shape).background(Color(0x0DFFFFFF)).border(1.dp, Px.Border, shape)
                    .padding(start = 16.dp, end = 12.dp, top = 9.dp, bottom = 9.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Column(Modifier.weight(1f, fill = false)) {
                    Text(t.t("copyright.response.protocolLabel").uppercase(), color = Px.TextMuted, fontSize = 10.88.sp, fontWeight = FontWeight.Bold, letterSpacing = 0.65.sp)
                    Text(id!!, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold, fontSize = 16.8.sp, letterSpacing = 0.67.sp, color = Px.TextLight)
                }
                PxButton(
                    if (copied) t.t("copyright.response.copied") else t.t("copyright.response.copy"),
                    onClick = { clipboard.setText(AnnotatedString(id!!)); copied = true },
                    variant = PxBtnVariant.Secondary, size = PxBtnSize.Sm, icon = Icons.Filled.ContentCopy,
                )
            }

            Text(t.t("copyright.response.message"), color = Px.TextMuted, fontSize = 14.08.sp, lineHeight = 23.9.sp, textAlign = TextAlign.Center, modifier = Modifier.widthIn(max = 560.dp))

            report?.let { r ->
                val box = RoundedCornerShape(Px.RadiusSm)
                Column(
                    Modifier.fillMaxWidth().padding(top = 10.dp).clip(box).border(1.dp, Px.Border, box).background(Px.Border),
                    verticalArrangement = Arrangement.spacedBy(1.dp),
                ) {
                    SummaryCell(t.t("copyright.response.summaryDate")) { Text(fmtDate(r.createdAt, langCode), color = Px.TextTitle, fontSize = 13.6.sp, fontWeight = FontWeight.SemiBold) }
                    SummaryCell(t.t("copyright.response.summaryItems")) { Text(r.items.size.toString(), color = Px.TextTitle, fontSize = 13.6.sp, fontWeight = FontWeight.SemiBold) }
                    SummaryCell(t.t("copyright.response.summaryStatus")) { StatusBadge(r.status) }
                }
            }
            ResponseActions(onGo)
        }
    }

    Spacer(Modifier.height(18.dp))
    PxCard(padding = 0.dp) {
        CardHeader(t.t("copyright.response.nextTitle"))
        CrSteps((1..4).map { n -> t.t("copyright.response.next${n}Title") to t.t("copyright.response.next$n") })
    }
}

@Composable
private fun SummaryCell(label: String, value: @Composable () -> Unit) {
    Column(Modifier.fillMaxWidth().background(Px.CardBg).padding(horizontal = 14.dp, vertical = 12.dp)) {
        Text(label.uppercase(), color = Px.TextMuted, fontSize = 10.88.sp, fontWeight = FontWeight.Bold, letterSpacing = 0.54.sp, modifier = Modifier.padding(bottom = 4.dp))
        value()
    }
}

@Composable
private fun SuccessIcon(icon: ImageVector, warn: Boolean) {
    val c = if (warn) Color(0xFFFFB830) else Px.Secondary
    Box(
        Modifier.padding(bottom = 6.dp).size(72.dp).clip(CircleShape).background(c.copy(alpha = 0.1f)).border(2.dp, c, CircleShape),
        contentAlignment = Alignment.Center,
    ) { Icon(icon, null, Modifier.size(36.dp), tint = c) }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ResponseActions(onGo: (CrRoute) -> Unit) {
    val t = LocalTranslator.current
    FlowRow(
        Modifier.padding(top = 14.dp), horizontalArrangement = Arrangement.spacedBy(10.dp, Alignment.CenterHorizontally),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        PxButton(t.t("copyright.response.toPortal"), onClick = { onGo(CrRoute.Portal) })
        PxButton(t.t("copyright.response.newReport"), onClick = { onGo(CrRoute.Form) }, variant = PxBtnVariant.Secondary)
    }
}

// ───────────────────────── /copyright/portal ─────────────────────────

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun PortalPage(repository: CopyrightRepository, langCode: String, onGo: (CrRoute) -> Unit) {
    val t = LocalTranslator.current
    val scope = rememberCoroutineScope()
    var reports by remember { mutableStateOf<List<PublicReport>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf("") }
    var updatedAt by remember { mutableStateOf<String?>(null) }
    var tick by remember { mutableIntStateOf(0) }
    val open = remember { mutableStateListOf<String>() }

    var lkId by remember { mutableStateOf("") }
    var lkEmail by remember { mutableStateOf("") }
    var lkMsg by remember { mutableStateOf("") }
    var lkBusy by remember { mutableStateOf(false) }

    suspend fun load() {
        val stored = repository.readStored().take(25)
        val now = java.time.LocalTime.now().format(DateTimeFormatter.ofPattern("HH:mm"))
        if (stored.isEmpty()) { reports = emptyList(); loading = false; updatedAt = now; return }
        try {
            val found = repository.lookup(stored.map { it.id to it.email }).filter { it.found }
                .sortedByDescending { it.createdAt ?: "" }
            reports = found; error = ""; updatedAt = now
        } catch (e: Exception) {
            error = t.t("copyright.errors.load")
        } finally {
            loading = false
        }
    }
    LaunchedEffect(tick) { load() }

    // .page-header: título à esquerda, barra de ações à direita (quebra em ecrãs estreitos)
    FlowRow(
        Modifier.fillMaxWidth().padding(bottom = 22.dp),
        horizontalArrangement = Arrangement.spacedBy(14.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Column(Modifier.widthIn(max = 720.dp)) {
            Text(t.t("copyright.portal.title"), style = PxText.pageTitle(), modifier = Modifier.padding(bottom = 8.dp))
            Text(t.t("copyright.portal.subtitle"), color = Px.TextMuted, fontSize = 14.72.sp, lineHeight = 25.sp)
        }
        FlowRow(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            updatedAt?.let { Text(t.t("copyright.portal.updatedAt", mapOf("time" to it)), color = Px.TextMuted, fontSize = 11.84.sp) }
            PxButton(t.t("copyright.portal.refresh"), onClick = { loading = true; tick++ }, variant = PxBtnVariant.Secondary, size = PxBtnSize.Sm, icon = Icons.Filled.Refresh, enabled = !loading)
            PxButton(t.t("copyright.portal.newReport"), onClick = { onGo(CrRoute.Form) }, size = PxBtnSize.Sm, icon = Icons.Filled.Add)
        }
    }

    if (error.isNotEmpty()) { CrError(error); Spacer(Modifier.height(16.dp)) }

    PxCard(padding = 0.dp) {
        if (loading && reports.isEmpty()) {
            Box(Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 64.dp), contentAlignment = Alignment.Center) {
                Text(t.t("copyright.portal.loading"), style = PxText.EmptyDesc)
            }
        } else if (reports.isEmpty()) {
            Column(
                Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 64.dp),
                horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Box(
                    Modifier.size(64.dp).clip(CircleShape).background(Color(0x14E50914)).border(2.dp, Color(0x33E50914), CircleShape),
                    contentAlignment = Alignment.Center,
                ) { Icon(Icons.Outlined.Shield, null, Modifier.size(30.dp), tint = Px.Primary) }
                Text(t.t("copyright.portal.emptyTitle"), style = PxText.EmptyTitle.copy(textAlign = TextAlign.Center))
                Text(t.t("copyright.portal.emptyBody"), style = PxText.EmptyDesc, modifier = Modifier.widthIn(max = 320.dp))
            }
        } else {
            reports.forEachIndexed { i, r ->
                val expanded = r.id in open
                val count = r.items.size
                Column(
                    Modifier.fillMaxWidth()
                        .then(if (i < reports.lastIndex) Modifier.drawBehind { drawLine(Px.Border, Offset(0f, size.height - 0.5.dp.toPx()), Offset(size.width, size.height - 0.5.dp.toPx()), 1.dp.toPx()) } else Modifier)
                        .padding(vertical = 6.dp),
                ) {
                    PortalRow(t.t("copyright.portal.colProtocol")) {
                        Column(horizontalAlignment = Alignment.End) {
                            Text(r.id, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold, fontSize = 13.12.sp, letterSpacing = 0.5.sp, color = Px.TextLight)
                            Row(
                                Modifier.padding(top = 6.dp).pxTap { if (expanded) open.remove(r.id) else open.add(r.id) },
                                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp),
                            ) {
                                Text(
                                    if (expanded) t.t("copyright.portal.hideDetails") else t.t("copyright.portal.showDetails"),
                                    color = Px.TextMuted, fontSize = 11.84.sp, fontWeight = FontWeight.SemiBold,
                                )
                                Icon(if (expanded) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore, null, Modifier.size(16.dp), tint = Px.TextMuted)
                            }
                        }
                    }
                    PortalRow(t.t("copyright.portal.colDate")) { Text(fmtDate(r.createdAt, langCode), color = Px.TextMuted, fontSize = 13.44.sp, textAlign = TextAlign.End) }
                    PortalRow(t.t("copyright.portal.colContents")) {
                        Text(
                            t.t(if (count == 1) "copyright.portal.contentsCount_one" else "copyright.portal.contentsCount_other", mapOf("count" to count.toString())),
                            color = Px.TextLight, fontSize = 13.44.sp,
                        )
                    }
                    PortalRow(t.t("copyright.portal.colStatus")) { StatusBadge(r.status) }
                    // td.cr-cell-block: rótulo por cima, bloco abaixo
                    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
                        PortalLabel(t.t("copyright.portal.colResponse"), Modifier.padding(bottom = 6.dp))
                        if (!r.teamResponse.isNullOrBlank()) Text(r.teamResponse, color = Px.TextLight, fontSize = 13.44.sp, lineHeight = 21.5.sp)
                        else Text(t.t("copyright.portal.noResponse"), color = Px.TextMuted, fontSize = 13.44.sp)
                    }
                    if (expanded) {
                        Column(
                            Modifier.fillMaxWidth().background(Color(0x05FFFFFF)).padding(horizontal = 16.dp, vertical = 14.dp),
                            verticalArrangement = Arrangement.spacedBy(14.dp),
                        ) {
                            DetailCell(t.t("copyright.portal.detailLinks")) {
                                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                    r.items.forEach { it ->
                                        Column {
                                            LinkText(it.url)
                                            Text(
                                                if (it.full || it.start == null || it.end == null) t.t("copyright.portal.wholeContent")
                                                else t.t("copyright.portal.range", mapOf("start" to formatClock(it.start.toInt()), "end" to formatClock(it.end.toInt()))),
                                                color = Px.TextMuted, fontSize = 13.12.sp,
                                            )
                                        }
                                    }
                                }
                            }
                            if (!r.workUrl.isNullOrBlank()) DetailCell(t.t("copyright.portal.detailWork")) { LinkText(r.workUrl) }
                            r.relationship?.let { k -> RELATIONSHIP_KEYS[k]?.let { DetailCell(t.t("copyright.portal.detailRelationship")) { DetailText(t.t(it)) } } }
                            r.detectionMethod?.let { k -> DETECTION_KEYS[k]?.let { DetailCell(t.t("copyright.portal.detailMethod")) { DetailText(t.t(it)) } } }
                            r.decidedAt?.let { DetailCell(t.t("copyright.portal.detailDecided")) { DetailText(fmtDate(it, langCode)) } }
                            r.respondedAt?.let { DetailCell(t.t("copyright.portal.detailResponded")) { DetailText(fmtDate(it, langCode)) } }
                        }
                    }
                }
            }
        }
    }

    // Consultar uma notificação
    Spacer(Modifier.height(18.dp))
    PxCard(padding = 0.dp) {
        CardHeader(t.t("copyright.portal.lookupTitle"), t.t("copyright.portal.lookupBody"))
        Column(Modifier.padding(17.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            PxField(t.t("copyright.portal.lookupProtocol"), lkId, { lkId = it.uppercase() }, maxLength = 20)
            PxField(t.t("copyright.portal.lookupEmail"), lkEmail, { lkEmail = it }, maxLength = 254, keyboardType = KeyboardType.Email)
            PxButton(
                t.t("copyright.portal.lookupSubmit"), variant = PxBtnVariant.Secondary, enabled = !lkBusy,
                modifier = Modifier.fillMaxWidth(),
                onClick = {
                    if (lkBusy) return@PxButton
                    val id = lkId.trim().uppercase()
                    val email = lkEmail.trim().lowercase()
                    if (!PROTOCOL_RE.matches(id) || !EMAIL_RE.matches(email)) { lkMsg = t.t("copyright.portal.lookupInvalid"); return@PxButton }
                    lkBusy = true; lkMsg = ""
                    scope.launch {
                        try {
                            val r = repository.lookup(listOf(id to email)).firstOrNull()
                            if (r == null || !r.found) lkMsg = t.t("copyright.portal.lookupNotFound")
                            else {
                                repository.saveStored(StoredReport(id, email, r.createdAt ?: java.time.Instant.now().toString()))
                                lkId = ""; lkEmail = ""; loading = true; tick++
                            }
                        } catch (e: Exception) {
                            lkMsg = t.t("copyright.errors.load")
                        } finally { lkBusy = false }
                    }
                },
            )
            if (lkMsg.isNotEmpty()) Text(lkMsg, color = Px.Primary, fontSize = 11.36.sp)
        }
    }
}

@Composable
private fun PortalLabel(text: String, modifier: Modifier = Modifier) {
    Text(text.uppercase(), color = Px.TextMuted, fontSize = 10.88.sp, fontWeight = FontWeight.Bold, letterSpacing = 0.54.sp, modifier = modifier)
}

/** td com data-label (≤760px): rótulo à esquerda, valor à direita. */
@Composable
private fun PortalRow(label: String, value: @Composable () -> Unit) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
        PortalLabel(label, Modifier.padding(top = 2.dp))
        Spacer(Modifier.weight(1f))
        value()
    }
}

@Composable
private fun DetailCell(label: String, value: @Composable () -> Unit) {
    Column {
        PortalLabel(label, Modifier.padding(bottom = 3.dp))
        value()
    }
}

@Composable
private fun DetailText(text: String) = Text(text, color = Px.TextLight, fontSize = 13.12.sp)

@Composable
private fun LinkText(url: String) {
    val uri = LocalUriHandler.current
    Text(
        url, color = Px.TextLight, fontSize = 13.12.sp, textDecoration = TextDecoration.Underline,
        modifier = Modifier.pxTap { runCatching { uri.openUri(url) } },
    )
}
