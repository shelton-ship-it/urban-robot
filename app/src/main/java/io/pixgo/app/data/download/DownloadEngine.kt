package io.pixgo.app.data.download

import android.content.Context
import io.pixgo.app.data.auth.TokenManager
import io.pixgo.app.data.network.NetworkModule
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.Call
import okhttp3.Request
import org.json.JSONObject
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean

sealed class DownloadStart {
    /** Download CONCLUÍDO (init + todos os segmentos em disco). */
    data class Started(val meta: DownloadMeta) : DownloadStart()
    /** 403 real do gate (plano free OU limite mensal atingido) — mensagem do backend. */
    data class GateBlocked(val message: String, val exhausted: Boolean, val currentPlan: String?) : DownloadStart()
    data class AlreadyDone(val meta: DownloadMeta) : DownloadStart()
    data class Failed(val message: String) : DownloadStart()
}

/**
 * Motor de download nativo — réplica de startDownload() em lib/downloads.ts +
 * resumeInterruptedDownloads() em downloads-resume.ts, adaptada ao Android.
 *
 * CORREÇÕES desta versão (todas confirmadas no código anterior):
 *
 *  1. O download corria no escopo de composição do ecrã que o iniciou
 *     (rememberCoroutineScope do WatchScreen). Sair da Watch — que é
 *     exatamente o que se faz para ir ver a página Downloads — cancelava a
 *     coroutine a meio; o catch(Exception) engolia a CancellationException e
 *     o meta ficava para sempre em "DOWNLOADING N%" sem ninguém a baixar.
 *     Agora o trabalho corre num escopo de PROCESSO (engineScope); o chamador
 *     só espera o resultado (await) e pode sair sem matar o download.
 *
 *  2. Cada ecrã criava a sua própria instância do motor, cada uma com os seus
 *     conjuntos `cancelled`/`inFlight`: "Cancelar" na página Downloads não
 *     parava o download iniciado na Watch, e o mesmo download podia correr
 *     duas vezes em simultâneo, a escrever nos mesmos .part. O estado
 *     (inFlight/cancelled/chamadas HTTP activas) é agora partilhado por todo
 *     o processo (companion), por isso qualquer instância vê o mesmo estado.
 *
 *  3. O meta só era criado DEPOIS de obter o manifesto: durante essa espera a
 *     página Downloads não mostrava nada. Agora é criado logo no início.
 *
 *  4. Offline com durações reais: o index.m3u8 gerado pelo process.yml tem o
 *     #EXTINF verdadeiro de cada segmento (com `-c:v copy` os cortes seguem os
 *     keyframes, não os 4 s de hls_time). O offline montava a playlist com 6 s
 *     fixos para todos. Agora as durações reais são lidas do index.m3u8 (a
 *     partir do masterUrl do manifesto) e guardadas em durations.txt.
 *
 * O conteúdo permanece CIFRADO em disco (chunk-v2 ChaCha20); a decifra só
 * acontece na reprodução, pelo BinDecryptDataSource.
 */
class DownloadEngine(private val context: Context) {

    private val store = DownloadStore(context)
    private val tokenManager = TokenManager(context)
    private val api by lazy { NetworkModule.stream(context, tokenManager) }
    // fetch() bruto do original para os .bin do CDN — sem Authorization/cookies.
    private val cdnClient by lazy { NetworkModule.plainHttpClient() }

    companion object {
        /** Duração de reserva por segmento (só usada se durations.txt não existir). */
        const val OFFLINE_SEG_DURATION = 6.0
        /** Teto defensivo por segmento (~200 MB) — segmentos típicos têm poucos MB. */
        private const val MAX_SEGMENT_BYTES = 200L * 1024 * 1024

        // Estado PARTILHADO por todo o processo (ver ponto 2 acima).
        private val engineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        private val cancelled = ConcurrentHashMap.newKeySet<String>()
        private val inFlight = ConcurrentHashMap.newKeySet<String>()
        private val activeCalls = ConcurrentHashMap<String, Call>()
        private val resuming = AtomicBoolean(false)
    }

    fun isDownloading(key: String): Boolean = key in inFlight

    fun cancel(key: String) {
        cancelled.add(key)
        activeCalls[key]?.cancel()
    }

    private class CancelledException : Exception("Download cancelado")

    private class PlaylistInfo(val durations: List<Double>, val quality: String?)

