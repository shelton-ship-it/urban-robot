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
 * Controlador do ecrã inteiro por botão. Há DOIS mecanismos de rodar o ecrã e eles não podem
 * pisar-se (era o bug "roda e volta a virar sozinho"):
 *  - NATIVO: rotação automática do sistema (orientação UNSPECIFIED) — o sistema decide;
 *  - BOTÃO do player: o app força a orientação.
 *
 * O bug antigo: depois de forçar paisagem pelo botão, assim que o utilizador rodava o aparelho
 * o controlador "soltava" o bloqueio (UNSPECIFIED = seguir o sistema). Com a rotação automática
 * do sistema DESLIGADA, seguir o sistema é retrato — o ecrã virava de volta sozinho (e cada
 * mudança de orientação reiniciava o spinner/estado do player). Agora:
 *  - FORCED_LANDSCAPE: fica em paisagem (SENSOR_LANDSCAPE, só alterna entre as 2 paisagens)
 *    até o utilizador SAIR (botão/voltar) — ou rodar o aparelho de volta a retrato, mas só se a
 *    rotação automática do sistema estiver ligada E o aparelho já tiver estado fisicamente em
 *    paisagem (senão sairia logo ao entrar, com o aparelho ainda em retrato);
 *  - FORCED_PORTRAIT (saída): retrato fixo até o aparelho estar fisicamente em retrato, depois
 *    devolve o controlo ao sistema.
 */
internal class FullscreenController(
    private val activity: Activity?,
    private val appContext: Context,
) {
    private enum class Mode { AUTO, FORCED_LANDSCAPE, FORCED_PORTRAIT }

    private var mode = Mode.AUTO
    private var sensor: OrientationEventListener? = null
    private var seenLandscape = false
    private var portraitSince = 0L

    fun toggle(landscapeNow: Boolean) {
        val a = activity ?: return
        if (landscapeNow) {
            mode = Mode.FORCED_PORTRAIT
            a.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
        } else {
            mode = Mode.FORCED_LANDSCAPE
            seenLandscape = false
            portraitSince = 0L
            a.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
        }
        startSensor()
    }

    private fun autoRotateOn(): Boolean = try {
        android.provider.Settings.System.getInt(
            appContext.contentResolver, android.provider.Settings.System.ACCELEROMETER_ROTATION, 1
        ) == 1
    } catch (_: Exception) { true }

    private fun startSensor() {
        stopSensor()
        val l = object : OrientationEventListener(appContext) {
            override fun onOrientationChanged(degrees: Int) {
                if (degrees == ORIENTATION_UNKNOWN) return
                val portrait = degrees <= 25 || degrees >= 335 || degrees in 155..205
                val land = degrees in 65..115 || degrees in 245..295
                when (mode) {
                    Mode.FORCED_PORTRAIT -> if (portrait) unlock()
                    Mode.FORCED_LANDSCAPE -> {
                        if (land) { seenLandscape = true; portraitSince = 0L }
                        else if (portrait && seenLandscape && autoRotateOn()) {
                            val now = System.currentTimeMillis()
                            if (portraitSince == 0L) portraitSince = now
                            // Só sai se ficar em retrato de forma estável (ignora tremores ao rodar).
                            if (now - portraitSince >= 700L) unlock()
                        } else if (!portrait) portraitSince = 0L
                    }
                    Mode.AUTO -> Unit
                }
            }
        }
        if (l.canDetectOrientation()) {
            sensor = l
            l.enable()
        } else if (mode == Mode.FORCED_PORTRAIT) {
            // Sem sensor não há como saber a posição física: ao SAIR devolve já ao sistema.
            unlock()
        }
    }

    private fun unlock() {
        mode = Mode.AUTO
        stopSensor()
        activity?.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
    }

    private fun stopSensor() {
        sensor?.disable()
        sensor = null
    }

    /** Ao sair do ecrã do player: volta SEMPRE ao automático (rotação livre). */
    fun release() {
        mode = Mode.AUTO
        stopSensor()
        activity?.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
    }
}
