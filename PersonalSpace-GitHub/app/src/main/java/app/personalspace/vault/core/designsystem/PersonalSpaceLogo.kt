package app.personalspace.vault.core.designsystem

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * Exact Minimal Bauhaus geometric composition matching the uploaded reference artwork:
 * 1. Large Yellow quarter-circle (#FEBD03) sweeping upper-right
 * 2. Bold Red square (#E94528) anchored bottom-left
 * 3. Blue quarter-circle (#25559A) curving bottom-center beside the Red square
 * 4. Thin vertical Black architectural line (#101010) rising upward from inside the Red square
 * 5. Solid Black circle (#101010) poised to the right of the Blue quarter-circle
 */
@Composable
fun PersonalSpaceLogo(
    modifier: Modifier = Modifier,
    emblemSize: Dp = 96.dp,
    showWordmark: Boolean = true,
    redProgress: Float = 1f,
    blueProgress: Float = 1f,
    yellowProgress: Float = 1f,
    lineProgress: Float = 1f,
    blackProgress: Float = 1f,
    wordmarkAlpha: Float = 1f
) {
    Column(
        modifier = modifier.semantics {
            contentDescription = "Personal Space Bauhaus emblem"
        },
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Canvas(modifier = Modifier.size(emblemSize)) {
            val w = size.width
            val h = size.height

            // Anchor intersection where Red square top-right meets Yellow arc bottom-left & Blue arc top-left
            val pivotX = w * 0.38f
            val pivotY = h * 0.56f

            // 1. Large Yellow quarter-circle (top-right quadrant above pivotY, curving up & right)
            if (yellowProgress > 0f) {
                val p = yellowProgress.coerceIn(0f, 1f)
                val radius = w * 0.54f
                val diameter = radius * 2f
                drawArc(
                    color = BauhausColors.Yellow,
                    startAngle = 180f,
                    sweepAngle = 90f * p,
                    useCenter = true,
                    topLeft = Offset(pivotX, pivotY - radius),
                    size = Size(diameter, diameter)
                )
            }

            // 2. Bold Red square (bottom-left block anchored at pivotX, pivotY)
            if (redProgress > 0f) {
                val p = redProgress.coerceIn(0f, 1f)
                val fullSize = w * 0.30f
                val curH = fullSize * p
                drawRect(
                    color = BauhausColors.Red,
                    topLeft = Offset(pivotX - fullSize, pivotY + (fullSize - curH)),
                    size = Size(fullSize, curH)
                )
            }

            // 3. Blue quarter-circle (curving down & right from pivotX, pivotY alongside Red square)
            if (blueProgress > 0f) {
                val p = blueProgress.coerceIn(0f, 1f)
                val radius = w * 0.30f
                val diameter = radius * 2f
                drawArc(
                    color = BauhausColors.Blue,
                    startAngle = -90f,
                    sweepAngle = 90f * p,
                    useCenter = true,
                    topLeft = Offset(pivotX - radius, pivotY),
                    size = Size(diameter, diameter)
                )
            }

            // 4. Thin vertical Black architectural line rising from inside Red square upward
            val effectiveLine = lineProgress.coerceIn(0f, 1f) * blackProgress.coerceIn(0f, 1f)
            if (effectiveLine > 0f) {
                val lineX = pivotX - w * 0.16f
                val bottomY = pivotY + w * 0.24f
                val topTargetY = h * 0.14f
                val currentTopY = bottomY - (bottomY - topTargetY) * effectiveLine
                drawLine(
                    color = BauhausColors.Black,
                    start = Offset(lineX, bottomY),
                    end = Offset(lineX, currentTopY),
                    strokeWidth = (w * 0.018f).coerceAtLeast(2.5f),
                    cap = StrokeCap.Round
                )
            }

            // 5. Solid Black circle floating beside the Blue quarter-circle below Yellow baseline
            if (blackProgress > 0f) {
                val p = blackProgress.coerceIn(0f, 1f)
                drawCircle(
                    color = BauhausColors.Black,
                    radius = w * 0.115f * p,
                    center = Offset(w * 0.74f, pivotY + w * 0.12f)
                )
            }
        }

        if (showWordmark && wordmarkAlpha > 0f) {
            Spacer(modifier = Modifier.height(10.dp))
            Text(
                text = "Personal Space",
                style = MaterialTheme.typography.headlineMedium.copy(
                    fontWeight = FontWeight.Bold,
                    letterSpacing = 0.4.sp,
                    color = BauhausColors.Black.copy(alpha = wordmarkAlpha.coerceIn(0f, 1f))
                )
            )
        }
    }
}
