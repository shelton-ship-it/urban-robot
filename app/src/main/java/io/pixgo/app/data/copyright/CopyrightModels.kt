package io.pixgo.app.data.copyright

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** Espelha frontend_web/src/lib/copyright.ts. */

const val MAX_ITEMS = 20
val PROTOCOL_RE = Regex("^DMCA-\\d{4}-[A-Z0-9]{8}$")
val EMAIL_RE = Regex("^[^\\s@]+@[^\\s@]+\\.[^\\s@]{2,}$")

val RELATIONSHIPS = listOf("owner", "agent", "licensee")
val DETECTION_METHODS = listOf("manual", "audio", "automated", "other")

val RELATIONSHIP_KEYS = mapOf(
    "owner" to "copyright.form.relOwner",
    "agent" to "copyright.form.relAgent",
    "licensee" to "copyright.form.relLicensee",
)

val DETECTION_KEYS = mapOf(
    "manual" to "copyright.form.detManual",
    "audio" to "copyright.form.detAudio",
    "automated" to "copyright.form.detAuto",
    "other" to "copyright.form.detOther",
)

fun isHttpUrl(v: String): Boolean {
    val s = v.trim()
    val ok = s.startsWith("http://", ignoreCase = true) || s.startsWith("https://", ignoreCase = true)
    if (!ok) return false
    return runCatching { java.net.URI(s).host?.isNotEmpty() == true }.getOrDefault(false)
}

/** Segundos para HH:MM:SS. */
fun formatClock(total: Int): String {
    val s = maxOf(0, total)
    val h = s / 3600
    val m = (s % 3600) / 60
    val sec = s % 60
    return listOf(h, m, sec).joinToString(":") { it.toString().padStart(2, '0') }
}

@Serializable
data class ReportItem(
    val url: String = "",
    @SerialName("content_id") val contentId: String? = null,
    val full: Boolean = true,
    val start: Double? = null,
    val end: Double? = null,
)

@Serializable
data class PublicReport(
    val found: Boolean = true,
    val id: String = "",
    val status: String? = null,
    val relationship: String? = null,
    @SerialName("work_url") val workUrl: String? = null,
    @SerialName("detection_method") val detectionMethod: String? = null,
    val items: List<ReportItem> = emptyList(),
    @SerialName("team_response") val teamResponse: String? = null,
    @SerialName("responded_at") val respondedAt: String? = null,
    @SerialName("decided_at") val decidedAt: String? = null,
    @SerialName("created_at") val createdAt: String? = null,
    @SerialName("updated_at") val updatedAt: String? = null,
)

/** Protocolos guardados neste dispositivo (equivalente ao localStorage 'pixgo_copyright_reports'). */
@Serializable
data class StoredReport(
    val id: String,
    val email: String,
    @SerialName("created_at") val createdAt: String = "",
)

data class ReportItemInput(
    val url: String,
    val full: Boolean,
    val start: Int,
    val end: Int,
)

data class ReportSubmission(
    val claimantName: String,
    val claimantEmail: String,
    val relationship: String,
    val workUrl: String,
    val detectionMethod: String,
    val details: String,
    val items: List<ReportItemInput>,
    val lang: String?,
)

sealed class SubmitResult {
    data class Ok(val id: String, val createdAt: String) : SubmitResult()
    object RateLimited : SubmitResult()
    object Failed : SubmitResult()
}
