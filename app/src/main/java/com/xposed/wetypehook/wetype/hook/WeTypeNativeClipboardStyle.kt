package com.xposed.wetypehook.wetype.hook

import android.content.res.Configuration
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.content.res.ColorStateList
import android.view.View
import android.view.ViewGroup
import android.view.ViewTreeObserver
import android.view.ViewOutlineProvider
import com.xposed.wetypehook.wetype.settings.WeTypeSettings
import java.lang.ref.WeakReference
import java.util.WeakHashMap

/** Only styles S15's native clipboard/phrase page; background optics remain owned by the IME. */
internal object WeTypeNativeClipboardStyle {
    private class Session {
        val backgrounds = WeakHashMap<View, Pair<Drawable?, Drawable>>()
        val outlines = WeakHashMap<View, Pair<ViewOutlineProvider?, Boolean>>()
        var observer: ViewTreeObserver? = null
        var draw: ViewTreeObserver.OnPreDrawListener? = null
        var attach: View.OnAttachStateChangeListener? = null
    }
    private val sessions = WeakHashMap<View, Session>()

    fun attach(root: View) {
        if (!WeTypeSettings.readSnapshotXposed().miuiClipboardGlass || sessions.containsKey(root)) return
        val state = Session()
        sessions[root] = state
        val ref = WeakReference(root)
        val draw = ViewTreeObserver.OnPreDrawListener {
            ref.get()?.let { current ->
                if (!WeTypeSettings.readSnapshotXposed().miuiClipboardGlass) restore(current)
                else apply(current, state)
            }
            true
        }
        state.draw = draw
        state.observer = root.viewTreeObserver.also { it.addOnPreDrawListener(draw) }
        val attach = object : View.OnAttachStateChangeListener {
            override fun onViewAttachedToWindow(v: View) = Unit
            override fun onViewDetachedFromWindow(v: View) { restore(v) }
        }
        state.attach = attach
        root.addOnAttachStateChangeListener(attach)
        apply(root, state)
    }

    private fun apply(root: View, state: Session) {
        val dark = root.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK == Configuration.UI_MODE_NIGHT_YES
        fun visit(view: View, inList: Boolean, cardStyled: Boolean = false) {
            val list = generateSequence(view.javaClass as Class<*>) { it.superclass }
                .any { it.simpleName in setOf("RecyclerView", "ListView", "GridView") }
            val background = view.background
            val existing = state.backgrounds[view]
            // Preserve the existing transparent page, tabs, buttons and image thumbnails.
            val card = inList && view is ViewGroup && !list && !cardStyled && background != null
            if (card && background != null && background !== existing?.second) {
                val radius = 20f * view.resources.displayMetrics.density
                val fill = GradientDrawable().apply {
                    cornerRadius = radius
                    setColor(if (dark) 0x38FFFFFF else 0x28FFFFFF)
                }
                val mask = GradientDrawable().apply { cornerRadius = radius; setColor(-1) }
                val replacement = RippleDrawable(ColorStateList.valueOf(if (dark) 0x24FFFFFF else 0x18000000), fill, mask)
                run {
                    state.backgrounds[view] = background to replacement
                    if (!state.outlines.containsKey(view)) {
                        state.outlines[view] = view.outlineProvider to view.clipToOutline
                    }
                    val padding = intArrayOf(view.paddingLeft, view.paddingTop, view.paddingRight, view.paddingBottom)
                    view.background = replacement
                    view.setPadding(padding[0], padding[1], padding[2], padding[3])
                    view.outlineProvider = ViewOutlineProvider.BACKGROUND
                    view.clipToOutline = true
                    view.invalidateOutline()
                    replacement.state = view.drawableState
                    replacement.jumpToCurrentState()
                    view.invalidate()
                }
            }
            if (view is ViewGroup) for (i in 0 until view.childCount) {
                visit(view.getChildAt(i), inList || list, if (list && !inList) false else cardStyled || card)
            }
        }
        visit(root, false)
    }

    private fun restore(root: View) {
        val state = sessions.remove(root) ?: return
        state.draw?.let { state.observer?.takeIf { it.isAlive }?.removeOnPreDrawListener(it) }
        state.attach?.let(root::removeOnAttachStateChangeListener)
        state.backgrounds.forEach { (view, pair) ->
            if (view.background === pair.second) {
                val padding = intArrayOf(view.paddingLeft, view.paddingTop, view.paddingRight, view.paddingBottom)
                view.background = pair.first
                view.setPadding(padding[0], padding[1], padding[2], padding[3])
            }
        }
        state.outlines.forEach { (view, original) ->
            view.outlineProvider = original.first
            view.clipToOutline = original.second
            view.invalidateOutline()
        }
    }

    fun clear() = sessions.keys.toList().forEach(::restore)
}
