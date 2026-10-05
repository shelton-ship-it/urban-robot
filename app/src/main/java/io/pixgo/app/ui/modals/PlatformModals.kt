package io.pixgo.app.ui.modals

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import io.pixgo.app.data.i18n.LocalTranslator
import io.pixgo.app.data.model.UpsellPlan
import io.pixgo.app.ui.common.PxBtnSize
import io.pixgo.app.ui.common.PxBtnVariant
import io.pixgo.app.ui.common.PxButton
import io.pixgo.app.ui.common.pxTap
import io.pixgo.app.ui.theme.Montserrat
import io.pixgo.app.ui.theme.Px

/**
 * Réplica de components/modals/UploadBlockedModal.tsx (mesmo layout: título +
 * botão fechar no cabeçalho, corpo muted e botão primário de largura total),
 * usada pelo item "Enviar conteúdo" do sidebar/menu de conta. No Android o
 * envio de conteúdo não existe: só informa que é feito na plataforma web.
 */
@Composable
fun UploadWebOnlyDialog(onClose: () -> Unit) {
    val t = LocalTranslator.current
    val shape = RoundedCornerShape(14.dp)
    Dialog(onDismissRequest = onClose, properties = DialogProperties(dismissOnClickOutside = false)) {
        Column(
            Modifier
                .fillMaxWidth()
                .widthIn(max = 420.dp)
                .clip(shape)
                .background(Px.CardBg)
                .border(1.dp, Px.Border, shape)
        ) {
            Row(
                Modifier.fillMaxWidth().padding(start = 20.dp, end = 20.dp, top = 18.dp, bottom = 14.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    t.t("upload.webOnlyTitle"),
                    fontFamily = Montserrat, fontWeight = FontWeight.ExtraBold, fontSize = 15.2.sp,
                    color = Px.TextTitle, modifier = Modifier.weight(1f),
                )
                Box(
                    Modifier.size(32.dp).clip(RoundedCornerShape(Px.RadiusSm)).pxTap(onClick = onClose),
                    contentAlignment = Alignment.Center,
                ) { Icon(Icons.Filled.Close, null, Modifier.size(18.dp), tint = Px.TextMuted) }
            }
            Box(Modifier.fillMaxWidth().height(1.dp).background(Px.Border))
            Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 16.dp)) {
                Text(
                    t.t("upload.webOnlyBody1"),
                    fontSize = 13.44.sp, lineHeight = 22.85.sp, color = Px.TextMuted,
                    modifier = Modifier.padding(bottom = 10.dp),
                )
                Text(t.t("upload.webOnlyBody2"), fontSize = 13.44.sp, lineHeight = 22.85.sp, color = Px.TextMuted)
            }
            PxButton(
                text = t.t("upload.webOnlyClose"),
                onClick = onClose,
                modifier = Modifier.fillMaxWidth().padding(start = 20.dp, end = 20.dp, bottom = 18.dp),
            )
        }
    }
}

/**
 * Réplica de components/ui/RateLimitModal.tsx (.modal-overlay + .modal.scale-in,
 * max-width 440, texto centrado). Preço, nome e features vêm SEMPRE de
 * `plans` (body.plans do 429); a frase vem de `message`. Textos literais do
 * original (não passam por i18n no web).
 */
