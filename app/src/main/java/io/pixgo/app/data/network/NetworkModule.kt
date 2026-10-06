package io.pixgo.app.data.network

import android.content.Context
import io.pixgo.app.data.auth.TokenManager
import kotlinx.coroutines.runBlocking
import java.io.File
import kotlinx.serialization.json.Json
import okhttp3.Cache
import okhttp3.ConnectionPool
import java.util.concurrent.TimeUnit
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import retrofit2.Retrofit
import com.jakewharton.retrofit2.converter.kotlinx.serialization.asConverterFactory
import okhttp3.MediaType.Companion.toMediaType

/**
 * Hosts confirmados no código (não inventados):
 *  - API_CORE: pixel.pixgo.qzz.io — hub (login/registo/Google/TV/planos/
 *    pagamentos), ver env NEXT_PUBLIC_API_URL fornecida para o hub.
 *  - PIXEL_SERVICE: api.pixgo.qzz.io — fallback hardcoded usado em todo o
 *    pixel/frontend_web (catálogo, conteúdo, stream, progresso, mylist,
 *    canais, e a própria sessão do dia a dia: me/refresh/logout/perfis).
 */
object Hosts {
    const val API_CORE = "https://pixel.pixgo.qzz.io"
    const val PIXEL_SERVICE = "https://api.pixgo.qzz.io"
    /** Fallback literal do próprio código (UPLOAD_BASE em lib/api.ts) — não inventado. */
    const val COPYRIGHT = "https://copyright.pixgo.qzz.io"
}

/**
 * TIMEOUTS CENTRALIZADOS — alinhados com o frontend_web.
 *
 * No web, NENHUMA chamada de API/página tem timeout próprio: authedFetch/fetch()
 * esperam pelo que o navegador esperar (não há AbortSignal em lib/api.ts,
 * store/auth.ts, legal, downloads, channels-source…). Os ÚNICOS timeouts do
 * frontend são: player hls.js/BinLoader 20 s TOTAIS (frag/manifest/level),
 * VAST 4 s, anúncio 30 s e sonda de adblock 2,5 s.
 *
 * O OkHttp, por defeito, corta aos 10 s de silêncio (connect/read/write) — em
 * rede lenta ou com servidor "frio" isso dava erro/spinner/lista vazia em
 * pedidos que no web simplesmente demoravam e funcionavam (falsos positivos).
 * Por isso, para tudo o que NÃO é player:
 *  - connect 30 s (3G/2G com DNS+TLS lentos);
 *  - read/write 60 s de INATIVIDADE (mede silêncio entre bytes — um download
 *    ou JSON grande a andar devagar NÃO é cortado);
 *  - SEM timeout total (callTimeout = 0), tal como o fetch() do navegador.
 * O player continua com 20 s TOTAIS (ver [playerHttp]), igual ao web.
 */
object NetTimeouts {
    const val CONNECT_S = 30L
    const val READ_S = 60L
    const val WRITE_S = 60L

    /** Player: igual ao hls.js (frag/manifest/levelLoadingTimeOut e BinLoader). */
    const val PLAYER_TOTAL_S = 20L
    const val PLAYER_CONNECT_S = 15L
}

/** Aplica os timeouts "estilo fetch do navegador" (generosos, sem total). */
fun OkHttpClient.Builder.webLikeTimeouts(): OkHttpClient.Builder = this
    .connectTimeout(NetTimeouts.CONNECT_S, TimeUnit.SECONDS)
    .readTimeout(NetTimeouts.READ_S, TimeUnit.SECONDS)
    .writeTimeout(NetTimeouts.WRITE_S, TimeUnit.SECONDS)
    .callTimeout(0, TimeUnit.MILLISECONDS)
    .retryOnConnectionFailure(true)

