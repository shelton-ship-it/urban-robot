package io.pixgo.app.data.download

import android.content.Context
import io.pixgo.app.data.auth.TokenManager
import io.pixgo.app.data.network.NetworkModule
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Request
import org.json.JSONObject
import java.io.File

sealed class DownloadStart {
    data class Started(val meta: DownloadMeta) : DownloadStart()
    /** 403 real do gate (plano free OU limite mensal atingido) — mensagem do backend. */
    data class GateBlocked(val message: String, val exhausted: Boolean, val currentPlan: String?) : DownloadStart()
    data class AlreadyDone(val meta: DownloadMeta) : DownloadStart()
    data class Failed(val message: String) : DownloadStart()
}

/**
 * Motor de download nativo — réplica fiel de startDownload() em
 * lib/downloads.ts + resumeInterruptedDownloads() em downloads-resume.ts,
 * adaptada ao Android (ficheiros no disco em vez de IndexedDB):
 *
 *  - obtém o manifesto REAL por GET /api/content/{id}/download (sessão
 *    autenticada existente via NetworkModule.stream/tokenManager);
 *  - baixa init.bin e cada segNNNNN.bin individualmente, escrevendo
 *    directo em disco (.part → rename atómico; nunca o ficheiro inteiro
 *    em RAM — correção do arrayBuffer() integral do OfflinePlayer web);
 *  - retoma do primeiro segmento em falta (savedSegmentCount), sem
 *    recomeçar do zero;
 *  - se um segmento falhar (URL assinada expirada entre sessões), reobtém
 *    o manifesto pelo MESMO endpoint real e continua com os segmentos já
 *    gravados (correção do bug de URL antiga no resume web);
 *  - cancelamento por contentId (mesmo padrão do Set `cancelledDownloads`);
 *  - marca COMPLETED apenas quando init + todos os segmentos existem em
 *    disco — integridade por presença física, não por HTTP 200.
 *
 * O conteúdo permanece CIFRADO em disco (chunk-v2 ChaCha20); a decifra só
 * acontece na reprodução, pelo BinDecryptDataSource existente.
 */
class DownloadEngine(private val context: Context) {

    private val store = DownloadStore(context)
    private val tokenManager = TokenManager(context)
    private val api by lazy { NetworkModule.stream(context, tokenManager) }
    // fetch() bruto do original para os .bin do CDN (raw.githubusercontent)
    // — sem Authorization/cookies; plainHttpClient é exatamente isso.
    private val cdnClient by lazy { NetworkModule.plainHttpClient() }

    private val cancelled = mutableSetOf<String>()
    private val inFlight = mutableSetOf<String>()

    companion object {
        /** Duração estimada por segmento — OFFLINE_SEG_DURATION = 6 em ShakaPlayer.tsx. */
        const val OFFLINE_SEG_DURATION = 6.0
        /** Teto defensivo por segmento (~200 MB) — segmentos típicos têm poucos MB. */
        private const val MAX_SEGMENT_BYTES = 200L * 1024 * 1024
    }

    fun isDownloading(key: String): Boolean = synchronized(inFlight) { key in inFlight }

    fun cancel(key: String) {
        synchronized(cancelled) { cancelled.add(key) }
    }

    private class CancelledException : Exception("Download cancelado")

    suspend fun start(contentId: String, episodeId: String?, fallbackTitle: String, fallbackPoster: String): DownloadStart =
        withContext(Dispatchers.IO) {
            val key = DownloadStore.keyFor(contentId, episodeId)
            if (synchronized(inFlight) { !inFlight.add(key) }) {
                return@withContext DownloadStart.Failed("Este download já está em curso.")
            }
            try {
                val existing = store.get(key)
                if (existing?.status == DownloadStatus.COMPLETED) {
                    return@withContext DownloadStart.AlreadyDone(existing)
                }
                synchronized(cancelled) { cancelled.remove(key) }

                val resp = api.download(contentId, buildParams(episodeId))
                when {
                    resp.code() == 403 -> {
                        val err = parseGateError(resp.errorBody()?.string())
                        return@withContext DownloadStart.GateBlocked(
                            err?.message ?: "Download disponível nos planos pagos",
                            err?.exhausted ?: false,
                            err?.currentPlan
                        )
                    }
                    !resp.isSuccessful -> {
                        val err = parseGateError(resp.errorBody()?.string())
                        return@withContext DownloadStart.Failed(err?.message ?: "Falha ao obter licença")
                    }
                }
                val data = resp.body() ?: return@withContext DownloadStart.Failed("Resposta vazia do servidor")
                runManifest(key, contentId, episodeId, data, fallbackTitle, fallbackPoster)
            } catch (e: CancelledException) {
                DownloadStart.Failed("cancelado")
            } catch (e: Exception) {
                DownloadStart.Failed(e.message ?: "Erro desconhecido")
            } finally {
                synchronized(inFlight) { inFlight.remove(key) }
            }
        }

