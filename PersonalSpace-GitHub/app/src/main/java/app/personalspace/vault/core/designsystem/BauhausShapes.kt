package app.personalspace.vault.core.designsystem

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.runtime.getValue
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

data class BauhausBarPoint(
    val label: String,
    val count: Int,
    val fullDateLabel: String = label,
    val recordsAdded: Int = 0,
    val foldersCreated: Int = 0,
    val remindersCount: Int = 0
)

private val BarPalette = listOf(
    BauhausColors.Black,
    BauhausColors.Yellow,
    BauhausColors.Red,
    BauhausColors.Blue,
    BauhausColors.Yellow,
    BauhausColors.Red,
    BauhausColors.Blue
)

/**
 * Interactive Minimal Bauhaus activity bar chart:
 * Tapping any date candle invokes [onSelectBar] so the parent card can immediately display
 * that date's Records, Folders, and Reminders breakdown inside the same card layer.
 */
@Composable
fun BauhausActivityBarChart(
    points: List<BauhausBarPoint>,
    modifier: Modifier = Modifier,
    chartHeight: Dp = 148.dp,
    selectedIndex: Int? = null,
    onSelectBar: ((Int) -> Unit)? = null,
    accessibilityLabel: String = "Recent note activity bar chart"
) {
    val maxVal = (points.maxOfOrNull { it.count } ?: 0).coerceAtLeast(1)

    Box(
        modifier = modifier
            .fillMaxWidth()
            .semantics { contentDescription = accessibilityLabel }
    ) {
        // Architectural Minimal Bauhaus subtle dashed guide lines behind the candles
        Canvas(
            modifier = Modifier
                .fillMaxWidth()
                .height(chartHeight)
                .padding(top = 20.dp, bottom = 26.dp)
        ) {
            val dash = PathEffect.dashPathEffect(floatArrayOf(6.dp.toPx(), 6.dp.toPx()), 0f)
            val lineColor = BauhausColors.Divider.copy(alpha = 0.85f)
            listOf(0.15f, 0.55f, 0.98f).forEach { ratio ->
                val y = size.height * ratio
                drawLine(
                    color = lineColor,
                    start = Offset(0f, y),
                    end = Offset(size.width, y),
                    strokeWidth = 1.dp.toPx(),
                    pathEffect = if (ratio < 0.95f) dash else null
                )
            }
        }

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(chartHeight),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.Bottom
        ) {
            points.forEachIndexed { index, point ->
                val color = BarPalette[index % BarPalette.size]
                val isSelected = selectedIndex == index
                val targetFraction = if (point.count == 0) {
                    0.11f
                } else {
                    (point.count.toFloat() / maxVal.toFloat()).coerceIn(0.24f, 1f)
                }
                val animatedFraction by animateFloatAsState(
                    targetValue = targetFraction,
                    animationSpec = tween(durationMillis = 320, easing = FastOutSlowInEasing),
                    label = "bar_fraction_$index"
                )

                val colShape = RoundedCornerShape(12.dp)
                val clickMod = if (onSelectBar != null) {
                    Modifier
                        .clip(colShape)
                        .background(
                            if (isSelected) BauhausColors.LightSurface else Color.Transparent
                        )
                        .then(
                            if (isSelected) {
                                Modifier.border(1.dp, BauhausColors.Divider, colShape)
                            } else {
                                Modifier
                            }
                        )
                        .clickable(
                            role = Role.Button,
                            onClickLabel = "Inspect activity for ${point.fullDateLabel}"
                        ) { onSelectBar(index) }
                        .padding(vertical = 5.dp, horizontal = 2.dp)
                } else {
                    Modifier.padding(vertical = 5.dp, horizontal = 2.dp)
                }

                Column(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxHeight()
                        .defaultMinSize(minWidth = 36.dp)
                        .then(clickMod)
                        .testTag("activity_bar_$index"),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Bottom
                ) {
                    Text(
                        text = if (point.count > 0) point.count.toString() else "",
                        style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold),
                        color = if (isSelected) BauhausColors.Red else BauhausColors.Black
                    )
                    Spacer(modifier = Modifier.height(4.dp))

                    // Sleek capsule track + filled Bauhaus candle
                    Canvas(
                        modifier = Modifier
                            .fillMaxWidth(0.58f)
                            .weight(1f)
                    ) {
                        val radius = CornerRadius(8.dp.toPx(), 8.dp.toPx())
                        // Subtle background track capsule
                        drawRoundRect(
                            color = BauhausColors.LightSurface.copy(alpha = 0.78f),
                            topLeft = Offset.Zero,
                            size = size,
                            cornerRadius = radius
                        )

                        val barH = (size.height * animatedFraction).coerceAtLeast(8.dp.toPx())
                        val barColor = when {
                            isSelected -> color
                            point.count == 0 -> BauhausColors.Divider
                            else -> color
                        }
                        drawRoundRect(
                            color = barColor,
                            topLeft = Offset(0f, size.height - barH),
                            size = Size(size.width, barH),
                            cornerRadius = radius
                        )
                        if (isSelected) {
                            drawRoundRect(
                                color = BauhausColors.Black,
                                topLeft = Offset(0f, size.height - barH),
                                size = Size(size.width, barH),
                                cornerRadius = radius,
                                style = Stroke(width = 1.6.dp.toPx())
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(6.dp))
                    Text(
                        text = point.label,
                        style = MaterialTheme.typography.labelSmall.copy(
                            fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium
                        ),
                        color = if (isSelected) BauhausColors.Black else BauhausColors.SecondaryText,
                        textAlign = TextAlign.Center,
                        maxLines = 1
                    )
                }
            }
        }
    }
}

