package io.pixgo.app.data.player

import android.app.ActivityManager
import android.content.Context
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.hls.HlsMediaSource
import androidx.media3.exoplayer.source.MediaSource
import androidx.media3.exoplayer.trackselection.AdaptiveTrackSelection
import androidx.media3.exoplayer.trackselection.DefaultTrackSelector
import androidx.media3.exoplayer.upstream.DefaultBandwidthMeter
import androidx.media3.exoplayer.upstream.DefaultLoadErrorHandlingPolicy
import androidx.media3.exoplayer.upstream.LoadErrorHandlingPolicy

/**
 * Configuração do ExoPlayer espelhando o `new Hls({...})` do ShakaPlayer.tsx do
 * frontend (que "tem performance até em redes lentas"):
 *
 *  web (hls.js)                                  →  Android (Media3)
 *  ─────────────────────────────────────────────────────────────────────────
 *  maxBufferLength 60s (lowRam 20s)              →  maxBuffer 60s (lowRam 30s)
 *  maxMaxBufferLength 120s (lowRam 30s)          →  teto do buffer (lowRam 30s)
 *  backBufferLength 30s (lowRam 10s)             →  setBackBuffer 30s (10s)
 *  fragLoadingMaxRetry 4 / retryDelay 500ms      →  PatientRetryPolicy (20 retries, 500ms·2^n, máx 6s)
 *  frag/manifest/levelLoadingTimeOut 20s         →  OkHttp callTimeout 120s + 30s de inatividade
 *  (detecção de deviceMemory <= 2GB)             →  ActivityManager.isLowRamDevice/memoryClass
 *
 * Extras que o hls.js faz sozinho e o ExoPlayer só faz se for configurado:
 *  - arranque rápido: começa a tocar com 1,5 s em buffer (e 4 s após um
 *    rebuffer) em vez dos 2,5 s/5 s por defeito;
 *  - ABR sensível: desce de qualidade mais depressa (15 s) quando a banda cai;
 *  - qualidade limitada ao tamanho real do ecrã (não gasta dados em 1080p num
 *    ecrã pequeno).
 */
@UnstableApi
object PlayerFactory {

    private fun isLowRam(context: Context): Boolean {
        val am = context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager ?: return false
        return am.isLowRamDevice || am.memoryClass <= 128
    }

    private fun loadControl(lowRam: Boolean): DefaultLoadControl =
        DefaultLoadControl.Builder()
            .setBufferDurationsMs(
                if (lowRam) 15_000 else 30_000,   // mínimo que tenta manter
                if (lowRam) 30_000 else 60_000,   // máximo (maxBufferLength do web)
                1_500,                              // começa a tocar com 1,5 s
                4_000,                              // retoma após rebuffer com 4 s
            )
            .setBackBuffer(if (lowRam) 10_000 else 30_000, false)
            .setPrioritizeTimeOverSizeThresholds(true)
            .build()

    private fun trackSelector(context: Context): DefaultTrackSelector {
        val selector = DefaultTrackSelector(
            context,
            AdaptiveTrackSelection.Factory(
                /* minDurationForQualityIncreaseMs = */ 10_000,
                /* maxDurationForQualityDecreaseMs = */ 15_000,
                /* minDurationToRetainAfterDiscardMs = */ 25_000,
                /* bandwidthFraction = */ 0.75f,
            ),
        )
        selector.parameters = selector.buildUponParameters()
            .setViewportSizeToPhysicalDisplaySize(context, true)
            .setExceedVideoConstraintsIfNecessary(true)
            .build()
        return selector
    }

    private fun builder(context: Context, mediaSourceFactory: MediaSource.Factory?): ExoPlayer.Builder {
        val app = context.applicationContext
        val b = ExoPlayer.Builder(app)
            .setLoadControl(loadControl(isLowRam(app)))
            .setTrackSelector(trackSelector(app))
            .setBandwidthMeter(DefaultBandwidthMeter.Builder(app).build())
            .setHandleAudioBecomingNoisy(true)
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(C.USAGE_MEDIA)
                    .setContentType(C.AUDIO_CONTENT_TYPE_MOVIE)
                    .build(),
                /* handleAudioFocus = */ true,
            )
        if (mediaSourceFactory != null) b.setMediaSourceFactory(mediaSourceFactory)
        return b
    }

    /**
     * VOD cifrado (.bin) — HlsMediaSource + BinDecryptDataSource.
     * `patient = false` (sessão OFFLINE): a política paciente (20 tentativas, ~100 s por
     * segmento) existe para redes lentas; num ficheiro local um erro não é transitório e
     * só escondia a falha atrás de um spinner infinito.
     */
    fun createVod(context: Context, dataSourceFactory: DataSource.Factory, patient: Boolean = true): ExoPlayer {
        val hls = HlsMediaSource.Factory(dataSourceFactory)
        if (patient) hls.setLoadErrorHandlingPolicy(PatientRetryPolicy())
        return builder(context, hls).build()
    }

    /**
     * Canais ao vivo (HLS/TS abertos). Antes usava o DefaultHttpDataSource por defeito do Media3
     * (8 s de connect/read) e a política por defeito (3 tentativas): um canal lento ou um CDN
     * momentaneamente indisponível dava erro/ecrã preso. Agora: 30 s connect, 60 s read, redirects
     * http↔https permitidos e a mesma política paciente do VOD.
     */
    fun createLive(context: Context): ExoPlayer {
        val app = context.applicationContext
        val http = DefaultHttpDataSource.Factory()
            .setConnectTimeoutMs(30_000)
            .setReadTimeoutMs(60_000)
            .setAllowCrossProtocolRedirects(true)
        val factory = DefaultMediaSourceFactory(app)
            .setDataSourceFactory(DefaultDataSource.Factory(app, http))
            .setLoadErrorHandlingPolicy(PatientRetryPolicy())
        return builder(context, factory).build()
    }
}

/**
 * Política de retry PACIENTE. O hls.js do web só tinha 4 retries mas recomeçava sozinho
 * (startLoad()) em qualquer erro fatal de rede; o ExoPlayer não — depois de esgotar as
 * tentativas lança erro e o vídeo "encravava" como se o segmento não existisse (e ao
 * atualizar tocava, porque a rede já tinha voltado). Agora são 20 tentativas por pedido
 * com atraso 0,5 s → 1 → 2 → 4 → 6 s (teto), ~100 s de paciência por segmento/playlist.
 * Quais erros SÃO re-tentáveis continua a decidir o DefaultLoadErrorHandlingPolicy
 * (ex.: ficheiro inexistente/parser não repete); aqui só mudam a contagem e o ATRASO.
 */
@UnstableApi
private class PatientRetryPolicy : DefaultLoadErrorHandlingPolicy(/* minimumLoadableRetryCount = */ 20) {
    override fun getRetryDelayMsFor(loadErrorInfo: LoadErrorHandlingPolicy.LoadErrorInfo): Long {
        val base = super.getRetryDelayMsFor(loadErrorInfo)
        if (base == C.TIME_UNSET) return base
        val n = (loadErrorInfo.errorCount - 1).coerceIn(0, 7)
        return minOf(500L shl n, 6_000L)
    }
}
