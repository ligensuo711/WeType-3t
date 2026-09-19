package com.xposed.wetypehook

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.Color as ComposeColor
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import com.xposed.wetypehook.wetype.logo.LogoImageRenderer
import com.xposed.wetypehook.wetype.logo.LogoImageStore
import com.xposed.wetypehook.wetype.settings.DARK_KEY_COLOR_GROUP_ID
import com.xposed.wetypehook.wetype.settings.LIGHT_KEY_COLOR_GROUP_ID
import com.xposed.wetypehook.wetype.settings.WeTypeSettings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import top.yukonga.miuix.kmp.basic.BasicComponent
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.basic.SmallTitle
import top.yukonga.miuix.kmp.basic.SmallTopAppBar
import top.yukonga.miuix.kmp.basic.Switch
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.Back
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.theme.darkColorScheme
import top.yukonga.miuix.kmp.theme.lightColorScheme
import top.yukonga.miuix.kmp.utils.overScrollVertical

private const val PREVIEW_BITMAP_EDGE = 192

// 与 WeTypeResourceHooks 的 LOGO_*_BG_ALPHA_FRACTION 保持一致。
private const val PREVIEW_LOGO_LIGHT_BG_FRACTION = 0.9f
private const val PREVIEW_LOGO_DARK_BG_FRACTION = 0.2f

@Composable
internal fun LogoImageApp(
    settingsContext: Context,
    onClose: () -> Unit
) {
    val darkMode = isSystemInDarkTheme()
    MiuixTheme(colors = if (darkMode) darkColorScheme() else lightColorScheme()) {
        SyncSystemBars(darkMode = darkMode)
        LogoImagePage(
            settingsContext = settingsContext,
            onClose = onClose
        )
    }
}

