package io.pixgo.app.ui.nav

import android.content.Intent
import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Android
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.Bookmark
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.ChildCare
import androidx.compose.material.icons.filled.CloudUpload
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Email
import androidx.compose.material.icons.filled.Gavel
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.LiveTv
import androidx.compose.material.icons.filled.Logout
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.Movie
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Translate
import androidx.compose.material.icons.outlined.Flag
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.layout
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.res.painterResource
import coil.compose.AsyncImage

import io.pixgo.app.R
import io.pixgo.app.data.i18n.LocalTranslator
import io.pixgo.app.data.i18n.SUPPORTED_LANGUAGES
import io.pixgo.app.data.model.ContentItem
import io.pixgo.app.data.model.Plan
import io.pixgo.app.data.model.Profile
import io.pixgo.app.data.model.User
import io.pixgo.app.ui.theme.Montserrat
import io.pixgo.app.ui.theme.Poppins
import io.pixgo.app.ui.theme.Px
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * Destinos que, no frontend_web, vivem DENTRO do AppShell (rotas app/main).
 * NAV do AppShell.tsx: home, catalog, channels(liveTV), mylist, search —
 * nessa ordem. ACCOUNT/LEGAL são as rotas /main/account e /main/legal.
 */
enum class MainDest { HOME, CATALOG, LIVE_TV, MY_LIST, SEARCH, DOWNLOADS, PLANS, ACCOUNT, LEGAL }

private val PxEase = CubicBezierEasing(0.25f, 0.46f, 0.45f, 0.94f)  // --transition-medium
private val CssEase = CubicBezierEasing(0.25f, 0.1f, 0.25f, 1f)       // CSS `ease`

@Composable
private fun Modifier.tap(onClick: () -> Unit): Modifier =
    this.clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = onClick)

/**
 * Réplica de components/layout/AppShell.tsx.
 * - SEM bottom bar: main/layout.tsx documenta que a bottom nav mobile foi
 *   "banida"; o sidebar (hamburger no header + overlay) é a única navegação.
 * - <=768dp: sidebar fechado + overlay. >768dp: sidebar aberto + margin-left.
 */