/**
 * Bauhaus circular ring gauge matching the Statistics screen.
 */
@Composable
fun BauhausCircularProgress(
    progress: Float,
    centerValueText: String,
    subtitle: String,
    accentColor: Color = BauhausColors.Blue,
    size: Dp = 122.dp,
    modifier: Modifier = Modifier
) {
    val clamped = progress.coerceIn(0f, 1f)
    Box(
        modifier = modifier
            .size(size)
            .semantics { contentDescription = "$subtitle: $centerValueText" },
        contentAlignment = Alignment.Center
    ) {
        Canvas(modifier = Modifier.size(size)) {
            val stroke = 13.dp.toPx()
            val arcSize = Size(this.size.width - stroke, this.size.height - stroke)
            val offset = Offset(stroke / 2f, stroke / 2f)
            drawArc(
                color = BauhausColors.Divider,
                startAngle = -90f,
                sweepAngle = 360f,
                useCenter = false,
                topLeft = offset,
                size = arcSize,
                style = Stroke(width = stroke, cap = StrokeCap.Round)
            )
            val visualSweep = if (clamped > 0f) 360f * clamped else 24f
            drawArc(
                color = if (clamped > 0f) accentColor else accentColor.copy(alpha = 0.28f),
                startAngle = -90f,
                sweepAngle = visualSweep,
                useCenter = false,
                topLeft = offset,
                size = arcSize,
                style = Stroke(width = stroke, cap = StrokeCap.Round)
            )
        }
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                text = centerValueText,
                style = MaterialTheme.typography.headlineMedium.copy(fontWeight = FontWeight.Bold),
                color = BauhausColors.Black
            )
            if (subtitle.isNotBlank()) {
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.labelSmall,
                    color = BauhausColors.SecondaryText
                )
            }
        }
    }
}

/**
 * Corner Bauhaus geometric motif for section headers.
 */
@Composable
fun BauhausCornerAccent(
    primaryColor: Color = BauhausColors.Yellow,
    secondaryColor: Color = BauhausColors.Blue,
    modifier: Modifier = Modifier
) {
    Canvas(modifier = modifier.size(width = 46.dp, height = 30.dp)) {
        drawArc(
            color = primaryColor,
            startAngle = 180f,
            sweepAngle = 180f,
            useCenter = true,
            topLeft = Offset(0f, size.height * 0.20f),
            size = Size(size.width * 0.66f, size.height * 1.15f)
        )
        drawCircle(
            color = secondaryColor,
            radius = size.height * 0.24f,
            center = Offset(size.width * 0.80f, size.height * 0.42f)
        )
    }
}

