package io.pixgo.app.ui.common

import android.widget.Toast
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.pixgo.app.data.catalog.CatalogRepository
import io.pixgo.app.data.i18n.LocalTranslator
import kotlinx.coroutines.launch

/**
 * Estado + acção de "Minha Lista" partilhados por TODOS os cards (Home,
 * Explorar, Pesquisa...). Antes cada ecrã tinha a sua cópia — a Home nem
 * sequer passava o botão, e no Explorar o clique era silencioso: sem perfil
 * ou com erro de rede não acontecia nada visível, o ícone nunca mudava.
 *
 *  - [isIn]: o ícone do card passa de "+" a "✓" assim que o toque é feito;
 *  - [toggle]: adiciona/remove com actualização optimista, avisa com um
 *    toast ("Adicionado…", "Removido…" ou o erro de rede) e reverte o ícone
 *    se o servidor falhar.
 */
class MyListUi(
    private val ids: Set<String>,
    val toggle: (contentId: String) -> Unit,
) {
    fun isIn(contentId: String): Boolean = contentId in ids
}

@Composable
fun rememberMyList(repo: CatalogRepository, profileId: String?): MyListUi {
    val ctx = LocalContext.current
    val t = LocalTranslator.current
    val scope = rememberCoroutineScope()
    val ids by repo.myListIds.collectAsStateWithLifecycle()

    LaunchedEffect(profileId) { repo.bindMyListProfile(profileId) }

    return remember(ids, profileId, t) {
        MyListUi(ids) { contentId ->
            if (profileId == null) {
                Toast.makeText(ctx, "Perfil não encontrado.", Toast.LENGTH_SHORT).show()
            } else {
                scope.launch {
                    when (repo.toggleMyList(profileId, contentId)) {
                        true -> Toast.makeText(ctx, t.t("myList.added"), Toast.LENGTH_SHORT).show()
                        false -> Toast.makeText(ctx, t.t("myList.removed"), Toast.LENGTH_SHORT).show()
                        null -> Toast.makeText(ctx, t.t("errors.networkError"), Toast.LENGTH_SHORT).show()
                    }
                }
            }
        }
    }
}
