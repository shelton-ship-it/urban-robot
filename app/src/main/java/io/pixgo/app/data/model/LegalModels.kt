package io.pixgo.app.data.model

import kotlinx.serialization.Serializable

/** GET /api/legal/:lang (routes/legal.js) — texto público, sem autenticação. */
@Serializable
data class LegalResponse(
    val legal: Map<String, String> = emptyMap(),
    val version: Int? = null,
    val updatedAt: String? = null
)
