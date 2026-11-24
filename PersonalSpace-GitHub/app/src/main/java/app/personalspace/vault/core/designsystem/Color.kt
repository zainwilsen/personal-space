package app.personalspace.vault.core.designsystem

import androidx.compose.ui.graphics.Color

/**
 * Fixed Minimal Bauhaus Color Palette for Personal Space (Version 1).
 * Warm Cream dominates; Black anchors typography/icons; Red, Yellow, and Blue provide
 * deliberate geometric and state distinction.
 */
object BauhausColors {
    val Background = Color(0xFFFDF6EC)      // Main application background
    val Red = Color(0xFFE94528)             // Primary accent and active indicators
    val Yellow = Color(0xFFFEBD03)          // Geometric accents and highlights
    val Blue = Color(0xFF25559A)            // Secondary accent
    val Black = Color(0xFF101010)           // Main typography and icons
    val CardSurface = Color(0xFFF6EFE5)     // Summary cards and subtle surfaces
    val SecondaryText = Color(0xFF77716A)   // Supporting text
    val Divider = Color(0xFFE2D9CD)         // Separators
    val LightSurface = Color(0xFFFFFDF9)    // Elevated content surfaces
}

enum class FolderAccentColor(val id: String, val label: String) {
    YELLOW("YELLOW", "Bauhaus Yellow"),
    RED("RED", "Bauhaus Red"),
    BLUE("BLUE", "Bauhaus Blue"),
    BLACK("BLACK", "Architectural Black");

    fun toComposeColor(): Color = when (this) {
        YELLOW -> BauhausColors.Yellow
        RED -> BauhausColors.Red
        BLUE -> BauhausColors.Blue
        BLACK -> BauhausColors.Black
    }

    companion object {
        fun fromId(id: String?): FolderAccentColor =
            entries.firstOrNull { it.id.equals(id, ignoreCase = true) } ?: YELLOW
    }
}
