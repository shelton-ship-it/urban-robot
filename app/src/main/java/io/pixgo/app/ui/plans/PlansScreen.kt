package io.pixgo.app.ui.plans

import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import io.pixgo.app.ui.common.PxBadge
import io.pixgo.app.ui.common.PxBadgeKind
import io.pixgo.app.ui.common.PxBtnVariant
import io.pixgo.app.ui.common.PxButton
import io.pixgo.app.ui.common.PxPageHeader
import io.pixgo.app.ui.common.PxPageLoading
import io.pixgo.app.ui.common.pagePadding

import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import io.pixgo.app.data.auth.AuthRepository
import io.pixgo.app.data.i18n.LocalTranslator
import io.pixgo.app.data.model.PaymentPlan
import io.pixgo.app.data.model.planFeatureKeys
import io.pixgo.app.data.model.planPeriodSuffix
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.withStyle
import io.pixgo.app.data.model.Plan
import io.pixgo.app.ui.theme.Montserrat
import io.pixgo.app.ui.theme.Px

/**
 * Réplica 1:1 de frontend_web/src/app/main/plans/page.tsx (rota ATIVA
 * /main/plans, v3.0):
 *  - Preço e nome vêm do hub: GET /api/plans do api-core (plansApi.list() do PlansPage.tsx),
 *    preço do env em BRL (MZN para IPs de MZ), formatado como lib/planPrice.ts. As features
 *    usam as mesmas chaves i18n do hub (plans.features*). O plano grátis aparece, como no hub;
 *  - "Melhor valor" = plano annual, salvo override `highlight`
 *    (no web chega via ?highlight= do RateLimitModal; aqui é o plano
 *    sugerido passado por quem abre a tela — mesmo mecanismo);
 *  - isMZN (currency==='MZN' num dos planos) → métodos do gateway activo (`methods`
 *    vindos do hub: M-Pesa, ou M-Pesa + e-Mola — decididos pela env MZ_GATEWAY do api-core);
 *    senão Pix/Visa/Mastercard/Boleto (mesmos SVGs oficiais de
 *    public/payment-icons, copiados para assets/payment-icons);
 *  - "Assinar" → window.location.href = HUB_CHECKOUT_URL?plan=&return_to=.
 *    No Android o equivalente exacto é abrir a rota REAL de checkout do hub
 *    (https://app.pixgo.qzz.io/main/plans/checkout — para onde também a
 *    rota local /main/plans/checkout redirecciona) na WebView única já
 *    autorizada (FastWebViewSheet), com plan e return_to preservidos.
 */
private const val HUB_CHECKOUT_URL = "https://app.pixgo.qzz.io/main/plans/checkout"

