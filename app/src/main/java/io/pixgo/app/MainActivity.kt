package io.pixgo.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import io.pixgo.app.data.auth.ApiException
import io.pixgo.app.data.auth.AuthState
import kotlinx.coroutines.launch

/**
 * Login/Home ficam sob o NavHost de topo; a navegação interna da home
 * (5 destinos + menu de conta/idioma, com os rótulos REAIS de pt.json,
 * não os nomes de rota) vive em io.pixgo.app.ui.nav.PixGoScaffold.
 */
sealed class Dest(val route: String) {
    object Login : Dest("login")
    object Home : Dest("home")
}

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val app = application as PixGoApp
        setContent {
            MaterialTheme(colorScheme = darkColorScheme()) {
                val nav = rememberNavController()
                val authState by app.authRepository.state.collectAsStateWithLifecycle()
                PixGoNavHost(nav, authState, app)
            }
        }
    }
}

@Composable
fun PixGoNavHost(nav: NavHostController, authState: AuthState, app: PixGoApp) {
    // Réplica de MainLayout.tsx: sem token (após hidratar) -> login;
    // com token -> home/shell.
    val startDestination = if (authState.hydrated && authState.token == null) Dest.Login.route else Dest.Home.route

    NavHost(nav, startDestination = startDestination) {
        composable(Dest.Login.route) {
            LoginScreen(loading = authState.loading)
        }
        composable(Dest.Home.route) {
            HomeShell(authState, app)
        }
    }

    // Redireciona automaticamente quando o estado de auth muda, tal como
    // o `if (hydrated && !token) router.replace(...)` de main/layout.tsx.
    LaunchedEffect(authState.hydrated, authState.token) {
        if (authState.hydrated && authState.token == null && nav.currentDestination?.route != Dest.Login.route) {
            nav.navigate(Dest.Login.route)
        } else if (authState.token != null && nav.currentDestination?.route == Dest.Login.route) {
            nav.navigate(Dest.Home.route)
        }
    }
}

