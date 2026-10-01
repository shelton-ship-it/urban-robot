package io.pixgo.app.ui.common

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage

/**
 * Equivalente a components/ui/ContentCard.tsx — poster 2:3 + título por
 * baixo. Reutilizado em Home/Explorar/Minha Coleção/Pesquisar, tal como
 * o original reutiliza o mesmo componente React nessas 4 páginas.
 */
@Composable
fun ContentCardCell(
    posterUrl: String?,
    title: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Column(modifier.clip(RoundedCornerShape(8.dp)).clickable(onClick = onClick)) {
        AsyncImage(
            model = posterUrl,
            contentDescription = title,
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(2f / 3f)
                .clip(RoundedCornerShape(8.dp)),
            contentScale = ContentScale.Crop
        )
        Text(
            title,
            style = MaterialTheme.typography.bodySmall,
            maxLines = 1,
            modifier = Modifier.padding(top = 4.dp)
        )
    }
}