    /**
     * Retomador automático — equivalente de resumeInterruptedDownloads():
     * procura metas 'downloading'/'error', pede um MANIFESTO FRESCO pelo
     * endpoint real (as URLs assinadas podem ter expirado) e continua a
     * partir do primeiro segmento em falta, sem apagar os válidos.
     */
    suspend fun resumeAll() = withContext(Dispatchers.IO) {
        val pending = store.allOnce().filter { it.status != DownloadStatus.COMPLETED }
        for (d in pending) {
            if (isDownloading(d.key)) continue
            if (synchronized(inFlight) { !inFlight.add(d.key) }) continue
            try {
                synchronized(cancelled) { cancelled.remove(d.key) }
                val resp = api.download(d.contentId, buildParams(d.episodeId))
                if (!resp.isSuccessful) continue
                val data = resp.body() ?: continue
                runManifest(d.key, d.contentId, d.episodeId, data, d.title, d.poster)
            } catch (e: Exception) {
                // item específico falhou a retomar (rede etc.) — segue para
                // os restantes, igual ao catch {} do resumidor original.
            } finally {
                synchronized(inFlight) { inFlight.remove(d.key) }
            }
        }
    }

    private fun buildParams(episodeId: String?): Map<String, String> =
        if (episodeId.isNullOrBlank()) emptyMap() else mapOf("episode" to episodeId)

    private suspend fun runManifest(
        key: String,
        contentId: String,
        episodeId: String?,
        data: DownloadResponse,
        fallbackTitle: String,
        fallbackPoster: String,
    ): DownloadStart {
        val title = data.content?.title?.takeIf { it.isNotBlank() } ?: fallbackTitle
        val poster = data.content?.poster ?: fallbackPoster
        val manifest = data.manifest
        val total = manifest.segUrls.size
        if (total == 0) throw Exception("No segments to download")

        // Snapshot local do manifesto — renovável por revalidação se uma URL
        // assinada expirar a meio da descarga (correção do resume web).
        var segUrls: List<String> = manifest.segUrls
        var initUrl: String? = manifest.initUrl

        var meta = store.get(key) ?: DownloadMeta(
            key = key, contentId = contentId, episodeId = episodeId,
            title = title, poster = poster, startedAt = nowIso(),
        ).let { store.upsert(it); it }

        suspend fun persistProgress(pct: Int, status: DownloadStatus, error: String? = null) {
            meta = meta.copy(status = status, progress = pct, error = error, segCount = total)
            store.upsert(meta)
        }

        val startIndex = store.savedSegmentCount(key, total)
        persistProgress(percent(startIndex, total), DownloadStatus.DOWNLOADING)

        // init.bin — FIX do original: sem ele a reprodução offline é impossível.
        if (initUrl != null && !File(store.dirFor(key), store.initFileName()).exists()) {
            try {
                downloadToFile(key, store.initFileName(), initUrl!!)
            } catch (e: CancelledException) { throw e } catch (_: Exception) { /* não bloqueia os segmentos */ }
        }

        // nonces — baixados mas NÃO usados pela decifra actual (nem o worker
        // web os usa: o formato chunk-v2 carrega o nonce inline por chunk).
        // Mantidos tal como o original guarda nd.nonces no meta concluído.
        try {
            manifest.noncesUrl?.let { url ->
                val req = Request.Builder().url(url).build()
                cdnClient.newCall(req).execute().use { r ->
                    if (r.isSuccessful) { /* corpo lido e descartado — paridade com o fetch() web */ }
                }
            }
        } catch (_: Exception) { /* non-fatal no original */ }

        try {
            var i = startIndex
            while (i < total) {
                if (synchronized(cancelled) { cancelled.contains(key) }) {
                    synchronized(cancelled) { cancelled.remove(key) }
                    store.remove(key)
                    throw CancelledException()
                }
                val fileName = store.segFileName(i)
                if (!File(store.dirFor(key), fileName).exists()) {
                    var attempt = 0
                    var ok = false
                    var lastErr = "sem erro registado"
                    while (!ok && attempt < 2) {
                        attempt++
                        val url = segUrls.getOrElse(i) { "" }
                        val saved = try { downloadToFile(key, fileName, url) } catch (e: CancelledException) { throw e } catch (e: Exception) { lastErr = e.message ?: "rede"; false }
                        if (saved) {
                            ok = true
                        } else if (attempt == 1) {
                            // Possível URL assinada expirada: reobtém o MANIFESTO
                            // fresco pelo MESMO endpoint real ANTES da próxima
                            // tentativa (correção do resume web que reusava a
                            // URL velha), sem apagar segmentos já gravados.
                            val fresh = runCatching { api.download(contentId, buildParams(episodeId)) }.getOrNull()
                            fresh?.body()?.let { newData ->
                                if (newData.manifest.segUrls.isNotEmpty()) segUrls = newData.manifest.segUrls
                                newData.manifest.initUrl?.let { initUrl = it }
                            }
                        }
                    }
                    if (!ok) throw Exception("Segment $i fetch failed: $lastErr")
                }
                i++
                persistProgress(percent(i, total), DownloadStatus.DOWNLOADING)
            }
        } catch (e: CancelledException) {
            throw e
        } catch (e: Exception) {
            // Mantém os segmentos já gravados (não apaga) — nova chamada retoma daqui.
            persistProgress(percent(store.savedSegmentCount(key, total), total), DownloadStatus.ERROR, e.message)
            throw e
        }

        // Checagem final anti-corrida de cancelamento (igual ao original).
        if (synchronized(cancelled) { cancelled.contains(key) }) {
            synchronized(cancelled) { cancelled.remove(key) }
            store.remove(key)
            throw CancelledException()
        }

        // Integridade REAL antes de marcar concluído: init + todos os
        // segmentos presentes em disco (nunca basta "HTTP 200").
        val hasInit = File(store.dirFor(key), store.initFileName()).exists()
        val saved = store.savedSegmentCount(key, total)
        if (!hasInit || saved < total) {
            persistProgress(percent(saved, total), DownloadStatus.ERROR, "Ficheiros incompletos após descarga")
            throw Exception("Download incompleto: init=${hasInit} segs=$saved/$total")
        }

        val completed = meta.copy(
            status = DownloadStatus.COMPLETED,
            progress = 100,
            error = null,
            expiresAt = data.expiresAt,
            downloadedAt = nowIso(),
            keyHex = data.drmKeyHex,
            hasInit = true,
            noncesUrl = manifest.noncesUrl,
            quality = manifest.segExt,
        )
        store.upsert(completed)
        return DownloadStart.Started(completed)
    }

