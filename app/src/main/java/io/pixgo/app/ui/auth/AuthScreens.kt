package io.pixgo.app.ui.auth

import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material.icons.outlined.Badge
import androidx.compose.material.icons.outlined.Email
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.PersonOutline
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.pixgo.app.R
import io.pixgo.app.data.auth.ApiException
import io.pixgo.app.data.auth.AuthRepository
import io.pixgo.app.data.i18n.LocalTranslator
import io.pixgo.app.ui.theme.Montserrat
import io.pixgo.app.ui.theme.Poppins
import io.pixgo.app.ui.theme.Px
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Login e registo NATIVOS — réplica de app/_shared/pages/LoginPage.tsx e
 * RegisterPage.tsx do hub, com os valores literais de `.auth-*` /
 * `.form-*` / `.input-*` / `.error-msg` de globals.css. Chamam diretamente
 * POST /api/auth/login | register | google (AuthRepository), sem WebView.
 *
 * Porque a versão anterior (WebView do hub) nunca concluía: o hub guarda a
 * sessão SÓ no cookie `pixgo_session` (HttpOnly — invisível a document.cookie)
 * e não escreve pixgo_token no localStorage (store/auth.ts: "Não há
 * localStorage nem gestão manual de token"). A deteção de sessão por JS
 * nunca encontrava nada e a sheet ficava presa em /main.
 *
 * Textos: bloco `hubAuth` dos locales (cópia do bloco `auth` do hub).
 * Desvio deliberado: sem GOOGLE_WEB_CLIENT_ID o botão do Google NÃO aparece
 * (como no web) e o separador "ou continuar com" também é omitido.
 */

private val CardTop = Color(0xFF141418)          // linear-gradient(135deg,#141418,#0e0e12)
private val CardBottom = Color(0xFF0E0E12)
private val CardRing = Color(0x12E50914)         // box-shadow 0 0 0 1px rgba(229,9,20,.07)
private val InputBg = Color(0x0DFFFFFF)          // rgba(255,255,255,.05)
private val ErrorText = Color(0xFFFF6B6B)        // .error-msg
private val ErrorBg = Color(0x17E50914)          // rgba(229,9,20,.09)
private val ErrorBorder = Color(0x33E50914)      // rgba(229,9,20,.2)

@Composable
fun AuthScreen(authRepository: AuthRepository) {
    var mode by rememberSaveable { mutableStateOf("login") } // "login" | "register"
    BackHandler(enabled = mode == "register") { mode = "login" }
    AuthPage {
        if (mode == "login") LoginForm(authRepository) { mode = "register" }
        else RegisterForm(authRepository) { mode = "login" }
    }
}

// ───────────────────────── layout (.auth-page / .auth-card) ─────────────────────────

/** `.auth-page`: fundo --color-bg-dark + dois radial-gradient, conteúdo centrado, padding 20. */
@Composable
private fun AuthPage(content: @Composable ColumnScope.() -> Unit) {
    BoxWithConstraints(
        Modifier
            .fillMaxSize()
            .background(Px.BgDark)
            .drawBehind {
                // radial-gradient(ellipse 60% 50% at 20% 40%, rgba(229,9,20,.06), transparent)
                ellipseGlow(size.width * 0.20f, size.height * 0.40f, size.width * 0.60f, size.height * 0.50f, Color(0x0FE50914))
                // radial-gradient(ellipse 50% 60% at 80% 60%, rgba(140,59,255,.04), transparent)
                ellipseGlow(size.width * 0.80f, size.height * 0.60f, size.width * 0.50f, size.height * 0.60f, Color(0x0A8C3BFF))
            }
            .windowInsetsPadding(WindowInsets.safeDrawing) // inclui o teclado: o card nunca fica tapado
    ) {
        val minHeight = maxHeight
        Column(
            Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .heightIn(min = minHeight)
                .padding(20.dp),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally,
            content = content
        )
    }
}

private fun DrawScope.ellipseGlow(cx: Float, cy: Float, rx: Float, ry: Float, color: Color) {
    withTransform({
        translate(cx, cy)
        scale(1f, ry / rx, pivot = Offset.Zero)
    }) {
        drawCircle(
            brush = Brush.radialGradient(listOf(color, Color.Transparent), center = Offset.Zero, radius = rx),
            radius = rx,
            center = Offset.Zero
        )
    }
}

/** `.auth-card.scale-in`: max-width 420, raio 20, padding 38/34, borda 1px, scaleIn .18s. */
@Composable
private fun AuthCard(content: @Composable ColumnScope.() -> Unit) {
    val grow = remember { Animatable(0.93f) }
    val fade = remember { Animatable(0f) }
    LaunchedEffect(Unit) {
        launch { grow.animateTo(1f, tween(180)) }
        fade.animateTo(1f, tween(180))
    }
    val shape = RoundedCornerShape(20.dp)
    Box(
        Modifier
            .widthIn(max = 422.dp)
            .fillMaxWidth()
            .graphicsLayer { scaleX = grow.value; scaleY = grow.value; alpha = fade.value }
            .border(1.dp, CardRing, RoundedCornerShape(21.dp))
            .padding(1.dp)
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                .shadow(24.dp, shape, clip = false, ambientColor = Color.Black, spotColor = Color.Black)
                .clip(shape)
                .background(Brush.linearGradient(listOf(CardTop, CardBottom)))
                .border(1.dp, Px.Border, shape)
                .padding(horizontal = 34.dp, vertical = 38.dp),
            content = content
        )
    }
}

