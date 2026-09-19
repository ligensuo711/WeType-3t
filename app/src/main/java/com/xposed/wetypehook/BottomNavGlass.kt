package com.xposed.wetypehook

import android.view.HapticFeedbackConstants
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.kyant.backdrop.backdrops.layerBackdrop
import com.kyant.backdrop.backdrops.rememberLayerBackdrop
import com.kyant.backdrop.Backdrop
import com.kyant.backdrop.drawBackdrop
import com.kyant.backdrop.effects.blur
import com.kyant.backdrop.effects.runtimeShaderEffect
import com.kyant.backdrop.effects.vibrancy
import com.xposed.wetypehook.liquidglass.LiquidBottomBar
import top.yukonga.miuix.kmp.basic.NavigationBar
import top.yukonga.miuix.kmp.basic.NavigationBarItem
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.theme.MiuixTheme

/**
 * 液态玻璃铬层：内容全 bleed，顶栏（高斯模糊）与底栏以 overlay 盖在内容上，采样同一份 backdrop。
 * 顶栏高度运行时实测（只增不减，滚动收起时不跳动），经 [content] 的 [topPadding] 参数回传。
 *
 * @param enableLiquidGlass 底栏是否加 lens 折射（液态玻璃）；关闭则退化为普通高斯模糊。
 * @param blurEnabled false 则顶栏/底栏全部实色，不做任何模糊。
 * @param floatingBar false 则底栏改用 docked 全宽样式（含文字标签）。
 * @param darkMode 当前 App 主题（跟随外观模式开关，不再内部读系统）。
 */
