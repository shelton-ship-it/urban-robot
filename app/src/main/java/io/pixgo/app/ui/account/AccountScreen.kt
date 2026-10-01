package io.pixgo.app.ui.account

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.pixgo.app.data.auth.AuthRepository
import io.pixgo.app.data.contact.ContactRepository
import io.pixgo.app.data.model.Profile
import io.pixgo.app.data.network.ProfileBody
import io.pixgo.app.data.network.UpdateMeBody
import kotlinx.coroutines.launch

/**
 * Réplica de app/main/account/page.tsx — 5 abas: Perfil/Segurança/
 * Assinatura/Perfis/Ajuda. Nomes de campo e regras (min 8 caracteres na
 * senha nova, logout forçado 1.5s depois de mudar a senha porque o
 * backend invalida as sessões, isPremium = plan.id !== 'free') todos
 * confirmados no ficheiro original.
 */
private enum class AccountTab(val label: String) {
    PROFILE("Perfil"), SECURITY("Segurança"), SUBSCRIPTION("Assinatura"), PROFILES("Perfis"), HELP("Ajuda")
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AccountScreen(
    authRepository: AuthRepository,
    contactRepository: ContactRepository,
    onForcedLogout: () -> Unit,
    onOpenPlans: () -> Unit
) {
    val state by authRepository.state.collectAsStateWithLifecycle()
    var tab by remember { mutableStateOf(AccountTab.PROFILE) }
    val scope = rememberCoroutineScope()

    Column(Modifier.fillMaxSize().padding(16.dp)) {
        Text("Configurações da Conta", style = MaterialTheme.typography.headlineSmall)
        Text("Gerencie seu perfil e assinatura", style = MaterialTheme.typography.bodySmall)
        Spacer(Modifier.height(16.dp))

        ScrollableTabRow(selectedTabIndex = tab.ordinal) {
            AccountTab.entries.forEach { t ->
                Tab(selected = t == tab, onClick = { tab = t }, text = { Text(t.label) })
            }
        }
        Spacer(Modifier.height(16.dp))

        when (tab) {
            AccountTab.PROFILE -> ProfileTab(authRepository, state.user?.name, state.user?.email, state.user?.username, scope)
            AccountTab.SECURITY -> SecurityTab(authRepository, scope, onForcedLogout)
            AccountTab.SUBSCRIPTION -> SubscriptionTab(state.plan, state.profiles.size, onOpenPlans)
            AccountTab.PROFILES -> ProfilesTab(authRepository, state.profiles, scope)
            AccountTab.HELP -> HelpTab(contactRepository, scope)
        }
    }
}

@Composable
private fun SubscriptionTab(plan: io.pixgo.app.data.model.Plan?, profileCount: Int, onOpenPlans: () -> Unit) {
    val isPremium = plan != null && plan.id != "free"
    Column {
        Row(
            Modifier.fillMaxWidth().padding(13.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column {
                Text(plan?.id ?: "free", fontWeight = androidx.compose.ui.text.font.FontWeight.Bold)
                Text(
                    if (isPremium) "Streaming ilimitado · Downloads" else "Streaming, limitado a 1h/dia",
                    style = MaterialTheme.typography.bodySmall
                )
                if (isPremium && plan?.expiresAt != null) {
                    Text("Válido até ${plan.expiresAt}", style = MaterialTheme.typography.bodySmall)
                }
                plan?.maxProfiles?.let {
                    Text("$profileCount de $it perfis usados", style = MaterialTheme.typography.bodySmall)
                }
            }
            if (isPremium) {
                AssistChip(onClick = {}, label = { Text("Ativo") })
            } else {
                Button(onClick = onOpenPlans) { Text("Assinar") }
            }
        }
        if (!isPremium) {
            Spacer(Modifier.height(12.dp))
            Card {
                Column(Modifier.padding(13.dp)) {
                    Text("Assine o Premium e tenha acesso ilimitado.")
                    Spacer(Modifier.height(8.dp))
                    Button(onClick = onOpenPlans) { Text("Ver planos") }
                }
            }
        }
    }
}

@Composable
private fun HelpTab(contactRepository: ContactRepository, scope: kotlinx.coroutines.CoroutineScope) {
    var reportTitle by remember { mutableStateOf("") }
    var reportReason by remember { mutableStateOf("") }
    var reportSending by remember { mutableStateOf(false) }
    var reportMsg by remember { mutableStateOf<String?>(null) }

    var supportEmail by remember { mutableStateOf("") }
    var supportMsg by remember { mutableStateOf("") }
    var supportSending by remember { mutableStateOf(false) }
    var supportResultMsg by remember { mutableStateOf<String?>(null) }

    val uriHandler = androidx.compose.ui.platform.LocalUriHandler.current

    Column(Modifier.verticalScroll(rememberScrollState())) {
        Text("Denunciar conteúdo abusivo ou ilícito", fontWeight = androidx.compose.ui.text.font.FontWeight.Bold)
        Spacer(Modifier.height(8.dp))
        OutlinedTextField(value = reportTitle, onValueChange = { reportTitle = it }, label = { Text("Título do conteúdo") }, modifier = Modifier.fillMaxWidth())
        Spacer(Modifier.height(8.dp))
        OutlinedTextField(value = reportReason, onValueChange = { reportReason = it }, label = { Text("Motivo da denúncia") }, modifier = Modifier.fillMaxWidth(), minLines = 3)
        reportMsg?.let { Text(it, modifier = Modifier.padding(top = 4.dp)) }
        Spacer(Modifier.height(8.dp))
        Button(
            enabled = !reportSending && reportTitle.isNotBlank() && reportReason.isNotBlank(),
            onClick = {
                reportSending = true
                scope.launch {
                    val ok = contactRepository.reportAbuse(reportTitle.trim(), reportReason.trim())
                    reportMsg = if (ok) {
                        reportTitle = ""; reportReason = ""
                        "Denúncia recebida. Obrigado por ajudar a manter a plataforma segura."
                    } else "Erro ao enviar."
                    reportSending = false
                }
            }
        ) { Text("Enviar denúncia") }

        Spacer(Modifier.height(24.dp))
        Text("Direitos autorais", fontWeight = androidx.compose.ui.text.font.FontWeight.Bold)
        Text("Para reclamações de direitos autorais, contacte-nos diretamente por e-mail.", style = MaterialTheme.typography.bodySmall)
        TextButton(onClick = { uriHandler.openUri("mailto:copyright@pixgo.qzz.io") }) { Text("copyright@pixgo.qzz.io") }

        Spacer(Modifier.height(24.dp))
        Text("Suporte", fontWeight = androidx.compose.ui.text.font.FontWeight.Bold)
        TextButton(onClick = { uriHandler.openUri("mailto:support@pixgo.qzz.io") }) { Text("support@pixgo.qzz.io") }
        OutlinedTextField(value = supportEmail, onValueChange = { supportEmail = it }, label = { Text("O seu e-mail") }, modifier = Modifier.fillMaxWidth())
        Spacer(Modifier.height(8.dp))
        OutlinedTextField(value = supportMsg, onValueChange = { supportMsg = it }, label = { Text("Mensagem") }, modifier = Modifier.fillMaxWidth(), minLines = 3)
        supportResultMsg?.let { Text(it, modifier = Modifier.padding(top = 4.dp)) }
        Spacer(Modifier.height(8.dp))
        Button(
            enabled = !supportSending && supportEmail.isNotBlank() && supportMsg.isNotBlank(),
            onClick = {
                supportSending = true
                scope.launch {
                    val ok = contactRepository.support(supportEmail.trim(), supportMsg.trim())
                    supportResultMsg = if (ok) {
                        supportEmail = ""; supportMsg = ""
                        "Mensagem enviada. Responderemos assim que possível."
                    } else "Erro ao enviar."
                    supportSending = false
                }
            }
        ) { Text("Enviar") }

        Spacer(Modifier.height(24.dp))
        Text("Sobre a PixGo", fontWeight = androidx.compose.ui.text.font.FontWeight.Bold)
        Spacer(Modifier.height(8.dp))
        AboutSection(
            "Quem somos",
            "A PixGo é uma plataforma aberta que disponibiliza ferramentas técnicas para que criadores entusiastas possam guardar, organizar e partilhar conteúdo audiovisual. Não somos um estúdio nem uma produtora. Somos a infraestrutura que outros usam para preservar e distribuir aquilo que criam ou catalogam."
        )
        AboutSection(
            "Porque existimos",
            "Nasceu do desejo de manter registados conteúdos que caíram em domínio público, cujos direitos autorais expiraram, ou que se tornaram difíceis de encontrar noutros lugares. Acreditamos que parte do valor cultural destas obras se perde quando deixam de estar acessíveis, e construímos esta plataforma para dar aos entusiastas as ferramentas para o evitar."
        )
        AboutSection(
            "Como nos sustentamos",
            "A manutenção de servidores, armazenamento e distribuição tem custos reais e contínuos. As assinaturas disponíveis na plataforma dão acesso a recursos técnicos adicionais (maior qualidade de reprodução, perfis extra, mais downloads) e os valores arrecadados destinam-se exclusivamente a manter o serviço em funcionamento. Não vendemos conteúdo: os criadores continuam a ser os únicos responsáveis por aquilo que publicam."
        )
        AboutSection(
            "O nosso compromisso",
            "Levamos a sério a proteção de direitos autorais e o bom uso da plataforma. Mantemos processos de verificação e um canal direto para reclamações, e agimos com prioridade sobre qualquer denúncia fundamentada."
        )
    }
}

@Composable
private fun AboutSection(title: String, body: String) {
    Column(Modifier.padding(bottom = 12.dp)) {
        Text(title, fontWeight = androidx.compose.ui.text.font.FontWeight.Bold, style = MaterialTheme.typography.bodyMedium)
        Text(body, style = MaterialTheme.typography.bodySmall)
    }
}

@Composable
private fun ProfileTab(
    authRepository: AuthRepository,
    initialName: String?,
    initialEmail: String?,
    username: String?,
    scope: kotlinx.coroutines.CoroutineScope
) {
    var name by remember(initialName) { mutableStateOf(initialName ?: "") }
    var email by remember(initialEmail) { mutableStateOf(initialEmail ?: "") }
    var saving by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }

    Column {
        Text("@${username ?: ""}", style = MaterialTheme.typography.bodySmall)
        Spacer(Modifier.height(12.dp))
        OutlinedTextField(value = name, onValueChange = { name = it }, label = { Text("Nome completo") }, modifier = Modifier.fillMaxWidth())
        Spacer(Modifier.height(8.dp))
        OutlinedTextField(
            value = email, onValueChange = { email = it }, label = { Text("E-mail (opcional)") },
            modifier = Modifier.fillMaxWidth(), placeholder = { Text("nome@exemplo.com") }
        )
        message?.let { Text(it, modifier = Modifier.padding(top = 8.dp)) }
        Spacer(Modifier.height(12.dp))
        Button(
            enabled = !saving,
            onClick = {
                // Mesma validação simples de e-mail do original (regex básico).
                if (email.isNotBlank() && !email.matches(Regex("^[^\\s@]+@[^\\s@]+\\.[^\\s@]+$"))) {
                    message = "E-mail inválido."
                    return@Button
                }
                saving = true
                message = null
                scope.launch {
                    try {
                        val body = UpdateMeBody(
                            name = name.trim().ifBlank { null },
                            email = email.trim().ifBlank { null }
                        )
                        authRepository.updateMe(body)
                        message = "Perfil atualizado"
                    } catch (e: Exception) {
                        message = "Erro ao salvar."
                    } finally {
                        saving = false
                    }
                }
            }
        ) {
            if (saving) CircularProgressIndicator(modifier = Modifier.size(18.dp)) else Text("Salvar alterações")
        }
    }
}

@Composable
private fun SecurityTab(authRepository: AuthRepository, scope: kotlinx.coroutines.CoroutineScope, onForcedLogout: () -> Unit) {
    var current by remember { mutableStateOf("") }
    var new by remember { mutableStateOf("") }
    var saving by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }

