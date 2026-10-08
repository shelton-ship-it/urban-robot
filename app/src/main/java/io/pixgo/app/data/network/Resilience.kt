package io.pixgo.app.data.network

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import retrofit2.Response
import java.io.IOException

/**
 * Falha que NÃO é "sem conteúdo": a rede/servidor não respondeu a tempo ou
 * respondeu com erro (5xx/408/429/4xx definitivo) mesmo depois de muita
 * paciência. A UI mostra "erro + Tentar novamente", NUNCA o estado vazio.
 */
class LoadFailedException(message: String, cause: Throwable? = null) : IOException(message, cause)

/**
 * Paciência centralizada para tudo o que é conteúdo/canais.
 *
 * Porquê: o backend (pixel_service_v1) nunca devolve "200 vazio" quando falha —
 * responde 500/503 (Turso/KV frio, timeout de 1,5 s no KV) e os `fetch()` do web
 * simplesmente esperavam e funcionavam à segunda. No Android uma falha
 * transitória virava `emptyList()` e a UI mostrava "nenhum conteúdo" (falso
 * positivo) até o utilizador recarregar.
 */
object Patience {
    /** Listas principais (catálogo, home, minha lista, conteúdo, canais). */
    const val MAIN_BUDGET_MS = 75_000L
    /** Pesquisa (o utilizador continua a escrever; cada tecla cancela a anterior). */
    const val SEARCH_BUDGET_MS = 40_000L
    /** Extras opcionais (carrossel, continuar a ver, gate de canais). */
    const val OPTIONAL_BUDGET_MS = 25_000L
    /** Handshake do player: fica no spinner, nunca mostra erro antes de ~90 s. */
    const val PLAYER_BUDGET_MS = 90_000L

    private val DELAYS_MS = longArrayOf(400L, 800L, 1_600L, 3_000L, 5_000L)

    fun delayFor(attemptIndex: Int): Long = DELAYS_MS[attemptIndex.coerceIn(0, DELAYS_MS.lastIndex)]

    /** 408/425/429/5xx: o servidor diz "agora não" — volta a tentar. */
    fun isTransient(code: Int, retry429: Boolean = true): Boolean =
        code == 408 || code == 425 || (retry429 && code == 429) || code >= 500
}

/**
 * Executa [call] repetidamente até obter uma resposta definitiva ou esgotar [budgetMs].
 *  - sucesso (2xx) → devolve;
 *  - 4xx definitivo (400/401/403/404…) → devolve para o chamador decidir;
 *  - erro de rede, corpo ilegível, 408/425/429/5xx → espera (backoff) e repete;
 *  - cancelamento → SEMPRE relançado (nunca é "falha").
 * Esgotado o orçamento lança [LoadFailedException] em vez de fingir "vazio".
 */
suspend fun <T> patiently(
    budgetMs: Long = Patience.MAIN_BUDGET_MS,
    // false quando o 429 É a resposta (limite de plano/tempo grátis): não se repete.
    retry429: Boolean = true,
    call: suspend () -> Response<T>,
): Response<T> {
    val deadline = System.currentTimeMillis() + budgetMs
    var attempt = 0
    var last: Throwable? = null
    var lastCode = 0
    while (true) {
        try {
            val resp = call()
            if (resp.isSuccessful || !Patience.isTransient(resp.code(), retry429)) return resp
            lastCode = resp.code()
            last = null
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            last = e
            lastCode = 0
        }
        val wait = Patience.delayFor(attempt++)
        if (System.currentTimeMillis() + wait >= deadline) break
        delay(wait)
    }
    throw LoadFailedException(
        if (lastCode != 0) "HTTP $lastCode após ${attempt} tentativas" else "Sem resposta após ${attempt} tentativas",
        last,
    )
}

/**
 * `runCatching` que NÃO engole o cancelamento das corrotinas. O `runCatching`
 * normal apanha a CancellationException (quando um LaunchedEffect reinicia, p.ex.
 * porque o perfil/idioma acabou de resolver) e o código seguinte corria com
 * "falha" → listas vazias → "nenhum conteúdo" a piscar.
 */
inline fun <T> runCatchingNonCancel(block: () -> T): Result<T> =
    try {
        Result.success(block())
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        Result.failure(e)
    }