    suspend fun start(contentId: String, episodeId: String?, fallbackTitle: String, fallbackPoster: String): DownloadStart =
        withContext(Dispatchers.IO) {
            val key = DownloadStore.keyFor(contentId, episodeId)
            val existing = store.get(key)
            if (existing?.status == DownloadStatus.COMPLETED) {
                return@withContext DownloadStart.AlreadyDone(existing)
            }
            if (!inFlight.add(key)) {
                return@withContext DownloadStart.Failed("Este download já está em curso.")
            }
            var handedOff = false
            try {
                cancelled.remove(key)

                // Entrada imediata: a página Downloads mostra "Preparando…" já,
                // sem esperar pelo manifesto.
                if (existing == null) {
                    store.upsert(
                        DownloadMeta(
                            key = key, contentId = contentId, episodeId = episodeId,
                            title = fallbackTitle, poster = fallbackPoster, startedAt = nowIso(),
                        )
                    )
                }

                val resp = api.download(contentId, buildParams(episodeId))
                if (resp.code() == 403) {
                    val err = parseGateError(resp.errorBody()?.string())
                    discardIfNew(key, existing)
                    return@withContext DownloadStart.GateBlocked(
                        err?.message ?: "Download disponível nos planos pagos",
                        err?.exhausted ?: false,
                        err?.currentPlan
                    )
                }
                if (!resp.isSuccessful) {
                    val err = parseGateError(resp.errorBody()?.string())
                    discardIfNew(key, existing)
                    return@withContext DownloadStart.Failed(err?.message ?: "Falha ao obter licença")
                }
                val data = resp.body()
                if (data == null) {
                    discardIfNew(key, existing)
                    return@withContext DownloadStart.Failed("Resposta vazia do servidor")
                }

                // O trabalho pesado vive no escopo do PROCESSO: se o chamador
                // sair do ecrã, só o await é cancelado — o download continua.
                val job = engineScope.async {
                    try {
                        runManifest(key, contentId, episodeId, data, fallbackTitle, fallbackPoster)
                    } finally {
                        inFlight.remove(key)
                        activeCalls.remove(key)
                    }
                }
                handedOff = true
                job.await()
            } catch (e: CancelledException) {
                DownloadStart.Failed("cancelado")
            } catch (e: CancellationException) {
                // O chamador saiu do ecrã. Se ainda não passou para o escopo do
                // processo, não deixa uma entrada "Preparando" órfã.
                if (!handedOff) withContext(NonCancellable) { discardIfNew(key, existing) }
                throw e
            } catch (e: Exception) {
                if (!handedOff) discardIfNew(key, existing)
                DownloadStart.Failed(e.message ?: "Erro desconhecido")
            } finally {
                if (!handedOff) inFlight.remove(key)
            }
        }

