package io.github.sasakiy84.qarry

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.*
import androidx.compose.ui.graphics.Color

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        window.isNavigationBarContrastEnforced = false
        setContent {
            val colors = if (isSystemInDarkTheme()) DarkColors else LightColors
            MaterialTheme(colorScheme = colors) {
                val preference = remember { ArticleOpeningPreference(this@MainActivity) }
                var mode by remember { mutableStateOf(preference.mode) }
                CompositionLocalProvider(LocalArticleOpenMode provides mode) {
                    QarryScreen(feedLibrary()) { updated ->
                        preference.mode = updated
                        mode = updated
                    }
                }
            }
        }
    }
}

private val LightColors = lightColorScheme(
    primary = Color(0xFF2851C7), onPrimary = Color.White,
    primaryContainer = Color(0xFFE3EAFF), onPrimaryContainer = Color(0xFF153477),
    background = Color(0xFFFBFAF6), onBackground = Color(0xFF1C2333),
    surface = Color(0xFFFBFAF6), onSurface = Color(0xFF1C2333),
    surfaceVariant = Color(0xFFE9EBF2), onSurfaceVariant = Color(0xFF49546A),
    surfaceContainer = Color(0xFFF1F0EC), surfaceContainerHigh = Color(0xFFEDECE9),
    outline = Color(0xFF747E92), outlineVariant = Color(0xFFD8DAE2),
    tertiary = Color(0xFF8C5200), tertiaryContainer = Color(0xFFFFE5BD),
)

private val DarkColors = darkColorScheme(
    primary = Color(0xFFB4C5FF), onPrimary = Color(0xFF102A68),
    primaryContainer = Color(0xFF243D78), onPrimaryContainer = Color(0xFFE3EAFF),
    background = Color(0xFF11141C), onBackground = Color(0xFFE4E8F4),
    surface = Color(0xFF11141C), onSurface = Color(0xFFE4E8F4),
    surfaceVariant = Color(0xFF2A303F), onSurfaceVariant = Color(0xFFBFC8DC),
    surfaceContainer = Color(0xFF1D2230), surfaceContainerHigh = Color(0xFF252B3A),
    outline = Color(0xFF959FB5), outlineVariant = Color(0xFF41495B),
    tertiary = Color(0xFFFFBD58), tertiaryContainer = Color(0xFF54401F),
)
