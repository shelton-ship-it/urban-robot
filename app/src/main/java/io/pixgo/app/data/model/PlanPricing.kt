package io.pixgo.app.data.model

import java.util.Locale

/**
 * Port de app/_shared/lib/planPrice.ts (hub). Símbolo por omissão = NEXT_PUBLIC_CURRENCY_SYMBOL
 * do hub (default 'R$'); só é usado quando o backend não manda `currency` (ou manda BRL).
 */
private const val DEFAULT_SYMBOL = "R$"

fun formatPlanPrice(value: Double, currency: String?, isFree: Boolean, fallbackCurrency: String?): String {
    if (isFree || value == 0.0) {
        val cur = currency ?: fallbackCurrency
        return if (cur != null && cur != "BRL") "0 $cur" else "${DEFAULT_SYMBOL}0"
    }
    if (currency != null && currency != "BRL") {
        val txt = if (value % 1.0 == 0.0) value.toLong().toString() else String.format(Locale.US, "%.2f", value)
        return "$txt $currency"
    }
    return DEFAULT_SYMBOL + String.format(Locale.US, "%.2f", value)
}

/** Ciclo de cobrança pelo id canónico do plano (mesmo critério do PlansPage.tsx: /ano, /trimestre, /mês). */
fun planCycle(id: String): String? = when (id) {
    "monthly" -> "monthly"
    "quarterly" -> "quarterly"
    "annual" -> "annual"
    else -> null
}

fun planPeriodSuffix(id: String): String = when (id) {
    "annual" -> "/ano"
    "quarterly" -> "/trimestre"
    "free" -> ""
    else -> "/mês"
}

/** Chaves i18n das features por plano — as mesmas de app/main/plans/page.tsx do hub. */
fun planFeatureKeys(id: String): List<String> = when (id) {
    "free" -> listOf("plans.featuresFreeLimited", "plans.featuresFreeAds")
    "monthly" -> listOf("plans.featuresPaidAccess", "plans.featuresPaidNoAds", "plans.featuresPaidPriority", "plans.featuresPaidSupport")
    "quarterly" -> listOf("plans.featuresQuarterlyEverything", "plans.featuresQuarterlySave")
    "annual" -> listOf("plans.featuresAnnualEverything", "plans.featuresAnnualPrice", "plans.featuresAnnualEarlyAccess")
    else -> emptyList()
}