    /** Remove a entrada criada por start() quando nada chegou a ser descarregado. */
    private suspend fun discardIfNew(key: String, existing: DownloadMeta?) {
        if (existing == null) store.remove(key)
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
            if (!inFlight.add(d.key)) continue
            try {
                cancelled.remove(d.key)
                val resp = api.download(d.contentId, buildParams(d.episodeId))
                if (!resp.isSuccessful) continue
                val data = resp.body() ?: continue
                runManifest(d.key, d.contentId, d.episodeId, data, d.title, d.poster)
            } catch (e: Exception) {
                // item específico falhou a retomar (rede etc.) — segue para os restantes.
            } finally {
                inFlight.remove(d.key)
                activeCalls.remove(d.key)
            }
        }
    }

    /** Dispara resumeAll() no escopo do processo (sem bloquear o ecrã que chama). */
    fun resumeInBackground() {
        if (!resuming.compareAndSet(false, true)) return
        engineScope.launch {
            try { resumeAll() } finally { resuming.set(false) }
        }
    }

    private fun buildParams(episodeId: String?): Map<String, String> =
        if (episodeId.isNullOrBlank()) emptyMap() else mapOf("episode" to episodeId)

    private suspend fun abortIfCancelled(key: String) {
        if (cancelled.remove(key)) {
            store.remove(key)
            throw CancelledException()
        }
    }

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

        var meta = (store.get(key) ?: DownloadMeta(
            key = key, contentId = contentId, episodeId = episodeId,
            title = title, poster = poster, startedAt = nowIso(),
        )).copy(title = title, poster = poster)

        suspend fun persistProgress(pct: Int, status: DownloadStatus, error: String? = null) {
            meta = meta.copy(status = status, progress = pct, error = error, segCount = total)
            store.upsert(meta)
        }

        if (total == 0) {
            persistProgress(0, DownloadStatus.ERROR, "Manifesto sem segmentos")
            throw Exception("No segments to download")
        }

        // Snapshot local do manifesto — renovável se uma URL assinada expirar.
        var segUrls: List<String> = manifest.segUrls
        var initUrl: String? = manifest.initUrl

        val startIndex = store.savedSegmentCount(key, total)
        persistProgress(percent(startIndex, total), DownloadStatus.DOWNLOADING)

        // init.bin — sem ele a reprodução offline é impossível.
        if (initUrl != null && !File(store.dirFor(key), store.initFileName()).exists()) {
            try {
                downloadToFile(key, store.initFileName(), initUrl!!)
            } catch (e: CancelledException) { throw e } catch (_: Exception) { /* não bloqueia os segmentos */ }
        }
        abortIfCancelled(key)

        // Durações REAIS + resolução, do index.m3u8 gerado pelo process.yml.
        if (!store.durationsFile(key).exists() || meta.quality.isBlank()) {
            fetchPlaylistInfo(manifest.masterUrl)?.let { info ->
                if (info.durations.size == total) store.writeDurations(key, info.durations)
                info.quality?.let { q -> meta = meta.copy(quality = q) }
            }
        }

        // nonces — baixados mas NÃO usados pela decifra actual (chunk-v2 leva o
        // nonce inline por chunk). Mantido como estava.
        try {
            manifest.noncesUrl?.let { url ->
                val req = Request.Builder().url(url).build()
                cdnClient.newCall(req).execute().use { _ -> }
            }
        } catch (_: Exception) { /* non-fatal no original */ }

        try {
            var i = startIndex
            while (i < total) {
                abortIfCancelled(key)
                val fileName = store.segFileName(i)
                if (!File(store.dirFor(key), fileName).exists()) {
                    var attempt = 0
                    var ok = false
                    var lastErr = "sem erro registado"
                    while (!ok && attempt < 2) {
                        attempt++
                        val url = segUrls.getOrElse(i) { "" }
                        val saved = try {
                            downloadToFile(key, fileName, url)
                        } catch (e: CancelledException) {
                            throw e
                        } catch (e: Exception) {
                            lastErr = e.message ?: "rede"; false
                        }
                        if (saved) {
                            ok = true
                        } else {
                            // cancelamento a meio de um segmento: a chamada HTTP foi
                            // abortada — sai limpo em vez de contar como falha de rede.
                            abortIfCancelled(key)
                            if (attempt == 1) {
                                // Possível URL assinada expirada: reobtém o MANIFESTO fresco.
                                val fresh = runCatching { api.download(contentId, buildParams(episodeId)) }.getOrNull()
                                fresh?.body()?.let { newData ->
                                    if (newData.manifest.segUrls.isNotEmpty()) segUrls = newData.manifest.segUrls
                                    newData.manifest.initUrl?.let { initUrl = it }
                                }
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
            // Mantém os segmentos já gravados — nova chamada retoma daqui.
            persistProgress(percent(store.savedSegmentCount(key, total), total), DownloadStatus.ERROR, e.message)
            throw e
        }

        abortIfCancelled(key)

        // Integridade REAL antes de marcar concluído: init + todos os segmentos em disco.
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
            quality = meta.quality.ifBlank { manifest.segExt },
        )
        store.upsert(completed)
        return DownloadStart.Started(completed)
    }

    private fun httpText(url: String): String? {
        val req = Request.Builder().url(url).build()
        return cdnClient.newCall(req).execute().use { r ->
            if (r.isSuccessful) r.body?.string() else null
        }
    }

    /**
     * master.m3u8 → (RESOLUTION, URL do index.m3u8) → index.m3u8 → #EXTINF reais.
     * Formato exacto escrito pelo process.yml (rewrite_urls): o master tem uma
     * única #EXT-X-STREAM-INF com RESOLUTION=WxH e a URL absoluta do index; o
     * index tem um #EXTINF por segmento, pela mesma ordem dos segNNNNN.bin.
     * Qualquer falha devolve null → o offline cai nas durações de reserva.
     */
    private fun fetchPlaylistInfo(masterUrl: String?): PlaylistInfo? {
        if (masterUrl.isNullOrBlank()) return null
        return try {
            val master = httpText(masterUrl) ?: return null
            val quality = Regex("RESOLUTION=(\\d+)x(\\d+)").find(master)
                ?.groupValues?.get(2)?.toIntOrNull()?.let { qualityLabel(it) }
            val variant = master.lineSequence().map { it.trim() }
                .firstOrNull { it.isNotEmpty() && !it.startsWith("#") }
            val durations = if (variant != null) {
                val abs = if (variant.startsWith("http")) variant
                else java.net.URI(masterUrl).resolve(variant).toString()
                val extinf = Regex("^#EXTINF:([0-9]+(?:\\.[0-9]+)?)")
                httpText(abs)?.lineSequence()
                    ?.mapNotNull { extinf.find(it.trim())?.groupValues?.get(1)?.toDoubleOrNull() }
                    ?.toList().orEmpty()
            } else emptyList()
            PlaylistInfo(durations, quality)
        } catch (e: Exception) { null }
    }

    // Mesmo mapeamento do `label` no process.yml.
    private fun qualityLabel(h: Int): String = when {
        h >= 2160 -> "4k"
        h >= 1440 -> "1440p"
        h >= 1080 -> "1080p"
        h >= 720 -> "720p"
        else -> "${h}p"
    }

    /** Stream directo para um .part no disco (chunks de 64 KB; nunca o body inteiro em RAM). */
    private fun fetchToPart(key: String, name: String, url: String): File? {
        if (url.isBlank()) return null
        val call = cdnClient.newCall(Request.Builder().url(url).build())
        activeCalls[key] = call
        try {
            return call.execute().use { r ->
                if (!r.isSuccessful) return@use null
                val tmp = File(store.dirFor(key), "$name.part")
                try {
                    val stream = r.body?.byteStream()
                    if (stream == null) { tmp.delete(); return@use null }
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
                if (tmp.length() == 0L) { tmp.delete(); null } else tmp
            }
        } finally {
            activeCalls.remove(key, call)
        }
    }

    /** Grava um recurso: stream → .part → rename atómico. true = sucesso. */
    private fun downloadToFile(key: String, name: String, url: String): Boolean {
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
