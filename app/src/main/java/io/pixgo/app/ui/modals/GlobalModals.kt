package io.pixgo.app.ui.modals

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import coil.compose.AsyncImage
import io.pixgo.app.R
import io.pixgo.app.data.i18n.LocalTranslator
import io.pixgo.app.data.i18n.SUPPORTED_LANGUAGES
import io.pixgo.app.ui.theme.Px

/**
 * Réplica de frontend_web/src/components/modals/DisclaimerModal.tsx.
 * Gate real (Providers.tsx DisclaimerGate): aparece quando há sessão, até o
 * utilizador aceitar; "Recusar" memoriza pixgo_disclaimer_dismissed e fecha.
 * 7 seções: disclaimer.s{n}t / disclaimer.s{n}; email destacado em monospace
 * vermelho (mesmo visual do <a mailto> original). Backdrop/back não fecham —
 * o overlay do web bloqueia interação fora do card.
 */
@Composable
fun DisclaimerDialog(
    onAccept: () -> Unit,
    onDismiss: () -> Unit,
) {
    val t = LocalTranslator.current
    Dialog(
        onDismissRequest = { },
        properties = DialogProperties(dismissOnClickOutside = false, dismissOnBackPress = false),
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                .widthIn(max = 520.dp)
                .clip(RoundedCornerShape(14.dp))
                .background(Px.CardBg)
                .border(1.dp, Px.Border, RoundedCornerShape(14.dp))
        ) {
            // Header — logo + título + subtítulo
            Column(Modifier.padding(start = 22.dp, end = 22.dp, top = 20.dp, bottom = 16.dp)) {
                AsyncImage(
                    model = R.drawable.ic_pixgo_logo,
                    contentDescription = "PixGo",
                    modifier = Modifier.height(28.dp),
                )
                Spacer(Modifier.height(6.dp))
                Text(
                    t.t("disclaimer.title"),
                    fontWeight = FontWeight.ExtraBold,
                    fontSize = 16.sp,
                    color = Px.TextTitle,
                )
                Text(
                    t.t("disclaimer.subtitle"),
                    fontSize = 12.sp,
                    color = Px.TextMuted,
                    modifier = Modifier.padding(top = 3.dp),
                )
            }
            Box(Modifier.fillMaxWidth().height(1.dp).background(Px.Border))
            // Body — 7 seções com divisórias, exatamente como o map original
            Column(
                Modifier
                    .weight(1f, fill = false)
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 22.dp, vertical = 18.dp)
            ) {
                for (n in 1..7) {
                    Text(
                        t.t("disclaimer.s${n}t"),
                        fontWeight = FontWeight.ExtraBold,
                        fontSize = 13.sp,
                        color = Px.TextTitle,
                    )
                    Text(
                        t.t("disclaimer.s$n"),
                        fontSize = 13.sp,
                        lineHeight = 21.sp,
                        color = Px.TextMuted,
                        modifier = Modifier.padding(top = 5.dp, bottom = if (n == 7) 4.dp else 16.dp),
                    )
                    if (n < 7) Box(Modifier.fillMaxWidth().height(1.dp).background(Px.Border))
                }
                // Email em destaque (chip monospace vermelho = link mailto do web)
                Box(
                    Modifier
                        .clip(RoundedCornerShape(6.dp))
                        .background(Px.Primary.copy(alpha = 0.06f))
                        .border(1.dp, Px.Primary.copy(alpha = 0.18f), RoundedCornerShape(6.dp))
                        .padding(horizontal = 10.dp, vertical = 6.dp)
                ) {
                    Text(
                        t.t("disclaimer.email"),
                        fontFamily = FontFamily.Monospace,
                        fontWeight = FontWeight.Bold,
                        fontSize = 13.sp,
                        color = Px.Primary,
                    )
                }
            }
            Box(Modifier.fillMaxWidth().height(1.dp).background(Px.Border))
            // Footer — Aceito (primário) + Recusa (secundário)
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 22.dp, vertical = 14.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                ModalButton(
                    label = t.t("disclaimer.accept"),
                    primary = true,
                    onClick = onAccept,
                    modifier = Modifier.weight(1f),
                )
                ModalButton(
                    label = t.t("disclaimer.dismiss"),
                    primary = false,
                    onClick = onDismiss,
                    modifier = Modifier.weight(1f),
                )
            }
        }
    }
}

/**
 * Réplica de frontend_web/src/components/modals/LanguageModal.tsx.
 * Gate real (Providers.tsx): aparece UMA vez, quando 'pixgo_lang' nunca foi
 * escolhido, antes de qualquer conteúdo. As mesmas 3 línguas de
 * LANGUAGES (i18n/index.ts), seleção com check vermelho, Continuar persiste.
 */