@Composable
fun GlassChromeScaffold(
    enableLiquidGlass: Boolean,
    blurEnabled: Boolean,
    floatingBar: Boolean,
    darkMode: Boolean,
    showBottomBar: Boolean = true,
    bottomBarHaptic: Boolean = true,
    topBarProgressiveBlur: Boolean = true,
    modifier: Modifier = Modifier,
    topBar: @Composable (backdrop: Backdrop) -> Unit,
    tabs: List<BottomTab>,
    selectedIndex: Int,
    onSelect: (Int) -> Unit,
    content: @Composable (contentModifier: Modifier, topPadding: Dp) -> Unit
) {
    // 底色跟随主题，避免 backdrop 采样到透明像素。
    // 浅色用浅灰而非纯白：Miuix 浅色 background 与卡片 surfaceContainer 都是纯白，不压深分不开。
    val backgroundColor =
        if (darkMode) Color.Black else Color(0xFFEFF0F4)
    val backdrop = rememberLayerBackdrop {
        drawRect(backgroundColor)
        drawContent()
    }
    val veil =
        if (darkMode) Color.Black.copy(alpha = 0.45f) else Color.White.copy(alpha = 0.5f)
    // 高斯分支（顶栏/docked）不用提亮，只留纯模糊，边缘不留高光圈。
    val flatVeil =
        if (darkMode) Color.Black.copy(alpha = 0.3f) else Color.White.copy(alpha = 0.3f)
    val density = LocalDensity.current
    var topChromeHeightPx by remember { mutableIntStateOf(0) }
    val topPadding = with(density) { topChromeHeightPx.toDp() }
    // Miuix Overlay 弹窗（如下拉框）需要 Scaffold 提供的 dialog 宿主，这里套一层透明空 Scaffold。
    // 注意：内部缩进保持原样（少一级），仅为减少 diff，功能不受影响。
    Scaffold(
        modifier = modifier,
        containerColor = Color.Transparent
    ) {
    Box(Modifier.fillMaxSize().background(backgroundColor)) {
        content(Modifier.layerBackdrop(backdrop), topPadding)
        // 顶栏：悬浮模式下常开高斯模糊；docked 模式跟随模糊开关。
        // onSizeChanged 必须在 statusBarsPadding 外侧，否则量不到状态栏高度；
        // 玻璃/实色都盖到状态栏底下，系统栏本身透明（见 SyncSystemBars）。
        // highlight/shadow/innerShadow 显式置空：库默认值会留一圈高光描边。
        // 取实时高度（Miuix 栏收起时 layout 真的变矮）：内容顶距实时跟随，栏底和内容永远贴合。
        if (floatingBar || blurEnabled) {
            Box(
                Modifier
                    .align(Alignment.TopCenter)
                    .onSizeChanged { topChromeHeightPx = it.height }
                    .drawBackdrop(
                        backdrop = backdrop,
                        shape = { RectangleShape },
                        effects = {
                            blur(10.dp.toPx())
                            if (topBarProgressiveBlur) {
                                runtimeShaderEffect(
                                    key = "TopBarProgressiveBlur",
                                    shaderString = """
uniform shader content;
uniform float2 size;

half4 main(float2 coord) {
    float blurAlpha = 1.0 - smoothstep(size.y * 0.7, size.y, coord.y);
    return content.eval(coord) * blurAlpha;
}
""",
                                    uniformShaderName = "content"
                                ) {
                                    setFloatUniform("size", size.width, size.height)
                                }
                            }
                        },
                        highlight = { null },
                        shadow = { null },
                        innerShadow = { null },
                        onDrawSurface = {
                            if (topBarProgressiveBlur) {
                                drawRect(
                                    brush = Brush.verticalGradient(
                                        colorStops = arrayOf(
                                            0.0f to flatVeil,
                                            0.7f to flatVeil,
                                            1.0f to Color.Transparent
                                        )
                                    )
                                )
                            } else {
                                drawRect(flatVeil)
                            }
                        }
                    )
                    .statusBarsPadding()
            ) {
                topBar(backdrop)
            }
        } else {
            Box(
                Modifier
                    .align(Alignment.TopCenter)
                    .onSizeChanged { topChromeHeightPx = it.height }
                    .background(MiuixTheme.colorScheme.surface)
                    .statusBarsPadding()
            ) {
                topBar(backdrop)
            }
        }
        // 底栏：悬浮态一律走自研交互液态组件（液态关闭则其内部退化为静态高斯）；
        // docked 态才看模糊开关（开=高斯，关=实色）。
        // 进入二级页面时通过 showBottomBar = false 控制底栏向下滑出隐藏。
        AnimatedVisibility(
            visible = showBottomBar,
            enter = slideInVertically(
                animationSpec = spring(
                    dampingRatio = 0.85f,
                    stiffness = Spring.StiffnessMediumLow
                )
            ) { it } + fadeIn(spring(stiffness = Spring.StiffnessMediumLow)),
            exit = slideOutVertically(
                animationSpec = spring(
                    stiffness = Spring.StiffnessMediumLow
                )
            ) { it } + fadeOut(spring(stiffness = Spring.StiffnessMediumLow)),
            modifier = Modifier.align(Alignment.BottomCenter)
        ) {
            if (floatingBar) {
                LiquidBottomBar(
                    backdrop = backdrop,
                    tabs = tabs,
                    selectedIndex = selectedIndex,
                    onSelect = onSelect,
                    darkMode = darkMode,
                    liquid = enableLiquidGlass,
                    haptic = bottomBarHaptic,
                    modifier = Modifier
                        .fillMaxWidth()
                        .navigationBarsPadding()
                        .padding(horizontal = 36.dp)
                        .padding(top = 8.dp, bottom = 11.dp)
                )
            } else {
                if (blurEnabled) {
                    Box(
                        Modifier.drawBackdrop(
                            backdrop = backdrop,
                            shape = { RectangleShape },
                            effects = {
                                blur(10.dp.toPx())
                            },
                            highlight = { null },
                            shadow = { null },
                            innerShadow = { null },
                            onDrawSurface = { drawRect(flatVeil) }
                        )
                    ) {
                        NavigationBar(
                            color = Color.Transparent,
                            showDivider = false
                        ) {
                            DockedBarItems(tabs, selectedIndex, onSelect, bottomBarHaptic)
                        }
                    }
                } else {
                    NavigationBar(
                        showDivider = false
                    ) {
                        DockedBarItems(tabs, selectedIndex, onSelect, bottomBarHaptic)
                    }
                }
            }
        }
    }
    }
}

@Composable
private fun RowScope.DockedBarItems(
    tabs: List<BottomTab>,
    selectedIndex: Int,
    onSelect: (Int) -> Unit,
    haptic: Boolean = true
) {
    val view = LocalView.current
    tabs.forEachIndexed { index, tab ->
        NavigationBarItem(
            selected = selectedIndex == index,
            onClick = {
                if (haptic && selectedIndex != index) {
                    view.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                }
                onSelect(index)
            },
            icon = tab.icon,
            label = tab.label
        )
    }
}

data class BottomTab(
    val label: String,
    val icon: ImageVector
)