@Composable
fun RateLimitModal(
    plans: List<UpsellPlan>,
    message: String?,
    onClose: () -> Unit,
    onUpgrade: (planId: String) -> Unit,
) {
    val featured = plans.find { it.billingCycle == "monthly" } ?: plans.firstOrNull()
    val others = plans.filter { it.id != featured?.id }
    ModalCard(maxWidth = 440, onBack = onClose) {
        Column(
            Modifier.fillMaxWidth().padding(horizontal = 26.dp, vertical = 32.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text("⏱", fontSize = 40.sp, modifier = Modifier.padding(bottom = 12.dp))
            Text(
                "Limite diário atingido",
                fontFamily = Montserrat, fontWeight = FontWeight.Black, fontSize = 19.2.sp,
                color = Px.TextLight, textAlign = TextAlign.Center, modifier = Modifier.padding(bottom = 8.dp),
            )
            Text(
                message?.takeIf { it.isNotBlank() }
                    ?: "Limite diário do plano gratuito atingido. Assine para streaming ilimitado.",
                fontSize = 14.sp, lineHeight = 22.4.sp, color = Px.TextMuted, textAlign = TextAlign.Center,
                modifier = Modifier.padding(bottom = 18.dp),
            )
            if (featured != null) {
                val box = RoundedCornerShape(10.dp)
                Column(
                    Modifier.fillMaxWidth().padding(bottom = 18.dp)
                        .clip(box).background(Px.BgDarker).border(1.dp, Px.Primary, box)
                        .padding(horizontal = 18.dp, vertical = 16.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Text(featured.name ?: "", fontSize = 12.sp, color = Px.TextMuted, modifier = Modifier.padding(bottom = 4.dp))
                    Text(
                        "por apenas ${featured.label ?: ""}",
                        fontFamily = Montserrat, fontWeight = FontWeight.Black, fontSize = 24.sp,
                        color = Px.TextLight, textAlign = TextAlign.Center,
                    )
                    if (featured.features.isNotEmpty()) {
                        Column(Modifier.fillMaxWidth().padding(top = 10.dp)) {
                            featured.features.forEach { f ->
                                Text("✓ $f", fontSize = 12.8.sp, color = Px.TextMuted, modifier = Modifier.padding(bottom = 4.dp))
                            }
                        }
                    }
                }
            }
            if (others.isNotEmpty()) {
                Text(
                    "Também disponível: ${others.joinToString(" · ") { it.label ?: it.id }}",
                    fontSize = 12.sp, color = Px.TextMuted, textAlign = TextAlign.Center,
                    modifier = Modifier.padding(bottom = 18.dp),
                )
            }
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp, Alignment.CenterHorizontally), verticalAlignment = Alignment.CenterVertically) {
                PxButton("Assinar", onClick = { onUpgrade(featured?.id ?: "monthly") })
                PxButton("Fechar", onClick = onClose, variant = PxBtnVariant.Ghost, size = PxBtnSize.Sm)
            }
        }
    }
}

/**
 * Réplica de components/ui/SessionReplacedModal.tsx — heartbeat 409: outro
 * dispositivo ultrapassou o limite de ecrãs do plano. A mensagem vem sempre
 * do backend (body.message).
 */
@Composable
fun SessionReplacedModal(message: String, onClose: () -> Unit) {
    ModalCard(maxWidth = 420, onBack = onClose) {
        Column(
            Modifier.fillMaxWidth().padding(horizontal = 26.dp, vertical = 32.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text("🔒", fontSize = 40.sp, modifier = Modifier.padding(bottom = 12.dp))
            Text(
                "Sessão encerrada",
                fontFamily = Montserrat, fontWeight = FontWeight.Black, fontSize = 19.2.sp,
                color = Px.TextLight, textAlign = TextAlign.Center, modifier = Modifier.padding(bottom = 8.dp),
            )
            Text(
                message,
                fontSize = 14.sp, lineHeight = 22.4.sp, color = Px.TextMuted, textAlign = TextAlign.Center,
                modifier = Modifier.padding(bottom = 22.dp),
            )
            PxButton("Entendi", onClick = onClose)
        }
    }
}

/** `.modal` (raio 18, borda, fundo card) dentro de um Dialog; o toque fora não fecha (como o overlay do web). */
@Composable
private fun ModalCard(maxWidth: Int, onBack: () -> Unit, content: @Composable () -> Unit) {
    val shape = RoundedCornerShape(18.dp)
    val scroll = rememberScrollState()
    Dialog(
        onDismissRequest = onBack,
        properties = DialogProperties(dismissOnClickOutside = false),
    ) {
        Box(
            Modifier
                .fillMaxWidth()
                .widthIn(max = maxWidth.dp)
                .clip(shape)
                .background(Px.CardBg)
                .border(1.dp, Px.Border, shape)
                .verticalScroll(scroll)
        ) { content() }
    }
}