    Column {
        OutlinedTextField(
            value = current, onValueChange = { current = it }, label = { Text("Senha atual") },
            visualTransformation = PasswordVisualTransformation(), modifier = Modifier.fillMaxWidth()
        )
        Spacer(Modifier.height(8.dp))
        OutlinedTextField(
            value = new, onValueChange = { new = it }, label = { Text("Nova senha") },
            visualTransformation = PasswordVisualTransformation(), modifier = Modifier.fillMaxWidth()
        )
        Text("Mínimo de 8 caracteres.", style = MaterialTheme.typography.bodySmall)
        message?.let { Text(it, modifier = Modifier.padding(top = 8.dp)) }
        Spacer(Modifier.height(12.dp))
        Button(
            enabled = !saving && current.isNotBlank() && new.length >= 8,
            onClick = {
                saving = true
                message = null
                scope.launch {
                    try {
                        authRepository.changePassword(current, new)
                        message = "Senha alterada. Faça login novamente."
                        current = ""; new = ""
                        // O backend invalida as sessões ao trocar a senha — forçar
                        // novo login, tal como o setTimeout(1500ms) do original.
                        kotlinx.coroutines.delay(1500)
                        authRepository.logout()
                        onForcedLogout()
                    } catch (e: Exception) {
                        message = "Senha atual incorreta."
                    } finally {
                        saving = false
                    }
                }
            }
        ) {
            if (saving) CircularProgressIndicator(modifier = Modifier.size(18.dp)) else Text("Alterar senha")
        }
    }
}

