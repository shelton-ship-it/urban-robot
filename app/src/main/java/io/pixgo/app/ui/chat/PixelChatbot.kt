package io.pixgo.app.ui.chat

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.draw.scale
import androidx.compose.animation.core.animateFloatAsState
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.pixgo.app.data.prefs.UiPrefs
import kotlin.math.hypot
import kotlin.math.roundToInt
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ChatBubbleOutline
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Send
import androidx.compose.material.icons.filled.SmartToy
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.pixgo.app.data.i18n.LocalTranslator
import io.pixgo.app.data.network.ChatMessageDto
import io.pixgo.app.ui.common.PxSpinnerSm
import io.pixgo.app.ui.common.pxTap
import io.pixgo.app.ui.theme.Montserrat
import io.pixgo.app.ui.theme.Poppins
import io.pixgo.app.ui.theme.Px
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

private const val GREETING_DELAY_MS = 5000L
private const val GREETING_AUTOHIDE_MS = 16000L

/**
 * Réplica de components/PixelChatbot.tsx — assistente "Pixel": botão flutuante 54×54
 * (canto inferior direito), balão proactivo 5s depois de abrir (uma única vez por
 * dispositivo), painel 340×460 com histórico só em memória (envia as últimas 6 mensagens).
 */
@Composable
fun BoxScope.PixelChatbot(
    isGreeted: suspend () -> Boolean,
    markGreeted: suspend () -> Unit,
    send: suspend (String, List<ChatMessageDto>) -> String?,
) {
    // Ícone oculto pelo utilizador (arrastado para o X): não compõe nada. Pode ser
    // reexibido em Conta → "Mostrar assistente Pixel".
    val hidden by UiPrefs.aiHidden.collectAsStateWithLifecycle()
    if (hidden) return

    val t = LocalTranslator.current
    val ctx = androidx.compose.ui.platform.LocalContext.current
    val density = LocalDensity.current
    val haptic = LocalHapticFeedback.current
    val scope = rememberCoroutineScope()
    var open by remember { mutableStateOf(false) }
    // Arrasto (pressionar e segurar → mover → largar sobre o X).
    var dragging by remember { mutableStateOf(false) }
    var dragX by remember { mutableStateOf(0f) }
    var dragY by remember { mutableStateOf(0f) }
    var overTarget by remember { mutableStateOf(false) }
    var showGreeting by remember { mutableStateOf(false) }
    val messages = remember { mutableStateListOf<ChatMessageDto>() }
    var input by remember { mutableStateOf("") }
    var sending by remember { mutableStateOf(false) }
    val listState = rememberLazyListState()
    val screenH = LocalConfiguration.current.screenHeightDp

    fun dismissGreeting() { showGreeting = false; scope.launch { runCatching { markGreeted() } } }

    LaunchedEffect(Unit) {
        if (runCatching { isGreeted() }.getOrDefault(false)) return@LaunchedEffect
        delay(GREETING_DELAY_MS)
        showGreeting = true
        delay(GREETING_AUTOHIDE_MS)
        if (showGreeting) dismissGreeting()
    }
    LaunchedEffect(messages.size, open) {
        if (open && messages.isNotEmpty()) listState.animateScrollToItem(messages.size - 1)
    }

    fun toggle() { if (showGreeting) dismissGreeting(); open = !open }

    fun doSend() {
        val text = input.trim()
        if (text.isEmpty() || sending) return
        val history = messages.takeLast(6).toList()
        messages.add(ChatMessageDto("user", text))
        input = ""
        sending = true
        scope.launch {
            val reply = send(text, history)
            messages.add(ChatMessageDto("assistant", reply ?: t.t("chatbot.error")))
            sending = false
        }
    }

    // balão proactivo (bottom 86, right 22, maxWidth 260)
    if (showGreeting && !open) {
        val shape = RoundedCornerShape(12.dp)
        Row(
            Modifier.align(Alignment.BottomEnd).padding(end = 22.dp, bottom = 86.dp)
                .widthIn(max = 260.dp)
                .shadow(16.dp, shape).clip(shape).background(Px.CardBg).border(1.dp, Px.Border, shape)
                .padding(horizontal = 14.dp, vertical = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Icon(Icons.Filled.SmartToy, null, Modifier.padding(top = 1.dp).size(20.dp), tint = Px.Primary)
            Text(t.t("chatbot.greeting"), color = Px.TextLight, fontSize = 12.8.sp, lineHeight = 19.2.sp, modifier = Modifier.weight(1f).pxTap { toggle() })
            Icon(Icons.Filled.Close, "Fechar", Modifier.size(15.dp).pxTap { dismissGreeting() }, tint = Px.TextMuted.copy(alpha = 0.6f))
        }
    }

    // painel (bottom 88, right 22, 340×460)
    if (open) {
        val shape = RoundedCornerShape(14.dp)
        Column(
            Modifier.align(Alignment.BottomEnd).padding(end = 22.dp, bottom = 88.dp).imePadding()
                .widthIn(max = 340.dp).fillMaxWidth(0.96f)
                .heightIn(max = minOf(460, (screenH - 140).coerceAtLeast(240)).dp)
                .height(minOf(460, (screenH - 140).coerceAtLeast(240)).dp)
                .shadow(24.dp, shape).clip(shape).background(Px.CardBg).border(1.dp, Px.Border, shape)
        ) {
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 14.dp),
                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Icon(Icons.Filled.SmartToy, null, Modifier.size(20.dp), tint = Px.Primary)
                Column(Modifier.weight(1f)) {
                    Text(t.t("chatbot.name"), fontFamily = Montserrat, fontWeight = FontWeight.ExtraBold, fontSize = 14.4.sp, color = Px.TextLight)
                    Text(t.t("chatbot.subtitle"), color = Px.TextMuted, fontSize = 10.88.sp)
                }
                Icon(Icons.Filled.Close, "Fechar", Modifier.size(17.dp).pxTap { open = false }, tint = Px.TextMuted.copy(alpha = 0.6f))
            }
            Box(Modifier.fillMaxWidth().height(1.dp).background(Px.Border))

            if (messages.isEmpty()) {
                Box(Modifier.weight(1f).fillMaxWidth().padding(top = 30.dp, start = 24.dp, end = 24.dp)) {
                    Text(t.t("chatbot.welcome"), color = Px.TextMuted, fontSize = 13.12.sp, lineHeight = 21.sp, textAlign = androidx.compose.ui.text.style.TextAlign.Center)
                }
            } else {
                LazyColumn(
                    state = listState, modifier = Modifier.weight(1f).fillMaxWidth(),
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(14.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    items(messages.size) { i ->
                        val m = messages[i]
                        val mine = m.role == "user"
                        Box(Modifier.fillMaxWidth(), contentAlignment = if (mine) Alignment.CenterEnd else Alignment.CenterStart) {
                            Text(
                                m.content,
                                color = if (mine) Color.White else Px.TextLight, fontSize = 13.28.sp, lineHeight = 19.9.sp,
                                modifier = Modifier.widthIn(max = 260.dp).clip(RoundedCornerShape(10.dp))
                                    .background(if (mine) Px.Primary else Color(0x0FFFFFFF))
                                    .padding(horizontal = 12.dp, vertical = 8.dp),
                            )
                        }
                    }
                    if (sending) item { PxSpinnerSm() }
                }
            }

            Box(Modifier.fillMaxWidth().height(1.dp).background(Px.Border))
            Row(Modifier.fillMaxWidth().padding(10.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                val fs = RoundedCornerShape(Px.RadiusSm)
                BasicTextField(
                    value = input, onValueChange = { input = it }, enabled = !sending, singleLine = true,
                    textStyle = TextStyle(fontFamily = Poppins, fontSize = 13.28.sp, color = Px.TextLight),
                    cursorBrush = SolidColor(Px.TextLight),
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                    keyboardActions = KeyboardActions(onSend = { doSend() }),
                    modifier = Modifier.weight(1f),
                    decorationBox = { inner ->
                        Box(
                            Modifier.fillMaxWidth().height(44.dp).clip(fs).background(Color(0x0DFFFFFF)).border(1.dp, Px.Border, fs).padding(horizontal = 14.dp),
                            contentAlignment = Alignment.CenterStart,
                        ) {
                            if (input.isEmpty()) Text(t.t("chatbot.placeholder"), color = Px.TextMuted.copy(alpha = 0.6f), fontSize = 13.28.sp, maxLines = 1)
                            inner()
                        }
                    },
                )
                val canSend = !sending && input.isNotBlank()
                Box(
                    Modifier.height(44.dp).widthIn(min = 40.dp).clip(fs).background(Px.Primary)
                        .then(if (canSend) Modifier.pxTap { doSend() } else Modifier)
                        .padding(horizontal = 12.dp),
                    contentAlignment = Alignment.Center,
                ) { Icon(Icons.Filled.Send, null, Modifier.size(17.dp), tint = Color.White.copy(alpha = if (canSend) 1f else 0.42f)) }
            }
        }
    }

    // ── Alvo "X" transparente (como nos sites): só aparece durante o arrasto ──
    val btn = 54f; val margin = 22f
    if (dragging) {
        val targetScale by animateFloatAsState(if (overTarget) 1.25f else 1f, label = "xScale")
        Box(
            Modifier.align(Alignment.BottomCenter).padding(bottom = 36.dp)
                .size(64.dp).scale(targetScale)
                .clip(CircleShape)
                .background(if (overTarget) Color(0x66E50914) else Color(0x33FFFFFF))
                .border(1.5.dp, if (overTarget) Px.Primary else Color(0x66FFFFFF), CircleShape),
            contentAlignment = Alignment.Center,
        ) { Icon(Icons.Filled.Close, "Ocultar", Modifier.size(28.dp), tint = Color.White) }
    }

    // botão flutuante 54×54, bottom 22 right 22 — pressionar e SEGURAR para arrastar
    Box(
        Modifier.align(Alignment.BottomEnd).padding(end = 22.dp, bottom = 22.dp)
            .offset { IntOffset(dragX.roundToInt(), dragY.roundToInt()) }
            .size(54.dp)
            .pointerInput(Unit) {
                // Posição do centro do botão em px (para testar a sobreposição ao X).
                fun overX(): Boolean {
                    val d = density.density
                    val dm = ctx.resources.displayMetrics
                    val cx = dm.widthPixels - (margin + btn / 2) * d + dragX
                    val cy = dm.heightPixels - (margin + btn / 2) * d + dragY
                    val tx = dm.widthPixels / 2f
                    val ty = dm.heightPixels - (36f + 32f) * d
                    return hypot(cx - tx, cy - ty) < 56f * d
                }
                detectDragGesturesAfterLongPress(
                    onDragStart = {
                        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                        if (open) open = false
                        if (showGreeting) dismissGreeting()
                        dragging = true
                    },
                    onDrag = { change, amount ->
                        change.consume()
                        dragX += amount.x; dragY += amount.y
                        overTarget = overX()
                    },
                    onDragEnd = {
                        val drop = overX()
                        dragging = false; overTarget = false
                        dragX = 0f; dragY = 0f
                        if (drop) UiPrefs.setAiHidden(ctx, true)
                    },
                    onDragCancel = { dragging = false; overTarget = false; dragX = 0f; dragY = 0f },
                )
            }
            .shadow(10.dp, CircleShape, ambientColor = Px.Primary, spotColor = Px.Primary)
            .clip(CircleShape).background(Px.Primary).pxTap { toggle() },
        contentAlignment = Alignment.Center,
    ) { Icon(if (open) Icons.Filled.Close else Icons.Filled.ChatBubbleOutline, "Pixel", Modifier.size(24.dp), tint = Color.White) }
}
