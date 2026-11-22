package pl.lk.papermind.ui.theme

import android.os.Build
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext

/** Kolory nakładki AR (stałe — muszą być czytelne na dowolnym tle kartki). */
object OverlayColors {
    val Correct = Color(0xFF39D98A)
    val Pending = Color(0xFFFFC857)
    val Failed = Color(0xFFFF5A5F)
    val Selected = Color(0xFFFFFFFF)
    val LineOutline = Color(0x55FFFFFF)
    val BubbleBackground = Color(0xE6101418)
    val BubbleText = Color(0xFFF2F4F7)
}

/** Aplikacja kamery: zawsze ciemny motyw; dynamic color (Android 12+) z paletą zapasową. */
@Composable
fun PaperMindTheme(content: @Composable () -> Unit) {
    val context = LocalContext.current
    val scheme = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) dynamicDarkColorScheme(context) else darkColorScheme()
    MaterialTheme(colorScheme = scheme, content = content)
}
