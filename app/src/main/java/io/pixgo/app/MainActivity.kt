package io.pixgo.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import io.pixgo.app.data.auth.ApiException
import io.pixgo.app.data.auth.AuthState
import io.pixgo.app.data.i18n.LocalTranslator
import io.pixgo.app.data.i18n.Translator
import io.pixgo.app.data.i18n.contentLangFor
import io.pixgo.app.ui.chat.PixelChatbot
import io.pixgo.app.ui.common.applyImmersive
import io.pixgo.app.ui.nav.MainDest
import io.pixgo.app.ui.nav.PixGoScaffold
import io.pixgo.app.ui.theme.PixGoTheme
import io.pixgo.app.ui.theme.Px
import androidx.compose.foundation.Image
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.input.KeyboardType
import io.pixgo.app.ui.auth.AuthScreen
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Login/Home ficam sob o NavHost de topo; o shell (header + sidebar, réplica de
 * AppShell.tsx) vive em io.pixgo.app.ui.nav.PixGoScaffold.
 */
sealed class Dest(val route: String) {
    object Login : Dest("login")
    object Home : Dest("home")
}

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // FULLSCREEN NATIVO: edge-to-edge + modo imersivo. Sem Navigation Bar a fazer de footer.
        window.applyImmersive()
        io.pixgo.app.data.prefs.UiPrefs.init(this)
        val app = application as PixGoApp
        setContent {
            PixGoTheme {
                val ctx = LocalContext.current
                val langCode by app.languageManager.languageCode.collectAsStateWithLifecycle(initialValue = "pt")
                // Página de escolha de idioma REMOVIDA do fluxo (pedido explícito): a app
                // entra sempre em português por defeito (LanguageManager.languageCode
                // devolve "pt" enquanto não houver escolha). O seletor pt/en/es continua
                // disponível no header, sem nunca bloquear a entrada.
                val translator = remember(langCode) { Translator.create(ctx, langCode) }
                CompositionLocalProvider(LocalTranslator provides translator) {
                    val authState by app.authRepository.state.collectAsStateWithLifecycle()
                    PixGoRoot(authState, app)
                }
            }
        }
    }

    // Diálogos/teclado/gestos trazem as barras de volta; reaplicamos ao recuperar o foco.
    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) window.applyImmersive()
    }
}

/**
 * Raiz da app: sem token (após hidratar) -> login; com token -> home/shell.
 *
 * Antes era um NavHost de 2 destinos com `startDestination` calculado a partir do
 * estado de auth + um LaunchedEffect a chamar navigate(). Isso (1) mostrava a
 * Home por instantes antes de ir para o Login, (2) deixava uma pilha de
 * navegação em que o botão Voltar SAÍA DA APP ou voltava ao Login, e (3) ao
 * recompor reiniciava o destino. Agora é uma escolha directa e estável: a
 * navegação interna (HomeShell) é própria e o Voltar é tratado lá.
 */
@Composable
fun PixGoRoot(authState: AuthState, app: PixGoApp) {
    when {
        // Ainda a ler o token do disco: fundo escuro (sem piscar Login/Home).
        !authState.hydrated -> Box(Modifier.fillMaxSize().background(Px.BgDark))
        authState.token == null -> LoginScreen()
        else -> HomeShell(authState, app)
    }
}

/**
 * Tela de entrada — login e registo NATIVOS (ui/auth/AuthScreens.kt), réplica de
 * LoginPage.tsx / RegisterPage.tsx do hub com o mesmo CSS. Chamam diretamente
 * POST /api/auth/login | register | google; ao autenticar, AuthRepository
 * preenche o token e o PixGoNavHost navega sozinho para a Home.
 * Sem WebView e sem campo de código de TV.
 */
@Composable
fun LoginScreen() {
    val context = LocalContext.current
    val app = context.applicationContext as PixGoApp
    AuthScreen(authRepository = app.authRepository)
}

