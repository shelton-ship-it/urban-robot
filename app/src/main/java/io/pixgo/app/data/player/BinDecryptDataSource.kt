package io.pixgo.app.data.player

import android.net.Uri
import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.BaseDataSource
import androidx.media3.datasource.DataSchemeDataSource
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import okhttp3.Call
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.io.EOFException
import java.io.File
import java.io.FileInputStream
import java.io.IOException
import java.io.InputStream

/**
 * Equivalente Android do BinLoader do hls.js original (ShakaPlayer.tsx) +
 * decrypt.worker.ts: intercepta qualquer pedido cujo caminho termine em
 * ".bin", faz o fetch e decifra ChaCha20 chunk-v2 (ver BinFormat); tudo o
 * resto (master.m3u8, index.m3u8) passa como um GET normal, sem decifra.
 *
 * REESCRITO para o desempenho do frontend (hls.js `progressive: true`):
 *
 *  1. DECIFRA EM STREAMING — antes descarregava o segmento INTEIRO, depois
 *     decifrava tudo e só então entregava o 1.º byte ao player. Agora cada chunk
 *     [nonce|len|corpo] é decifrado assim que chega e entregue de imediato, tal
 *     como o worker original. Reduz a latência até ao 1.º frame e o pico de
 *     memória (não há ByteArray com o segmento todo + cópia decifrada).
 *
 *  2. MEDIDOR DE BANDA — `addTransferListener` era um no-op, por isso o
 *     DefaultBandwidthMeter do ExoPlayer NUNCA recebia amostras e o ABR ficava
 *     cego (não descia de qualidade quando a rede piorava → buffering e
 *     "encravar" em redes lentas). Agora (BaseDataSource) cada byte de rede é
 *     reportado, o que também alimenta a estimativa inicial/contínua.
 *
 *  3. BUG DO OFFLINE — o teste `url.endsWith(".bin")` falhava em
 *     "pixgo-offline://k/seg.bin?i=3" (a query estragava o sufixo) e os
 *     segmentos locais ficavam SEM decifrar. Agora usa `uri.path`.
 *
 *  4. Suporta o esquema `data:` (playlist sintética offline) — antes o OkHttp
 *     rebentava com IllegalArgumentException.
 *
 * Um segmento truncado (rede caiu a meio) lança IOException → o ExoPlayer faz
 * retry com a política de erros, em vez de entregar vídeo corrompido em silêncio.
 */
