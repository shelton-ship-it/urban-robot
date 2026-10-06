package io.pixgo.app.data.player

import android.app.ActivityManager
import android.content.Context
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
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
 *  fragLoadingMaxRetry 4 / retryDelay 500ms      →  HlsRetryPolicy (4 retries, 500ms·2^n, máx 64s)
 *  frag/manifest/levelLoadingTimeOut 20s         →  OkHttp callTimeout 20s (total, por tentativa)
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

    /** VOD cifrado (.bin) — HlsMediaSource + BinDecryptDataSource. */
    fun createVod(context: Context, dataSourceFactory: DataSource.Factory): ExoPlayer {
        val hls = HlsMediaSource.Factory(dataSourceFactory)
            .setLoadErrorHandlingPolicy(HlsRetryPolicy())
        return builder(context, hls).build()
    }

    /** Canais ao vivo (HLS/TS abertos) — media source por defeito do Media3. */
    fun createLive(context: Context): ExoPlayer = builder(context, null).build()
}

/**
 * Política de retry igual à do hls.js do frontend: até 4 tentativas extra
 * (fragLoadingMaxRetry) com atraso exponencial a partir de 500 ms
 * (fragLoadingRetryDelay) — 500, 1000, 2000, 4000 ms, teto de 64 s
 * (maxRetryTimeout). Quais erros SÃO re-tentáveis continua a decidir o
 * DefaultLoadErrorHandlingPolicy (ex.: 404/ficheiro inexistente não repete);
 * aqui só se substitui o ATRASO (o padrão do ExoPlayer era 0, 1 s, 2 s, 3 s…).
 */
@UnstableApi
private class HlsRetryPolicy : DefaultLoadErrorHandlingPolicy(/* minimumLoadableRetryCount = */ 4) {
    override fun getRetryDelayMsFor(loadErrorInfo: LoadErrorHandlingPolicy.LoadErrorInfo): Long {
        val base = super.getRetryDelayMsFor(loadErrorInfo)
        if (base == C.TIME_UNSET) return base
        val n = (loadErrorInfo.errorCount - 1).coerceIn(0, 7)
        return minOf(500L shl n, 64_000L)
    }
}
