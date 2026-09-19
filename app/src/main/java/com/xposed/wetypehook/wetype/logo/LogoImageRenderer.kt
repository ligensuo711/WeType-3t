package com.xposed.wetypehook.wetype.logo

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.PorterDuff
import android.util.Base64
import com.caverock.androidsvg.SVG
import com.xposed.wetypehook.wetype.hook.WeTypeIconDrawable

/**
 * 自定义图片 Logo 的位图渲染（hook 侧与 Compose 预览侧共用，保证所见即所得）。
 *
 * SVG 重着色语义为整体单色 Tint：栅格化后以 SRC_IN 把全部不透明像素染成目标色，
 * 保留原始透明度；PNG 始终原色显示。
 */
object LogoImageRenderer {
    /** Logo 槽位边长（宿主 logo_iv 实测 90x90）：SVG 栅格化与预览统一按槽位输出，所见即所得。 */
    const val LOGO_SLOT_PX = 90

    fun decodePng(base64: String): Bitmap? {
        if (base64.isEmpty()) return null
        return runCatching {
            val bytes = Base64.decode(base64, Base64.NO_WRAP)
            if (bytes.isEmpty()) return null
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
        }.getOrNull()
    }

    /**
     * SVG 栅格化为 [sizePx] 正方形位图：按铺满缩放后居中裁切（centerCrop 语义，
     * 超出部分由画布裁掉），与 hook 侧 CustomLogoDrawable.centerCrop /
     * 预览侧 ContentScale.Crop 完全对齐；输出恒为正方形且无透明留白。
     * 默认按槽位 [LOGO_SLOT_PX] 输出，与真机 1:1。
     */
    fun renderSvg(svgText: String, sizePx: Int = LOGO_SLOT_PX): Bitmap? {
        if (svgText.isEmpty()) return null
        return runCatching {
            val svg = SVG.getFromString(svgText)
            val picture = svg.renderToPicture()
            if (picture.width <= 0 || picture.height <= 0) return null
            val scale = maxOf(
                sizePx / picture.width.toFloat(),
                sizePx / picture.height.toFloat()
            )
            val dstWidth = picture.width * scale
            val dstHeight = picture.height * scale
            val bitmap = Bitmap.createBitmap(sizePx, sizePx, Bitmap.Config.ARGB_8888)
            Canvas(bitmap).apply {
                save()
                translate((sizePx - dstWidth) / 2f, (sizePx - dstHeight) / 2f)
                scale(dstWidth / picture.width.toFloat(), dstHeight / picture.height.toFloat())
                drawPicture(picture)
                restore()
            }
            bitmap
        }.getOrNull()
    }

    /**
     * 整体单色 Tint：全部不透明像素统一染成 [tint]，透明度保持不变。
     * [tint] 为 null 时返回原位图（不复制）。
     */
    fun tintSrcIn(src: Bitmap, tint: Int?): Bitmap {
        if (tint == null) return src
        val out = Bitmap.createBitmap(src.width, src.height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(out)
        val paint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
            colorFilter = android.graphics.PorterDuffColorFilter(tint, PorterDuff.Mode.SRC_IN)
        }
        canvas.drawBitmap(src, 0f, 0f, paint)
        return out
    }

    /**
     * 矢量回退 Logo（现有“微”字样式）渲染到位图，用于预览。
     * 与 hook 侧 [WeTypeIconDrawable] 同一绘制代码，只显式传入颜色，不断言已保存的偏好。
     */
    fun renderVectorFallback(
        accent: Int,
        backgroundAlpha: Int,
        isDark: Boolean,
        backgroundAlphaFraction: Float,
        sizePx: Int
    ): Bitmap {
        val bitmap = Bitmap.createBitmap(sizePx, sizePx, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        WeTypeIconDrawable(
            backgroundAlphaFraction = backgroundAlphaFraction,
            isDark = isDark,
            accentOverride = accent,
            backgroundAlphaOverride = backgroundAlpha
        ).apply {
            setBounds(0, 0, sizePx, sizePx)
            draw(canvas)
        }
        return bitmap
    }

    /** 一级页 Logo 主体颜色：跟随品牌色取主题色，跟随系统按明暗取黑白。 */
    fun resolveAccentColor(
        colorMode: String,
        brandColor: Int,
        customColor: Int,
        isNight: Boolean
    ): Int = when (
        com.xposed.wetypehook.wetype.settings.WeTypeSettings.normalizeLogoColorMode(colorMode)
    ) {
        com.xposed.wetypehook.wetype.settings.WeTypeSettings.LOGO_COLOR_MODE_SYSTEM ->
            if (isNight) Color.WHITE else Color.BLACK
        com.xposed.wetypehook.wetype.settings.WeTypeSettings.LOGO_COLOR_MODE_CUSTOM -> customColor
        else -> brandColor
    }
}