@Composable
fun PixGoScaffold(
    current: MainDest,
    onNavigate: (MainDest) -> Unit,
    user: User?,
    plan: Plan?,
    profiles: List<Profile>,
    activeProfileId: String?,
    onSelectProfile: (String) -> Unit,
    currentLangCode: String,
    onSelectLanguage: (String) -> Unit,
    downloadCount: Int,
    onOpenDownloads: () -> Unit,
    onUpgrade: () -> Unit,
    onSignOut: () -> Unit,
    suggest: suspend (String) -> List<ContentItem>,
    onOpenContent: (String) -> Unit,
    onSubmitSearch: (String) -> Unit,
    /** "Enviar conteúdo": no Android só abre o modal informando que o envio é feito na plataforma web. */
    onUploadClick: () -> Unit,
    /** Rota /copyright (Central de direitos autorais). */
    onOpenCopyright: () -> Unit,
    content: @Composable () -> Unit,
) {
    val t = LocalTranslator.current
    val density = LocalDensity.current

    val canDownload = plan != null && plan.id != "free" && plan.isActive == true
    val isPremium = plan != null && plan.id != "free"
    val initials = (user?.name?.takeIf { it.isNotBlank() } ?: user?.username?.takeIf { it.isNotBlank() } ?: "?")
        .split(" ").take(2).joinToString("") { it.take(1) }.uppercase()

    BoxWithConstraints(Modifier.fillMaxSize().background(Px.BgDark)) {
        val widthDp = maxWidth
        val wide = widthDp > 768.dp
        val xl = widthDp >= 1920.dp
        val headerH = when { xl -> 76.dp; wide -> 64.dp; else -> 58.dp }
        val sidebarW = when {
            !wide -> 250.dp
            xl -> 270.dp
            widthDp <= 1024.dp -> 220.dp
            else -> 240.dp
        }

        // env(safe-area-inset-*): recorte (notch) e barras SE estiverem visíveis. Em modo
        // imersivo as barras ficam ocultas -> 0, sem criar "footer" artificial.
        val safe = WindowInsets.safeDrawing.asPaddingValues()
        val topInset = safe.calculateTopPadding()
        val leftInset = safe.calculateLeftPadding(LayoutDirection.Ltr)
        val rightInset = safe.calculateRightPadding(LayoutDirection.Ltr)

        var sidebarOpen by remember { mutableStateOf(wide) }
        var userMenuOpen by remember { mutableStateOf(false) }
        var langMenuOpen by remember { mutableStateOf(false) }
        var query by remember { mutableStateOf("") }
        var results by remember { mutableStateOf<List<ContentItem>>(emptyList()) }
        var searching by remember { mutableStateOf(false) }
        var showDrop by remember { mutableStateOf(false) }

        var searchRect by remember { mutableStateOf<Rect?>(null) }
        var langRect by remember { mutableStateOf<Rect?>(null) }
        var userRect by remember { mutableStateOf<Rect?>(null) }
        var langMenuRect by remember { mutableStateOf<Rect?>(null) }
        var userMenuRect by remember { mutableStateOf<Rect?>(null) }
        var dropRect by remember { mutableStateOf<Rect?>(null) }

        // useEffect resize: width <= 768 -> fecha; senão abre.
        LaunchedEffect(wide) { sidebarOpen = wide }
        // useEffect [pathname]: fecha o sidebar ao mudar de rota no mobile.
        LaunchedEffect(current) { if (!wide) sidebarOpen = false }

        // Debounce de 350ms (AppShell.tsx); o `suggest` do chamador usa limit 6.
        LaunchedEffect(query) {
            if (query.isBlank()) { results = emptyList(); showDrop = false; searching = false; return@LaunchedEffect }
            delay(350)
            searching = true
            try {
                results = suggest(query)
                showDrop = true
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                results = emptyList()
            } finally {
                searching = false
            }
        }

        val closeSidebarOnMobile = { if (!wide) sidebarOpen = false }
        val anyMenu = userMenuOpen || langMenuOpen || showDrop
        // Escape do original -> botão Voltar do Android.
        BackHandler(enabled = anyMenu) { userMenuOpen = false; langMenuOpen = false; showDrop = false }
        BackHandler(enabled = !anyMenu && !wide && sidebarOpen) { sidebarOpen = false }

        // document 'mousedown' fora do contentor fecha cada menu — sem consumir o toque.
        val outsideState = rememberUpdatedState(Triple(userMenuOpen, langMenuOpen, showDrop))
        Box(
            Modifier
                .fillMaxSize()
                .pointerInput(Unit) {
                    awaitEachGesture {
                        val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
                        val p = down.position
                        val (u, l, d) = outsideState.value
                        if (u && userRect?.contains(p) != true && userMenuRect?.contains(p) != true) userMenuOpen = false
                        if (l && langRect?.contains(p) != true && langMenuRect?.contains(p) != true) langMenuOpen = false
                        if (d && searchRect?.contains(p) != true && dropRect?.contains(p) != true) showDrop = false
                    }
                }
        ) {
            // ── .main-content (margin-left) + .page-content (margin-top: var(--header-h))
            val contentStart by animateDpAsState(
                if (wide && sidebarOpen) sidebarW else 0.dp, tween(280, easing = PxEase), label = "mainStart"
            )
            Box(
                Modifier
                    .fillMaxSize()
                    .padding(start = contentStart, top = headerH + topInset)
                    .imePadding()
            ) { content() }

            // ── .sidebar-overlay (só mobile): rgba(0,0,0,.6)
            if (!wide && sidebarOpen) {
                Box(Modifier.fillMaxSize().background(Color(0x99000000)).tap { sidebarOpen = false })
            }

            // ── .sidebar (top: header-h; translateX 0.28s)
            val sidebarX by animateDpAsState(
                if (sidebarOpen) 0.dp else -sidebarW, tween(280, easing = PxEase), label = "sidebarX"
            )
            Column(
                Modifier
                    .padding(top = headerH + topInset)
                    .offset(x = sidebarX)
                    .width(sidebarW)
                    .fillMaxHeight()
                    .background(Brush.verticalGradient(listOf(Color(0xFF0D0D10), Px.BgDark)))
                    .drawBehind {
                        val w = 1.dp.toPx()
                        drawLine(Px.Border, Offset(size.width - w / 2, 0f), Offset(size.width - w / 2, size.height), w)
                    }
            ) {
                Column(
                    Modifier.weight(1f).verticalScroll(rememberScrollState())
                        .padding(horizontal = 10.dp, vertical = 14.dp)
                ) {
                    SectionLabel("Menu")
                    NavItem(t.t("nav.home"), Icons.Filled.Home, current == MainDest.HOME) { onNavigate(MainDest.HOME); closeSidebarOnMobile() }
                    NavItem(t.t("nav.catalog"), Icons.Filled.Movie, current == MainDest.CATALOG) { onNavigate(MainDest.CATALOG); closeSidebarOnMobile() }
                    NavItem(t.t("nav.liveTV"), Icons.Filled.LiveTv, current == MainDest.LIVE_TV) { onNavigate(MainDest.LIVE_TV); closeSidebarOnMobile() }
                    NavItem(t.t("nav.myList"), Icons.Filled.Bookmark, current == MainDest.MY_LIST) { onNavigate(MainDest.MY_LIST); closeSidebarOnMobile() }
                    NavItem(t.t("nav.search"), Icons.Filled.Search, current == MainDest.SEARCH) { onNavigate(MainDest.SEARCH); closeSidebarOnMobile() }
                    // AppShell: <Link href="/copyright" className="nav-item nav-report"> logo após "Pesquisar"
                    NavItem(
                        t.t("nav.reportCopyright"), Icons.Outlined.Flag, false,
                        color = Px.TextLight, iconTint = Px.Primary,
                    ) { onOpenCopyright(); closeSidebarOnMobile() }

                    Spacer(Modifier.height(22.dp))
                    SectionLabel("Conta")
                    if (canDownload) {
                        NavItem(
                            "Downloads", Icons.Filled.Download, false,
                            badge = if (downloadCount > 0) {
                                { Badge(downloadCount.toString(), Px.Primary, Color.White, 9.28.sp) }
                            } else null
                        ) { onOpenDownloads(); closeSidebarOnMobile() }
                    } else {
                        NavItem(
                            "Downloads", Icons.Filled.Download, false, color = Color(0x59FFFFFF),
                            badge = { Badge("PRO", Color(0x14FFFFFF), Color(0x59FFFFFF), 9.6.sp) }
                        // /main/plans (AppShell: Link href="/main/plans" quando free)
                        ) { onNavigate(MainDest.PLANS); closeSidebarOnMobile() }
                    }
                    NavItem(t.t("nav.upload"), Icons.Filled.CloudUpload, false, color = Color(0x8CFFFFFF)) { onUploadClick(); closeSidebarOnMobile() }
                    // AppShell real: <Link href="/main/plans"> — "Fazer upgrade"
                    // navega para a tela de planos nativa (nunca abre o
                    // checkout directamente).
                    NavItem(
                        t.t("nav.upgrade"), Icons.Filled.Bolt, current == MainDest.PLANS,
                        badge = if (!isPremium) {
                            { Badge("Free", Color(0x2EE50914), Px.Primary, 9.28.sp) }
                        } else null
                    ) { onNavigate(MainDest.PLANS); closeSidebarOnMobile() }
                    NavItem(t.t("nav.account"), Icons.Filled.Settings, current == MainDest.ACCOUNT) { onNavigate(MainDest.ACCOUNT); closeSidebarOnMobile() }
                    NavItem(t.t("legal.title"), Icons.Filled.Gavel, current == MainDest.LEGAL) { onNavigate(MainDest.LEGAL); closeSidebarOnMobile() }
                }
                SidebarFooter(onSignOut, onReport = { onOpenCopyright(); closeSidebarOnMobile() })
            }

            // ── .header (z-index 1100: acima do sidebar e do overlay)
            Row(
                Modifier
                    .fillMaxWidth()
                    .shadow(12.dp, RectangleShape, clip = false, ambientColor = Color.Black, spotColor = Color.Black)
                    .background(Px.BgDark)
                    .background(Brush.horizontalGradient(listOf(Color(0xD9000000), Color(0xF20A0A0C))))
                    .drawBehind {
                        val w = 1.dp.toPx()
                        drawLine(Color(0x33E50914), Offset(0f, size.height - w / 2), Offset(size.width, size.height - w / 2), w)
                    }
                    .height(headerH + topInset)
                    .padding(top = topInset)
                    .padding(
                        start = max(16f, leftInset.value).dp,
                        end = max(16f, rightInset.value).dp
                    ),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                // .sidebar-toggle 42×42, marginRight:12 (+ gap:10)
                Box(
                    Modifier.size(42.dp).clip(RoundedCornerShape(Px.RadiusSm)).tap { sidebarOpen = !sidebarOpen },
                    contentAlignment = Alignment.Center
                ) { Icon(Icons.Filled.Menu, "Toggle sidebar", Modifier.size(21.dp), tint = Px.TextLight) }
                Spacer(Modifier.width(22.dp))

                // .logo img height:18px, marginRight:16 (+ gap:10)
                // Logo real do produto (public/logo.svg do frontend_web), convertido em
                // vector drawable nativo (ic_pixgo_logo.xml) — mesma arte, sem SVG runtime.
                Icon(
                    painter = painterResource(R.drawable.ic_pixgo_logo),
                    contentDescription = "Pixgo",
                    tint = Color.Unspecified,
                    modifier = Modifier.height(18.dp).width((18f * 786f / 237f).dp).tap { onNavigate(MainDest.HOME) }
                )
                Spacer(Modifier.width(26.dp))

                // Barra de pesquisa do header REMOVIDA (pedido explícito). A pesquisa
                // continua disponível pelo item "Pesquisar" do menu lateral. O espaço
                // flexível empurra as acções (idioma, downloads, avatar) para a direita.
                Spacer(Modifier.weight(1f))
                Spacer(Modifier.width(10.dp))

                // .header-actions gap:7
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                    // idioma
                    Row(
                        Modifier
                            .height(38.dp).widthIn(min = 38.dp)
                            .clip(RoundedCornerShape(Px.RadiusSm))
                            .onGloballyPositioned { langRect = it.boundsInRoot() }
                            .tap { langMenuOpen = !langMenuOpen }
                            .padding(horizontal = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(5.dp, Alignment.CenterHorizontally),
                    ) {
                        Icon(Icons.Filled.Translate, null, Modifier.size(17.dp), tint = Px.TextMuted)
                        // <=480px: `.header-actions .icon-btn span:not(svg){display:none}`
                        if (widthDp > 480.dp) {
                            Text(
                                currentLangCode.uppercase(), color = Px.TextMuted, fontSize = 11.2.sp,
                                fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace
                            )
                        }
                        Icon(Icons.Filled.KeyboardArrowDown, null, Modifier.size(13.dp), tint = Px.TextMuted)
                    }
                    if (canDownload) {
                        Box(
                            Modifier.height(38.dp).widthIn(min = 38.dp).clip(RoundedCornerShape(Px.RadiusSm)).tap { onOpenDownloads() },
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(Icons.Filled.Download, "Downloads", Modifier.size(19.dp), tint = Px.TextMuted)
                            if (downloadCount > 0) {
                                Box(
                                    Modifier.align(Alignment.TopEnd).padding(top = 4.dp, end = 4.dp)
                                        .size(8.dp).clip(CircleShape).background(Px.Primary)
                                )
                            }
                        }
                    }
                    // .avatar-btn 36×36, anel box-shadow 0 0 0 2px rgba(229,9,20,.3)
                    Box(
                        Modifier
                            .size(36.dp)
                            .drawBehind { drawCircle(Color(0x4DE50914), radius = size.minDimension / 2 + 2.dp.toPx()) }
                            .clip(CircleShape)
                            .background(Color.White)
                            .onGloballyPositioned { userRect = it.boundsInRoot() }
                            .tap { userMenuOpen = !userMenuOpen },
                        contentAlignment = Alignment.Center
                    ) {
                        Text(initials, color = Color.Black, fontFamily = Montserrat, fontWeight = FontWeight.Black, fontSize = 12.48.sp)
                    }
                }
            }

            // ── dropdowns (z-index 9999)
            if (langMenuOpen) langRect?.let { a ->
                AnchoredMenu(a, 155.dp, { langMenuRect = it }) {
                    SUPPORTED_LANGUAGES.forEach { lang ->
                        MenuItem(onClick = { onSelectLanguage(lang.code); langMenuOpen = false }) {
                            Text(lang.flag, fontSize = 17.6.sp)
                            Text(
                                lang.native, color = Px.TextMuted, fontSize = 14.sp,
                                fontWeight = if (lang.code == currentLangCode) FontWeight.Bold else FontWeight.Normal
                            )
                        }
                    }
                }
            }
            if (userMenuOpen) userRect?.let { a ->
                AnchoredMenu(a, 200.dp, { userMenuRect = it }) {
                    Column(
                        Modifier.fillMaxWidth().drawBehind {
                            val w = 1.dp.toPx()
                            drawLine(Px.Border, Offset(0f, size.height - w / 2), Offset(size.width, size.height - w / 2), w)
                        }.padding(start = 15.dp, end = 15.dp, top = 11.dp, bottom = 9.dp)
                    ) {
                        Text(user?.name ?: "", color = Px.TextLight, fontWeight = FontWeight.Bold, fontSize = 14.4.sp)
                        Text("@${user?.username ?: ""}", color = Px.TextMuted, fontSize = 11.84.sp, modifier = Modifier.padding(top = 2.dp))
                        user?.email?.takeIf { it.isNotBlank() }?.let {
                            Text(it, color = Px.TextMuted, fontSize = 11.52.sp, modifier = Modifier.padding(top = 1.dp))
                        }
                        Box(Modifier.padding(top = 6.dp)) {
                            Badge(
                                (plan?.id ?: "free").replaceFirstChar { it.uppercase() },
                                if (isPremium) Color(0x24E50914) else Color(0x12FFFFFF),
                                if (isPremium) Color(0xFFFF6B6B) else Px.TextMuted,
                                10.72.sp, hPad = 9.dp, vPad = 3.dp,
                            )
                        }
                    }
                    if (profiles.isNotEmpty()) {
                        Text(
                            t.t("nav.profiles").uppercase(), color = Px.TextMuted, fontSize = 10.88.sp, fontWeight = FontWeight.Bold,
                            letterSpacing = 0.04.sp,
                            modifier = Modifier.padding(start = 15.dp, end = 15.dp, top = 9.dp, bottom = 4.dp)
                        )
                        profiles.forEach { p ->
                            MenuItem(onClick = { onSelectProfile(p.id); userMenuOpen = false }, gap = 8.dp) {
                                Box(
                                    Modifier.size(22.dp).clip(CircleShape)
                                        .background(Color.White),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Text(p.name.take(1).ifEmpty { "?" }.uppercase(), color = Color.Black, fontSize = 10.88.sp, fontWeight = FontWeight.ExtraBold)
                                }
                                Text(p.name, color = Px.TextMuted, fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                                if (p.isKid == true) Icon(Icons.Filled.ChildCare, null, Modifier.size(14.dp), tint = Px.TextMuted)
                                if (p.id == activeProfileId) Icon(Icons.Filled.Check, null, Modifier.size(15.dp), tint = Px.Primary)
                            }
                        }
                        MenuSep()
                    }
                    MenuItem(onClick = { onNavigate(MainDest.ACCOUNT); userMenuOpen = false }) { Text(t.t("nav.account"), color = Px.TextMuted, fontSize = 14.sp) }
                    MenuItem(onClick = { onNavigate(MainDest.PLANS); userMenuOpen = false }) { Text(t.t("nav.upgrade"), color = Px.TextMuted, fontSize = 14.sp) }
                    MenuItem(onClick = { onNavigate(MainDest.MY_LIST); userMenuOpen = false }) { Text(t.t("nav.myList"), color = Px.TextMuted, fontSize = 14.sp) }
                    if (canDownload) MenuItem(onClick = { onOpenDownloads(); userMenuOpen = false }) { Text("Downloads", color = Px.TextMuted, fontSize = 14.sp) }
                    MenuSep()
                    MenuItem(onClick = { userMenuOpen = false; onUploadClick() }) {
                        Icon(Icons.Filled.CloudUpload, null, Modifier.size(15.dp), tint = Px.TextMuted)
                        Text(t.t("nav.upload"), color = Px.TextMuted, fontSize = 14.sp)
                    }
                    MenuSep()
                    MenuItem(onClick = { userMenuOpen = false; onSignOut() }) {
                        Icon(Icons.Filled.Logout, null, Modifier.size(15.dp), tint = Px.Primary)
                        Text(t.t("nav.signOut"), color = Px.Primary, fontSize = 14.sp)
                    }
                }
            }

            // ── .search-dropdown (top:calc(100%+6px); left:0; right:0; min-width:300px)
            if (showDrop) searchRect?.let { rect ->
                val w = with(density) { max(rect.width.toDp().value, 300f).dp }
                val shape = RoundedCornerShape(Px.Radius)
                FadeIn(
                    Modifier
                        .offset { IntOffset(rect.left.roundToInt(), (rect.bottom + 6.dp.toPx()).roundToInt()) }
                        .width(w)
                        .onGloballyPositioned { dropRect = it.boundsInRoot() }
                        .shadow(20.dp, shape, ambientColor = Color.Black, spotColor = Color.Black)
                        .clip(shape)
                        .background(Px.CardBg)
                        .border(1.dp, Px.Border, shape)
                ) {
                    if (results.isNotEmpty()) {
                        results.forEach { item ->
                            Row(
                                Modifier.fillMaxWidth().heightIn(min = 44.dp)
                                    .tap { onOpenContent(item.id); query = ""; showDrop = false }
                                    .padding(horizontal = 13.dp, vertical = 9.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(10.dp),
                            ) {
                                item.displayPoster?.let {
                                    AsyncImage(it, null, Modifier.size(48.dp, 36.dp).clip(RoundedCornerShape(4.dp)), contentScale = ContentScale.Crop)
                                }
                                Column {
                                    Text(item.displayTitle, color = Px.TextLight, fontSize = 14.sp, fontWeight = FontWeight.Medium)
                                    Text("${item.year ?: ""} · ${item.type ?: ""}", color = Px.TextMuted, fontSize = 11.52.sp, modifier = Modifier.padding(top = 2.dp))
                                }
                            }
                        }
                        Box(
                            Modifier.fillMaxWidth().heightIn(min = 40.dp)
                                .drawBehind { drawLine(Px.Border, Offset(0f, 0.5.dp.toPx()), Offset(size.width, 0.5.dp.toPx()), 1.dp.toPx()) }
                                .tap { onSubmitSearch(query); showDrop = false }
                                .padding(horizontal = 13.dp, vertical = 9.dp),
                            contentAlignment = Alignment.Center
                        ) { Text("Ver todos os resultados →", color = Px.Primary, fontSize = 13.12.sp, fontWeight = FontWeight.SemiBold) }
                    } else {
                        Text(
                            t.t("common.noResults"), color = Px.TextMuted, fontSize = 14.sp, textAlign = TextAlign.Center,
                            modifier = Modifier.fillMaxWidth().padding(14.dp)
                        )
                    }
                }
            }
        }
    }
}

// ───────────────────────── peças (classes do globals.css) ─────────────────────────

/** `.search-input` (height 38, bg rgba(255,255,255,.07), borda --color-border, radius 12, paddingLeft 33). */
@Composable
private fun HeaderSearch(
    query: String,
    onQueryChange: (String) -> Unit,
    placeholder: String,
    maxInputWidth: Dp,
    onFocus: () -> Unit,
    onSubmit: () -> Unit,
) {
    val shape = RoundedCornerShape(Px.Radius)
    BasicTextField(
        value = query,
        onValueChange = onQueryChange,
        singleLine = true,
        textStyle = TextStyle(fontFamily = Poppins, fontSize = 14.sp, color = Px.TextLight),
        cursorBrush = SolidColor(Px.TextLight),
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
        keyboardActions = KeyboardActions(onSearch = { onSubmit() }),
        modifier = Modifier
            .then(if (maxInputWidth != Dp.Unspecified) Modifier.widthIn(max = maxInputWidth) else Modifier)
            .fillMaxWidth()
            .onFocusChanged { if (it.isFocused) onFocus() },
        decorationBox = { inner ->
            Box(
                Modifier.fillMaxWidth().height(38.dp).clip(shape)
                    .background(Color(0x12FFFFFF)).border(1.dp, Px.Border, shape),
                contentAlignment = Alignment.CenterStart
            ) {
                Icon(Icons.Filled.Search, null, Modifier.padding(start = 11.dp).size(16.dp), tint = Px.TextMuted)
                Box(Modifier.padding(start = 33.dp, end = 8.dp)) {
                    if (query.isEmpty()) Text(placeholder, color = Px.TextMuted.copy(alpha = 0.65f), fontSize = 14.sp, maxLines = 1)
                    inner()
                }
            }
        }
    )
}

/** `.spinner` (borda 2px rgba(255,255,255,.1), topo --color-primary, 0.7s linear). */
@Composable
private fun Spinner(size: Dp) {
    val rot by rememberInfiniteTransition(label = "spin").animateFloat(
        0f, 360f, infiniteRepeatable(tween(700, easing = LinearEasing)), label = "rot"
    )
    Canvas(Modifier.size(size).graphicsLayer { rotationZ = rot }) {
        val sw = 2.dp.toPx()
        val s = Size(this.size.width - sw, this.size.height - sw)
        val tl = Offset(sw / 2, sw / 2)
        drawArc(Color(0x1AFFFFFF), 0f, 360f, false, tl, s, style = Stroke(sw))
        drawArc(Px.Primary, -90f, 90f, false, tl, s, style = Stroke(sw))
    }
}

/** `.fade-in`: fadeIn 0.22s ease (opacity 0→1, translateY 8px→0). */
@Composable
private fun FadeIn(modifier: Modifier, content: @Composable ColumnScope.() -> Unit) {
    var shown by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { shown = true }
    val a by animateFloatAsState(if (shown) 1f else 0f, tween(220, easing = CssEase), label = "fadeIn")
    Column(
        Modifier.graphicsLayer { alpha = a; translationY = (1f - a) * 8.dp.toPx() }.then(modifier),
        content = content
    )
}

/** `.dropdown`: top:calc(100%+8px); right:0 — alinhado à direita do botão. */
@Composable
private fun AnchoredMenu(anchor: Rect, minWidth: Dp, onBounds: (Rect) -> Unit, content: @Composable ColumnScope.() -> Unit) {
    val gap = with(LocalDensity.current) { 8.dp.roundToPx() }
    val shape = RoundedCornerShape(Px.Radius)
    FadeIn(
        Modifier
            // O nó ocupa toda a área para o hit-test do menu cair dentro dos limites do pai.
            .layout { m, c ->
                val p = m.measure(c.copy(minWidth = 0, minHeight = 0))
                layout(c.maxWidth, c.maxHeight) {
                    p.place((anchor.right - p.width).roundToInt().coerceAtLeast(0), (anchor.bottom + gap).roundToInt())
                }
            }
            .onGloballyPositioned { onBounds(it.boundsInRoot()) }
            .shadow(24.dp, shape, ambientColor = Color.Black, spotColor = Color.Black)
            .clip(shape)
            .background(Px.CardBg)
            .border(1.dp, Px.Border, shape)
            .widthIn(min = minWidth)
            .width(IntrinsicSize.Max),
        content = content
    )
}

/** `.dropdown-item`: padding 9/15, gap 10, min-height 40, 0.875rem, --color-text-muted. */
@Composable
private fun MenuItem(
    gap: Dp = 10.dp,
    onClick: () -> Unit,
    content: @Composable RowScope.() -> Unit,
) {
    Row(
        Modifier.fillMaxWidth().heightIn(min = 40.dp).tap(onClick).padding(horizontal = 15.dp, vertical = 9.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(gap),
        content = content,
    )
}

/** `.dropdown-sep`: 1px --color-border, margin 3px 0. */
@Composable
private fun MenuSep() {
    Box(Modifier.fillMaxWidth().padding(vertical = 3.dp).height(1.dp).background(Px.Border))
}

/** `.nav-section-label`: 0.58rem, 700, letter-spacing .12em, uppercase, opacity .55. */
@Composable
private fun SectionLabel(text: String) {
    Text(
        text.uppercase(),
        color = Px.TextMuted.copy(alpha = 0.55f),
        fontSize = 9.28.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.11.sp,
        modifier = Modifier.padding(start = 10.dp, end = 10.dp, bottom = 5.dp)
    )
}

/** `.nav-badge` / `.badge` */
@Composable
private fun Badge(
    text: String, bg: Color, fg: Color, size: TextUnit,
    hPad: Dp = 7.dp, vPad: Dp = 2.dp,
) {
    Text(
        text, color = fg, fontSize = size, fontWeight = FontWeight.Bold,
        modifier = Modifier.clip(RoundedCornerShape(99.dp)).background(bg).padding(horizontal = hPad, vertical = vPad)
    )
}

/** `.nav-item` (+ `.active` com barra lateral 3×18). */
@Composable
private fun NavItem(
    label: String,
    icon: ImageVector,
    active: Boolean,
    color: Color = Px.TextMuted,
    badge: (@Composable () -> Unit)? = null,
    iconTint: Color? = null,
    onClick: () -> Unit,
) {
    val shape = RoundedCornerShape(Px.RadiusSm)
    val fg = if (active) Color.White else color
    Box(
        Modifier.fillMaxWidth().heightIn(min = 44.dp).clip(shape)
            .then(
                if (active) Modifier
                    .background(Brush.linearGradient(listOf(Color(0x38E50914), Color(0x14E50914))))
                    .border(1.dp, Color(0x47E50914), shape)
                else Modifier.border(1.dp, Color.Transparent, shape)
            )
            .tap(onClick)
    ) {
        Row(
            Modifier.fillMaxWidth().heightIn(min = 44.dp).padding(horizontal = 12.dp, vertical = 9.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Icon(icon, null, Modifier.size(17.dp), tint = iconTint ?: fg)
            Text(label, color = fg, fontSize = 14.sp, fontWeight = FontWeight.Medium, modifier = Modifier.weight(1f))
            badge?.invoke()
        }
        if (active) {
            Box(
                Modifier.align(Alignment.CenterStart).width(3.dp).height(18.dp)
                    .clip(RoundedCornerShape(topEnd = 3.dp, bottomEnd = 3.dp)).background(Px.Primary)
            )
        }
    }
}

/** `.sidebar-footer` — botão de denúncia de direitos autorais e sair. (Instalar/Baixar app não existem no APK.) */
@Composable
private fun SidebarFooter(onSignOut: () -> Unit, onReport: () -> Unit) {
    val t = LocalTranslator.current
    val navBottom = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
    Column(
        Modifier.fillMaxWidth()
            .drawBehind { drawLine(Px.Border, Offset(0f, 0.5.dp.toPx()), Offset(size.width, 0.5.dp.toPx()), 1.dp.toPx()) }
            .padding(start = 10.dp, end = 10.dp, top = 8.dp, bottom = 10.dp + navBottom)
    ) {
        Column(
            Modifier.fillMaxWidth()
                .drawBehind { drawLine(Color(0x0DFFFFFF), Offset(0f, 0.5.dp.toPx()), Offset(size.width, 0.5.dp.toPx()), 1.dp.toPx()) }
                .padding(start = 10.dp, end = 10.dp, top = 10.dp, bottom = 8.dp)
        ) {
            Text(
                t.t("contact.copyright").uppercase(), color = Color(0x4DFFFFFF),
                fontSize = 9.6.sp, fontWeight = FontWeight.Bold, letterSpacing = 0.96.sp,
                modifier = Modifier.padding(bottom = 7.dp)
            )
            // .sidebar-report
            val shape = RoundedCornerShape(Px.RadiusSm)
            Row(
                Modifier.fillMaxWidth().heightIn(min = 40.dp).clip(shape)
                    .background(Color(0x1AE50914)).border(1.dp, Color(0x4DE50914), shape)
                    .tap(onReport)
                    .padding(horizontal = 14.dp, vertical = 9.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
            ) {
                Icon(Icons.Outlined.Flag, null, Modifier.size(16.dp), tint = Px.Primary)
                Text(t.t("contact.reportCopyrightButton"), color = Px.TextLight, fontSize = 13.12.sp, fontWeight = FontWeight.Bold)
            }
        }
        NavItem(t.t("nav.signOut"), Icons.Filled.Logout, false, onClick = onSignOut)
    }
}