object NetworkModule {

    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
        explicitNulls = false
    }

    @Volatile private var cookieJar: PersistentCookieJar? = null
    @Volatile private var okHttpClient: OkHttpClient? = null

    /**
     * Importa cookies gravados pelo android.webkit.CookieManager (ex.: o
     * pixgo_session + token definidos pelo hub durante o login na
     * HubLoginSheet) para o jar OkHttp partilhado — caminho inverso do
     * WebViewCookieSync, usando a mesma persistência já existente.
     */
    fun importCookiesFromWebView(context: Context, url: String) {
        val jar = cookieJar ?: PersistentCookieJar(context.applicationContext).also { cookieJar = it }
        val raw = android.webkit.CookieManager.getInstance().getCookie(url) ?: return
        raw.split(";").map { it.trim() }.filter { it.contains("=") }.forEach { pair ->
            jar.importCookie(context, url, pair)
        }
    }

    private fun authInterceptor(tokenManager: TokenManager): Interceptor = Interceptor { chain ->
        val token = runBlocking { tokenManager.getToken() }
        val request = if (token != null) {
            chain.request().newBuilder()
                .header("Authorization", "Bearer $token")
                .build()
        } else {
            chain.request()
        }
        chain.proceed(request)
    }

    /**
     * Um único OkHttpClient (e portanto um único CookieJar) para TODOS os
     * pedidos aos dois hosts — equivalente a `credentials: 'include'` no
     * browser com um cookie Domain=.pixgo.qzz.io partilhado.
     */
    fun okHttp(context: Context, tokenManager: TokenManager): OkHttpClient {
        okHttpClient?.let { return it }
        synchronized(this) {
            okHttpClient?.let { return it }
            val jar = cookieJar ?: PersistentCookieJar(context.applicationContext).also { cookieJar = it }
            val logging = HttpLoggingInterceptor().apply { level = HttpLoggingInterceptor.Level.BASIC }
            // Cache HTTP em disco (20 MB): o navegador do frontend_web já honra
            // o Cache-Control do pixel_service (catálogo: public, max-age=60;
            // legal: max-age=3600). Sem isto cada visita a um separador
            // repetia o pedido ao servidor. Rotas com no-store (me, content,
            // progress, payments, channels...) continuam a não ser guardadas.
            val httpCache = Cache(File(context.applicationContext.cacheDir, "http_cache"), 20L * 1024 * 1024)
            val client = OkHttpClient.Builder()
                .cache(httpCache)
                .webLikeTimeouts()
                .cookieJar(jar)
                .addInterceptor(authInterceptor(tokenManager))
                .addInterceptor(logging)
                .build()
            okHttpClient = client
            return client
        }
    }

    fun clearCookies() {
        cookieJar?.clear()
    }

    fun exportCookiesForWebView(): List<okhttp3.Cookie> = cookieJar?.exportAll() ?: emptyList()

    @Volatile private var contactClient: OkHttpClient? = null

    /**
     * Cliente ISOLADO para copyright.pixgo.qzz.io — de propósito sem o
     * CookieJar partilhado (uploadReq() no original usa fetch() simples,
     * sem credentials:'include'; só manda Authorization Bearer).
     */
    private fun contactHttp(tokenManager: TokenManager): OkHttpClient {
        contactClient?.let { return it }
        synchronized(this) {
            contactClient?.let { return it }
            val client = OkHttpClient.Builder()
                .webLikeTimeouts()
                .addInterceptor(authInterceptor(tokenManager))
                .build()
            contactClient = client
            return client
        }
    }

    fun contact(tokenManager: TokenManager): ContactApi =
        retrofit(Hosts.COPYRIGHT, contactHttp(tokenManager)).create(ContactApi::class.java)

    /** uploadApi do original (lib/api.ts) — mesmo Worker, mesmo cliente isolado. */
    fun upload(tokenManager: TokenManager): UploadApi =
        retrofit(Hosts.COPYRIGHT, contactHttp(tokenManager)).create(UploadApi::class.java)

    /** copyrightApi do original (lib/api.ts) — endpoints públicos, sem Authorization nem cookies. */
    fun copyright(): CopyrightApi =
        retrofit(Hosts.COPYRIGHT, plainHttpClient()).create(CopyrightApi::class.java)

    private fun retrofit(baseUrl: String, client: OkHttpClient): Retrofit {
        val contentType = "application/json".toMediaType()
        return Retrofit.Builder()
            .baseUrl(baseUrl)
            .client(client)
            .addConverterFactory(json.asConverterFactory(contentType))
            .build()
    }

    fun apiCoreAuth(context: Context, tokenManager: TokenManager): ApiCoreAuthApi =
        retrofit(Hosts.API_CORE, okHttp(context, tokenManager)).create(ApiCoreAuthApi::class.java)

    fun pixelServiceAuth(context: Context, tokenManager: TokenManager): PixelServiceAuthApi =
        retrofit(Hosts.PIXEL_SERVICE, okHttp(context, tokenManager)).create(PixelServiceAuthApi::class.java)

    fun catalog(context: Context, tokenManager: TokenManager): CatalogApi =
        retrofit(Hosts.PIXEL_SERVICE, okHttp(context, tokenManager)).create(CatalogApi::class.java)

    fun channelsGate(context: Context, tokenManager: TokenManager): ChannelsGateApi =
        retrofit(Hosts.PIXEL_SERVICE, okHttp(context, tokenManager)).create(ChannelsGateApi::class.java)

    fun stream(context: Context, tokenManager: TokenManager): StreamApi =
        retrofit(Hosts.PIXEL_SERVICE, okHttp(context, tokenManager)).create(StreamApi::class.java)

    @Volatile private var playerClient: OkHttpClient? = null

    /**
     * Cliente do PLAYER (segmentos .bin/.m3u8 do CDN). Equivalente às opções de
     * rede do hls.js do frontend (fragLoadingTimeOut/manifestLoadingTimeOut 20 s,
     * fragLoadingMaxRetry 4):
     *  - timeout TOTAL de 20 s por pedido (callTimeout) + 20 s de inatividade;
     *  - retryOnConnectionFailure + pool de ligações HTTP/2 reutilizadas (menos
     *    handshakes TLS entre segmentos consecutivos);
     *  - cache em disco de 64 MB: os segmentos do jsDelivr são imutáveis
     *    (Cache-Control longo), por isso recuar no vídeo / rever não volta a
     *    descarregar. Segue sem Authorization nem cookies (fetch bruto do worker).
     */
    fun playerHttp(context: Context): OkHttpClient {
        playerClient?.let { return it }
        synchronized(this) {
            playerClient?.let { return it }
            val cache = Cache(File(context.applicationContext.cacheDir, "player_http_cache"), 64L * 1024 * 1024)
            val client = OkHttpClient.Builder()
                .cache(cache)
                .connectTimeout(NetTimeouts.PLAYER_CONNECT_S, TimeUnit.SECONDS)
                .readTimeout(NetTimeouts.PLAYER_TOTAL_S, TimeUnit.SECONDS)
                .writeTimeout(NetTimeouts.PLAYER_CONNECT_S, TimeUnit.SECONDS)
                // TIMEOUT TOTAL por pedido (conexão + headers + corpo inteiro): é o
                // equivalente exato do setTimeout(20s) do BinLoader e dos
                // frag/manifest/levelLoadingTimeOut: 20_000 do hls.js. O readTimeout
                // acima só mede inatividade entre bytes — um segmento a pingar nunca
                // estourava e o player ficava "encravado". Cada retry recomeça o relógio.
                .callTimeout(NetTimeouts.PLAYER_TOTAL_S, TimeUnit.SECONDS)
                .retryOnConnectionFailure(true)
                .connectionPool(ConnectionPool(8, 5, TimeUnit.MINUTES))
                .build()
            playerClient = client
            return client
        }
    }

    @Volatile private var plainClient: OkHttpClient? = null

    /**
     * Cliente SEM interceptor de auth nem cookies — para buscar os
     * segmentos .bin/.m3u8 do CDN (jsDelivr), igual ao fetch() bruto do
     * decrypt.worker.ts original (sem Authorization, sem credentials).
     * Também usado por: copyright público, downloads do CDN, playlist de canais e
     * imagens (Coil) — tudo `fetch`/`<img>` sem timeout no web.
     */
    fun plainHttpClient(): OkHttpClient {
        plainClient?.let { return it }
        synchronized(this) {
            plainClient?.let { return it }
            val client = OkHttpClient.Builder().webLikeTimeouts().build()
            plainClient = client
            return client
        }
    }
}
