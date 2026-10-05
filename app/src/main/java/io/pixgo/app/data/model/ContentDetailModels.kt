package io.pixgo.app.data.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * GET /api/content/:id — contrato confirmado em
 * pixel_service_v1/node-functions/api/routes/content.js:
 *   res.json({ ...content, meta, tags, seasons, uploader, download, in_list })
 * Nenhum campo inventado; tudo tem default para tolerar ausências.
 */
@Serializable
data class Episode(
    val id: String,
    val number: Int? = null,
    val title: String? = null,
    val description: String? = null,
    val poster: String? = null,
    /** segundos (rota devolve ep.duration || 0) */
    val duration: Int? = null
)

@Serializable
data class Season(
    val id: String,
    val number: Int? = null,
    @SerialName("episode_count") val episodeCount: Int? = null,
    val episodes: List<Episode> = emptyList()
)

@Serializable
data class ContentFullMeta(
    val title: String? = null,
    val poster: String? = null,
    val description: String? = null,
    val rating: Double? = null
)

/**
 * `uploader` de GET /api/content/:id — getUploaderPublic() em
 * lib/uploader-views.js ({ username, name, avatar, contents_count, total_views });
 * o ecrã só usa username + avatar (UploaderAvatar de watch/[id]/page.tsx).
 */
@Serializable
data class ContentUploader(
    val username: String? = null,
    val avatar: String? = null
)

/** POST /api/content/:id/view → { ok, counted, views } (routes/content.js). */
@Serializable
data class ViewRegisterResponse(
    val ok: Boolean = false,
    val counted: Boolean = false,
    val views: Long? = null
)

@Serializable
data class ContentDetail(
    val id: String,
    val type: String? = null,
    val year: Int? = null,
    val title: String? = null,
    val poster: String? = null,
    val description: String? = null,
    val meta: ContentFullMeta? = null,
    val seasons: List<Season> = emptyList(),
    /** Total de visualizações (content.views); ausente/null → o badge não aparece. */
    val views: Long? = null,
    val uploader: ContentUploader? = null,
    /** `in_list` embutido (Rodada 3) — ausente quando não há profile_id. */
    @SerialName("in_list") val inListRaw: Boolean? = null
) {
    val displayTitle: String get() = meta?.title ?: title ?: "—"
    val displayPoster: String? get() = meta?.poster ?: poster
    val displayRating: Double? get() = meta?.rating
    val displayDescription: String? get() = meta?.description ?: description
}

/** GET /api/mylist/check/:contentId — rota devolve { inList, profileId }. */
@Serializable
data class MyListCheckResponse(val inList: Boolean = false)

/** POST /api/progress/update — body camelCase exacto de lib/api.ts progressApi.update. */
@Serializable
data class ProgressUpdateBody(
    val profileId: String,
    val contentId: String,
    val episodeId: String? = null,
    val lang: String = "en",
    val progress: Int,
    val duration: Int? = null
)

/** Plano do upsell — shape de buildUpsellPlans() em middleware/rate-limit.js. */
@Serializable
data class UpsellPlan(
    val id: String,
    val name: String? = null,
    val price: Double? = null,
    val label: String? = null,
    @SerialName("billing_cycle") val billingCycle: String? = null,
    val features: List<String> = emptyList()
)

/**
 * Body do 429 (rate-limit.js): { message, plans } — usado tanto no
 * handshake /stream como no /heartbeat. O 409 devolve apenas { message }.
 */
@Serializable
data class StreamLimitErrorBody(
    val message: String? = null,
    val plans: List<UpsellPlan> = emptyList()
)