@Composable
internal fun LogoImagePage(
    settingsContext: Context,
    onClose: () -> Unit
) {
    val resolver = settingsContext.contentResolver
    val saved = remember(settingsContext) { WeTypeSettings.readLocalSnapshot(settingsContext) }
    var enabled by remember { mutableStateOf(saved.logoImageEnabled) }
    var imageType by remember {
        mutableStateOf(WeTypeSettings.normalizeLogoImageType(saved.logoImageType))
    }
    var recolor by remember { mutableStateOf(saved.logoSvgRecolorEnabled) }
    var pngBase64 by remember { mutableStateOf(saved.logoImagePngBase64) }
    var svgText by remember { mutableStateOf(saved.logoImageSvgText) }
    var imageName by remember { mutableStateOf(saved.logoImageName) }
    var message by remember { mutableStateOf("") }
    var importing by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    fun persistToggles(failureHint: String = "保存失败，请重试") {
        WeTypeSettings.saveLogoImage(
            settingsContext,
            enabled = enabled,
            imageType = imageType,
            svgRecolorEnabled = recolor,
            onPersisted = { ok -> if (!ok) message = failureHint }
        )
    }

    fun handlePngImport(uri: Uri) {
        if (importing) return
        importing = true
        message = "正在导入 PNG…"
        scope.launch {
            val result = withContext(Dispatchers.IO) {
                LogoImageStore.importPng(resolver, uri)
            }
            result.onSuccess { png ->
                val now = System.currentTimeMillis()
                // 最后上传者胜：PNG 导入后即成为生效类型。
                WeTypeSettings.saveLogoImage(
                    settingsContext,
                    enabled = enabled,
                    imageType = WeTypeSettings.LOGO_IMAGE_TYPE_PNG,
                    svgRecolorEnabled = recolor,
                    pngBase64 = png.base64,
                    imageName = png.name,
                    updatedAt = now,
                    onPersisted = { ok ->
                        if (ok) {
                            imageType = WeTypeSettings.LOGO_IMAGE_TYPE_PNG
                            pngBase64 = png.base64
                            imageName = png.name
                            message = "PNG 已导入并生效：${png.name}"
                        } else {
                            message = "保存失败，请重试"
                        }
                        importing = false
                    }
                )
            }.onFailure { error ->
                message = "导入失败：${error.message ?: "未知错误"}"
                importing = false
            }
        }
    }

    fun handleSvgImport(uri: Uri) {
        if (importing) return
        importing = true
        message = "正在导入 SVG…"
        scope.launch {
            val result = withContext(Dispatchers.IO) {
                LogoImageStore.importSvg(resolver, uri)
            }
            result.onSuccess { svg ->
                val now = System.currentTimeMillis()
                WeTypeSettings.saveLogoImage(
                    settingsContext,
                    enabled = enabled,
                    imageType = WeTypeSettings.LOGO_IMAGE_TYPE_SVG,
                    svgRecolorEnabled = recolor,
                    svgText = svg.text,
                    imageName = svg.name,
                    updatedAt = now,
                    onPersisted = { ok ->
                        if (ok) {
                            imageType = WeTypeSettings.LOGO_IMAGE_TYPE_SVG
                            svgText = svg.text
                            imageName = svg.name
                            message = "SVG 已导入并生效：${svg.name}"
                        } else {
                            message = "保存失败，请重试"
                        }
                        importing = false
                    }
                )
            }.onFailure { error ->
                message = "导入失败：${error.message ?: "未知错误"}"
                importing = false
            }
        }
    }

    fun activateType(type: String) {
        imageType = type
        message = ""
        persistToggles()
    }

    fun clearImages() {
        WeTypeSettings.saveLogoImage(
            settingsContext,
            enabled = enabled,
            imageType = imageType,
            svgRecolorEnabled = recolor,
            pngBase64 = "",
            svgText = "",
            imageName = "",
            updatedAt = 0L,
            onPersisted = { ok ->
                if (ok) {
                    pngBase64 = ""
                    svgText = ""
                    imageName = ""
                    message = "已清除自定义图片，恢复矢量 Logo"
                } else {
                    message = "保存失败，请重试"
                }
            }
        )
    }

    val hostActivity = settingsContext as? Activity

    fun launchPickPng() {
        if (hostActivity == null) {
            message = "无法获取宿主窗口，请重试"
            return
        }
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "image/png"
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        runCatching {
            hostActivity.startActivityForResult(intent, WeTypeHostActivityResultBridge.REQUEST_PICK_LOGO_PNG)
        }.onFailure { message = "无法打开文件选择器" }
    }

    fun launchPickSvg() {
        if (hostActivity == null) {
            message = "无法获取宿主窗口，请重试"
            return
        }
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "image/svg+xml"
            putExtra(
                Intent.EXTRA_MIME_TYPES,
                arrayOf("image/svg+xml", "image/svg", "text/xml")
            )
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        runCatching {
            hostActivity.startActivityForResult(intent, WeTypeHostActivityResultBridge.REQUEST_PICK_LOGO_SVG)
        }.onFailure { message = "无法打开文件选择器" }
    }

    DisposableEffect(hostActivity) {
        WeTypeHostActivityResultBridge.register(
            WeTypeHostActivityResultBridge.REQUEST_PICK_LOGO_PNG
        ) { resultCode, data ->
            val uri = if (resultCode == Activity.RESULT_OK) data?.data else null
            if (uri != null) handlePngImport(uri)
        }
        WeTypeHostActivityResultBridge.register(
            WeTypeHostActivityResultBridge.REQUEST_PICK_LOGO_SVG
        ) { resultCode, data ->
            val uri = if (resultCode == Activity.RESULT_OK) data?.data else null
            if (uri != null) handleSvgImport(uri)
        }
        onDispose {
            WeTypeHostActivityResultBridge.unregister(WeTypeHostActivityResultBridge.REQUEST_PICK_LOGO_PNG)
            WeTypeHostActivityResultBridge.unregister(WeTypeHostActivityResultBridge.REQUEST_PICK_LOGO_SVG)
        }
    }

    val isPng = imageType == WeTypeSettings.LOGO_IMAGE_TYPE_PNG
    val nameSuffix = imageName.takeIf { it.isNotEmpty() }?.let { "：$it" }.orEmpty()
    val pngSummary = when {
        pngBase64.isEmpty() -> "未上传，点击选择 PNG 图片（原色显示）"
        isPng -> "生效中$nameSuffix，点击重新选择"
        else -> "已上传，点击切换为 PNG 生效"
    }
    val svgSummary = when {
        svgText.isEmpty() -> "未上传，点击选择 SVG 图片"
        !isPng -> "生效中$nameSuffix，点击重新选择"
        else -> "已上传，点击切换为 SVG 生效"
    }

    val lightPreview = remember(enabled, imageType, recolor, pngBase64, svgText, saved) {
        renderPreviewBitmap(saved, enabled, imageType, recolor, pngBase64, svgText, isNight = false)
    }
    val darkPreview = remember(enabled, imageType, recolor, pngBase64, svgText, saved) {
        renderPreviewBitmap(saved, enabled, imageType, recolor, pngBase64, svgText, isNight = true)
    }
    val lightKeyColor = saved.appearanceColors[LIGHT_KEY_COLOR_GROUP_ID] ?: 0xABFFFFFF.toInt()
    val darkKeyColor = saved.appearanceColors[DARK_KEY_COLOR_GROUP_ID] ?: 0x2BEDEDED.toInt()

    Scaffold(
        modifier = Modifier
            .fillMaxSize()
            .consumeWindowInsets(WindowInsets.systemBars.only(WindowInsetsSides.Top))
            .background(MiuixTheme.colorScheme.background),
        topBar = {
            SmallTopAppBar(
                title = "自定义图片 Logo",
                navigationIcon = {
                    IconButton(onClick = onClose) {
                        Icon(
                            imageVector = MiuixIcons.Back,
                            contentDescription = "返回"
                        )
                    }
                }
            )
        }
    ) { paddingValues ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .overScrollVertical()
                .imePadding(),
            contentPadding = PaddingValues(
                top = paddingValues.calculateTopPadding(),
                bottom = 40.dp
            ),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            item {
                Card(
                    modifier = Modifier.padding(horizontal = 16.dp),
                    insideMargin = PaddingValues(16.dp)
                ) {
                    Text(
                        text = "PNG 原色显示，不跟随系统/品牌色；SVG 可开启重着色，跟随一级页 Logo 主体颜色。PNG 与 SVG 可各存一张，最后上传者生效，点击未生效的一行可直接切换。",
                        style = MiuixTheme.textStyles.body2,
                        color = MiuixTheme.colorScheme.onSurfaceVariantSummary
                    )
                }
            }
            item {
                SmallTitle(text = "图片替换")
            }
            item {
                Card(
                    modifier = Modifier.padding(horizontal = 16.dp),
                    insideMargin = PaddingValues(0.dp)
                ) {
                    Column {
                        BasicComponent(
                            title = "替换为图片",
                            summary = "关闭则用回矢量 Logo，已上传文件保留",
                            endActions = {
                                Switch(
                                    checked = enabled,
                                    onCheckedChange = {
                                        enabled = it
                                        message = ""
                                        persistToggles()
                                    }
                                )
                            },
                            onClick = {
                                enabled = !enabled
                                message = ""
                                persistToggles()
                            }
                        )
                        BasicComponent(
                            title = "替换为 PNG",
                            summary = pngSummary,
                            onClick = {
                                if (pngBase64.isNotEmpty() && !isPng) {
                                    activateType(WeTypeSettings.LOGO_IMAGE_TYPE_PNG)
                                } else {
                                    launchPickPng()
                                }
                            }
                        )
                        BasicComponent(
                            title = "替换为 SVG",
                            summary = svgSummary,
                            onClick = {
                                if (svgText.isNotEmpty() && isPng) {
                                    activateType(WeTypeSettings.LOGO_IMAGE_TYPE_SVG)
                                } else {
                                    launchPickSvg()
                                }
                            }
                        )
                        BasicComponent(
                            title = "替换 SVG 颜色",
                            summary = "开启后 SVG 整体染成一级页 Logo 主体颜色（品牌色/自定义/系统黑白）；关闭显示原色",
                            endActions = {
                                Switch(
                                    checked = recolor,
                                    onCheckedChange = {
                                        recolor = it
                                        message = ""
                                        persistToggles()
                                    }
                                )
                            },
                            onClick = {
                                recolor = !recolor
                                message = ""
                                persistToggles()
                            }
                        )
                        BasicComponent(
                            title = "清除已上传图片",
                            summary = "删除 PNG 与 SVG，恢复矢量 Logo",
                            onClick = { clearImages() }
                        )
                    }
                }
            }
            item {
                SmallTitle(text = "效果预览")
            }
            item {
                Card(
                    modifier = Modifier.padding(horizontal = 16.dp),
                    insideMargin = PaddingValues(16.dp)
                ) {
                    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        LogoPreviewStrip(
                            label = "浅色模式",
                            backgroundColor = saved.lightColor,
                            keyColor = lightKeyColor,
                            logo = lightPreview
                        )
                        LogoPreviewStrip(
                            label = "深色模式",
                            backgroundColor = saved.darkColor,
                            keyColor = darkKeyColor,
                            logo = darkPreview
                        )
                        Text(
                            text = if (enabled) {
                                "主体颜色跟随一级页设置实时预览"
                            } else {
                                "总开关关闭，当前显示矢量 Logo"
                            },
                            style = MiuixTheme.textStyles.body2,
                            color = MiuixTheme.colorScheme.onSurfaceVariantSummary
                        )
                    }
                }
            }
            if (importing || message.isNotEmpty()) {
                item {
                    Card(
                        modifier = Modifier.padding(horizontal = 16.dp),
                        insideMargin = PaddingValues(16.dp)
                    ) {
                        Text(
                            text = message,
                            style = MiuixTheme.textStyles.body2,
                            color = MiuixTheme.colorScheme.onSurfaceVariantSummary
                        )
                    }
                }
            }
        }
    }
}

