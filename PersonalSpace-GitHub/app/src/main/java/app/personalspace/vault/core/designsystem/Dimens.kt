package app.personalspace.vault.core.designsystem

import androidx.compose.ui.unit.dp

/**
 * Density-independent dimensions calibrated for POCO F7 (6.83", 1280x2772)
 * matching the Minimal Bauhaus visual layout with stable, balanced top/bottom spacing.
 */
object BauhausDimens {
    /**
     * Balanced top breathing room added after status bar / punch-hole safe-drawing inset
     * so headers sit stably near the top without feeling cramped or pushed too low.
     */
    val HeaderExtraTopSpacing = 14.dp
    val HeaderBottomSpacing = 8.dp

    val PageHorizontalPadding = 20.dp
    val SectionSpacing = 16.dp
    val CardSpacing = 14.dp
    val ContentSpacing = 12.dp

    val MinTouchTarget = 48.dp
    val BottomNavHeight = 64.dp
    val CornerRadius = 18.dp
    val SmallCornerRadius = 10.dp
    val BorderStroke = 1.dp
}
