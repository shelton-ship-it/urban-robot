package io.pixgo.app.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import io.pixgo.app.R

/** Tokens literais de app/globals.css (:root). Nada aqui é inventado. */
object Px {
    val Primary = Color(0xFFE50914)
    val PrimaryGlow = Color(0xFFFF2A2A)
    val Secondary = Color(0xFF1CE783)
    val Accent = Color(0xFF8C3BFF)

    val BgDark = Color(0xFF0A0A0C)
    val BgDarker = Color(0xFF050507)
    val CardBg = Color(0xFF121216)
    val CardHover = Color(0xFF1A1A20)

    val TextLight = Color(0xFFFFFFFF)
    val TextMuted = Color(0xFFA0A0A0)
    val TextTitle = Color(0xFFF1F1F1)

    val Border = Color(0x14FFFFFF)          // rgba(255,255,255,0.08)
    val BorderHover = Color(0x4DE50914)     // rgba(229,9,20,0.3)

    val Radius = 12.dp                      // --border-radius
    val RadiusSm = 6.dp                     // --border-radius-sm
}

/** --font-main: Poppins · --font-display: Montserrat */
val Poppins = FontFamily(
    Font(R.font.poppins_regular, FontWeight.Normal),
    Font(R.font.poppins_medium, FontWeight.Medium),
    Font(R.font.poppins_semibold, FontWeight.SemiBold),
    Font(R.font.poppins_bold, FontWeight.Bold),
    Font(R.font.poppins_extrabold, FontWeight.ExtraBold),
)

val Montserrat = FontFamily(
    Font(R.font.montserrat_semibold, FontWeight.SemiBold),
    Font(R.font.montserrat_bold, FontWeight.Bold),
    Font(R.font.montserrat_extrabold, FontWeight.ExtraBold),
    Font(R.font.montserrat_black, FontWeight.Black),
)

private val base = TextStyle(fontFamily = Poppins, lineHeight = 1.5.em) // html,body{line-height:1.5}

private fun Typography.withPoppins() = copy(
    displayLarge = displayLarge.copy(fontFamily = Poppins),
    displayMedium = displayMedium.copy(fontFamily = Poppins),
    displaySmall = displaySmall.copy(fontFamily = Poppins),
    headlineLarge = headlineLarge.copy(fontFamily = Poppins),
    headlineMedium = headlineMedium.copy(fontFamily = Poppins),
    headlineSmall = headlineSmall.copy(fontFamily = Poppins),
    titleLarge = titleLarge.copy(fontFamily = Poppins),
    titleMedium = titleMedium.copy(fontFamily = Poppins),
    titleSmall = titleSmall.copy(fontFamily = Poppins),
    bodyLarge = base.copy(fontSize = bodyLarge.fontSize),
    bodyMedium = bodyMedium.copy(fontFamily = Poppins),
    bodySmall = bodySmall.copy(fontFamily = Poppins),
    labelLarge = labelLarge.copy(fontFamily = Poppins),
    labelMedium = labelMedium.copy(fontFamily = Poppins),
    labelSmall = labelSmall.copy(fontFamily = Poppins),
)

@Composable
fun PixGoTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = darkColorScheme(
            primary = Px.Primary,
            onPrimary = Px.TextLight,
            secondary = Px.Secondary,
            tertiary = Px.Accent,
            background = Px.BgDark,
            onBackground = Px.TextLight,
            surface = Px.CardBg,
            onSurface = Px.TextLight,
            surfaceVariant = Px.CardHover,
            onSurfaceVariant = Px.TextMuted,
            outline = Px.Border,
            error = Px.Primary,
        ),
        typography = Typography().withPoppins(),
        content = content,
    )
}
