package com.xposed.wetypehook.liquidglass

// Bottom bar adapted from Kyant0/AndroidLiquidGlass (Apache-2.0):
// app/src/commonMain/kotlin/com/kyant/backdrop/catalog/components/LiquidBottomTabs.kt
// Changes vs upstream:
// - Lives in our package with our BottomTab model ([com.xposed.wetypehook.BottomTab]).
// - Capsule() (sibling shapes library) replaced with CircleShape to avoid a new dependency.
// - Light/dark driven by the [darkMode] parameter (our 外观模式) instead of isSystemInDarkTheme().
// - Tab cells rendered internally (selected = theme primary); the press-scale
//   CompositionLocal garnish is dropped, the sliding lens thumb keeps full physics.

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.EaseOut
import androidx.compose.animation.core.spring
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.ui.draw.shadow
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import android.view.HapticFeedbackConstants
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.util.fastCoerceIn
import androidx.compose.ui.util.fastRoundToInt
import androidx.compose.ui.util.lerp
import com.kyant.backdrop.Backdrop
import com.kyant.backdrop.backdrops.layerBackdrop
import com.kyant.backdrop.backdrops.rememberCombinedBackdrop
import com.kyant.backdrop.backdrops.rememberLayerBackdrop
import com.kyant.backdrop.drawBackdrop
import com.kyant.backdrop.effects.blur
import com.kyant.backdrop.effects.lens
import com.kyant.backdrop.effects.vibrancy
import com.kyant.backdrop.highlight.Highlight
import com.kyant.backdrop.shadow.InnerShadow
import com.kyant.backdrop.shadow.Shadow
import com.xposed.wetypehook.BottomTab
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.launch
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.theme.MiuixTheme
import kotlin.math.abs
import kotlin.math.sign