private data class PaymentMethod(val name: String, val asset: String)
private val PAYMENT_METHODS = listOf(
    PaymentMethod("Pix", "payment-icons/pix.svg"),
    PaymentMethod("Visa", "payment-icons/visa.svg"),
    PaymentMethod("Mastercard", "payment-icons/mastercard.svg"),
    PaymentMethod("Boleto", "payment-icons/boleto.svg"),
)
private val MPESA_METHOD = PaymentMethod("M-Pesa", "payment-icons/M-PESA_LOGO-01.svg")
private val EMOLA_METHOD = PaymentMethod("e-Mola", "payment-icons/emola.svg")

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun PlansScreen(
    authRepository: AuthRepository,
    plan: Plan?,
    highlight: String?,
    onSubscribe: (url: String) -> Unit,
) {
    val t = LocalTranslator.current
    var plans by remember { mutableStateOf<List<PaymentPlan>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        try {
            plans = authRepository.paymentPlans()
        } catch (_: Exception) {
            error = true
        } finally {
            loading = false
        }
    }

    val isMZN = plans.any { it.currency == "MZN" }
    // Métodos do gateway activo em MZ, como o hub os manda (nunca decididos aqui).
    val mzCodes = plans.firstNotNullOfOrNull { it.methods }.orEmpty()
    val mzMethods = buildList {
        add(MPESA_METHOD)
        if ("emola" in mzCodes) add(EMOLA_METHOD)
    }
    val paymentMethods = if (isMZN) mzMethods else PAYMENT_METHODS
    val payWithKey = if (isMZN && "emola" in mzCodes) "plans.payWithMpesaEmola" else "plans.payWithMpesa"

    fun handleSubscribe(planId: String) {
        // Equivalente ao returnTo do original (`${origin}${pathname}${search}`
        // de /main/plans), apontando para o domínio público da ferramenta.
        val returnTo = "https://pixgo.qzz.io/main/plans" +
            (highlight?.let { "?highlight=$it" } ?: "")
        // px_app=android: sinal explícito para o hub abrir o checkout Hotmart em página inteira (WebView)
        onSubscribe("$HUB_CHECKOUT_URL?plan=$planId&return_to=${Uri.encode(returnTo)}&px_app=android")
    }

    val narrow = androidx.compose.ui.platform.LocalConfiguration.current.screenWidthDp <= 768

    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(pagePadding()),
    ) {
        // Cabeçalho COMPACTO: os cards ficam logo a seguir a "Escolha o plano…"
        // (PxPageHeader tinha 22dp extra e o loading ocupava 320dp mínimos —
        // era isso que empurrava os cards para baixo).
        Column(Modifier.fillMaxWidth().padding(bottom = 14.dp)) {
            Text(t.t("plans.title"), style = io.pixgo.app.ui.common.PxText.pageTitle())
            Text(t.t("plans.subtitle"), style = io.pixgo.app.ui.common.PxText.PageSubtitle, modifier = Modifier.padding(top = 3.dp))
            if (isMZN) {
                Row(
                    Modifier.padding(top = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    mzMethods.forEach { m ->
                        AsyncImage(
                            model = "file:///android_asset/${m.asset}",
                            contentDescription = m.name,
                            contentScale = ContentScale.Fit,
                            modifier = Modifier.height(26.dp),
                        )
                    }
                    Text(t.t(payWithKey), color = Px.TextLight, fontSize = 13.6.sp, fontWeight = FontWeight.SemiBold)
                }
            }
        }

        // Skeleton dos cards (nada de spinner) enquanto os planos carregam.
        if (loading) {
            if (narrow) {
                Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(18.dp)) {
                    repeat(2) { io.pixgo.app.ui.common.PxPlanCardSkeleton(Modifier.fillMaxWidth()) }
                }
            } else {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(18.dp)) {
                    repeat(3) { io.pixgo.app.ui.common.PxPlanCardSkeleton(Modifier.width(320.dp)) }
                }
            }
        }

        if (!loading && error) {
            // Texto literal idêntico ao fallback da página web.
            Text(
                "Não foi possível carregar os planos agora. Tenta novamente em instantes.",
                color = Px.TextMuted,
                fontSize = 14.sp,
            )
        }

        if (!loading && !error) {
            // Layout determinístico (antes: FlowRow com fillMaxWidth + widthIn, que em
            // ecrãs estreitos inflava o espaço antes dos planos). Estreito: uma coluna
            // de cards, logo abaixo do cabeçalho. Largo: cards lado a lado (.flex 1 1 280).
            if (narrow) {
                Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(18.dp)) {
                    plans.forEach { p ->
                        PlansCard(
                            plan = p,
                            isCurrent = plan?.id == p.id && plan?.isActive != false,
                            isFeatured = if (highlight != null) p.id == highlight else p.id == "annual",
                            onSubscribe = { handleSubscribe(p.id) },
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                }
            } else {
                FlowRow(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(18.dp),
                    verticalArrangement = Arrangement.spacedBy(18.dp),
                ) {
                    plans.forEach { p ->
                        PlansCard(
                            plan = p,
                            isCurrent = plan?.id == p.id && plan?.isActive != false,
                            isFeatured = if (highlight != null) p.id == highlight else p.id == "annual",
                            onSubscribe = { handleSubscribe(p.id) },
                            modifier = Modifier.width(320.dp),
                        )
                    }
                }
            }

            Spacer(Modifier.height(28.dp))
            Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
                Text("Métodos de pagamento aceites", color = Px.TextMuted, fontSize = 12.sp)
                Spacer(Modifier.height(10.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    paymentMethods.map { m ->
                        AsyncImage(
                            model = "file:///android_asset/${m.asset}",
                            contentDescription = m.name,
                            contentScale = ContentScale.Fit,
                            modifier = Modifier
                                .height(32.dp)
                                .clip(RoundedCornerShape(6.dp)),
                        )
                    }
                }
            }
        }
    }
}

