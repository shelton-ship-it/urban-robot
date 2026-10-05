package io.pixgo.app.ui.account

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.CheckCircleOutline
import androidx.compose.material.icons.filled.ChildCare
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.pixgo.app.data.auth.AuthRepository
import io.pixgo.app.data.contact.ContactRepository
import io.pixgo.app.data.i18n.LocalTranslator
import io.pixgo.app.data.model.Plan
import io.pixgo.app.data.model.Profile
import io.pixgo.app.data.network.ProfileBody
import io.pixgo.app.data.network.UpdateMeBody
import io.pixgo.app.ui.common.PxAlert
import io.pixgo.app.ui.common.PxAlertKind
import io.pixgo.app.ui.common.PxAvatar
import io.pixgo.app.ui.common.PxBadge
import io.pixgo.app.ui.common.PxBadgeKind
import io.pixgo.app.ui.common.PxBtnSize
import io.pixgo.app.ui.common.PxBtnVariant
import io.pixgo.app.ui.common.PxButton
import io.pixgo.app.ui.common.PxCard
import io.pixgo.app.ui.common.PxCardWithHeader
import io.pixgo.app.ui.common.PxField
import io.pixgo.app.ui.common.PxPageHeader
import io.pixgo.app.ui.common.PxTabs
import io.pixgo.app.ui.common.pagePadding
import io.pixgo.app.ui.common.pxTap
import io.pixgo.app.ui.modals.PlansNoticeDialog
import io.pixgo.app.ui.theme.Montserrat
import io.pixgo.app.ui.theme.Px
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.time.OffsetDateTime
import java.util.Locale

/**
 * Réplica de app/main/account/page.tsx — abas Perfil / Segurança / Assinatura /
 * Perfis / Suporte(Ajuda), no desenho do web (.tabs, .card + .card-header,
 * .form-input, .btn, .badge, .alert). Regras mantidas: senha nova >= 8,
 * logout forçado 1.5s depois de mudar a senha, PlansNoticeModal ao abrir a
 * aba Assinatura, isPremium = plan.id != 'free'. Mensagens como toast (Snackbar).
 */
private val TAB_KEYS = listOf("profile", "security", "subscription", "profiles", "help")

private fun formatDatePtBr(iso: String?): String? {
    if (iso.isNullOrBlank()) return null
    return runCatching {
        val d = java.util.Date.from(OffsetDateTime.parse(iso).toInstant())
        SimpleDateFormat("dd/MM/yyyy", Locale("pt", "BR")).format(d)
    }.getOrElse { iso.take(10) }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun AccountScreen(
    authRepository: AuthRepository,
    contactRepository: ContactRepository,
    onForcedLogout: () -> Unit,
    onOpenPlans: () -> Unit,
    onOpenCopyright: () -> Unit = {},
) {
    val t = LocalTranslator.current
    val state by authRepository.state.collectAsStateWithLifecycle()
    var tabIdx by remember { mutableStateOf(0) }
    var showPlansNotice by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val snack = remember { SnackbarHostState() }
    val toast: (String) -> Unit = { msg ->
        scope.launch { snack.currentSnackbarData?.dismiss(); snack.showSnackbar(msg) }
    }

    if (showPlansNotice) PlansNoticeDialog(onDismiss = { showPlansNotice = false })

    Box(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(pagePadding())) {
            Column(Modifier.widthIn(max = 680.dp)) {
                PxPageHeader(title = t.t("account.title"), subtitle = t.t("account.subtitle"))
                PxTabs(
                    labels = listOf(
                        t.t("account.profile"), t.t("account.security"), t.t("account.subscription"),
                        t.t("account.profiles"), t.t("contact.support"),
                    ),
                    selected = tabIdx,
                    onSelect = { tabIdx = it; if (TAB_KEYS[it] == "subscription") showPlansNotice = true },
                )
                when (TAB_KEYS[tabIdx]) {
                    "profile" -> ProfileTab(authRepository, state.user?.name, state.user?.email, state.user?.username, state.plan, scope, toast)
                    "security" -> SecurityTab(authRepository, scope, toast, onForcedLogout)
                    "subscription" -> SubscriptionTab(state.plan, state.profiles.size, onOpenPlans)
                    "profiles" -> ProfilesTab(authRepository, state.profiles, state.plan, scope, toast)
                    else -> HelpTab(contactRepository, scope, toast, onOpenCopyright)
                }
            }
        }
        SnackbarHost(snack, Modifier.align(Alignment.BottomCenter))
    }
}