/**
 * Vibrant filled geometric folder icon (Yellow, Red, Blue, Black).
 */
@Composable
fun BauhausFolderIcon(
    accent: FolderAccentColor,
    modifier: Modifier = Modifier,
    size: Dp = 36.dp
) {
    Canvas(
        modifier = modifier
            .size(size)
            .padding(2.dp)
    ) {
        val w = this.size.width
        val h = this.size.height
        val fill = accent.toComposeColor()
        val tabShade = fill.copy(alpha = 0.82f)
        drawRoundRect(
            color = tabShade,
            topLeft = Offset(0f, h * 0.12f),
            size = Size(w * 0.46f, h * 0.26f),
            cornerRadius = CornerRadius(3.dp.toPx(), 3.dp.toPx())
        )
        drawRoundRect(
            color = fill,
            topLeft = Offset(0f, h * 0.26f),
            size = Size(w, h * 0.64f),
            cornerRadius = CornerRadius(4.dp.toPx(), 4.dp.toPx())
        )
    }
}

/**
 * Vibrant Minimal Bauhaus geometric File / Note icon matching [BauhausFolderIcon] proportions:
 * Rounded sheet with a folded top-right dog-ear corner and clean ruled note lines.
 */
@Composable
fun BauhausNoteFileIcon(
    accentColor: Color = BauhausColors.Blue,
    modifier: Modifier = Modifier,
    size: Dp = 34.dp
) {
    Canvas(
        modifier = modifier
            .size(size)
            .padding(2.dp)
            .testTag("bauhaus_note_file_icon")
    ) {
        val w = this.size.width
        val h = this.size.height
        val sheetLeft = w * 0.11f
        val sheetRight = w * 0.89f
        val sheetTop = h * 0.06f
        val sheetBottom = h * 0.94f
        val fold = w * 0.24f
        val r = 4.dp.toPx()

        val bodyPath = Path().apply {
            moveTo(sheetLeft + r, sheetTop)
            lineTo(sheetRight - fold, sheetTop)
            lineTo(sheetRight, sheetTop + fold)
            lineTo(sheetRight, sheetBottom - r)
            quadraticTo(sheetRight, sheetBottom, sheetRight - r, sheetBottom)
            lineTo(sheetLeft + r, sheetBottom)
            quadraticTo(sheetLeft, sheetBottom, sheetLeft, sheetBottom - r)
            lineTo(sheetLeft, sheetTop + r)
            quadraticTo(sheetLeft, sheetTop, sheetLeft + r, sheetTop)
            close()
        }
        drawPath(path = bodyPath, color = accentColor)

        // Folded top-right corner flap
        val flapPath = Path().apply {
            moveTo(sheetRight - fold, sheetTop)
            lineTo(sheetRight - fold, sheetTop + fold - r * 0.4f)
            quadraticTo(sheetRight - fold, sheetTop + fold, sheetRight - fold + r * 0.4f, sheetTop + fold)
            lineTo(sheetRight, sheetTop + fold)
            close()
        }
        drawPath(path = flapPath, color = BauhausColors.Yellow)

        // Three subtle Minimal Bauhaus ruled text lines inside the note icon
        val lineStroke = 2.dp.toPx()
        val lineStart = sheetLeft + w * 0.14f
        val lineEndFull = sheetRight - w * 0.14f
        val lineEndShort = sheetLeft + (sheetRight - sheetLeft) * 0.58f
        val lineColor = BauhausColors.LightSurface.copy(alpha = 0.92f)

        drawLine(
            color = lineColor,
            start = Offset(lineStart, h * 0.45f),
            end = Offset(lineEndFull, h * 0.45f),
            strokeWidth = lineStroke,
            cap = StrokeCap.Round
        )
        drawLine(
            color = lineColor,
            start = Offset(lineStart, h * 0.62f),
            end = Offset(lineEndFull, h * 0.62f),
            strokeWidth = lineStroke,
            cap = StrokeCap.Round
        )
        drawLine(
            color = lineColor,
            start = Offset(lineStart, h * 0.78f),
            end = Offset(lineEndShort, h * 0.78f),
            strokeWidth = lineStroke,
            cap = StrokeCap.Round
        )
    }
}

