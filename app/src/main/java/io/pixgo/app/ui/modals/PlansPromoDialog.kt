package io.pixgo.app.ui.modals

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
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
import io.pixgo.app.data.model.PaymentPlan
import io.pixgo.app.ui.common.PxBtnSize
import io.pixgo.app.ui.common.PxBtnVariant
import io.pixgo.app.ui.common.PxButton
import io.pixgo.app.ui.theme.Montserrat
import io.pixgo.app.ui.theme.Px

/**
 * Réplica de components/ui/PlansModal.tsx — modal promocional do /catalog:
 * só plano free, no máximo 1x por dia (a regra vive em quem o abre), mostra só
 * o plano pago em destaque (mensal, senão o primeiro), "Também disponível: …",
 * botões "Ver planos" / "Continuar no grátis". Textos literais do original.
 */
@Composable
fun PlansPromoDialog(
    plans: List<PaymentPlan>,
    onSeePlans: () -> Unit,
    onDismiss: () -> Unit,
) {
    val tr = io.pixgo.app.data.i18n.LocalTranslator.current
    val paid = plans.filter { it.id != "free" }
    val featured = paid.firstOrNull { it.billingCycle == "monthly" } ?: paid.firstOrNull()
    val others = paid.filter { it.id != featured?.id }

    Dialog(onDismissRequest = onDismiss) {
        Column(
            Modifier
                .widthIn(max = 480.dp)
                .fillMaxWidth()
                .clip(RoundedCornerShape(14.dp))
                .background(Px.CardBg)
                .border(1.dp, Px.Border, RoundedCornerShape(14.dp))
                .padding(horizontal = 26.dp, vertical = 32.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                "Aproveite a promoção",
                fontFamily = Montserrat, fontWeight = FontWeight.Black, fontSize = 19.2.sp,
                color = Px.TextLight, modifier = Modifier.padding(bottom = 18.dp),
            )

            if (featured != null) {
                Column(
                    Modifier
                        .fillMaxWidth()
                        .padding(bottom = 14.dp)
                        .clip(RoundedCornerShape(10.dp))
                        .background(Px.BgDarker)
                        .border(1.dp, Px.Primary, RoundedCornerShape(10.dp))
                        .padding(horizontal = 18.dp, vertical = 16.dp),
                ) {
                    Text(featured.name, fontSize = 12.sp, color = Px.TextMuted, modifier = Modifier.padding(bottom = 4.dp))
                    Text(
                        (featured.label ?: "") + io.pixgo.app.data.model.planPeriodSuffix(featured.id),
                        fontFamily = Montserrat, fontWeight = FontWeight.Black, fontSize = 22.4.sp, color = Px.TextLight,
                    )
                    val featureKeys = io.pixgo.app.data.model.planFeatureKeys(featured.id)
                    if (featureKeys.isNotEmpty()) {
                        Column(Modifier.padding(top = 10.dp)) {
                            featureKeys.forEach { k ->
                                Text("✓ ${tr.t(k)}", fontSize = 12.8.sp, color = Px.TextMuted, modifier = Modifier.padding(bottom = 4.dp))
                            }
                        }
                    }
                }
            }

            if (others.isNotEmpty()) {
                Text(
                    "Também disponível: " + others.joinToString(" · ") { (it.label ?: it.name) + io.pixgo.app.data.model.planPeriodSuffix(it.id) },
                    fontSize = 12.sp, color = Px.TextMuted, textAlign = TextAlign.Center,
                    modifier = Modifier.padding(bottom = 18.dp),
                )
            }

            Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                PxButton("Ver planos", onClick = onSeePlans)
                PxButton("Continuar no grátis", onClick = onDismiss, variant = PxBtnVariant.Ghost, size = PxBtnSize.Sm)
            }
        }
    }
}
