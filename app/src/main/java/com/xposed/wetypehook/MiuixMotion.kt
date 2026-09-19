package com.xposed.wetypehook

import androidx.compose.animation.ContentTransform
import androidx.compose.animation.core.FiniteAnimationSpec
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.IntOffset

object MiuixMotion {
    /**
     * MIUI / HyperOS 标志性物理阻尼弹簧曲线
     */
    fun <T> springSpec(
        dampingRatio: Float = 0.82f,
        stiffness: Float = Spring.StiffnessMediumLow
    ): FiniteAnimationSpec<T> = spring(
        dampingRatio = dampingRatio,
        stiffness = stiffness
    )

    fun isDetailRoute(key: String): Boolean =
        key !in setOf("home", "function", "beautify", "settings")

    fun tabIndex(key: String): Int = when (key) {
        "home" -> 0
        "function" -> 1
        "beautify" -> 2
        "settings" -> 3
        else -> -1
    }

    /**
     * HyperOS 页面级导航转场规范：
     * 1. Hub -> 二级页：二级页右侧推入，Hub 左侧轻微退移；
     * 2. 二级页 -> Hub：二级页右侧滑出，Hub 左侧回正；
     * 3. 主 Tab 切换：根据相对索引自适应左右横向滑动。
     */
    fun pageTransition(
        initialState: String,
        targetState: String
    ): ContentTransform {
        val enterSpec: FiniteAnimationSpec<IntOffset> = springSpec()
        val exitSpec: FiniteAnimationSpec<IntOffset> = springSpec()
        val fadeSpec: FiniteAnimationSpec<Float> = spring(stiffness = Spring.StiffnessMediumLow)

        val initialIsDetail = isDetailRoute(initialState)
        val targetIsDetail = isDetailRoute(targetState)

        return when {
            // 从 Hub 进入二级页面 (Push)
            !initialIsDetail && targetIsDetail -> {
                (slideInHorizontally(enterSpec) { width -> width } + fadeIn(fadeSpec))
                    .togetherWith(
                        slideOutHorizontally(exitSpec) { width -> -width / 4 } + fadeOut(fadeSpec)
                    )
            }
            // 从二级页面返回 Hub (Pop)
            initialIsDetail && !targetIsDetail -> {
                (slideInHorizontally(enterSpec) { width -> -width / 4 } + fadeIn(fadeSpec))
                    .togetherWith(
                        slideOutHorizontally(exitSpec) { width -> width } + fadeOut(fadeSpec)
                    )
            }
            // 主 Tab 之间切换 (Directional Slide)
            !initialIsDetail && !targetIsDetail -> {
                val direction = if (tabIndex(targetState) >= tabIndex(initialState)) 1 else -1
                (slideInHorizontally(enterSpec) { width -> direction * width } + fadeIn(fadeSpec))
                    .togetherWith(
                        slideOutHorizontally(exitSpec) { width -> -direction * width } + fadeOut(fadeSpec)
                    )
            }
            // 二级页之间切换
            else -> {
                (slideInHorizontally(enterSpec) { width -> width } + fadeIn(fadeSpec))
                    .togetherWith(
                        slideOutHorizontally(exitSpec) { width -> -width } + fadeOut(fadeSpec)
                    )
            }
        }
    }
}

/**
 * MIUI Folme 物理按压微缩放动效修饰符
 */
fun Modifier.miuiPressBounce(
    targetScale: Float = 0.97f
): Modifier = composed {
    var isPressed by remember { mutableStateOf(false) }
    val scale by animateFloatAsState(
        targetValue = if (isPressed) targetScale else 1f,
        animationSpec = spring(
            dampingRatio = 0.72f,
            stiffness = Spring.StiffnessMedium
        ),
        label = "miuiPressBounce"
    )

    this
        .graphicsLayer {
            scaleX = scale
            scaleY = scale
        }
        .pointerInput(Unit) {
            awaitPointerEventScope {
                while (true) {
                    awaitFirstDown(requireUnconsumed = false)
                    isPressed = true
                    waitForUpOrCancellation()
                    isPressed = false
                }
            }
        }
}