@Composable
fun LanguageChoiceDialog(
    initialSelected: String,
    onContinue: (String) -> Unit,
) {
    val t = LocalTranslator.current
    var selected by remember { mutableStateOf(initialSelected) }
    Dialog(
        onDismissRequest = { },
        properties = DialogProperties(dismissOnClickOutside = false, dismissOnBackPress = false),
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                .widthIn(max = 400.dp)
                .clip(RoundedCornerShape(14.dp))
                .background(Px.CardBg)
                .border(1.dp, Px.Border, RoundedCornerShape(14.dp))
        ) {
            Column(
                Modifier.fillMaxWidth().padding(top = 32.dp, start = 28.dp, end = 28.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                AsyncImage(
                    model = R.drawable.ic_pixgo_logo,
                    contentDescription = "PixGo",
                    modifier = Modifier.height(44.dp).padding(bottom = 22.dp),
                )
                Text(
                    t.t("language.title"),
                    fontWeight = FontWeight.ExtraBold,
                    fontSize = 21.sp,
                    color = Px.TextTitle,
                    textAlign = TextAlign.Center,
                )
                Text(
                    t.t("language.subtitle"),
                    fontSize = 14.sp,
                    color = Px.TextMuted,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.padding(top = 6.dp, bottom = 24.dp),
                )
            }
            Column(
                Modifier.fillMaxWidth().padding(horizontal = 24.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                SUPPORTED_LANGUAGES.forEach { lang ->
                    val isSel = lang.code == selected
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(10.dp))
                            .background(if (isSel) Px.Primary.copy(alpha = 0.07f) else Px.CardHover)
                            .border(
                                1.dp,
                                if (isSel) Px.Primary.copy(alpha = 0.35f) else Px.Border,
                                RoundedCornerShape(10.dp),
                            )
                            .clickable { selected = lang.code }
                            .padding(horizontal = 14.dp, vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(lang.flag, fontSize = 18.sp)
                        Spacer(Modifier.width(10.dp))
                        Column(Modifier.weight(1f)) {
                            Text(lang.native, fontWeight = FontWeight.SemiBold, fontSize = 14.sp, color = Px.TextTitle)
                            Text(lang.label, fontSize = 12.sp, color = Px.TextMuted)
                        }
                        if (isSel) {
                            Text("\u2713", color = Px.Primary, fontWeight = FontWeight.ExtraBold, fontSize = 18.sp)
                        }
                    }
                }
            }
            ModalButton(
                label = t.t("language.continue"),
                primary = true,
                onClick = { onContinue(selected) },
                modifier = Modifier.padding(24.dp),
            )
        }
    }
}

/**
 * Réplica de frontend_web/src/components/modals/PlansNoticeModal.tsx.
 * Regras reais do original: aparece SEMPRE que se entra em /plans e ao
 * abrir a aba "Assinatura" da conta; nunca é memorizado; só fecha no botão
 * "Entendi" (backdrop/back não fecham, de propósito, para leitura
 * deliberada). 3 parágrafos: plansNotice.p1..p3.
 */
@Composable
fun PlansNoticeDialog(onDismiss: () -> Unit) {
    val t = LocalTranslator.current
    Dialog(
        onDismissRequest = { },
        properties = DialogProperties(dismissOnClickOutside = false, dismissOnBackPress = false),
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                .widthIn(max = 520.dp)
                .clip(RoundedCornerShape(14.dp))
                .background(Px.CardBg)
                .border(1.dp, Px.Border, RoundedCornerShape(14.dp))
        ) {
            Text(
                t.t("plansNotice.title"),
                fontWeight = FontWeight.ExtraBold,
                fontSize = 17.sp,
                color = Px.TextTitle,
                modifier = Modifier.padding(start = 24.dp, end = 24.dp, top = 20.dp, bottom = 14.dp),
            )
            Box(Modifier.fillMaxWidth().height(1.dp).background(Px.Border))
            Column(
                Modifier
                    .weight(1f, fill = false)
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 24.dp, vertical = 16.dp),
            ) {
                for ((i, k) in listOf("p1", "p2", "p3").withIndex()) {
                    Text(
                        t.t("plansNotice.$k"),
                        fontSize = 13.4.sp,
                        lineHeight = 23.sp,
                        color = Px.TextMuted,
                        modifier = Modifier.padding(bottom = if (i == 2) 0.dp else 12.dp),
                    )
                }
            }
            Box(Modifier.fillMaxWidth().height(1.dp).background(Px.Border))
            ModalButton(
                label = t.t("plansNotice.understood"),
                primary = true,
                onClick = onDismiss,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 16.dp),
            )
        }
    }
}

/** Botão padrão dos modais portados (.btn btn-primary / .btn-secondary do web). */
@Composable
private fun ModalButton(
    label: String,
    primary: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val shape = RoundedCornerShape(8.dp)
    Box(
        modifier
            .clip(shape)
            .background(if (primary) Px.Primary else Px.CardHover)
            .then(if (primary) Modifier else Modifier.border(1.dp, Px.Border, shape))
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 11.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            label,
            color = if (primary) Color.White else Px.TextTitle,
            fontWeight = FontWeight.Bold,
            fontSize = 14.sp,
            textAlign = TextAlign.Center,
        )
    }
}