@Composable
fun LiquidBottomBar(
    backdrop: Backdrop,
    tabs: List<BottomTab>,
    selectedIndex: Int,
    onSelect: (index: Int) -> Unit,
    darkMode: Boolean,
    liquid: Boolean,
    modifier: Modifier = Modifier,
    haptic: Boolean = true
) {
    val tabsCount = tabs.size
    if (tabsCount == 0) return
    val accentColor =
        if (!darkMode) Color(0xFF3875E8)
        else Color(0xFF4C8DFF)
    val unselectedColor =
        if (!darkMode) Color.Black
        else Color.White
    val containerColor =
        if (!darkMode) Color(0xFFFAFAFA).copy(alpha = 0.12f)
        else Color(0xFF121212).copy(alpha = 0.12f)

    val tabsBackdrop = rememberLayerBackdrop()

    BoxWithConstraints(
        modifier,
        contentAlignment = Alignment.CenterStart
    ) {
        val density = LocalDensity.current
        val view = LocalView.current
        val tabWidth = with(density) {
            (constraints.maxWidth.toFloat() - 8f.dp.toPx()) / tabsCount
        }

        val offsetAnimation = remember { Animatable(0f) }
        val panelOffset by remember(density) {
            derivedStateOf {
                val fraction = (offsetAnimation.value / constraints.maxWidth).fastCoerceIn(-1f, 1f)
                with(density) {
                    4f.dp.toPx() * fraction.sign * EaseOut.transform(abs(fraction))
                }
            }
        }

        val isLtr = LocalLayoutDirection.current == LayoutDirection.Ltr
        val animationScope = rememberCoroutineScope()
        var currentIndex by remember { mutableIntStateOf(selectedIndex.coerceIn(0, tabsCount - 1)) }
        var lastHapticIndex by remember { mutableIntStateOf(selectedIndex.coerceIn(0, tabsCount - 1)) }
        val dampedDragAnimation = remember(animationScope) {
            DampedDragAnimation(
                animationScope = animationScope,
                initialValue = selectedIndex.coerceIn(0, tabsCount - 1).toFloat(),
                valueRange = 0f..(tabsCount - 1).toFloat(),
                visibilityThreshold = 0.001f,
                initialScale = 1f,
                pressedScale = 78f / 56f,
                onDragStarted = {},
                onDragStopped = {
                    val targetIndex = targetValue.fastRoundToInt().fastCoerceIn(0, tabsCount - 1)
                    currentIndex = targetIndex
                    animateToValue(targetIndex.toFloat())
                    animationScope.launch {
                        offsetAnimation.animateTo(
                            0f,
                            spring(1f, 300f, 0.5f)
                        )
                    }
                },
                onDrag = { _, dragAmount ->
                    updateValue(
                        (targetValue + dragAmount.x / tabWidth * if (isLtr) 1f else -1f)
                            .fastCoerceIn(0f, (tabsCount - 1).toFloat())
                    )
                    val dragIndex = targetValue.fastRoundToInt().fastCoerceIn(0, tabsCount - 1)
                    if (dragIndex != lastHapticIndex) {
                        lastHapticIndex = dragIndex
                        if (haptic) {
                            view.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
                        }
                    }
                    animationScope.launch {
                        offsetAnimation.snapTo(offsetAnimation.value + dragAmount.x)
                    }
                }
            )
        }
        LaunchedEffect(selectedIndex) {
            currentIndex = selectedIndex.coerceIn(0, tabsCount - 1)
            lastHapticIndex = currentIndex
        }
        LaunchedEffect(dampedDragAnimation) {
            snapshotFlow { currentIndex }
                .drop(1)
                .collectLatest { index ->
                    if (haptic) {
                        view.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                    }
                    lastHapticIndex = index
                    dampedDragAnimation.animateToValue(index.toFloat())
                    onSelect(index)
                }
        }

        val interactiveHighlight = remember(animationScope) {
            InteractiveHighlight(
                animationScope = animationScope,
                position = { size, offset ->
                    Offset(
                        if (isLtr) (dampedDragAnimation.value + 0.5f) * tabWidth + panelOffset
                        else size.width - (dampedDragAnimation.value + 0.5f) * tabWidth + panelOffset,
                        size.height / 2f
                    )
                }
            )
        }

        // 底层：整条胶囊玻璃 + 图标文案。格子自带点击（滑块只盖住选中项，其余 tap 落到这里）。
        Row(
            Modifier
                .graphicsLayer {
                    translationX = panelOffset
                }
                .drawBackdrop(
                    backdrop = backdrop,
                    shape = { CircleShape },
                    effects = {
                        vibrancy()
                        blur(8f.dp.toPx())
                        if (liquid) lens(24f.dp.toPx(), 24f.dp.toPx())
                    },
                    layerBlock = {
                        val progress = dampedDragAnimation.pressProgress
                        val scale = lerp(1f, 1f + 16f.dp.toPx() / size.width, progress)
                        scaleX = scale
                        scaleY = scale
                    },
                    onDrawSurface = { drawRect(containerColor) }
                )
                .then(interactiveHighlight.modifier)
                .height(64f.dp)
                .fillMaxWidth()
                .padding(4f.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            tabs.forEachIndexed { index, tab ->
                Box(
                    Modifier
                        .weight(1f)
                        .clickable(
                            interactionSource = remember { MutableInteractionSource() },
                            indication = null
                        ) {
                            if (index != currentIndex) currentIndex = index
                        },
                    contentAlignment = Alignment.Center
                ) {
                    TabCell(
                        tab = tab,
                        selected = index == currentIndex,
                        accentColor = accentColor,
                        unselectedColor = unselectedColor
                    )
                }
            }
        }

        // 中层：不可见的图标层，供滑块做联合采样（与上游同构）。
        Row(
            Modifier
                .clearAndSetSemantics {}
                .alpha(0f)
                .layerBackdrop(tabsBackdrop)
                .graphicsLayer {
                    translationX = panelOffset
                }
                .drawBackdrop(
                    backdrop = backdrop,
                    shape = { CircleShape },
                    effects = {
                        val progress = dampedDragAnimation.pressProgress
                        vibrancy()
                        blur(8f.dp.toPx())
                        lens(
                            24f.dp.toPx() * progress,
                            24f.dp.toPx() * progress
                        )
                    },
                    highlight = {
                        val progress = dampedDragAnimation.pressProgress
                        Highlight.Default.copy(alpha = progress)
                    },
                    onDrawSurface = { drawRect(containerColor) }
                )
                .then(interactiveHighlight.modifier)
                .height(56f.dp)
                .fillMaxWidth()
                .padding(horizontal = 4f.dp)
                .graphicsLayer(colorFilter = ColorFilter.tint(accentColor)),
            verticalAlignment = Alignment.CenterVertically
        ) {
            tabs.forEachIndexed { index, tab ->
                Box(
                    Modifier.weight(1f),
                    contentAlignment = Alignment.Center
                ) {
                    TabCell(
                        tab = tab,
                        selected = index == currentIndex,
                        accentColor = accentColor,
                        unselectedColor = unselectedColor,
                        forSamplingOnly = true
                    )
                }
            }
        }

        // 顶层：跟手/跟选中的折射滑块（高斯模式下静默，仅保留点选滑动）。
        val dragModifier =
            if (liquid) dampedDragAnimation.modifier else Modifier
        val highlightGestureModifier =
            if (liquid) interactiveHighlight.gestureModifier else Modifier
        Box(
            Modifier
                .padding(horizontal = 4f.dp)
                .graphicsLayer {
                    translationX =
                        if (isLtr) dampedDragAnimation.value * tabWidth + panelOffset
                        else size.width - (dampedDragAnimation.value + 1f) * tabWidth + panelOffset
                }
                .then(highlightGestureModifier)
                .then(dragModifier)
                .drawBackdrop(
                    backdrop = rememberCombinedBackdrop(backdrop, tabsBackdrop),
                    shape = { CircleShape },
                    effects = {
                        if (liquid) {
                            val progress = dampedDragAnimation.pressProgress
                            lens(
                                10f.dp.toPx() * progress,
                                14f.dp.toPx() * progress,
                                chromaticAberration = true
                            )
                        }
                    },
                    highlight = {
                        if (liquid) {
                            val progress = dampedDragAnimation.pressProgress
                            Highlight.Default.copy(alpha = progress)
                        } else null
                    },
                    shadow = {
                        if (liquid) {
                            val progress = dampedDragAnimation.pressProgress
                            Shadow(alpha = progress)
                        } else null
                    },
                    innerShadow = {
                        if (liquid) {
                            val progress = dampedDragAnimation.pressProgress
                            InnerShadow(
                                radius = 8f.dp * progress,
                                alpha = progress
                            )
                        } else null
                    },
                    layerBlock = {
                        scaleX = dampedDragAnimation.scaleX
                        scaleY = dampedDragAnimation.scaleY
                        val velocity = dampedDragAnimation.velocity / 10f
                        scaleX /= 1f - (velocity * 0.75f).fastCoerceIn(-0.2f, 0.2f)
                        scaleY *= 1f - (velocity * 0.25f).fastCoerceIn(-0.2f, 0.2f)
                    },
                    onDrawSurface = {
                        val progress = dampedDragAnimation.pressProgress
                        drawRect(
                            if (!darkMode) Color.Black.copy(0.1f)
                            else Color.White.copy(0.1f),
                            alpha = 1f - progress
                        )
                        drawRect(Color.Black.copy(alpha = 0.03f * progress))
                    }
                )
                .height(56f.dp)
                .fillMaxWidth(1f / tabsCount)
        )
    }
}

@Composable
private fun TabCell(
    tab: BottomTab,
    selected: Boolean,
    accentColor: Color,
    unselectedColor: Color,
    forSamplingOnly: Boolean = false
) {
    val tint = if (selected) accentColor else unselectedColor
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(2.dp)
    ) {
        Image(
            imageVector = tab.icon,
            contentDescription = if (forSamplingOnly) null else tab.label,
            modifier = Modifier.size(26.dp),
            colorFilter = ColorFilter.tint(tint)
        )
        Text(
            text = tab.label,
            color = tint,
            fontSize = 11.sp,
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Medium,
            maxLines = 1
        )
    }
}
