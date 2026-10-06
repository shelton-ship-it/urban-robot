package io.pixgo.app.ui.common

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.pm.ActivityInfo
import android.content.res.Configuration
import android.view.OrientationEventListener
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext

tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}

/**
 * Estado de ecrã inteiro DERIVADO da orientação real do aparelho — é assim que
 * o YouTube funciona:
 *  - virar o telemóvel para paisagem  → ecrã inteiro (sem reiniciar nada);
 *  - botão nativo de ecrã inteiro      → roda o ecrã (paisagem ↔ retrato);
 *  - virar de volta para retrato       → sai do ecrã inteiro.
 * O vídeo continua a reproduzir sempre: o mesmo ExoPlayer/PlayerView é só
 * re-dimensionado (a Activity não é recriada — ver `configChanges` no manifest).
 */
@Immutable
class FullscreenState(
    val isFullscreen: Boolean,
    /** Alterna ecrã inteiro (roda o aparelho para paisagem, ou volta a retrato). */
    val toggle: () -> Unit,
)

@Composable
fun rememberFullscreenState(): FullscreenState {
    val ctx = LocalContext.current
    val landscape = LocalConfiguration.current.orientation == Configuration.ORIENTATION_LANDSCAPE
    val controller = remember { FullscreenController(ctx.findActivity(), ctx.applicationContext) }
    DisposableEffect(controller) { onDispose { controller.release() } }
    return remember(landscape, controller) { FullscreenState(landscape) { controller.toggle(landscape) } }
}

/**
 * Força a orientação pedida pelo botão e SOLTA o bloqueio (volta ao automático)
 * assim que o utilizador roda fisicamente o aparelho para essa orientação —
 * evitando ficar "preso" em paisagem/retrato depois de usar o botão.
 */
internal class FullscreenController(
    private val activity: Activity?,
    private val appContext: Context,
) {
    private enum class Want { NONE, LANDSCAPE, PORTRAIT }

    private var want = Want.NONE
    private var sensor: OrientationEventListener? = null

    fun toggle(landscapeNow: Boolean) {
        val a = activity ?: return
        if (landscapeNow) {
            want = Want.PORTRAIT
            a.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR_PORTRAIT
        } else {
            want = Want.LANDSCAPE
            a.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
        }
        startSensor()
    }

    private fun startSensor() {
        stopSensor()
        val l = object : OrientationEventListener(appContext) {
            override fun onOrientationChanged(degrees: Int) {
                if (degrees == ORIENTATION_UNKNOWN) return
                val portrait = degrees <= 25 || degrees >= 335 || degrees in 155..205
                val land = degrees in 65..115 || degrees in 245..295
                val matched = when (want) {
                    Want.LANDSCAPE -> land
                    Want.PORTRAIT -> portrait
                    Want.NONE -> false
                }
                if (matched) unlock()
            }
        }
        if (l.canDetectOrientation()) {
            sensor = l
            l.enable()
        } else {
            // Sem sensor: mantém o bloqueio até o utilizador usar o botão outra vez,
            // mas ao SAIR liberta já (não há como detectar a posição física).
            if (want == Want.PORTRAIT) unlock()
        }
    }

    private fun unlock() {
        want = Want.NONE
        stopSensor()
        activity?.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
    }

    private fun stopSensor() {
        sensor?.disable()
        sensor = null
    }

    /** Ao sair do ecrã do player: volta SEMPRE ao automático (rotação livre). */
    fun release() {
        want = Want.NONE
        stopSensor()
        activity?.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
    }
}