@Composable
private fun ProfilesTab(authRepository: AuthRepository, profiles: List<Profile>, scope: kotlinx.coroutines.CoroutineScope) {
    var editing by remember { mutableStateOf<Profile?>(null) }
    var creating by remember { mutableStateOf(false) }
    var formName by remember { mutableStateOf("") }
    var formKid by remember { mutableStateOf(false) }
    var confirmDeleteId by remember { mutableStateOf<String?>(null) }

    Column {
        profiles.forEach { p ->
            Row(
                Modifier.fillMaxWidth().padding(vertical = 6.dp),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Column {
                    Text(p.name)
                    if (p.isKid == true) Text("Perfil infantil", style = MaterialTheme.typography.bodySmall)
                }
                Row {
                    TextButton(onClick = { editing = p; formName = p.name; formKid = p.isKid ?: false }) { Text("Editar") }
                    TextButton(
                        enabled = profiles.size > 1,
                        onClick = { confirmDeleteId = p.id }
                    ) { Text("Remover") }
                }
            }
        }

        Spacer(Modifier.height(8.dp))
        TextButton(onClick = { creating = true; formName = ""; formKid = false }) { Text("Adicionar perfil") }

        if (creating || editing != null) {
            Spacer(Modifier.height(12.dp))
            OutlinedTextField(value = formName, onValueChange = { formName = it }, label = { Text("Nome") })
            Row(verticalAlignment = Alignment.CenterVertically) {
                Checkbox(checked = formKid, onCheckedChange = { formKid = it })
                Text("Perfil infantil")
            }
            Row {
                Button(onClick = {
                    scope.launch {
                        val id = editing?.id
                        if (id != null) authRepository.updateProfile(id, ProfileBody(formName.trim(), formKid))
                        else authRepository.createProfile(ProfileBody(formName.trim(), formKid))
                        creating = false
                        editing = null
                    }
                }) { Text("Salvar") }
                TextButton(onClick = { creating = false; editing = null }) { Text("Cancelar") }
            }
        }
    }

    confirmDeleteId?.let { id ->
        AlertDialog(
            onDismissRequest = { confirmDeleteId = null },
            title = { Text("Remover perfil") },
            text = { Text("Tem certeza que deseja remover este perfil?") },
            confirmButton = {
                TextButton(onClick = {
                    scope.launch { authRepository.deleteProfile(id) }
                    confirmDeleteId = null
                }) { Text("Remover") }
            },
            dismissButton = { TextButton(onClick = { confirmDeleteId = null }) { Text("Cancelar") } }
        )
    }
}
