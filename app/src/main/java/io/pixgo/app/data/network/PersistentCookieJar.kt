package io.pixgo.app.data.network

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import okhttp3.Cookie
import okhttp3.CookieJar
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import java.util.concurrent.ConcurrentHashMap

private val Context.cookieDataStore by preferencesDataStore(name = "pixgo_cookies")
private val COOKIES_KEY = stringPreferencesKey("cookies_v1")

/**
 * CookieJar único, partilhado por TODOS os pedidos (api.pixgo.qzz.io e
 * pixel.pixgo.qzz.io), replicando o que o browser faz com um cookie
 * `Domain=.pixgo.qzz.io` (pixgo_session — ver session-cookie.js/auth.js
 * dos dois backends): um único cookie válido para ambos os hosts.
 *
 * Persiste em DataStore para sobreviver ao fecho da app (o cookie real
 * dura 365 dias no servidor).
 *
 * Só implementa o necessário — sem expiração local (o backend já expira
 * o cookie/token do lado dele); cookies expirados simplesmente deixam de
 * ser devolvidos por Cookie.expiresAt via okhttp3.Cookie internamente
 * quando persistem cookies expirados, então filtramos por expiresAt aqui.
 */
class PersistentCookieJar(private val context: Context) : CookieJar {

    // chave: "${domain}|${name}" -> Cookie
    private val store = ConcurrentHashMap<String, Cookie>()
    @Volatile private var loaded = false

    private fun key(cookie: Cookie) = "${cookie.domain}|${cookie.name}"

    private fun ensureLoaded() {
        if (loaded) return
        synchronized(this) {
            if (loaded) return
            val raw = runBlocking { context.cookieDataStore.data.first()[COOKIES_KEY] }
            if (!raw.isNullOrBlank()) {
                raw.split("\n").forEach { line ->
                    // formato: url<TAB>setCookieHeader
                    val parts = line.split("\t", limit = 2)
                    if (parts.size == 2) {
                        val url = parts[0].toHttpUrlOrNull() ?: return@forEach
                        Cookie.parse(url, parts[1])?.let { store[key(it)] = it }
                    }
                }
            }
            loaded = true
        }
    }

    private fun persist() {
        val lines = store.values.joinToString("\n") { c ->
            // Reconstrói um Set-Cookie mínimo o suficiente para Cookie.parse reler.
            val scheme = if (c.secure) "https" else "http"
            val fakeUrl = "$scheme://${c.domain.trimStart('.')}/"
            "$fakeUrl\t${c.toString()}"
        }
        runBlocking {
            context.cookieDataStore.edit { it[COOKIES_KEY] = lines }
        }
    }

    override fun saveFromResponse(url: HttpUrl, cookies: List<Cookie>) {
        ensureLoaded()
        var changed = false
        for (cookie in cookies) {
            if (cookie.expiresAt <= System.currentTimeMillis() && cookie.persistent) {
                store.remove(key(cookie))
            } else {
                store[key(cookie)] = cookie
            }
            changed = true
        }
        if (changed) persist()
    }

    override fun loadForRequest(url: HttpUrl): List<Cookie> {
        ensureLoaded()
        val now = System.currentTimeMillis()
        return store.values.filter { it.matches(url) && (!it.persistent || it.expiresAt > now) }
    }

    fun clear() {
        store.clear()
        persist()
    }

    /** Exporta o cookie de sessão partilhado para sincronizar com o android.webkit.CookieManager (ver WebViewCookieSync). */
    fun exportAll(): List<Cookie> {
        ensureLoaded()
        return store.values.toList()
    }

    /**
     * Caminho INVERSO do WebViewCookieSync: importa um cookie gravado pelo
     * android.webkit.CookieManager (ex.: pixgo_session definido pelo hub
     * durante o login na HubLoginSheet) para este jar OkHttp, usando o
     * MESMO mecanismo de persistência já existente (Cookie.parse +
     * saveFromResponse -> DataStore). Não cria sistema paralelo.
     */
    fun importCookie(context: Context, url: String, setCookieHeader: String) {
        val httpUrl = url.toHttpUrlOrNull() ?: return
        val cookie = Cookie.parse(httpUrl, setCookieHeader) ?: return
        saveFromResponse(httpUrl, listOf(cookie))
    }
}
