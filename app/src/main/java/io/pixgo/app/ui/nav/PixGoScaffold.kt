package io.pixgo.app.ui.nav

import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AccountCircle
import androidx.compose.material.icons.filled.Explore
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.LiveTv
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Bookmark
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import io.pixgo.app.data.i18n.Language
import io.pixgo.app.data.i18n.SUPPORTED_LANGUAGES

/**
 * Estrutura de navegação — 5 destinos principais no bottom nav, com os
 * rótulos REAIS de i18n/locales/pt.json (nav.*), não os nomes das rotas
 * (que continuam catalog/channels/mylist/search por baixo). Confirmado
 * pelo dono do projecto: os rótulos visíveis mudaram e são "cosméticos"
 * mas importantes — não usar "Catálogo"/"Canais"/"Minha Lista" na UI.
 */
enum class BottomDest(val route: String, val label: String) {
    HOME("home", "Tendências"),
    EXPLORE("explore", "Explorar"),
    LIVE_TV("livetv", "Sinal Aberto"),
    MY_LIST("mylist", "Minha Coleção"),
    SEARCH("search", "Pesquisar"),
}

/** Itens da secção de conta — nav.profiles/downloads/upload/upgrade/account/signOut. */
enum class AccountAction(val label: String) {
    PROFILES("Perfis"),
    DOWNLOADS("Downloads"),
    UPLOAD("Enviar conteúdo"),
    UPGRADE("Assinar Premium"),
    ACCOUNT("Conta"),
    LEGAL("Informação Legal"),
    SIGN_OUT("Sair"),
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PixGoScaffold(
    current: BottomDest,
    onSelectDest: (BottomDest) -> Unit,
    currentLangCode: String,
    onSelectLanguage: (String) -> Unit,
    onAccountAction: (AccountAction) -> Unit,
    content: @Composable (PaddingValues) -> Unit
) {
    var langMenuOpen by remember { mutableStateOf(false) }
    var accountMenuOpen by remember { mutableStateOf(false) }
    val currentLang = SUPPORTED_LANGUAGES.find { it.code == currentLangCode } ?: SUPPORTED_LANGUAGES.first()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("PixGo", fontWeight = FontWeight.Bold) },
                actions = {
                    Box {
                        IconButton(onClick = { langMenuOpen = true }) {
                            Text(currentLang.flag)
                        }
                        DropdownMenu(expanded = langMenuOpen, onDismissRequest = { langMenuOpen = false }) {
                            SUPPORTED_LANGUAGES.forEach { lang: Language ->
                                DropdownMenuItem(
                                    text = {
                                        Row(verticalAlignment = Alignment.CenterVertically) {
                                            Text(lang.flag)
                                            Spacer(Modifier.width(8.dp))
                                            Text(
                                                lang.native,
                                                fontWeight = if (lang.code == currentLangCode) FontWeight.Bold else FontWeight.Normal
                                            )
                                        }
                                    },
                                    onClick = {
                                        langMenuOpen = false
                                        onSelectLanguage(lang.code)
                                    }
                                )
                            }
                        }
                    }
                    Box {
                        IconButton(onClick = { accountMenuOpen = true }) {
                            Icon(Icons.Filled.AccountCircle, contentDescription = "Conta")
                        }
                        DropdownMenu(expanded = accountMenuOpen, onDismissRequest = { accountMenuOpen = false }) {
                            AccountAction.entries.forEach { action ->
                                DropdownMenuItem(
                                    text = { Text(action.label) },
                                    onClick = {
                                        accountMenuOpen = false
                                        onAccountAction(action)
                                    }
                                )
                            }
                        }
                    }
                }
            )
        },
        bottomBar = {
            NavigationBar {
                val icons = mapOf(
                    BottomDest.HOME to Icons.Filled.Home,
                    BottomDest.EXPLORE to Icons.Filled.Explore,
                    BottomDest.LIVE_TV to Icons.Filled.LiveTv,
                    BottomDest.MY_LIST to Icons.Filled.Bookmark,
                    BottomDest.SEARCH to Icons.Filled.Search,
                )
                BottomDest.entries.forEach { dest ->
                    NavigationBarItem(
                        selected = dest == current,
                        onClick = { onSelectDest(dest) },
                        icon = { Icon(icons.getValue(dest), contentDescription = dest.label) },
                        label = { Text(dest.label) }
                    )
                }
            }
        }
    ) { padding -> content(padding) }
}
