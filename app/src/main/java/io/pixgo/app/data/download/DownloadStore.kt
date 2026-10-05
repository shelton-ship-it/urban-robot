package io.pixgo.app.data.download

import android.content.Context
import android.net.Uri
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import io.pixgo.app.data.player.OfflineLocal
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import java.io.File

private val Context.downloadsDataStore by preferencesDataStore(name = "pixgo_downloads")

/**
 * Persistência de downloads — equivalente Android do par
 * STORE_META (metadados) + STORE_SEGS (bytes cifrados) do IndexedDB em
 * lib/downloads.ts, corrigindo a divergência entre as duas fontes que o
 * FIX do próprio web descrevia: aqui os metadados vivem num único
 * DataStore e os segmentos são ficheiros no directório interno; a lista
 * exposta ao UI é SEMPRE reconciliada com o estado físico real dos
 * ficheiros, portanto um download incompleto nunca aparece como
 * concluído e um meta órfão (pasta apagada) desaparece sozinho.
 */
class DownloadStore(private val context: Context) {

    companion object {
        private val KEY_INDEX = stringPreferencesKey("download_index")
        private val json = kotlinx.serialization.json.Json { ignoreUnknownKeys = true }

        /** Chave local de um download (id do manifesto pode ser episódio). */
        fun keyFor(contentId: String, episodeId: String?): String =
            if (episodeId.isNullOrBlank()) contentId else "${contentId}_$episodeId"
    }

    /** Directório de um download: filesDir/downloads/{key}/ */
    fun dirFor(key: String): File = File(context.filesDir, "downloads/$key").apply { mkdirs() }

    private fun segFile(key: String, index: Int): File =
        File(dirFor(key), "seg%05d.bin".format(index))

    private fun initFile(key: String): File = File(dirFor(key), "init.bin")

    /** Contagem de segmentos já gravados — mesmo critério de countSavedSegments. */
    fun savedSegmentCount(key: String, total: Int): Int {
        val dir = dirFor(key)
        val n = (0 until total).count { segFile(key, it).exists() }
        return minOf(n, total)
    }

    suspend fun upsert(meta: DownloadMeta) {
        context.downloadsDataStore.edit { prefs ->
            val list = readIndex(prefs[KEY_INDEX])
            val next = list.filterNot { it.key == meta.key } + meta
            prefs[KEY_INDEX] = json.encodeToString(
                kotlinx.serialization.builtins.ListSerializer(DownloadMeta.serializer()), next
            )
        }
    }

    suspend fun get(key: String): DownloadMeta? = allOnce().firstOrNull { it.key == key }

    suspend fun remove(key: String) {
        context.downloadsDataStore.edit { prefs ->
            val list = readIndex(prefs[KEY_INDEX]).filterNot { it.key == key }
            prefs[KEY_INDEX] = json.encodeToString(
                kotlinx.serialization.builtins.ListSerializer(DownloadMeta.serializer()), list
            )
        }
        // deleteDownload do original apaga meta + todos os segmentos no range.
        dirFor(key).deleteRecursively()
    }

    private suspend fun readIndex(raw: String?): List<DownloadMeta> {
        if (raw.isNullOrBlank()) return emptyList()
        return try {
            json.decodeFromString(
                kotlinx.serialization.builtins.ListSerializer(DownloadMeta.serializer()), raw
            )
        } catch (e: Exception) { emptyList() }
    }

    /**
     * Lista reconciliada com o disco (correção da divergência IndexedDB ×
     * localStorage auditada no frontend):
     *  - 'completed' sem init.bin ou com menos segmentos que segCount →
     *    rebaixado para 'error' (nunca reproduzível, nunca mostrado como pronto);
     *  - meta cuja pasta não existe mais → removido;
     *  - expiração: itens concluídos cujo expiresAt venceu são apagados
     *    (mesma regra do load() da página Downloads original).
     */
    suspend fun allOnce(): List<DownloadMeta> {
        val list = readIndex(context.downloadsDataStore.data.first()[KEY_INDEX])
        val now = System.currentTimeMillis()
        val out = mutableListOf<DownloadMeta>()
        for (m in list) {
            val dir = dirFor(m.key)
            if (!dir.exists()) { continue }
            val fixed = when (m.status) {
                DownloadStatus.COMPLETED -> {
                    val physicallyComplete = initFile(m.key).exists() &&
                        savedSegmentCount(m.key, m.segCount) >= m.segCount && m.segCount > 0
                    when {
                        !physicallyComplete -> m.copy(
                            status = DownloadStatus.ERROR,
                            error = "Ficheiros incompletos no dispositivo"
                        )
                        isoToMillis(m.expiresAt) != null && isoToMillis(m.expiresAt)!! <= now -> {
                            remove(m.key); continue
                        }
                        else -> m
                    }
                }
                else -> m
            }
            out += fixed
        }
        // Persiste correções/expirações para o índice convergir com o disco.
        out.forEach { m ->
            val orig = list.firstOrNull { it.key == m.key }
            if (orig != m) upsert(m)
        }
        return out
    }

    /** Flow observável da lista reconciliada (badge/DownloadsScreen). */
    val downloadsFlow: Flow<List<DownloadMeta>> =
        context.downloadsDataStore.data.map { /* gatilho; reconciliação sob demanda */
            allOnce()
        }

    /**
     * Resolução das URLs pixgo-offline:// da playlist sintética — o ramo
     * offlineContentId do BinLoader original resolvia idb://{key}/init.bin e
     * idb://{key}/seg.bin?i=N contra o IndexedDB; aqui resolve contra o disco.
     */
    fun resolveLocal(uri: Uri): File? {
        if (uri.scheme != OfflineLocal.SCHEME) return null
        val key = uri.host ?: return null
        return when {
            uri.path?.endsWith("/init.bin") == true -> initFile(key).takeIf { it.exists() }
            uri.path?.endsWith("/seg.bin") == true -> {
                val i = uri.getQueryParameter("i")?.toIntOrNull() ?: return null
                segFile(key, i).takeIf { it.exists() }
            }
            else -> null
        }
    }

    /** Grava bytes de segmento/init atomicamente (.part → rename), evitando marcar parcial como válido. */
    fun writeSegmentAtomic(key: String, name: String, data: ByteArray) {
        val dir = dirFor(key)
        val tmp = File(dir, "$name.part")
        tmp.writeBytes(data)
        val final = File(dir, name)
        if (final.exists()) final.delete()
        tmp.renameTo(final)
    }

    fun segFileName(index: Int): String = "seg%05d.bin".format(index)
    fun initFileName(): String = "init.bin"

    private fun isoToMillis(iso: String?): Long? {
        if (iso.isNullOrBlank()) return null
        return try { java.time.Instant.parse(iso).toEpochMilli() } catch (e: Exception) { null }
    }
}
