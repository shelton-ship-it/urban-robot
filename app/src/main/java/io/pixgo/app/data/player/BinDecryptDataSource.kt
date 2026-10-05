package io.pixgo.app.data.player

import android.net.Uri
import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.TransferListener
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.IOException

/**
 * Equivalente Android do BinLoader do hls.js original (ShakaPlayer.tsx) +
 * decrypt.worker.ts: intercepta qualquer pedido cujo URL termine em
 * ".bin", faz o fetch e decifra ChaCha20 chunk-v2 (ver BinFormat); tudo
 * o resto (master.m3u8, index.m3u8) passa como um GET normal, sem
 * decifra — são texto simples, nunca foram encriptados.
 *
 * Ao contrário do original (que decifra progressivamente, chunk a
 * chunk, streamado directo da rede), aqui descarrega o recurso inteiro
 * e decifra de uma vez antes de servir — cada .bin é um segmento de
 * poucos segundos de vídeo (SEG_DURATION do pipeline, tipicamente 4s),
 * portanto poucos MB; a simplicidade de "buffer inteiro em memória" é
 * mais segura do que replicar o parsing incremental por chunks aqui,
 * sem mudar o resultado final (os mesmos bytes decifrados, na mesma
 * ordem).
 */
@UnstableApi
class BinDecryptDataSource(
    private val httpClient: OkHttpClient,
    private val keyProvider: () -> ByteArray?,
    // Resolução opcional para URLs "pixgo-offline://" dos downloads locais
    // (equivalente Android do ramo offlineContentId do BinLoader original,
    // que lia os bytes cifrados do IndexedDB em vez da rede). Default nulo
    // = comportamento remoto exatamente como antes.
    private val localResolver: ((Uri) -> File?)? = null
) : DataSource {

    private var uri: Uri? = null
    private var buffer: ByteArray = ByteArray(0)
    private var readPosition = 0
    private var bytesRemaining = 0L

    override fun addTransferListener(transferListener: TransferListener) {}

    override fun open(dataSpec: DataSpec): Long {
        uri = dataSpec.uri
        val url = dataSpec.uri.toString()

        val raw = when {
            // Ramo offline local: mesma origem de bytes diferente, resto do
            // pipeline (decifra chunk-v2 abaixo) é idêntico ao remoto — tal
            // como o comentário do FIX no loader original descreve.
            dataSpec.uri.scheme == OfflineLocal.SCHEME -> {
                val file = localResolver?.invoke(dataSpec.uri)
                    ?: throw IOException("Segmento offline não encontrado para $url")
                if (!file.exists()) throw IOException("Segmento offline ausente: $url")
                file.readBytes()
            }
            else -> try {
                httpClient.newCall(Request.Builder().url(url).build()).execute().use { resp ->
                    if (!resp.isSuccessful) {
                        throw IOException("HTTP ${resp.code} a buscar $url")
                    }
                    resp.body?.bytes() ?: ByteArray(0)
                }
            } catch (e: IOException) {
                throw e
            }
        }

        val payload = if (url.endsWith(".bin")) {
            val key = keyProvider() ?: throw IOException(
                "Chave de decifra indisponível para $url"
            )
            BinFormat.decrypt(raw, key)
        } else {
            raw
        }

        val start = dataSpec.position.coerceIn(0, payload.size.toLong()).toInt()
        buffer = payload
        readPosition = start
        bytesRemaining = if (dataSpec.length != C.LENGTH_UNSET.toLong()) {
            minOf(dataSpec.length, (payload.size - start).toLong())
        } else {
            (payload.size - start).toLong()
        }
        return bytesRemaining
    }

    override fun read(target: ByteArray, offset: Int, length: Int): Int {
        if (bytesRemaining == 0L) return C.RESULT_END_OF_INPUT
        val toRead = minOf(length.toLong(), bytesRemaining).toInt()
        System.arraycopy(buffer, readPosition, target, offset, toRead)
        readPosition += toRead
        bytesRemaining -= toRead
        return toRead
    }

    override fun getUri(): Uri? = uri

    override fun getResponseHeaders(): Map<String, List<String>> = emptyMap()

    override fun close() {
        buffer = ByteArray(0)
        readPosition = 0
        bytesRemaining = 0
        uri = null
    }

    @UnstableApi
    class Factory(
        private val httpClient: OkHttpClient,
        private val keyProvider: () -> ByteArray?,
        private val localResolver: ((Uri) -> File?)? = null
    ) : DataSource.Factory {
        override fun createDataSource(): DataSource =
            BinDecryptDataSource(httpClient, keyProvider, localResolver)
    }
}

/**
 * Esquema de URL usado pela playlist HLS sintética offline (equivalente do
 * "idb://" original em ShakaPlayer.tsx buildOfflinePlaylist). Os URLs têm a
 * forma pixgo-offline://{downloadKey}/init.bin e
 * pixgo-offline://{downloadKey}/seg.bin?i=N; quem conhece o mapa
 * downloadKey→pasta (DownloadStore) resolve para o File cifrado no disco.
 */
object OfflineLocal {
    const val SCHEME = "pixgo-offline"
}