@OptIn(androidx.media3.common.util.UnstableApi::class)
@Composable
fun HomeShell(authState: AuthState, app: PixGoApp) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    // Estado de navegação sobrevivente a recomposição (ex.: rotação) — o
    // padrão do web é URL-based; aqui persistimos o destino + contexto aberto.
    val navSaved = rememberSaveable { mutableStateOf(MainDest.HOME) }
    val watchSaved = rememberSaveable { mutableStateOf<String?>(null) }
    val watchOfflineSaved = rememberSaveable {
        mutableStateOf<Pair<String, String?>?>(null)
    }
    var current by navSaved
    var searchQuery by rememberSaveable { mutableStateOf("") }
    val langCode by app.languageManager.languageCode.collectAsStateWithLifecycle(initialValue = "pt")
    // Estado da rota /main/plans (tela nativa PlansScreen): "highlight" é o
    // ?highlight= do RateLimitModal/ChannelsRateLimit do web; pendingCheckout
    // guarda a URL de checkout que PlansScreen pediu para abrir (handleSubscribe
    // → HUB_CHECKOUT_URL?plan=&return_to=), consumida pelo FastWebViewSheet.
    var plansHighlight by rememberSaveable { mutableStateOf<String?>(null) }
    fun openPlans(highlight: String? = null) {
        plansHighlight = highlight
        current = MainDest.PLANS
    }
    // URL do checkout do hub aberta a partir da tela Plans ("Assinar" →
    // HUB_CHECKOUT_URL?plan=&return_to=, exactamente como handleSubscribe em
    // plans/page.tsx). null = nenhuma sheet de checkout aberta.
    var checkoutUrl by rememberSaveable { mutableStateOf<String?>(null) }
    // O aviso jurídico dos planos nunca é memorizado no web (PlansNoticeModal):
    // abre SEMPRE que se entra em /main/plans (useState(true) na página) e
    // volta a abrir antes de cada checkout. `plansNoticeOpen` cobre a entrada
    // no ecrã; `plansNoticeShown` o fluxo de checkout (comportamento anterior).
    var plansNoticeShown by rememberSaveable { mutableStateOf(false) }
    var plansNoticeOpen by rememberSaveable { mutableStateOf(false) }
    // Só reage a MUDANÇAS reais de destino/checkout. Antes o LaunchedEffect corria
    // também em cada (re)composição inicial — ao virar o ecrã o modal dos planos
    // voltava a abrir em loop.
    var lastDest by rememberSaveable { mutableStateOf(current) }
    var lastCheckout by rememberSaveable { mutableStateOf(checkoutUrl) }
    LaunchedEffect(checkoutUrl) {
        if (checkoutUrl != lastCheckout) {
            lastCheckout = checkoutUrl
            if (checkoutUrl != null) plansNoticeShown = false
        }
    }
    LaunchedEffect(current) {
        if (current != lastDest) {
            lastDest = current
            plansNoticeOpen = current == MainDest.PLANS
        }
    }
    var watchContentId by watchSaved
    // Abertura de download concluído pela tela Downloads → Watch em modo
    // offline (PlayerScreen usa PlayerRepository.startLocal; sem rede).
    var watchOffline by watchOfflineSaved
    // Contagem real do badge — DownloadStore reconcilia DataStore + disco.
    val downloadsStore = remember { io.pixgo.app.data.download.DownloadStore(context) }
    var downloadsCount by remember { mutableStateOf(0) }
    LaunchedEffect(Unit) {
        while (true) {
            downloadsCount = runCatching { downloadsStore.allOnce().size }.getOrDefault(0)
            delay(3_000L)
        }
    }
    // "Enviar conteúdo": no Android só abre o modal (envio é feito na plataforma web).
    var showUploadWebOnly by rememberSaveable { mutableStateOf(false) }
    // Central de direitos autorais (/copyright, /copyright/response, /copyright/portal, /legal) em ecrã inteiro.
    var showCopyright by rememberSaveable { mutableStateOf(false) }
    var watchingChannel by remember { mutableStateOf<io.pixgo.app.data.channels.ChannelListItem?>(null) }
    // DisclaimerGate real (Providers.tsx): com sessão ativa, o DisclaimerModal
    // aparece até aceitar; "Recusar" memoriza pixgo_disclaimer_dismissed.
    var showDisclaimer by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(authState.hydrated, authState.token) {
        // SessionFlags: depois de aceitar/recusar NÃO volta a abrir nesta sessão,
        // mesmo que a composição seja recriada (era o "modal em loop").
        if (authState.hydrated && authState.token != null &&
            !io.pixgo.app.data.prefs.SessionFlags.disclaimerHandled &&
            !app.authRepository.isDisclaimerDismissed()
        ) showDisclaimer = true
    }
    if (showDisclaimer) {
        io.pixgo.app.ui.modals.DisclaimerDialog(
            onAccept = {
                io.pixgo.app.data.prefs.SessionFlags.disclaimerHandled = true
                showDisclaimer = false
            },
            onDismiss = {
                io.pixgo.app.data.prefs.SessionFlags.disclaimerHandled = true
                showDisclaimer = false
                app.ioScope.launch { app.authRepository.setDisclaimerDismissed(true) }
            },
        )
    }
    val activeProfile = authState.profiles.find { it.id == authState.activeProfileId }

    // VOLTAR do Android (todas as páginas): fora da Home, volta à Home em vez de
    // SAIR da app. Só na Home (sem nada aberto por cima) o sistema fecha a app.
    // Os overlays (Watch, Canal, Copyright, Checkout) e o menu lateral registam o
    // seu próprio BackHandler mais abaixo na composição e, por isso, têm
    // prioridade sobre este.
    BackHandler(enabled = current != MainDest.HOME) {
        searchQuery = ""
        current = MainDest.HOME
    }
    var snackbarText by remember { mutableStateOf<String?>(null) }
    val snackbarHostState = remember { SnackbarHostState() }

    LaunchedEffect(snackbarText) {
        snackbarText?.let { snackbarHostState.showSnackbar(it) }
    }

    Box(Modifier.fillMaxSize()) {
        PixGoScaffold(
            current = current,
            onNavigate = { dest -> searchQuery = ""; current = dest },
            user = authState.user,
            plan = authState.plan,
            profiles = authState.profiles,
            activeProfileId = authState.activeProfileId,
            onSelectProfile = { id -> scope.launch { app.authRepository.setActiveProfile(id) } },
            currentLangCode = langCode,
            onSelectLanguage = { code ->
                scope.launch {
                    app.languageManager.setLanguage(code)
                    app.authRepository.setLanguageServerSide(code)
                }
            },
            // Downloads offline nativos — DownloadEngine/DownloadStore
            // (equivalente de lib/downloads.ts + IndexedDB do web).
            downloadCount = downloadsCount,
            onOpenDownloads = { current = MainDest.DOWNLOADS },
            onUpgrade = { openPlans() },
            onSignOut = { scope.launch { app.authRepository.logout() } },
            suggest = { q -> app.catalogRepository.search(q, contentLangFor(langCode), limit = 6) },
            onOpenContent = { id -> watchContentId = id },
            onSubmitSearch = { q -> searchQuery = q; current = MainDest.SEARCH },
            onUploadClick = { showUploadWebOnly = true },
            onOpenCopyright = { showCopyright = true },
        ) {
            when (current) {
                MainDest.HOME -> io.pixgo.app.ui.home.HomeScreen(
                    catalogRepository = app.catalogRepository,
                    activeProfileId = authState.activeProfileId,
                    uiLang = langCode,
                    onOpenContent = { id -> watchContentId = id }
                )
                MainDest.CATALOG -> io.pixgo.app.ui.explore.ExploreScreen(
                    catalogRepository = app.catalogRepository,
                    activeProfileId = authState.activeProfileId,
                    isKidProfile = activeProfile?.isKid == true,
                    uiLang = langCode,
                    onOpenContent = { id -> watchContentId = id },
                    // PlansModal.tsx: isFree = !!user && (!user.plan_id || user.plan_id === 'free')
                    isFreeUser = authState.user != null &&
                        (authState.user.planId.isNullOrEmpty() || authState.user.planId == "free"),
                    loadPlans = { app.authRepository.paymentPlans() },
                    plansModalLastSeen = { app.authRepository.plansModalLastSeen() },
                    markPlansModalSeen = { day -> app.authRepository.markPlansModalSeen(day) },
                    onSeePlans = { openPlans() },
                )
                MainDest.MY_LIST -> io.pixgo.app.ui.mylist.MyListScreen(
                    catalogRepository = app.catalogRepository,
                    activeProfileId = authState.activeProfileId,
                    onOpenContent = { id -> watchContentId = id },
                    onBrowseCatalog = { current = MainDest.CATALOG }
                )
                MainDest.SEARCH -> io.pixgo.app.ui.search.SearchScreen(
                    catalogRepository = app.catalogRepository,
                    uiLang = langCode,
                    onOpenContent = { id -> watchContentId = id },
                    initialQuery = searchQuery,
                    activeProfileId = authState.activeProfileId,
                )
                MainDest.LIVE_TV -> io.pixgo.app.ui.channels.ChannelsScreen(
                    repository = app.channelsRepository,
                    hasUser = authState.token != null,
                    onOpenChannel = { ch -> watchingChannel = ch },
                    onUpgrade = { planId -> openPlans(planId) }
                )
                MainDest.ACCOUNT -> io.pixgo.app.ui.account.AccountScreen(
                    authRepository = app.authRepository,
                    contactRepository = app.contactRepository,
                    onForcedLogout = { current = MainDest.HOME },
                    onOpenPlans = { openPlans() },
                    onOpenCopyright = { showCopyright = true }
                )
                MainDest.LEGAL -> io.pixgo.app.ui.legal.LegalScreen(
                    legalRepository = app.legalRepository,
                    uiLang = langCode,
                    onReportCopyright = { showCopyright = true }
                )
                MainDest.DOWNLOADS -> io.pixgo.app.ui.downloads.DownloadsScreen(
                    authState = authState,
                    onOpenDownload = { cid, ep -> watchOffline = cid to ep },
                    onUpgrade = { openPlans() },
                    onBrowseCatalog = { current = MainDest.CATALOG }
                )
                // /main/plans real (page.tsx) — tela nativa; "Assinar" abre o
                // checkout do hub (?plan=&return_to=) na WebView única.
                MainDest.PLANS -> io.pixgo.app.ui.plans.PlansScreen(
                    authRepository = app.authRepository,
                    plan = authState.plan,
                    highlight = plansHighlight,
                    onSubscribe = { url -> checkoutUrl = url }
                )
            }
        }

        SnackbarHost(
            hostState = snackbarHostState,
            modifier = Modifier.align(Alignment.BottomCenter)
        )

        // main/layout.tsx renderiza <PixelChatbot /> ao lado do AppShell, em todas as páginas de /main.
        // Fica por baixo dos ecrãs a ecrã inteiro (Watch/Canal) — o web também o oculta nesses modais.
        if (watchContentId == null && watchOffline == null && watchingChannel == null && checkoutUrl == null && !showCopyright) {
            PixelChatbot(
                isGreeted = { app.authRepository.isPixelGreeted() },
                markGreeted = { app.authRepository.markPixelGreeted() },
                send = { msg, hist -> app.contactRepository.chat(msg, hist) },
            )
        }

        watchContentId?.let { id ->
            // Watch nativa (réplica de /main/watch/[id] ATIVO): chrome,
            // ações, episódios, recomendações, progresso e modais em volta
            // do PlayerScreen existente. Card → Watch direto; sem Content
            // Detail (banida) e sem Bottom Nav (banida).
            io.pixgo.app.ui.watch.WatchScreen(
                contentId = id,
                episodeId = null,
                authState = authState,
                catalogRepository = app.catalogRepository,
                uiLang = langCode,
                onClose = { watchContentId = null },
                onOpenRecommendation = { cid -> watchContentId = cid },
                onUpgrade = { openPlans() }
            )
        }

        // Download concluído aberto pela tela Downloads → Watch offline
        // (PlayerScreen detecta a sessão local via startLocal; sem rede).
        watchOffline?.let { (cid, ep) ->
            io.pixgo.app.ui.watch.WatchScreen(
                contentId = cid,
                episodeId = ep,
                authState = authState,
                catalogRepository = app.catalogRepository,
                uiLang = langCode,
                onClose = { watchOffline = null },
                onOpenRecommendation = { nid -> watchContentId = nid; watchOffline = null },
                onUpgrade = { openPlans() },
                offline = true
            )
        }

        // PlansNoticeModal real: no web ele vive em /plans (sempre ao entrar) e
        // na aba "subscription" da conta. No Android o checkout é o hub externo
        // (mesmo HUB_CHECKOUT_URL?plan=&return_to= de plans/page.tsx), então o
        // aviso aparece SEMPRE antes de abrir esse fluxo — nunca memorizado,
        // exatamente como no original.
        if (plansNoticeOpen && current == MainDest.PLANS && checkoutUrl == null) {
            // Entrada em /plans: o modal aparece SEMPRE (sem memória), por cima da
            // página já com os planos a carregar por baixo.
            io.pixgo.app.ui.modals.PlansNoticeDialog(
                onDismiss = { plansNoticeOpen = false }
            )
        }
        if (checkoutUrl != null && !plansNoticeShown) {
            io.pixgo.app.ui.modals.PlansNoticeDialog(
                onDismiss = { plansNoticeShown = true }
            )
        }

        watchingChannel?.let { ch ->
            val url = ch.url
            if (url != null) {
                io.pixgo.app.ui.channels.ChannelsPlayerScreen(
                    channelId = ch.id,
                    channelName = ch.name,
                    streamUrl = url,
                    channelsRepository = app.channelsRepository,
                    onClose = { watchingChannel = null },
                    onUpgrade = { planId -> watchingChannel = null; openPlans(planId) }
                )
            } else {
                LaunchedEffect(ch.id) { watchingChannel = null }
            }
        }

        // Central de direitos autorais: ecrã inteiro por cima do app (rotas de /copyright e /legal públicas).
        if (showCopyright) {
            io.pixgo.app.ui.copyright.CopyrightHost(
                repository = app.copyrightRepository,
                legalRepository = app.legalRepository,
                langCode = langCode,
                onSelectLanguage = { code ->
                    scope.launch {
                        app.languageManager.setLanguage(code)
                        app.authRepository.setLanguageServerSide(code)
                    }
                },
                onClose = { showCopyright = false },
            )
        }

        if (showUploadWebOnly) {
            io.pixgo.app.ui.modals.UploadWebOnlyDialog(onClose = { showUploadWebOnly = false })
        }

        // Checkout REAL do hub (handleSubscribe de plans/page.tsx →
        // HUB_CHECKOUT_URL?plan=&return_to=) na WebView única. A URL vem
        // SEMPRE da tela Plans nativa via onSubscribe; o fallback antigo
        // (abrir /main/plans direto, ignorando a tela nativa) foi eliminado.
        checkoutUrl?.let { url ->
            if (plansNoticeShown) {
                io.pixgo.app.ui.webview.FastWebViewSheet(
                    url = url,
                    onClose = {
                        checkoutUrl = null
                        // Réplica do fluxo ?px_paid= (CheckoutStatusPage/ZumboPay): ao
                        // fechar, invalida o cache de /me para reflectir um plano novo
                        // sem esperar pelo TTL de 30min.
                        scope.launch { app.authRepository.invalidateMeCacheAfterPayment() }
                    }
                )
            }
        }
    }

}