/** `.auth-logo` (altura 26, margem inferior 26) + `.auth-title` (Montserrat 800, 1.35rem). */
@Composable
private fun ColumnScope.AuthHeader(title: String) {
    Image(
        painter = painterResource(id = R.drawable.ic_pixgo_logo),
        contentDescription = "Pixgo",
        modifier = Modifier
            .align(Alignment.CenterHorizontally)
            .height(26.dp)
            .aspectRatio(786f / 237f)
    )
    Spacer(Modifier.height(26.dp))
    Text(
        title,
        color = Px.TextLight,
        fontFamily = Montserrat,
        fontWeight = FontWeight.ExtraBold,
        fontSize = 21.6.sp,
        modifier = Modifier.padding(bottom = 5.dp)
    )
}

// ───────────────────────── formulários ─────────────────────────

@Composable
private fun LoginForm(authRepository: AuthRepository, onSwitch: () -> Unit) {
    val tr = LocalTranslator.current
    val scope = rememberCoroutineScope()
    val keyboard = LocalSoftwareKeyboardController.current
    val auth by authRepository.state.collectAsStateWithLifecycle()
    val loading = auth.loading

    var username by rememberSaveable { mutableStateOf("") }
    var password by remember { mutableStateOf("") } // nunca persistir a senha no estado salvo
    var error by remember { mutableStateOf("") }

    val canSubmit = !loading && username.trim().isNotEmpty() && password.isNotEmpty()

    fun submit() {
        if (!canSubmit) return
        error = ""
        keyboard?.hide()
        scope.launch {
            // NonCancellable: ao autenticar, o NavHost sai deste ecrã e cancelaria o scope
            // a meio do fetchMe(force) que traz perfis/plano — a sessão ficaria incompleta.
            val result = withContext(Dispatchers.IO + NonCancellable) {
                runCatching { authRepository.login(username.trim(), password) }
            }
            result.exceptionOrNull()?.let { e ->
                error = when {
                    e is ApiException && e.status == 401 -> tr.t("hubAuth.invalidCredentials")
                    e is ApiException && e.status == 429 && !e.message.isNullOrBlank() -> e.message!! // bloqueio temporário (mensagem do servidor, em PT)
                    else -> tr.t("hubAuth.loginFailed")
                }
            }
        }
    }

    AuthCard {
        AuthHeader(tr.t("hubAuth.welcomeBack"))
        if (error.isNotEmpty()) AuthError(error)

        AuthField(
            label = tr.t("hubAuth.username"), value = username, onValueChange = { username = it },
            icon = Icons.Outlined.PersonOutline, enabled = !loading,
            imeAction = ImeAction.Next
        )
        Spacer(Modifier.height(14.dp))
        AuthField(
            label = tr.t("hubAuth.password"), value = password, onValueChange = { password = it },
            icon = Icons.Outlined.Lock, enabled = !loading, password = true,
            imeAction = ImeAction.Done, onImeAction = ::submit
        )
        AuthButton(tr.t("hubAuth.signIn"), enabled = canSubmit, loading = loading, onClick = ::submit)

        GoogleSection(authRepository, disabled = loading, onError = { error = it })

        AuthDivider()
        AuthSwitchLink(tr.t("hubAuth.noAccount"), tr.t("hubAuth.signUp"), onSwitch)
    }
}

