package io.pixgo.app.data.player

import android.util.Base64
import java.security.KeyPairGenerator
import java.security.interfaces.ECPublicKey
import java.security.spec.ECGenParameterSpec

/**
 * Gera um par de chaves EC P-256 e exporta a pública em formato raw
 * (não-comprimido), o mesmo que `crypto.subtle.exportKey('raw', ...)`
 * produz no browser: 0x04 || X(32 bytes) || Y(32 bytes) = 65 bytes.
 *
 * Confirmado no backend (routes/content.js, endpoint /stream): o
 * `clientPubKey` só precisa de ser um base64 não-vazio que
 * `app.serverECDH()` consiga processar — a chave ChaCha20 devolvida
 * (`drm_key_hex`) é sempre a chave global do servidor em texto simples,
 * NUNCA derivada deste handshake (`server_pub` é calculado e devolvido,
 * mas o cliente original nunca o usa para nada). Por isso aqui só
 * precisamos de gerar uma chave EC válida — não de completar
 * derivação ECDH nenhuma.
 */
object EcdhKeyExchange {
    fun generateClientPubKeyBase64(): String {
        val kpg = KeyPairGenerator.getInstance("EC")
        kpg.initialize(ECGenParameterSpec("secp256r1"))
        val keyPair = kpg.generateKeyPair()
        val pub = keyPair.public as ECPublicKey

        val fieldSize = 32
        val x = unsignedBytes(pub.w.affineX.toByteArray(), fieldSize)
        val y = unsignedBytes(pub.w.affineY.toByteArray(), fieldSize)

        val raw = ByteArray(1 + fieldSize * 2)
        raw[0] = 0x04
        System.arraycopy(x, 0, raw, 1, fieldSize)
        System.arraycopy(y, 0, raw, 1 + fieldSize, fieldSize)

        return Base64.encodeToString(raw, Base64.NO_WRAP)
    }

    /** BigInteger.toByteArray() pode ter um byte de sinal extra à esquerda, ou faltar padding — normaliza para exactamente [size] bytes big-endian. */
    private fun unsignedBytes(src: ByteArray, size: Int): ByteArray {
        val out = ByteArray(size)
        if (src.size == size) {
            System.arraycopy(src, 0, out, 0, size)
        } else if (src.size > size) {
            // byte de sinal (0x00) a mais à esquerda — descarta-o(s)
            System.arraycopy(src, src.size - size, out, 0, size)
        } else {
            System.arraycopy(src, 0, out, size - src.size, src.size)
        }
        return out
    }
}