private fun previewAccent(snapshot: WeTypeSettings.Snapshot, isNight: Boolean): Int =
    LogoImageRenderer.resolveAccentColor(
        colorMode = snapshot.logoColorMode,
        brandColor = snapshot.appearanceColors["theme_color"]
            ?: WeTypeSettings.DEFAULT_LOGO_CUSTOM_COLOR,
        customColor = snapshot.logoCustomColor,
        isNight = isNight
    )

/**
 * 与 hook 侧同一渲染路径：自定义图片优先（PNG 原色 / SVG 可选 Tint），
 * 未启用或无图/解码失败则回退矢量 Logo。
 */
private fun renderPreviewBitmap(
    snapshot: WeTypeSettings.Snapshot,
    enabled: Boolean,
    imageType: String,
    recolor: Boolean,
    pngBase64: String,
    svgText: String,
    isNight: Boolean
): Bitmap {
    if (enabled) {
        val type = WeTypeSettings.normalizeLogoImageType(imageType)
        if (type == WeTypeSettings.LOGO_IMAGE_TYPE_SVG && svgText.isNotEmpty()) {
            // 按槽位栅格化（默认 LOGO_SLOT_PX），与 hook 侧同一份裁切数学，所见即所得。
            LogoImageRenderer.renderSvg(svgText)?.let { bitmap ->
                val tint = if (recolor) previewAccent(snapshot, isNight) else null
                return LogoImageRenderer.tintSrcIn(bitmap, tint)
            }
        }
        if (type == WeTypeSettings.LOGO_IMAGE_TYPE_PNG && pngBase64.isNotEmpty()) {
            LogoImageRenderer.decodePng(pngBase64)?.let { return it }
        }
    }
    return LogoImageRenderer.renderVectorFallback(
        accent = previewAccent(snapshot, isNight),
        backgroundAlpha = snapshot.toolbarIconBgOpacity,
        isDark = isNight,
        backgroundAlphaFraction = if (isNight) {
            PREVIEW_LOGO_DARK_BG_FRACTION
        } else {
            PREVIEW_LOGO_LIGHT_BG_FRACTION
        },
        sizePx = PREVIEW_BITMAP_EDGE
    )
}

@Composable
private fun LogoPreviewStrip(
    label: String,
    backgroundColor: Int,
    keyColor: Int,
    logo: Bitmap
) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(
            text = label,
            style = MiuixTheme.textStyles.body2,
            color = MiuixTheme.colorScheme.onSurfaceVariantSummary
        )
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(84.dp)
                .clip(RoundedCornerShape(18.dp))
                .background(ComposeColor(backgroundColor))
                .padding(12.dp),
            contentAlignment = Alignment.CenterStart
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Image(
                    bitmap = logo.asImageBitmap(),
                    contentDescription = null,
                    modifier = Modifier.size(40.dp),
                    // 与 hook 侧 CustomLogoDrawable.centerCrop 同语义：方形槽位居中裁切，保证所见即所得。
                    contentScale = ContentScale.Crop
                )
                repeat(3) {
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .height(40.dp)
                            .clip(RoundedCornerShape(10.dp))
                            .background(ComposeColor(keyColor))
                    )
                }
            }
        }
    }
}
