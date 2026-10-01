package io.pixgo.app.data.player

import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Formato "chunk-v2" (mesmo nome usado no próprio process.yml e no
 * `nonces.json` gerado por ele: `{'format': 'chunk-v2', ...}`):
 *
 *   [4 bytes LE: nChunks]
 *   por chunk: [12 bytes nonce][4 bytes LE: length][body cifrado, length bytes]
 *
 * Confirmado byte a byte contra process.yml (escrita, `encrypt_segment`)
 * E decrypt.worker.ts (leitura, `segPush`) — os dois lados bateram
 * exactamente, nada aqui foi inventado ou aproximado.
 *
 * O guard de nChunks (0 ou >=100000 → vazio) replica literalmente
 * `if (!st.nChunks || st.nChunks >= 100000)` do worker original.
 */
object BinFormat {

    private const val NONCE_SIZE = 12
    private const val MAX_CHUNKS = 100_000

    /**
     * Decifra um ficheiro .bin inteiro (init.bin ou segNNNNN.bin) e
     * devolve os bytes originais (fMP4 — init.mp4 ou o segmento .m4s).
     */
    fun decrypt(encrypted: ByteArray, key: ByteArray): ByteArray {
        if (encrypted.size < 4) return ByteArray(0)
        val buf = ByteBuffer.wrap(encrypted).order(ByteOrder.LITTLE_ENDIAN)
        val nChunks = buf.int
        if (nChunks <= 0 || nChunks >= MAX_CHUNKS) return ByteArray(0)

        val out = ByteArrayOutputStream(encrypted.size)
        repeat(nChunks) {
            if (buf.remaining() < NONCE_SIZE + 4) return@repeat
            val nonce = ByteArray(NONCE_SIZE)
            buf.get(nonce)
            val len = buf.int
            if (len < 0 || len > buf.remaining()) return@repeat
            val cipherChunk = ByteArray(len)
            buf.get(cipherChunk)
            val plain = ChaCha20.process(cipherChunk, key, nonce, counter = 1)
            out.write(plain)
        }
        return out.toByteArray()
    }

    fun keyFromHex(hex: String): ByteArray {
        require(hex.length == 64) { "drm_key_hex tem de ter 64 caracteres hex (32 bytes), tinha ${hex.length}" }
        val out = ByteArray(32)
        for (i in 0 until 32) {
            out[i] = ((Character.digit(hex[i * 2], 16) shl 4) + Character.digit(hex[i * 2 + 1], 16)).toByte()
        }
        return out
    }
}