@Composable
private fun RegisterForm(authRepository: AuthRepository, onSwitch: () -> Unit) {
    val tr = LocalTranslator.current
    val scope = rememberCoroutineScope()
    val keyboard = LocalSoftwareKeyboardController.current
    val auth by authRepository.state.collectAsStateWithLifecycle()
    val loading = auth.loading

    var name by rememberSaveable { mutableStateOf("") }
    var username by rememberSaveable { mutableStateOf("") }
    var email by rememberSaveable { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var error by remember { mutableStateOf("") }

    val canSubmit = !loading && name.trim().isNotEmpty() && username.trim().isNotEmpty() && password.length >= 8

    // Regras do schema `register` do backend (api-core lib/validation.js), validadas antes de
    // enviar para o utilizador ver a causa real em vez de um "Não foi possível criar a conta".
    fun validate(): String? {
        if (name.trim().length < 2) return tr.t("auth.nameMin")
        val u = username.trim()
        if (u.length < 3) return tr.t("auth.usernameMin")
        if (!Regex("^[a-zA-Z0-9_]+$").matches(u)) return tr.t("auth.usernameChars")
        val e = email.trim()
        if (e.isNotEmpty() && !android.util.Patterns.EMAIL_ADDRESS.matcher(e).matches()) return tr.t("auth.emailInvalid")
        return null
    }

    fun submit() {
        if (!canSubmit) return
        validate()?.let { error = it; return }
        error = ""
        keyboard?.hide()
        scope.launch {
            val result = withContext(Dispatchers.IO + NonCancellable) {
                runCatching { authRepository.register(name, username, email, password) }
            }
            result.exceptionOrNull()?.let { e ->
                error = when {
                    e is ApiException && e.status == 409 ->
                        if (e.message.orEmpty().contains("mail", ignoreCase = true)) tr.t("auth.emailTaken")
                        else tr.t("auth.usernameTaken")
                    else -> tr.t("hubAuth.registerFailed")
                }
            }
        }
    }

    AuthCard {
        AuthHeader(tr.t("hubAuth.createAccount"))
        if (error.isNotEmpty()) AuthError(error)

        AuthField(
            label = tr.t("hubAuth.name"), value = name, onValueChange = { name = it },
            icon = Icons.Outlined.Badge, enabled = !loading,
            capitalization = KeyboardCapitalization.Words
        )
        Spacer(Modifier.height(14.dp))
        AuthField(
            label = tr.t("hubAuth.username"), value = username, onValueChange = { username = it },
            icon = Icons.Outlined.PersonOutline, enabled = !loading
        )
        Spacer(Modifier.height(14.dp))
        AuthField(
            label = tr.t("hubAuth.email"), optionalSuffix = tr.t("hubAuth.optional"),
            value = email, onValueChange = { email = it },
            icon = Icons.Outlined.Email, enabled = !loading, keyboardType = KeyboardType.Email
        )
        Spacer(Modifier.height(14.dp))
        AuthField(
            label = tr.t("hubAuth.password"), value = password, onValueChange = { password = it },
            icon = Icons.Outlined.Lock, enabled = !loading, password = true,
            helper = tr.t("auth.minPassword"),
            imeAction = ImeAction.Done, onImeAction = ::submit
        )
        AuthButton(tr.t("hubAuth.signUp"), enabled = canSubmit, loading = loading, onClick = ::submit)

        GoogleSection(authRepository, disabled = loading, onError = { error = it })

        AuthDivider()
        AuthSwitchLink(tr.t("hubAuth.hasAccount"), tr.t("hubAuth.signIn"), onSwitch)
    }
}

// ───────────────────────── "ou continuar com" + Google ─────────────────────────

@Composable
private fun GoogleSection(authRepository: AuthRepository, disabled: Boolean, onError: (String) -> Unit) {
    if (!GoogleSignIn.enabled) return // igual ao web: sem client id, nada é renderizado
    val tr = LocalTranslator.current
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var busy by remember { mutableStateOf(false) }

    Row(
        Modifier.fillMaxWidth().padding(vertical = 20.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Box(Modifier.weight(1f).height(1.dp).background(Px.Border))
        Text(
            tr.t("hubAuth.orContinueWith").uppercase(),
            color = Px.TextMuted, fontFamily = Poppins, fontSize = 12.48.sp, letterSpacing = 0.04.em
        )
        Box(Modifier.weight(1f).height(1.dp).background(Px.Border))
    }

    GoogleButton(
        label = tr.t("hubAuth.continueWithGoogle"),
        enabled = !disabled && !busy
    ) {
        onError("")
        scope.launch {
            busy = true
            when (val r = GoogleSignIn.requestIdToken(context)) {
                is GoogleResult.Token -> {
                    val res = withContext(Dispatchers.IO + NonCancellable) {
                        runCatching { authRepository.loginWithGoogleCredential(r.idToken) }
                    }
                    res.exceptionOrNull()?.let { e ->
                        // LoginPage.tsx: err.error === 'AccountExistsUnlinked' ? err.message : googleLoginFailed
                        onError(
                            if (e is ApiException && e.error == "AccountExistsUnlinked" && !e.message.isNullOrBlank()) e.message!!
                            else tr.t("hubAuth.googleLoginFailed")
                        )
                    }
                }
                GoogleResult.Cancelled -> Unit
                is GoogleResult.Failure -> onError(tr.t("hubAuth.googleLoginFailed"))
            }
            busy = false
        }
    }
}

/** Botão do GIS: theme filled_black, shape pill, size large (44), width 340, logo centrado. */
@Composable
private fun GoogleButton(label: String, enabled: Boolean, onClick: () -> Unit) {
    val shape = RoundedCornerShape(percent = 50)
    Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
        Row(
            Modifier
                .widthIn(max = 340.dp)
                .fillMaxWidth()
                .height(44.dp)
                .alpha(if (enabled) 1f else 0.5f) // .google-auth-btn-wrap disabled: opacity .5
                .clip(shape)
                .background(Color(0xFF131314))
                .border(1.dp, Color(0xFF8E918F), shape)
                .clickable(enabled = enabled, onClick = onClick)
                .padding(horizontal = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Center
        ) {
            Image(painterResource(id = R.drawable.ic_google_g), contentDescription = null, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(10.dp))
            Text(label, color = Color(0xFFE3E3E3), fontFamily = Poppins, fontWeight = FontWeight.Medium, fontSize = 14.sp, maxLines = 1)
        }
    }
}

// ───────────────────────── componentes ─────────────────────────

/** `.form-group` + `.form-label` + `.input-wrap` (ícone 18 à esquerda, olho à direita) + `.form-input` (44). */
@Composable
private fun AuthField(
    label: String,
    value: String,
    onValueChange: (String) -> Unit,
    icon: ImageVector,
    modifier: Modifier = Modifier,
    optionalSuffix: String? = null,
    helper: String? = null,
    enabled: Boolean = true,
    password: Boolean = false,
    keyboardType: KeyboardType = KeyboardType.Text,
    capitalization: KeyboardCapitalization = KeyboardCapitalization.None,
    imeAction: ImeAction = ImeAction.Next,
    onImeAction: (() -> Unit)? = null,
) {
    var showPw by remember { mutableStateOf(false) }
    val focus = LocalFocusManager.current
    val shape = RoundedCornerShape(Px.RadiusSm)

    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(5.dp)) {
        Row {
            Text(label, color = Px.TextMuted, fontFamily = Poppins, fontWeight = FontWeight.SemiBold, fontSize = 12.32.sp)
            if (optionalSuffix != null) {
                Text(
                    " $optionalSuffix",
                    color = Px.TextMuted.copy(alpha = 0.65f), fontFamily = Poppins,
                    fontWeight = FontWeight.Normal, fontSize = 12.32.sp
                )
            }
        }
        BasicTextField(
            value = value,
            onValueChange = onValueChange,
            enabled = enabled,
            singleLine = true,
            textStyle = TextStyle(fontFamily = Poppins, fontSize = 14.4.sp, color = Px.TextLight),
            cursorBrush = SolidColor(Px.TextLight),
            visualTransformation = if (password && !showPw) PasswordVisualTransformation() else VisualTransformation.None,
            keyboardOptions = KeyboardOptions(
                capitalization = capitalization,
                autoCorrect = false,
                keyboardType = if (password) KeyboardType.Password else keyboardType,
                imeAction = imeAction
            ),
            keyboardActions = KeyboardActions(
                onNext = { focus.moveFocus(FocusDirection.Down) },
                onDone = { onImeAction?.invoke() }
            ),
            modifier = Modifier.fillMaxWidth().alpha(if (enabled) 1f else 0.45f),
            decorationBox = { inner ->
                Box(
                    Modifier
                        .fillMaxWidth()
                        .height(44.dp)
                        .clip(shape)
                        .background(InputBg)
                        .border(1.dp, Px.Border, shape),
                    contentAlignment = Alignment.CenterStart
                ) {
                    Icon(
                        icon, contentDescription = null, tint = Px.TextMuted,
                        modifier = Modifier.padding(start = 12.dp).size(18.dp)
                    )
                    Box(
                        Modifier.fillMaxWidth().padding(start = 42.dp, end = if (password) 42.dp else 14.dp),
                        contentAlignment = Alignment.CenterStart
                    ) { inner() }
                    if (password) {
                        Box(
                            Modifier
                                .align(Alignment.CenterEnd)
                                .width(42.dp)
                                .height(44.dp)
                                .clickable(
                                    interactionSource = remember { MutableInteractionSource() },
                                    indication = null
                                ) { showPw = !showPw },
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                if (showPw) Icons.Filled.VisibilityOff else Icons.Filled.Visibility,
                                contentDescription = null, tint = Px.TextMuted, modifier = Modifier.size(18.dp)
                            )
                        }
                    }
                }
            }
        )
        if (helper != null) {
            Text(helper, color = Px.TextMuted, fontFamily = Poppins, fontSize = 11.36.sp) // .form-helper .71rem
        }
    }
}