@UnstableApi
class BinDecryptDataSource(
    private val httpClient: OkHttpClient,
    private val keyProvider: () -> ByteArray?,
    // Resolução opcional para URLs "pixgo-offline://" dos downloads locais
    // (equivalente Android do ramo offlineContentId do BinLoader original).
    private val localResolver: ((Uri) -> File?)? = null,
) : BaseDataSource(/* isNetwork = */ true) {

    private var uri: Uri? = null
    private var call: Call? = null
    private var response: Response? = null
    private var input: InputStream? = null
    private var dataDelegate: DataSchemeDataSource? = null

    private var network = false
    private var transferOpen = false
    private var decrypt = false
    private var key: ByteArray? = null

    // Estado do parser chunk-v2.
    private var chunksLeft = -1
    private val chunkHdr = ByteArray(CHUNK_HDR)
    private var cipher = ByteArray(0)
    private var plainPos = 0
    private var plainLimit = 0

    private var bytesRemaining = C.LENGTH_UNSET.toLong()

    override fun open(dataSpec: DataSpec): Long {
        uri = dataSpec.uri
        val scheme = dataSpec.uri.scheme

        // data: URI (playlist offline sintética)
        if (scheme == "data") {
            val d = DataSchemeDataSource()
            dataDelegate = d
            return d.open(dataSpec)
        }

        network = scheme == "http" || scheme == "https"
        if (network) transferInitializing(dataSpec)

        decrypt = dataSpec.uri.path?.endsWith(".bin") == true
        key = if (decrypt) {
            keyProvider() ?: throw IOException("Chave de decifra indisponível para ${dataSpec.uri}")
        } else null

        input = if (scheme == OfflineLocal.SCHEME) {
            val file = localResolver?.invoke(dataSpec.uri)
                ?: throw IOException("Segmento offline não encontrado para ${dataSpec.uri}")
            if (!file.exists()) throw IOException("Segmento offline ausente: ${dataSpec.uri}")
            FileInputStream(file).buffered(64 * 1024)
        } else {
            val rb = Request.Builder().url(dataSpec.uri.toString())
            // Binário cifrado: sem gzip transparente (não comprime e falsearia o
            // débito medido). Playlists de texto continuam a poder vir comprimidas.
            if (decrypt) rb.header("Accept-Encoding", "identity")
            val c = httpClient.newCall(rb.build())
            call = c
            val resp = c.execute()
            response = resp
            if (!resp.isSuccessful) {
                val code = resp.code
                closeQuietly()
                throw IOException("HTTP $code a buscar ${dataSpec.uri}")
            }
            (resp.body ?: run { closeQuietly(); throw IOException("Resposta vazia de ${dataSpec.uri}") }).byteStream()
        }

        if (network) { transferOpen = true; transferStarted(dataSpec) }

        chunksLeft = -1
        plainPos = 0
        plainLimit = 0

        // Posição pedida refere-se aos bytes JÁ decifrados — descarta até lá.
        var toSkip = dataSpec.position
        if (toSkip > 0) {
            val scratch = ByteArray(8 * 1024)
            while (toSkip > 0) {
                val n = readPayload(scratch, 0, minOf(scratch.size.toLong(), toSkip).toInt())
                if (n == C.RESULT_END_OF_INPUT) break
                toSkip -= n
            }
        }

        bytesRemaining = dataSpec.length
        return bytesRemaining
    }

    override fun read(target: ByteArray, offset: Int, length: Int): Int {
        dataDelegate?.let { return it.read(target, offset, length) }
        if (length == 0) return 0
        if (bytesRemaining == 0L) return C.RESULT_END_OF_INPUT

        val want = if (bytesRemaining == C.LENGTH_UNSET.toLong()) length
        else minOf(length.toLong(), bytesRemaining).toInt()

        val n = readPayload(target, offset, want)
        if (n == C.RESULT_END_OF_INPUT) {
            if (bytesRemaining != C.LENGTH_UNSET.toLong() && bytesRemaining != 0L) throw EOFException()
            return C.RESULT_END_OF_INPUT
        }
        if (bytesRemaining != C.LENGTH_UNSET.toLong()) bytesRemaining -= n
        return n
    }

    /** Bytes de PAYLOAD (já decifrados quando é .bin) — nunca devolve 0 para length > 0. */
    private fun readPayload(target: ByteArray, offset: Int, length: Int): Int {
        if (!decrypt) return readNet(target, offset, length)

        while (plainPos >= plainLimit) {
            if (!nextChunk()) return C.RESULT_END_OF_INPUT
        }
        val n = minOf(length, plainLimit - plainPos)
        System.arraycopy(cipher, plainPos, target, offset, n)
        plainPos += n
        return n
    }

    /** Lê e decifra o próximo chunk [nonce(12)|len(4 LE)|corpo]. false = fim do segmento. */
    private fun nextChunk(): Boolean {
        if (chunksLeft < 0) {
            val hdr = ByteArray(4)
            if (!readFully(hdr, 0, 4)) { chunksLeft = 0; return false }
            val n = leInt(hdr, 0)
            // Mesmo guard do worker original: 0 ou >= 100000 → vazio.
            chunksLeft = if (n <= 0 || n >= MAX_CHUNKS) 0 else n
        }
        if (chunksLeft == 0) return false

        if (!readFully(chunkHdr, 0, CHUNK_HDR)) throw EOFException("Segmento truncado (cabeçalho de chunk)")
        val len = leInt(chunkHdr, NONCE)
        if (len < 0 || len > MAX_CHUNK_BYTES) throw IOException("Chunk inválido ($len bytes)")
        if (cipher.size < len) cipher = ByteArray(maxOf(len, cipher.size * 2))
        if (!readFully(cipher, 0, len)) throw EOFException("Segmento truncado (corpo de chunk)")

        val nonce = chunkHdr.copyOfRange(0, NONCE)
        ChaCha20.processInPlace(cipher, 0, len, key!!, nonce, 1)
        plainPos = 0
        plainLimit = len
        chunksLeft--
        return true
    }

    private fun readFully(buf: ByteArray, off: Int, len: Int): Boolean {
        var got = 0
        while (got < len) {
            val n = readNet(buf, off + got, len - got)
            if (n == C.RESULT_END_OF_INPUT) return if (got == 0) false else throw EOFException()
            got += n
        }
        return true
    }

    private fun readNet(buf: ByteArray, off: Int, len: Int): Int {
        val ins = input ?: return C.RESULT_END_OF_INPUT
        val n = ins.read(buf, off, len)
        if (n < 0) return C.RESULT_END_OF_INPUT
        if (network && n > 0) bytesTransferred(n)
        return n
    }

    private fun leInt(b: ByteArray, o: Int): Int =
        (b[o].toInt() and 0xFF) or ((b[o + 1].toInt() and 0xFF) shl 8) or
            ((b[o + 2].toInt() and 0xFF) shl 16) or ((b[o + 3].toInt() and 0xFF) shl 24)

    override fun getUri(): Uri? = dataDelegate?.uri ?: uri

    override fun getResponseHeaders(): Map<String, List<String>> =
        response?.headers?.toMultimap() ?: emptyMap()

    private fun closeQuietly() {
        runCatching { input?.close() }
        runCatching { response?.close() }
        runCatching { call?.cancel() }
        input = null
        response = null
        call = null
    }

    override fun close() {
        try {
            dataDelegate?.close()
            closeQuietly()
        } finally {
            dataDelegate = null
            uri = null
            key = null
            plainPos = 0
            plainLimit = 0
            chunksLeft = -1
            bytesRemaining = C.LENGTH_UNSET.toLong()
            if (transferOpen) {
                transferOpen = false
                transferEnded()
            }
        }
    }

    @UnstableApi
    class Factory(
        private val httpClient: OkHttpClient,
        private val keyProvider: () -> ByteArray?,
        private val localResolver: ((Uri) -> File?)? = null,
    ) : DataSource.Factory {
        override fun createDataSource(): DataSource =
            BinDecryptDataSource(httpClient, keyProvider, localResolver)
    }

    private companion object {
        const val NONCE = 12
        const val CHUNK_HDR = NONCE + 4
        const val MAX_CHUNKS = 100_000
        const val MAX_CHUNK_BYTES = 64 * 1024 * 1024
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