/** `.plan-card` (+ `.featured`): borda 2px, raio 12, padding 22; destaque = borda primária, brilho e barra de 3px em gradiente. */
@Composable
private fun PlansCard(
    plan: PaymentPlan,
    isCurrent: Boolean,
    isFeatured: Boolean,
    onSubscribe: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val shape = RoundedCornerShape(Px.Radius)
    Box(
        modifier
            .then(
                if (isFeatured) Modifier.shadow(14.dp, shape, ambientColor = Px.Primary.copy(alpha = 0.16f), spotColor = Px.Primary.copy(alpha = 0.16f))
                else Modifier
            )
            .clip(shape)
            .background(Px.CardBg)
            .border(2.dp, if (isFeatured) Px.Primary else Px.Border, shape)
    ) {
        if (isFeatured) {
            Box(
                Modifier.fillMaxWidth().height(3.dp).align(Alignment.TopStart)
                    .background(Brush.horizontalGradient(listOf(Px.Primary, Px.Accent)))
            )
            PxBadge("Melhor valor", PxBadgeKind.Red, Modifier.align(Alignment.TopEnd).padding(top = 14.dp, end = 14.dp))
        }
        Column(Modifier.padding(22.dp)) {
            val t = LocalTranslator.current
            val isFree = plan.id == "free"
            Text(
                t.t("plans.${plan.id}").takeIf { it != "plans.${plan.id}" } ?: plan.name,
                color = Px.TextLight, fontFamily = Montserrat, fontWeight = FontWeight.ExtraBold, fontSize = 17.6.sp,
                modifier = Modifier.padding(bottom = 4.dp)
            )
            // `.plan-price` do hub: 1.9rem/900 + sufixo .8rem muted (/mês, /trimestre, /ano)
            Text(
                buildAnnotatedString {
                    append(plan.label ?: "")
                    if (!isFree) withStyle(SpanStyle(color = Px.TextMuted, fontWeight = FontWeight.Medium, fontSize = 12.8.sp)) {
                        append(planPeriodSuffix(plan.id))
                    }
                },
                color = Px.TextLight, fontWeight = FontWeight.Black, fontSize = 30.4.sp,
                modifier = Modifier.padding(bottom = 14.dp)
            )
            Column(Modifier.padding(bottom = 20.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                planFeatureKeys(plan.id).forEach { k -> FeatureRow(t.t(k)) }
            }
            when {
                isCurrent -> PxButton(t.t("plans.current"), onClick = {}, enabled = false, variant = PxBtnVariant.Secondary, modifier = Modifier.fillMaxWidth())
                isFree -> PxButton(t.t("plans.free"), onClick = {}, enabled = false, variant = PxBtnVariant.Secondary, modifier = Modifier.fillMaxWidth())
                else -> PxButton(t.t("plans.subscribe"), onClick = onSubscribe, modifier = Modifier.fillMaxWidth())
            }
        }
    }
}

@Composable
private fun FeatureRow(text: String) {
    Row(verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Icon(Icons.Filled.Check, null, Modifier.size(16.dp), tint = Px.Secondary)
        Text(text, color = Px.TextMuted, fontSize = 13.44.sp, lineHeight = 20.sp)
    }
}