/** `.error-msg`. */
@Composable
private fun AuthError(text: String) {
    val shape = RoundedCornerShape(Px.RadiusSm)
    Row(
        Modifier
            .fillMaxWidth()
            .padding(bottom = 14.dp)
            .clip(shape)
            .background(ErrorBg)
            .border(1.dp, ErrorBorder, shape)
            .padding(horizontal = 14.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Icon(Icons.Outlined.ErrorOutline, contentDescription = null, tint = ErrorText, modifier = Modifier.size(18.dp))
        Text(text, color = ErrorText, fontFamily = Poppins, fontWeight = FontWeight.Medium, fontSize = 13.28.sp)
    }
}

/** `.auth-btn`: 46px, raio 6, --color-primary, 700 .95rem, margin-top 18, sombra vermelha; disabled = opacity .5. */
@Composable
private fun AuthButton(text: String, enabled: Boolean, loading: Boolean, onClick: () -> Unit) {
    val shape = RoundedCornerShape(Px.RadiusSm)
    Box(
        Modifier
            .padding(top = 18.dp)
            .fillMaxWidth()
            .height(46.dp)
            .alpha(if (enabled) 1f else 0.5f)
            .shadow(8.dp, shape, clip = false, ambientColor = Px.Primary, spotColor = Px.Primary)
            .clip(shape)
            .background(Px.Primary)
            .clickable(enabled = enabled, onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        if (loading) AuthSpinner()
        else Text(text, color = Color.White, fontFamily = Poppins, fontWeight = FontWeight.Bold, fontSize = 15.2.sp)
    }
}

/** `.spinner-sm` (15px, 2px). Topo branco: sobre o fundo vermelho do botão o vermelho do web não se vê. */
@Composable
private fun AuthSpinner(size: Dp = 15.dp) {
    val rot by rememberInfiniteTransition(label = "authSpinner").animateFloat(
        0f, 360f, infiniteRepeatable(tween(700, easing = LinearEasing), RepeatMode.Restart), label = "authSpinnerRot"
    )
    Canvas(Modifier.size(size).graphicsLayer { rotationZ = rot }) {
        val sw = 2.dp.toPx()
        val s = Size(this.size.width - sw, this.size.height - sw)
        val tl = Offset(sw / 2, sw / 2)
        drawArc(Color(0x33FFFFFF), 0f, 360f, false, tl, s, style = Stroke(sw))
        drawArc(Color.White, -90f, 90f, false, tl, s, style = Stroke(sw))
    }
}

/** `.auth-divider`: 1px, margem 20 vertical. */
@Composable
private fun AuthDivider() {
    Box(Modifier.fillMaxWidth().padding(vertical = 20.dp).height(1.dp).background(Px.Border))
}

/** `.auth-link`: .83rem centrado, link --color-primary 600. */
@Composable
private fun AuthSwitchLink(prefix: String, action: String, onClick: () -> Unit) {
    Text(
        buildAnnotatedString {
            append("$prefix ")
            withStyle(SpanStyle(color = Px.Primary, fontWeight = FontWeight.SemiBold)) { append(action) }
        },
        color = Px.TextMuted,
        fontFamily = Poppins,
        fontSize = 13.28.sp,
        textAlign = androidx.compose.ui.text.style.TextAlign.Center,
        modifier = Modifier
            .fillMaxWidth()
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onClick
            )
            .padding(vertical = 6.dp)
    )
}
