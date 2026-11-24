package app.personalspace.vault.presentation.launch

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import app.personalspace.vault.core.designsystem.BauhausColors
import app.personalspace.vault.core.designsystem.LocalReducedMotion
import app.personalspace.vault.core.designsystem.PersonalSpaceLogo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

private val SilkyBauhausEasing = CubicBezierEasing(0.16f, 1.0f, 0.30f, 1.0f)

/**
 * Cinematic, unhurried (~2.5s) Minimal Bauhaus opening choreography matching the uploaded
 * reference artwork (Yellow quarter-circle, Red square, Blue quarter-circle, vertical Black
 * architectural spine, and floating Black circle) with smooth multi-spring & cubic-bezier motion.
 * Concurrently preloads all encrypted Vault & Dashboard data on Dispatchers.IO so Dashboard
 * opens instantly with real totals and zero delayed data flash.
 */
@Composable
fun LaunchAnimationScreen(
    onFinished: () -> Unit,
    preloadTask: (suspend () -> Unit)? = null
) {
    val reducedMotion = LocalReducedMotion.current

    val yellowAnim = remember { Animatable(if (reducedMotion) 1f else 0f) }
    val redAnim = remember { Animatable(if (reducedMotion) 1f else 0f) }
    val blueAnim = remember { Animatable(if (reducedMotion) 1f else 0f) }
    val lineAnim = remember { Animatable(if (reducedMotion) 1f else 0f) }
    val blackDotAnim = remember { Animatable(if (reducedMotion) 1f else 0f) }
    val stageScale = remember { Animatable(if (reducedMotion) 1f else 0.90f) }
    val stageAlpha = remember { Animatable(if (reducedMotion) 1f else 0f) }

    LaunchedEffect(reducedMotion) {
        coroutineScope {
            val preloadDeferred = async(Dispatchers.IO) {
                runCatching { preloadTask?.invoke() }
            }

            if (reducedMotion) {
                delay(150L)
                withTimeoutOrNull(1800L) { preloadDeferred.await() }
                onFinished()
            } else {
                delay(180L)
                coroutineScope {
                    launch {
                        stageAlpha.animateTo(1f, tween(durationMillis = 380, easing = SilkyBauhausEasing))
                    }
                    launch {
                        stageScale.animateTo(
                            targetValue = 1f,
                            animationSpec = tween(durationMillis = 1550, easing = SilkyBauhausEasing)
                        )
                    }
                    launch {
                        yellowAnim.animateTo(
                            targetValue = 1f,
                            animationSpec = tween(durationMillis = 760, easing = SilkyBauhausEasing)
                        )
                    }
                    launch {
                        delay(210L)
                        redAnim.animateTo(
                            targetValue = 1f,
                            animationSpec = tween(durationMillis = 680, easing = SilkyBauhausEasing)
                        )
                    }
                    launch {
                        delay(420L)
                        blueAnim.animateTo(
                            targetValue = 1f,
                            animationSpec = tween(durationMillis = 700, easing = SilkyBauhausEasing)
                        )
                    }
                    launch {
                        delay(640L)
                        lineAnim.animateTo(
                            targetValue = 1f,
                            animationSpec = tween(durationMillis = 720, easing = SilkyBauhausEasing)
                        )
                    }
                    launch {
                        delay(840L)
                        blackDotAnim.animateTo(
                            targetValue = 1f,
                            animationSpec = spring(
                                dampingRatio = 0.66f,
                                stiffness = Spring.StiffnessLow
                            )
                        )
                    }
                }
                delay(480L)
                withTimeoutOrNull(1800L) { preloadDeferred.await() }
                stageAlpha.animateTo(0f, tween(durationMillis = 260, easing = SilkyBauhausEasing))
                onFinished()
            }
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(BauhausColors.Background)
            .windowInsetsPadding(WindowInsets.safeDrawing)
            .testTag("launch_animation_screen"),
        contentAlignment = Alignment.Center
    ) {
        PersonalSpaceLogo(
            modifier = Modifier.graphicsLayer {
                scaleX = stageScale.value
                scaleY = stageScale.value
                alpha = stageAlpha.value
            },
            emblemSize = 276.dp,
            showWordmark = false,
            redProgress = redAnim.value,
            blueProgress = blueAnim.value,
            yellowProgress = yellowAnim.value,
            lineProgress = lineAnim.value,
            blackProgress = blackDotAnim.value,
            wordmarkAlpha = 0f
        )
    }
}