@Composable
fun LoginScreen(loading: Boolean) {
    var username by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    val context = androidx.compose.ui.platform.LocalContext.current
    val app = context.applicationContext as PixGoApp

    Column(
        modifier = Modifier.fillMaxSize().padding(24.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text("PixGo", style = MaterialTheme.typography.headlineLarge)
        Spacer(Modifier.height(24.dp))
        OutlinedTextField(value = username, onValueChange = { username = it }, label = { Text("Utilizador") })
        Spacer(Modifier.height(8.dp))
        OutlinedTextField(
            value = password, onValueChange = { password = it }, label = { Text("Senha") },
            visualTransformation = androidx.compose.ui.text.input.PasswordVisualTransformation()
        )
        error?.let {
            Spacer(Modifier.height(8.dp))
            Text(it, color = MaterialTheme.colorScheme.error)
        }
        Spacer(Modifier.height(16.dp))
        Button(
            enabled = !loading,
            onClick = {
                error = null
                scope.launch {
                    try {
                        app.authRepository.login(username, password)
                    } catch (e: ApiException) {
                        error = e.message
                    } catch (e: Exception) {
                        error = "Falha de rede."
                    }
                }
            }
        ) {
            if (loading) CircularProgressIndicator(modifier = Modifier.size(18.dp)) else Text("Entrar")
        }
    }
}

@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class, androidx.media3.common.util.UnstableApi::class)
@Composable
fun HomeShell(authState: AuthState, app: PixGoApp) {
    val scope = rememberCoroutineScope()
    var current by remember { mutableStateOf(io.pixgo.app.ui.nav.BottomDest.HOME) }
    val langCode by app.languageManager.languageCode.collectAsStateWithLifecycle(initialValue = "pt")
    var uploadDialog by remember { mutableStateOf(false) }
    var plansWebView by remember { mutableStateOf(false) }
    var accountScreenOpen by remember { mutableStateOf(false) }
    var legalScreenOpen by remember { mutableStateOf(false) }
    var watchContentId by remember { mutableStateOf<String?>(null) }
    var watchingChannel by remember { mutableStateOf<io.pixgo.app.data.channels.ChannelListItem?>(null) }
    val activeProfile = authState.profiles.find { it.id == authState.activeProfileId }
    var snackbarText by remember { mutableStateOf<String?>(null) }
    val snackbarHostState = remember { SnackbarHostState() }

    LaunchedEffect(snackbarText) {
        snackbarText?.let { snackbarHostState.showSnackbar(it) }
    }

    Box {
        io.pixgo.app.ui.nav.PixGoScaffold(
            current = current,
            onSelectDest = { current = it },
            currentLangCode = langCode,
            onSelectLanguage = { code ->
                scope.launch {
                    app.languageManager.setLanguage(code)
                    app.authRepository.setLanguageServerSide(code)
                }
            },
            onAccountAction = { action ->
                when (action) {
                    io.pixgo.app.ui.nav.AccountAction.UPLOAD -> uploadDialog = true
                    io.pixgo.app.ui.nav.AccountAction.UPGRADE -> plansWebView = true
                    io.pixgo.app.ui.nav.AccountAction.SIGN_OUT -> scope.launch { app.authRepository.logout() }
                    io.pixgo.app.ui.nav.AccountAction.ACCOUNT,
                    io.pixgo.app.ui.nav.AccountAction.PROFILES -> accountScreenOpen = true
                    io.pixgo.app.ui.nav.AccountAction.LEGAL -> legalScreenOpen = true
                    else -> snackbarText = "${action.label}: ainda por construir."
                }
            }
        ) { padding ->
            Box(Modifier.padding(padding)) {
                when (current) {
                    io.pixgo.app.ui.nav.BottomDest.HOME -> io.pixgo.app.ui.home.HomeScreen(
                        catalogRepository = app.catalogRepository,
                        activeProfileId = authState.activeProfileId,
                        uiLang = langCode,
                        onOpenContent = { id -> watchContentId = id }
                    )
                    io.pixgo.app.ui.nav.BottomDest.EXPLORE -> io.pixgo.app.ui.explore.ExploreScreen(
                        catalogRepository = app.catalogRepository,
                        activeProfileId = authState.activeProfileId,
                        isKidProfile = activeProfile?.isKid == true,
                        uiLang = langCode,
                        onOpenContent = { id -> watchContentId = id }
                    )
                    io.pixgo.app.ui.nav.BottomDest.MY_LIST -> io.pixgo.app.ui.mylist.MyListScreen(
                        catalogRepository = app.catalogRepository,
                        activeProfileId = authState.activeProfileId,
                        onOpenContent = { id -> watchContentId = id },
                        onBrowseCatalog = { current = io.pixgo.app.ui.nav.BottomDest.EXPLORE }
                    )
                    io.pixgo.app.ui.nav.BottomDest.SEARCH -> io.pixgo.app.ui.search.SearchScreen(
                        catalogRepository = app.catalogRepository,
                        uiLang = langCode,
                        onOpenContent = { id -> watchContentId = id }
                    )
                    io.pixgo.app.ui.nav.BottomDest.LIVE_TV -> io.pixgo.app.ui.channels.ChannelsScreen(
                        repository = app.channelsRepository,
                        hasUser = authState.token != null,
                        onOpenChannel = { ch -> watchingChannel = ch }
                    )
                }
            }
        }

        SnackbarHost(
            hostState = snackbarHostState,
            modifier = Modifier.align(Alignment.BottomCenter)
        )

        if (accountScreenOpen) {
            Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
                Column(Modifier.fillMaxSize()) {
                    TopAppBar(
                        title = { Text("Conta") },
                        navigationIcon = {
                            IconButton(onClick = { accountScreenOpen = false }) {
                                Icon(androidx.compose.material.icons.Icons.Filled.ArrowBack, contentDescription = "Voltar")
                            }
                        }
                    )
                    io.pixgo.app.ui.account.AccountScreen(
                        authRepository = app.authRepository,
                        contactRepository = app.contactRepository,
                        onForcedLogout = { accountScreenOpen = false },
                        onOpenPlans = { accountScreenOpen = false; plansWebView = true }
                    )
                }
            }
        }

        watchContentId?.let { id ->
            io.pixgo.app.ui.watch.PlayerScreen(
                contentId = id,
                episodeId = null,
                onClose = { watchContentId = null }
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
                    onClose = { watchingChannel = null }
                )
            } else {
                LaunchedEffect(ch.id) { watchingChannel = null }
            }
        }

        if (legalScreenOpen) {
            Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
                Column(Modifier.fillMaxSize()) {
                    TopAppBar(
                        title = { Text("Informação Legal") },
                        navigationIcon = {
                            IconButton(onClick = { legalScreenOpen = false }) {
                                Icon(androidx.compose.material.icons.Icons.Filled.ArrowBack, contentDescription = "Voltar")
                            }
                        }
                    )
                    io.pixgo.app.ui.legal.LegalScreen(legalRepository = app.legalRepository, uiLang = langCode)
                }
            }
        }

        if (plansWebView) {
            io.pixgo.app.ui.webview.FastWebViewSheet(
                url = "https://app.pixgo.qzz.io/main/plans",
                onClose = {
                    plansWebView = false
                    // Réplica do fluxo ?px_paid= (CheckoutStatusPage/ZumboPay): ao
                    // fechar, invalida o cache de /me para reflectir um plano novo
                    // sem esperar pelo TTL de 30min.
                    scope.launch { app.authRepository.invalidateMeCacheAfterPayment() }
                }
            )
        }
    }

    if (uploadDialog) {
        AlertDialog(
            onDismissRequest = { uploadDialog = false },
            confirmButton = {
                TextButton(onClick = { uploadDialog = false }) { Text("OK") }
            },
            title = { Text("Enviar conteúdo") },
            text = { Text("Uploads disponíveis no site apenas.") }
        )
    }
}