    /** Stream directo para um .part no disco (chunks de 64 KB; nunca o body
     *  inteiro acumulado em RAM — correção do blob/arrayBuffer do web). */
    private suspend fun fetchToPart(key: String, name: String, url: String): File? = withContext(Dispatchers.IO) {
        if (url.isBlank()) return@withContext null
        val req = Request.Builder().url(url).build()
        cdnClient.newCall(req).execute().use { r ->
            if (!r.isSuccessful) return@withContext null
            val tmp = File(store.dirFor(key), "$name.part")
            try {
                val stream = r.body?.byteStream()
                if (stream == null) { tmp.delete(); return@withContext null }
                stream.use { input ->
                    tmp.outputStream().use { output ->
                        val buf = ByteArray(64 * 1024)
                        var totalRead = 0L
                        var n = input.read(buf)
                        while (n >= 0) {
                            totalRead += n
                            if (totalRead > MAX_SEGMENT_BYTES) throw Exception("Segmento excede tamanho esperado")
                            output.write(buf, 0, n)
                            n = input.read(buf)
                        }
                    }
                }
            } catch (e: Exception) { tmp.delete(); throw e }
            if (tmp.length() == 0L) { tmp.delete(); return@withContext null }
            tmp
        }
    }

    /** Grava um recurso: stream → .part → rename atómico. true = sucesso. */
    private suspend fun downloadToFile(key: String, name: String, url: String): Boolean {
        val tmp = fetchToPart(key, name, url) ?: return false
        val final = File(store.dirFor(key), name)
        if (final.exists()) final.delete()
        val ok = tmp.renameTo(final)
        if (!ok) tmp.delete()
        return ok
    }

    private fun percent(done: Int, total: Int): Int = ((done.toDouble() / total) * 100).toInt().coerceIn(0, 100)
    private fun nowIso(): String = java.time.Instant.now().toString()

    private fun parseGateError(raw: String?): DownloadGateError? {
        if (raw.isNullOrBlank()) return null
        return try {
            val o = JSONObject(raw)
            DownloadGateError(
                message = o.optString("message").takeIf { it.isNotBlank() },
                currentPlan = o.optString("current_plan").takeIf { it.isNotBlank() },
                exhausted = o.optBoolean("exhausted", false),
            )
        } catch (e: Exception) { null }
    }
}
