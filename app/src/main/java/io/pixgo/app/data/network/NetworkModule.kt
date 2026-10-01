package io.pixgo.app.data.network

import android.content.Context
import io.pixgo.app.data.auth.TokenManager
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
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

object NetworkModule {

    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
        explicitNulls = false
    }

    @Volatile private var cookieJar: PersistentCookieJar? = null
    @Volatile private var okHttpClient: OkHttpClient? = null

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
            val client = OkHttpClient.Builder()
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
                .addInterceptor(authInterceptor(tokenManager))
                .build()
            contactClient = client
            return client
        }
    }

    fun contact(tokenManager: TokenManager): ContactApi =
        retrofit(Hosts.COPYRIGHT, contactHttp(tokenManager)).create(ContactApi::class.java)

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

    @Volatile private var plainClient: OkHttpClient? = null

    /**
     * Cliente SEM interceptor de auth nem cookies — para buscar os
     * segmentos .bin/.m3u8 do CDN (jsDelivr), igual ao fetch() bruto do
     * decrypt.worker.ts original (sem Authorization, sem credentials).
     */
    fun plainHttpClient(): OkHttpClient {
        plainClient?.let { return it }
        synchronized(this) {
            plainClient?.let { return it }
            val client = OkHttpClient.Builder().build()
            plainClient = client
            return client
        }
    }
}