@Composable
private fun ProfileTab(
    authRepository: AuthRepository,
    initialName: String?,
    initialEmail: String?,
    username: String?,
    plan: Plan?,
    scope: CoroutineScope,
    toast: (String) -> Unit,
) {
    val t = LocalTranslator.current
    var name by remember(initialName) { mutableStateOf(initialName ?: "") }
    var email by remember(initialEmail) { mutableStateOf(initialEmail ?: "") }
    var saving by remember { mutableStateOf(false) }
    val isPremium = plan != null && plan.id != "free"
    val initials = (initialName?.takeIf { it.isNotBlank() } ?: username ?: "?")
        .split(" ").take(2).joinToString("") { it.take(1) }.uppercase()

    PxCardWithHeader(t.t("account.profile")) {
        // cartão do utilizador: avatar 46 + nome/@user/e-mail + badge do plano
        Row(
            Modifier.fillMaxWidth().padding(bottom = 20.dp).clip(RoundedCornerShape(8.dp))
                .background(Px.BgDarker).border(1.dp, Px.Border, RoundedCornerShape(8.dp)).padding(13.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            PxAvatar(initials, 46.dp, 16.8.sp)
            Column {
                Text(initialName ?: "", color = Px.TextLight, fontWeight = FontWeight.Bold, fontSize = 15.sp)
                Text("@${username ?: ""}", color = Px.TextMuted, fontSize = 12.48.sp)
                if (!initialEmail.isNullOrBlank()) Text(initialEmail, color = Px.TextMuted, fontSize = 11.68.sp, modifier = Modifier.padding(top = 1.dp))
                PxBadge((plan?.id ?: "free").replaceFirstChar { it.uppercase() }, if (isPremium) PxBadgeKind.Red else PxBadgeKind.Gray, Modifier.padding(top = 5.dp))
            }
        }
        Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
            PxField(t.t("auth.fullName"), name, { name = it })
            PxField(t.t("auth.username"), username ?: "", {}, enabled = false)
            PxField(
                t.t("auth.email"), email, { email = it },
                labelSuffix = t.t("auth.emailOptional"), placeholder = "nome@exemplo.com",
                keyboardType = androidx.compose.ui.text.input.KeyboardType.Email,
            )
        }
        PxButton(
            text = t.t("account.saveName"),
            enabled = !saving,
            modifier = Modifier.padding(top = 16.dp),
            onClick = {
                // Mesma validação simples de e-mail do original.
                if (email.isNotBlank() && !email.matches(Regex("^[^\\s@]+@[^\\s@]+\\.[^\\s@]+$"))) {
                    toast(t.t("auth.emailInvalid")); return@PxButton
                }
                saving = true
                scope.launch {
                    try {
                        authRepository.updateMe(UpdateMeBody(name = name.trim().ifBlank { null }, email = email.trim().ifBlank { null }))
                        toast(t.t("account.saved"))
                    } catch (e: Exception) {
                        toast(t.t("common.error"))
                    } finally { saving = false }
                }
            },
        )
    }
}

@Composable
private fun SecurityTab(authRepository: AuthRepository, scope: CoroutineScope, toast: (String) -> Unit, onForcedLogout: () -> Unit) {
    val t = LocalTranslator.current
    var current by remember { mutableStateOf("") }
    var new by remember { mutableStateOf("") }
    var saving by remember { mutableStateOf(false) }

    PxCardWithHeader(t.t("account.changePassword")) {
        PxField(t.t("account.currentPassword"), current, { current = it }, password = true, modifier = Modifier.padding(bottom = 13.dp))
        PxField(t.t("account.newPassword"), new, { new = it }, password = true, helper = t.t("auth.minPassword"))
        PxButton(
            text = t.t("account.changePassword"),
            enabled = !saving && current.isNotBlank() && new.length >= 8,
            modifier = Modifier.padding(top = 16.dp),
            onClick = {
                saving = true
                scope.launch {
                    try {
                        authRepository.changePassword(current, new)
                        toast(t.t("account.passwordChanged"))
                        current = ""; new = ""
                        // O backend invalida as sessões — novo login após 1.5s, como o original.
                        kotlinx.coroutines.delay(1500)
                        authRepository.logout()
                        onForcedLogout()
                    } catch (e: Exception) {
                        toast(t.t("common.error"))
                    } finally { saving = false }
                }
            },
        )
    }
}

