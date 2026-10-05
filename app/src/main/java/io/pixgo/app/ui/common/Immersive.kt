package io.pixgo.app.ui.common

import android.view.Window
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.window.DialogWindowProvider
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat

/** Esconde status + navigation bar numa janela, com revelação transitória por gesto (estilo jogo). */
fun Window.applyImmersive() {
    WindowCompat.setDecorFitsSystemWindows(this, false)
    WindowCompat.getInsetsController(this, decorView).apply {
        systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        hide(WindowInsetsCompat.Type.systemBars())
    }
}

/**
 * Janelas de Dialog/AlertDialog/ModalBottomSheet são janelas separadas e NÃO herdam o modo
 * imersivo da Activity — sem isto a Navigation Bar reaparece enquanto o diálogo está aberto.
 * Chamar dentro do conteúdo de cada Dialog.
 */
@Composable
fun DialogImmersive() {
    val view = LocalView.current
    SideEffect {
        (view.parent as? DialogWindowProvider)?.window?.applyImmersive()
    }
}
