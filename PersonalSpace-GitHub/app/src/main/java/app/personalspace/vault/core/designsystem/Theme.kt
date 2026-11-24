package app.personalspace.vault.core.designsystem

import android.provider.Settings
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.platform.LocalContext

val LocalReducedMotion = staticCompositionLocalOf { false }

private val BauhausColorScheme = lightColorScheme(
    primary = BauhausColors.Red,
    onPrimary = BauhausColors.LightSurface,
    primaryContainer = BauhausColors.CardSurface,
    onPrimaryContainer = BauhausColors.Black,
    secondary = BauhausColors.Blue,
    onSecondary = BauhausColors.LightSurface,
    secondaryContainer = BauhausColors.CardSurface,
    onSecondaryContainer = BauhausColors.Black,
    tertiary = BauhausColors.Yellow,
    onTertiary = BauhausColors.Black,
    background = BauhausColors.Background,
    onBackground = BauhausColors.Black,
    surface = BauhausColors.Background,
    onSurface = BauhausColors.Black,
    surfaceVariant = BauhausColors.CardSurface,
    onSurfaceVariant = BauhausColors.SecondaryText,
    surfaceContainerHighest = BauhausColors.LightSurface,
    surfaceContainerHigh = BauhausColors.LightSurface,
    surfaceContainer = BauhausColors.CardSurface,
    surfaceContainerLow = BauhausColors.CardSurface,
    outline = BauhausColors.Divider,
    outlineVariant = BauhausColors.Divider,
    error = BauhausColors.Red,
    onError = BauhausColors.LightSurface
)

@Composable
fun PersonalSpaceTheme(
    preferReducedMotion: Boolean = false,
    content: @Composable () -> Unit
) {
    val context = LocalContext.current
    val systemReducedMotion = remember(context) {
        runCatching {
            val scale = Settings.Global.getFloat(
                context.contentResolver,
                Settings.Global.ANIMATOR_DURATION_SCALE,
                1f
            )
            scale == 0f
        }.getOrDefault(false)
    }

    CompositionLocalProvider(
        LocalReducedMotion provides (preferReducedMotion || systemReducedMotion)
    ) {
        MaterialTheme(
            colorScheme = BauhausColorScheme,
            typography = BauhausTypography,
            content = content
        )
    }
}