@Composable
private fun SubscriptionTab(plan: Plan?, profileCount: Int, onOpenPlans: () -> Unit) {
    val t = LocalTranslator.current
    val isPremium = plan != null && plan.id != "free"
    Column {
        PxCardWithHeader(t.t("account.currentPlan"), Modifier.padding(bottom = 14.dp)) {
            val shape = RoundedCornerShape(8.dp)
            FlowRowSpaced(
                Modifier.fillMaxWidth().clip(shape)
                    .background(if (isPremium) Color(0x0FE50914) else Px.BgDarker)
                    .border(1.dp, if (isPremium) Color(0x40E50914) else Px.Border, shape).padding(13.dp)
            ) {
                Column(Modifier.widthIn(max = 420.dp)) {
                    Text(
                        "${(plan?.id ?: "free").replaceFirstChar { it.uppercase() }} Plan",
                        fontFamily = Montserrat, fontWeight = FontWeight.ExtraBold, fontSize = 16.sp, color = Px.TextLight,
                        modifier = Modifier.padding(bottom = 3.dp),
                    )
                    Text(if (isPremium) t.t("account.premiumDesc") else t.t("account.freeDesc"), color = Px.TextMuted, fontSize = 13.12.sp)
                    if (isPremium) formatDatePtBr(plan?.expiresAt)?.let {
                        Text("Válido até $it", color = Px.TextMuted, fontSize = 11.68.sp, modifier = Modifier.padding(top = 3.dp))
                    }
                    plan?.maxProfiles?.let {
                        Text("$profileCount de $it perfis usados", color = Px.TextMuted, fontSize = 11.68.sp, modifier = Modifier.padding(top = 3.dp))
                    }
                }
                if (isPremium) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                        Icon(Icons.Filled.CheckCircleOutline, null, Modifier.size(14.dp), tint = Px.Secondary)
                        PxBadge(t.t("account.active"), PxBadgeKind.Green)
                    }
                } else PxButton(t.t("account.upgrade"), onClick = onOpenPlans, size = PxBtnSize.Sm)
            }
        }
        if (!isPremium) {
            PxAlert(PxAlertKind.Info) {
                Row(horizontalArrangement = Arrangement.spacedBy(9.dp)) {
                    Icon(Icons.Filled.ErrorOutline, null, Modifier.size(17.dp), tint = Color(0xFF5CC4F8))
                    Column {
                        Text(t.t("account.upgradePromo"), color = Color(0xFF5CC4F8), fontSize = 14.sp)
                        PxButton(t.t("account.viewPlans"), onClick = onOpenPlans, size = PxBtnSize.Sm, modifier = Modifier.padding(top = 10.dp))
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun FlowRowSpaced(modifier: Modifier, content: @Composable () -> Unit) {
    FlowRow(
        modifier,
        horizontalArrangement = Arrangement.spacedBy(10.dp, Alignment.Start),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) { content() }
}

@Composable
private fun ProfilesTab(authRepository: AuthRepository, profiles: List<Profile>, plan: Plan?, scope: CoroutineScope, toast: (String) -> Unit) {
    val t = LocalTranslator.current
    var editing by remember { mutableStateOf<Profile?>(null) }
    var formOpen by remember { mutableStateOf(false) }
    var formName by remember { mutableStateOf("") }
    var formKid by remember { mutableStateOf(false) }
    var saving by remember { mutableStateOf(false) }
    var confirmDeleteId by remember { mutableStateOf<String?>(null) }
    val max = plan?.maxProfiles
    val atLimit = max != null && profiles.size >= max

    PxCardWithHeader(t.t("account.profiles")) {
        if (max != null) {
            Text(
                "${profiles.size} de $max perfis usados" + if (atLimit) " — ${t.t("account.profileLimitReached")}" else "",
                color = Px.TextMuted, fontSize = 13.12.sp, modifier = Modifier.padding(bottom = 14.dp),
            )
        }
        Column(Modifier.padding(bottom = 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            profiles.forEach { p ->
                val shape = RoundedCornerShape(8.dp)
                Row(
                    Modifier.fillMaxWidth().clip(shape).background(Px.BgDarker).border(1.dp, Px.Border, shape).padding(11.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    PxAvatar(p.name.take(1).ifEmpty { "?" }.uppercase(), 38.dp, 15.sp, display = false)
                    Row(Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text(p.name, color = Px.TextLight, fontWeight = FontWeight.SemiBold, maxLines = 1)
                        if (p.isKid == true) Icon(Icons.Filled.ChildCare, null, Modifier.size(15.dp), tint = Px.TextMuted)
                    }
                    IconAction(Icons.Filled.Edit, t.t("account.editProfile"), true) {
                        editing = p; formOpen = true; formName = p.name; formKid = p.isKid ?: false
                    }
                    IconAction(Icons.Filled.DeleteOutline, t.t("account.deleteProfile"), profiles.size > 1) { confirmDeleteId = p.id }
                }
            }
        }

        if (formOpen) {
            val shape = RoundedCornerShape(8.dp)
            Column(
                Modifier.fillMaxWidth().clip(shape).background(Px.BgDarker).border(1.dp, Px.Border, shape).padding(13.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                PxField(t.t("auth.fullName"), formName, { formName = it })
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Checkbox(
                        checked = formKid, onCheckedChange = { formKid = it },
                        colors = CheckboxDefaults.colors(checkedColor = Px.Primary, uncheckedColor = Px.TextMuted, checkmarkColor = Color.White),
                    )
                    Text(t.t("account.kidProfile"), color = Px.TextMuted, fontSize = 12.32.sp, fontWeight = FontWeight.SemiBold)
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    PxButton(
                        t.t("account.saveName"),
                        enabled = !saving && formName.isNotBlank(),
                        onClick = {
                            saving = true
                            scope.launch {
                                try {
                                    val id = editing?.id
                                    if (id != null) authRepository.updateProfile(id, ProfileBody(formName.trim(), formKid))
                                    else authRepository.createProfile(ProfileBody(formName.trim(), formKid))
                                    formOpen = false; editing = null
                                    toast(t.t("account.saved"))
                                } catch (e: Exception) {
                                    // o backend devolve 403 "Limite máximo de N perfil(is) atingido..."
                                    toast(e.message?.takeIf { it.isNotBlank() } ?: t.t("common.error"))
                                } finally { saving = false }
                            }
                        },
                    )
                    PxButton(t.t("common.cancel"), onClick = { formOpen = false; editing = null }, variant = PxBtnVariant.Ghost, size = PxBtnSize.Sm)
                }
            }
        } else {
            PxButton(
                t.t("account.addProfile"), icon = Icons.Filled.Add, size = PxBtnSize.Sm, enabled = !atLimit,
                onClick = { editing = null; formOpen = true; formName = ""; formKid = false },
            )
        }
    }

    confirmDeleteId?.let { id ->
        Dialog(onDismissRequest = { confirmDeleteId = null }) {
            Column(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(Px.CardBg)
                    .border(1.dp, Px.Border, RoundedCornerShape(14.dp)).padding(24.dp)
            ) {
                Text(t.t("account.confirmDeleteProfile"), color = Px.TextTitle, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, lineHeight = 22.sp)
                Row(Modifier.padding(top = 18.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    PxButton(t.t("account.deleteProfile"), variant = PxBtnVariant.Danger, onClick = {
                        confirmDeleteId = null
                        scope.launch {
                            try { authRepository.deleteProfile(id); toast(t.t("account.saved")) }
                            catch (e: Exception) { toast(e.message?.takeIf { it.isNotBlank() } ?: t.t("common.error")) }
                        }
                    })
                    PxButton(t.t("common.cancel"), variant = PxBtnVariant.Ghost, onClick = { confirmDeleteId = null })
                }
            }
        }
    }
}

/** `.btn .btn-ghost .btn-sm` só com ícone. */
@Composable
private fun IconAction(icon: androidx.compose.ui.graphics.vector.ImageVector, description: String, enabled: Boolean, onClick: () -> Unit) {
    Box(
        Modifier.size(34.dp).alpha(if (enabled) 1f else 0.42f).clip(RoundedCornerShape(6.dp))
            .pxTap(enabled = enabled, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) { Icon(icon, description, Modifier.size(16.dp), tint = Px.TextMuted) }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun HelpTab(contactRepository: ContactRepository, scope: CoroutineScope, toast: (String) -> Unit, onOpenCopyright: () -> Unit) {
    val t = LocalTranslator.current
    val uriHandler = LocalUriHandler.current
    var reportTitle by remember { mutableStateOf("") }
    var reportReason by remember { mutableStateOf("") }
    var reportSending by remember { mutableStateOf(false) }
    var supportEmail by remember { mutableStateOf("") }
    var supportMsg by remember { mutableStateOf("") }
    var supportSending by remember { mutableStateOf(false) }

    Column(Modifier.padding(top = 20.dp), verticalArrangement = Arrangement.spacedBy(24.dp)) {
        // Denúncia de conteúdo abusivo/ilícito
        PxCard(padding = 18.dp) {
            Text(t.t("contact.report"), color = Px.TextLight, fontSize = 15.2.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(bottom = 4.dp))
            Column(Modifier.padding(top = 10.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                PxField(t.t("contact.reportTitleLabel"), reportTitle, { reportTitle = it }, maxLength = 300)
                PxField(t.t("contact.reportReasonLabel"), reportReason, { reportReason = it }, multiline = true, maxLength = 2000)
                PxButton(
                    t.t("contact.reportSubmit"), size = PxBtnSize.Sm,
                    enabled = !reportSending && reportTitle.isNotBlank() && reportReason.isNotBlank(),
                    onClick = {
                        reportSending = true
                        scope.launch {
                            val ok = contactRepository.reportAbuse(reportTitle.trim(), reportReason.trim())
                            if (ok) { toast(t.t("contact.reportSuccess")); reportTitle = ""; reportReason = "" } else toast(t.t("common.error"))
                            reportSending = false
                        }
                    },
                )
            }
        }

        // Direitos autorais: botão Denunciar como primeira opção, e-mail como alternativa
        PxCard(padding = 18.dp) {
            Text(t.t("contact.copyright"), color = Px.TextLight, fontSize = 15.2.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(bottom = 4.dp))
            Text(t.t("contact.copyrightDesc"), color = Px.TextMuted, fontSize = 13.12.sp, modifier = Modifier.padding(bottom = 12.dp))
            PxButton(t.t("contact.reportCopyrightButton"), onClick = onOpenCopyright, size = PxBtnSize.Sm)
            FlowRow(
                Modifier.padding(top = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Text(t.t("contact.orEmail"), color = Px.TextMuted, fontSize = 11.84.sp)
                Text(
                    t.t("contact.copyrightEmail"), color = Px.Primary, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold, fontSize = 11.84.sp,
                    modifier = Modifier.pxTap { uriHandler.openUri("mailto:${t.t("contact.copyrightEmail")}") },
                )
            }
        }

        // Suporte — e-mail + formulário
        PxCard(padding = 18.dp) {
            Text(t.t("contact.support"), color = Px.TextLight, fontSize = 15.2.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(bottom = 4.dp))
            Text(
                t.t("contact.supportEmail"), color = Px.Primary, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold, fontSize = 12.8.sp,
                modifier = Modifier.padding(bottom = 12.dp).pxTap { uriHandler.openUri("mailto:${t.t("contact.supportEmail")}") },
            )
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                PxField(
                    t.t("contact.supportEmailLabel"), supportEmail, { supportEmail = it }, maxLength = 300,
                    keyboardType = androidx.compose.ui.text.input.KeyboardType.Email,
                )
                PxField(t.t("contact.supportMessageLabel"), supportMsg, { supportMsg = it }, multiline = true, maxLength = 4000)
                PxButton(
                    t.t("contact.supportSubmit"), size = PxBtnSize.Sm,
                    enabled = !supportSending && supportEmail.isNotBlank() && supportMsg.isNotBlank(),
                    onClick = {
                        supportSending = true
                        scope.launch {
                            val ok = contactRepository.support(supportEmail.trim(), supportMsg.trim())
                            if (ok) { toast(t.t("contact.supportSuccess")); supportEmail = ""; supportMsg = "" } else toast(t.t("common.error"))
                            supportSending = false
                        }
                    },
                )
            }
        }

        // Sobre
        PxCard(padding = 18.dp) {
            Text(t.t("about.title"), color = Px.TextLight, fontSize = 15.2.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(bottom = 10.dp))
            listOf("p1", "p2", "p3", "p4").forEach { p ->
                Column(Modifier.padding(bottom = 12.dp)) {
                    Text(t.t("about.${p}t"), color = Px.TextTitle, fontSize = 12.8.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(bottom = 4.dp))
                    Text(t.t("about.$p"), color = Px.TextMuted, fontSize = 12.8.sp, lineHeight = 20.48.sp)
                }
            }
        }
    }
}
