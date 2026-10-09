package io.pixgo.app.ui.common

import android.view.View
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.platform.LocalView

/**
 * Mantém o ecrã ligado enquanto este composable estiver na composição.
 *
 * Usa `View.keepScreenOn` (o sistema só respeita a flag com a janela visível, por isso ao ir
 * para segundo plano o ecrã volta a apagar normalmente). Com contagem de referências por View:
 * vários ecrãs podem pedir ao mesmo tempo (ex.: Watch + Player) e só o ÚLTIMO a sair desliga —
 * assim sair do Player não desliga a flag enquanto a Watch ainda está aberta.
 */
private val holds = java.util.WeakHashMap<View, Int>()

@Composable
fun KeepScreenOn() {
    val view = LocalView.current
    DisposableEffect(view) {
        holds[view] = (holds[view] ?: 0) + 1
        view.keepScreenOn = true
        onDispose {
            val left = (holds[view] ?: 1) - 1
            if (left <= 0) {
                holds.remove(view)
                view.keepScreenOn = false
            } else {
                holds[view] = left
            }
        }
    }
}
