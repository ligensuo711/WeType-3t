package com.xposed.wetypehook.wetype.hook

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorFilter
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.PorterDuff
import android.graphics.PorterDuffColorFilter
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.text.Layout
import android.text.Spannable
import android.text.SpannableStringBuilder
import android.text.style.ForegroundColorSpan
import android.text.style.StyleSpan
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.InputConnection
import android.view.inputmethod.InputConnectionWrapper
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.xposed.wetypehook.wetype.clipboard.BeautyClipboardSearch
import com.xposed.wetypehook.wetype.clipboard.ClipboardDb
import com.xposed.wetypehook.wetype.settings.WeTypeSettings
import com.xposed.wetypehook.xposed.Log
import com.xposed.wetypehook.xposed.hookAfter
import org.luckypray.dexkit.DexKitBridge
import java.lang.ref.WeakReference
import java.lang.reflect.Method
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.max
import kotlin.math.min

/**
 * 剪贴板搜索（WeType 美化 1.28.4 ClipboardSearchHooks 机制复刻，日志码 LCS-001~017 对齐）。
 *
 * 链路：
 * 1. DexKit 定位 `S34ClipboardBombKeyboard` / `S15CustomPhraseAndClipboardKeyboard`
 *    的 void 方法（clip_keyboard_hook, LCS-003），after 回调里向剪贴板键盘的
 *    TabContainerView 旁注入放大镜入口（tag `wetype_clipboard_search_entry`，
 *    初始 INVISIBLE+alpha0，布局就绪后淡入, LCS-004 entry_injected）。
 * 2. 入口点击 = 点宿主原生返回键（反射 S34#getBackBtnContainerRl()/getIvBackBtn()
 *    + performClick，回字母键盘）+ 打开搜索面板（LCS-007 panel_shown）。
 * 3. 面板：顶栏（返回 + 查询显示「| 输入关键词筛选…」+ ✕）+ ScrollView 结果区，
 *    tag `wetype_clipboard_keyboard_top_panel`；底边距按 IMS mInputFrame/mCandidatesFrame
 *    计算（LCS-010），悬于字母键盘上方。
 * 4. 输入：hook `InputMethodService#getCurrentInputConnection`（LCS-002），搜索激活时
 *    包一层 InputConnectionWrapper——commitText/setComposingText 进查询串、
 *    deleteSurroundingText 退格，全部吞掉不落编辑框；退出后原样放行。
 * 5. 数据：宿主 `ime_database` 的 `clipboard_record` 直读（时间倒序）。
 * 6. 结果行 tag `wetype_clipboard_row_text`，高亮命中区间；点击 commit_item
 *    （LCS-008）真 InputConnection commitText 上屏并收面板。
 * 7. 返回键 / onWindowHidden / onFinishInputView → exit_search（LCS-009）。
 * 8. 拼音索引与转写预热（clipboard-pinyin-index / clipboard-search-prewarm，LCS-017）。
 */
internal object WeTypeClipboardSearchBeauty {

    private const val TAG = "ClipboardSearch"

    private const val S34 =
        "com.tencent.wetype.plugin.hld.keyboard.S34ClipboardBombKeyboard"
    private const val S15 =
        "com.tencent.wetype.plugin.hld.keyboard.S15CustomPhraseAndClipboardKeyboard"

    private const val ENTRY_TAG = "wetype_clipboard_search_entry"
    private const val PANEL_TAG = "wetype_clipboard_keyboard_top_panel"
    private const val ROW_TAG = "wetype_clipboard_row_text"
    private const val TAB_CONTAINER = "TabContainerView"

    // ---------- 状态 ----------
    private var installed = false
    @Volatile private var searchActive = false
    private val query = StringBuilder()
    private var composing = ""

    private var keyboardRef = WeakReference<Any>(null)      // S34/S15 键盘实例
    private var imsRef = WeakReference<Any>(null)           // InputMethodService
    private var realIcRef = WeakReference<InputConnection>(null)
    private var panelRef = WeakReference<View>(null)
    private var queryTextRef = WeakReference<TextView>(null)
    private var countTextRef = WeakReference<TextView>(null)
    private var resultsRef = WeakReference<LinearLayout>(null)

    private val main = Handler(Looper.getMainLooper())
    private val searchExecutor = Executors.newSingleThreadExecutor { r ->
        Thread(r, "clipboard-beauty-search").apply { isDaemon = true }
    }
    private val searchGen = AtomicInteger(0)
    private var searchScheduled = false
    private var themeNight = false
    private var accentColor = 0xFF1A73E8.toInt()
    private var lastHits: List<BeautyClipboardSearch.Hit> = emptyList()

    // ---------- 对外（ImageList 兼容面） ----------
    fun isReplaying(): Boolean = false
    fun currentKeyword(): String = if (searchActive) query.toString() else ""

    // ---------- 安装 ----------

    fun install(sourceDir: String?, classLoader: ClassLoader) {
        if (installed) return
        if (!WeTypeSettings.isClipboardSearchEnabledXposed()) {
            lcs("LCS-001", "install", "skip", "reason=disabled")
            return
        }
        installed = true
        runCatching {
            val apkPath = sourceDir ?: error("sourceDir unavailable")
            System.loadLibrary("dexkit")
            DexKitBridge.create(apkPath).use { bridge ->
                hookClipKeyboards(bridge, classLoader)
            }
        }.onFailure {
            lcs("LCS-003", "clip_keyboard_hook", "fail", "reason=${it.message}")
        }
        hookInputConnection()
        hookLifecycle()
        Thread({
            BeautyClipboardSearch.prewarm()
            lcs("LCS-017", "prewarm", "ok", "transliterator=${BeautyClipboardSearch.transliteratorReady}")
        }, "clipboard-search-prewarm").apply { isDaemon = true }.start()
    }

    /** LCS-003：DexKit 找 S34/S15 的 void 方法，after 注入搜索入口（幂等）。 */
    private fun hookClipKeyboards(bridge: DexKitBridge, classLoader: ClassLoader) {
        val owners = setOf(S34, S15)
        val found = bridge.findMethod {
            searchPackages("com.tencent.wetype.plugin.hld.keyboard")
            matcher { returnType = "void" }
        }
        val targets = found.mapNotNull { data ->
            runCatching { data.getMethodInstance(classLoader) }.getOrNull()
        }.filter { method ->
            method.declaringClass.name in owners && method.parameterCount <= 2
        }
        if (targets.isEmpty()) {
            lcs("LCS-003", "clip_keyboard_hook", "skip", "reason=no_void_method_found")
            return
        }
        for (method in targets) {
            method.hookAfter { param ->
                val kb = param.thisObject ?: return@hookAfter
                keyboardRef = WeakReference(kb)
                val view = asView(kb) ?: return@hookAfter
                main.post { injectEntry(view) }
            }
        }
        lcs("LCS-003", "clip_keyboard_hook", "ok", "methods=${targets.size}")
    }

    /** LCS-002：截获输入连接。 */
    private fun hookInputConnection() {
        runCatching {
            val imsClass = Class.forName("android.inputmethodservice.InputMethodService")
            val m = imsClass.getDeclaredMethod("getCurrentInputConnection")
            m.isAccessible = true
            m.hookAfter { param ->
                imsRef = WeakReference(param.thisObject ?: return@hookAfter)
                val ic = param.result as? InputConnection ?: return@hookAfter
                if (!searchActive) {
                    realIcRef = WeakReference(ic)
                    return@hookAfter
                }
                if (ic is SearchInputConnection) return@hookAfter
                realIcRef = WeakReference(ic)
                param.result = SearchInputConnection(ic)
            }
            lcs("LCS-002", "input_connection_hook", "ok", "")
        }.onFailure {
            lcs("LCS-002", "input_connection_hook", "fail", "reason=${it.message}")
        }
    }

    /** LCS-015：键盘收起/输入结束 → 静默清理面板。 */
    private fun hookLifecycle() {
        val imsClass = runCatching {
            Class.forName("android.inputmethodservice.InputMethodService")
        }.getOrNull() ?: return
        for (name in listOf("onFinishInputView", "onWindowHidden", "onStartInput")) {
            val m = imsClass.declaredMethods.firstOrNull { it.name == name } ?: continue
            runCatching {
                m.isAccessible = true
                m.hookAfter { _ ->
                    if (searchActive) main.post { exitSearch(false) }
                }
            }
        }
        lcs("LCS-015", "lifecycle_hook", "ok", "")
    }

    // ---------- 入口注入（LCS-004） ----------

    private fun asView(any: Any): View? {
        (any as? View)?.let { return it }
        var cls: Class<*>? = any.javaClass
        while (cls != null) {
            for (f in cls.declaredFields) {
                if (View::class.java == f.type) {
                    runCatching {
                        f.isAccessible = true
                        (f.get(any) as? View)?.takeIf { it.parent != null }?.let { return it }
                    }
                }
            }
            cls = cls.superclass
        }
        return null
    }

    private fun findByClassName(root: View, simpleName: String): View? {
        if (root.javaClass.simpleName == simpleName) return root
        if (root !is ViewGroup) return null
        for (i in 0 until root.childCount) {
            findByClassName(root.getChildAt(i), simpleName)?.let { return it }
        }
        return null
    }

    private fun injectEntry(keyboardView: View) {
        runCatching {
            if (!WeTypeSettings.isClipboardSearchEnabledXposed()) return
            if (keyboardView.findViewWithTag<View>(ENTRY_TAG) != null) return
            val tab = findByClassName(keyboardView, TAB_CONTAINER) ?: return
            val host = tab.parent as? ViewGroup ?: return
            host.clipChildren = false
            host.clipToPadding = false
            (keyboardView as? ViewGroup)?.clipChildren = false

            val ctx = keyboardView.context
            applyTheme(ctx)

            val entry = FrameLayout(ctx).apply {
                tag = ENTRY_TAG
                isClickable = true
                isFocusable = true
                visibility = View.INVISIBLE
                alpha = 0f
                background = GradientDrawable().apply {
                    shape = GradientDrawable.OVAL
                    setColor(scrimColor())
                }
            }
            val icon = ImageView(ctx).apply {
                layoutParams = FrameLayout.LayoutParams(dp(ctx, 21), dp(ctx, 21), Gravity.CENTER)
                isClickable = false
                isFocusable = false
                setImageDrawable(MagnifierDrawable().apply { tint = accentColor })
            }
            entry.addView(icon)
            entry.setOnClickListener { v ->
                clickNativeBack()
                showPanel(v)
            }

            val size = anchorSize(host, tab, ctx)
            host.addView(entry, ViewGroup.LayoutParams(size, size))
            entryRef = WeakReference(entry)

            val reveal = {
                runCatching {
                    entry.x = max(0f, (host.width - size - dp(ctx, 8)).toFloat())
                    if (entry.visibility != View.VISIBLE) {
                        entry.visibility = View.VISIBLE
                        entry.animate().alpha(1f).setDuration(160L).start()
                    }
                }
            }
            keyboardView.post { reveal() }
            entry.postDelayed({ reveal() }, 30L)
            host.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ -> reveal() }
            lcs(
                "LCS-004", "entry_injected", "ok",
                "keyboard=${keyboardView.javaClass.simpleName} tabW=${tab.width}"
            )
        }.onFailure {
            lcs("LCS-004", "entry_injected", "fail", "reason=${it.message}")
        }
    }

    private var entryRef = WeakReference<View>(null)

    private fun anchorSize(host: ViewGroup, tab: View, ctx: Context): Int {
        val anchor = (0 until host.childCount)
            .map { host.getChildAt(it) }
            .firstOrNull { child ->
                child is FrameLayout && child !== tab && child.id != View.NO_ID &&
                    runCatching {
                        val name = ctx.resources.getResourceEntryName(child.id)
                        name.contains("custom_btn") || name.contains("setting")
                    }.getOrDefault(false)
            }
        val base = dp(ctx, 36)
        anchor?.layoutParams?.let { lp ->
            return max(base, max(lp.width, dp(ctx, 30)))
        }
        return base
    }

    // ---------- 面板（LCS-007） ----------

    @SuppressLint("ClickableViewAccessibility")
    private fun showPanel(from: View) {
        if (searchActive) return
        runCatching {
            val ctx = from.context
            applyTheme(ctx)
            val decor = from.rootView as? ViewGroup ?: return
            if (decor.findViewWithTag<View>(PANEL_TAG) != null) return

            val records = ClipboardDb.query(ctx, 0)
            BeautyClipboardSearch.ensurePinyinIndex(records) {
                main.post { if (searchActive) scheduleSearch(force = true) }
            }

            val panel = LinearLayout(ctx).apply {
                tag = PANEL_TAG
                orientation = LinearLayout.VERTICAL
                isClickable = true
                isFocusable = true
                elevation = dp(ctx, 6).toFloat()
                background = panelBackground(decor)
            }

            val bar = LinearLayout(ctx).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(ctx, 10), dp(ctx, 6), dp(ctx, 10), dp(ctx, 6))
            }
            val back = ImageView(ctx).apply {
                layoutParams = LinearLayout.LayoutParams(dp(ctx, 24), dp(ctx, 24))
                setImageDrawable(BackDrawable().apply { tint = accentColor })
                isClickable = true
                background = ripple(ctx)
                setOnClickListener { exitSearch(triggerNativeBack = true) }
            }
            val queryText = TextView(ctx).apply {
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                textSize = 15f
                setTextColor(textPrimary())
                setSingleLine()
                ellipsize = android.text.TextUtils.TruncateAt.END
                setPadding(dp(ctx, 10), 0, dp(ctx, 6), 0)
                includeFontPadding = false
            }
            val count = TextView(ctx).apply {
                textSize = 12f
                setTextColor(textSecondary())
                gravity = Gravity.CENTER
                visibility = View.GONE
                includeFontPadding = false
            }
            val clear = TextView(ctx).apply {
                text = "✕"
                textSize = 15f
                setTextColor(textSecondary())
                gravity = Gravity.CENTER
                setPadding(dp(ctx, 8), 0, dp(ctx, 4), 0)
                isClickable = true
                includeFontPadding = false
                setOnClickListener { clearQuery() }
            }
            bar.addView(back)
            bar.addView(queryText)
            bar.addView(count)
            bar.addView(clear)

            val scroll = ScrollView(ctx).apply {
                isVerticalScrollBarEnabled = false
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f
                )
            }
            val results = LinearLayout(ctx).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(ctx, 8), dp(ctx, 2), dp(ctx, 8), dp(ctx, 8))
            }
            scroll.addView(results)
            panel.addView(bar, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
            ))
            panel.addView(scroll, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f
            ))

            val lp = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT,
                Gravity.BOTTOM
            )
            lp.bottomMargin = bottomInsetForPanel(ctx, decor)
            decor.addView(panel, lp)

            panel.addOnAttachStateChangeListener(object : View.OnAttachStateChangeListener {
                override fun onViewAttachedToWindow(v: View) {}
                override fun onViewDetachedFromWindow(v: View) {
                    if (searchActive) exitSearch(false)
                }
            })
            decor.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ ->
                positionPanel(panel, ctx, decor)
            }

            panelRef = WeakReference(panel)
            queryTextRef = WeakReference(queryText)
            countTextRef = WeakReference(count)
            resultsRef = WeakReference(results)

            searchActive = true
            query.setLength(0)
            composing = ""
            updateQueryDisplay()
            renderResults(records.map { BeautyClipboardSearch.Hit(it, 0, emptyList()) })
            lcs("LCS-007", "panel_shown", "ok", "records=${records.size}")
        }.onFailure {
            lcs("LCS-007", "panel_shown", "fail", "reason=${it.message}")
        }
    }

    /** LCS-010：面板底边距 = IMS 输入区高度（悬于键盘上方）。 */
    private fun bottomInsetForPanel(ctx: Context, decor: View): Int {
        val ims = imsRef.get()
        var inset = 0
        if (ims != null) {
            for (field in listOf("mInputFrame", "mCandidatesFrame")) {
                runCatching {
                    val f = findField(ims.javaClass, field) ?: return@runCatching
                    f.isAccessible = true
                    val v = f.get(ims) as? View ?: return@runCatching
                    if (v.visibility == View.VISIBLE && v.height > 0) inset += v.height
                }
            }
        }
        if (inset <= 0) inset = (decor.height * 0.55f).toInt().coerceAtLeast(dp(ctx, 160))
        return inset
    }

    private fun positionPanel(panel: View, ctx: Context, decor: View) {
        runCatching {
            val lp = panel.layoutParams as? FrameLayout.LayoutParams ?: return
            val target = bottomInsetForPanel(ctx, decor)
            if (lp.bottomMargin != target) {
                lp.bottomMargin = target
                panel.layoutParams = lp
            }
        }
    }

    private fun findField(cls: Class<*>, name: String): java.lang.reflect.Field? {
        var c: Class<*>? = cls
        while (c != null) {
            runCatching { return c.getDeclaredField(name) }
            c = c.superclass
        }
        return null
    }

    // ---------- 输入截获 ----------

    private class SearchInputConnection(real: InputConnection) :
        InputConnectionWrapper(real, true) {

        override fun commitText(text: CharSequence, newCursorPosition: Int): Boolean {
            if (!searchActive) return super.commitText(text, newCursorPosition)
            stripComposing()
            if (text.isNotEmpty()) {
                query.append(text)
                onQueryChanged()
            }
            return true
        }

        override fun setComposingText(text: CharSequence, newCursorPosition: Int): Boolean {
            if (!searchActive) return super.setComposingText(text, newCursorPosition)
            if (composing.isNotEmpty() && query.endsWith(composing)) {
                query.setLength(query.length - composing.length)
            }
            composing = text.toString()
            if (composing.isNotEmpty()) query.append(composing)
            onQueryChanged()
            return true
        }

        override fun deleteSurroundingText(beforeLength: Int, afterLength: Int): Boolean {
            if (!searchActive) return super.deleteSurroundingText(beforeLength, afterLength)
            if (composing.isNotEmpty()) return true // IME 自己处理组字区退格
            if (beforeLength > 0 && query.isNotEmpty()) {
                query.setLength(query.length - 1)
                onQueryChanged()
            }
            return true
        }

        override fun sendKeyEvent(event: KeyEvent): Boolean {
            if (searchActive &&
                event.action == KeyEvent.ACTION_DOWN &&
                event.keyCode == KeyEvent.KEYCODE_DEL
            ) {
                if (composing.isNotEmpty()) return true
                if (query.isNotEmpty()) {
                    query.setLength(query.length - 1)
                    onQueryChanged()
                }
                return true
            }
            return super.sendKeyEvent(event)
        }

        private fun stripComposing() {
            if (composing.isNotEmpty() && query.endsWith(composing)) {
                query.setLength(query.length - composing.length)
            }
            composing = ""
        }
    }

    private fun onQueryChanged() {
        main.post {
            updateQueryDisplay()
            scheduleSearch()
        }
    }

    // ---------- 搜索调度 ----------

    private fun scheduleSearch(force: Boolean = false) {
        if (!searchActive) return
        if (!force && searchScheduled) return
        searchScheduled = true
        val gen = searchGen.incrementAndGet()
        main.postDelayed({
            searchScheduled = false
            if (!searchActive || gen != searchGen.get()) return@postDelayed
            val ctx = panelRef.get()?.context ?: return@postDelayed
            val q = query.toString()
            searchExecutor.execute {
                val records = ClipboardDb.query(ctx, 2000)
                val hits = BeautyClipboardSearch.search(records, q)
                main.post {
                    if (!searchActive || gen != searchGen.get()) return@post
                    renderResults(hits)
                }
            }
        }, 120L)
    }

    private fun clearQuery() {
        query.setLength(0)
        composing = ""
        updateQueryDisplay()
        scheduleSearch(force = true)
    }

    private fun updateQueryDisplay() {
        val tv = queryTextRef.get() ?: return
        if (query.isEmpty()) {
            val sb = SpannableStringBuilder("|")
            sb.setSpan(ForegroundColorSpan(accentColor), 0, 1, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
            sb.append(" 输入关键词筛选…")
            val start = sb.length - " 输入关键词筛选…".length
            sb.setSpan(
                ForegroundColorSpan(hintColor()), start, sb.length,
                Spannable.SPAN_EXCLUSIVE_EXCLUSIVE
            )
            tv.text = sb
            countTextRef.get()?.visibility = View.GONE
        } else {
            tv.text = query.toString()
            countTextRef.get()?.let {
                it.visibility = View.VISIBLE
                it.text = "${lastHits.size}"
            }
        }
    }

    // ---------- 结果渲染 / 提交 ----------

    private fun renderResults(hits: List<BeautyClipboardSearch.Hit>) {
        lastHits = hits
        val results = resultsRef.get() ?: return
        val ctx = results.context
        results.removeAllViews()
        if (hits.isEmpty()) {
            results.addView(TextView(ctx).apply {
                text = "无匹配结果"
                textSize = 13f
                setTextColor(textSecondary())
                gravity = Gravity.CENTER
                setPadding(0, dp(ctx, 18), 0, dp(ctx, 18))
            })
            updateQueryDisplay()
            return
        }
        for (hit in hits) {
            results.addView(buildRow(ctx, hit))
        }
        updateQueryDisplay()
    }

    private fun buildRow(ctx: Context, hit: BeautyClipboardSearch.Hit): View {
        val row = TextView(ctx).apply {
            tag = ROW_TAG
            textSize = 13.5f
            setTextColor(textPrimary())
            setSingleLine(false)
            maxLines = 2
            ellipsize = android.text.TextUtils.TruncateAt.END
            setPadding(dp(ctx, 10), dp(ctx, 7), dp(ctx, 10), dp(ctx, 7))
            includeFontPadding = false
            background = ripple(ctx)
            isClickable = true
        }
        val content = hit.record.content
        val sb = SpannableStringBuilder(content)
        for (span in hit.spans) {
            val s = span.first.coerceIn(0, sb.length)
            val e = (span.last + 1).coerceIn(0, sb.length)
            if (e <= s) continue
            sb.setSpan(ForegroundColorSpan(accentColor), s, e, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
            sb.setSpan(StyleSpan(android.graphics.Typeface.BOLD), s, e, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
        row.text = sb
        row.setOnClickListener { commitItem(hit) }
        return row
    }

    /** LCS-008：点击结果行 → 真 InputConnection 上屏 + 收面板 + 原生返回。 */
    private fun commitItem(hit: BeautyClipboardSearch.Hit) {
        runCatching {
            val ic = realIcRef.get()
            if (ic == null || hit.record.content.isEmpty()) {
                lcs("LCS-008", "commit_item", "fail", "reason=no_ic")
            } else {
                ic.finishComposingText()
                ic.commitText(hit.record.content, 1)
                lcs("LCS-008", "commit_item", "ok", "len=${hit.record.content.length}")
            }
        }.onFailure {
            lcs("LCS-008", "commit_item", "fail", "err=${it.message}")
        }
        exitSearch(triggerNativeBack = true)
    }

    // ---------- 退出（LCS-009） ----------

    private fun exitSearch(triggerNativeBack: Boolean) {
        if (!searchActive) return
        searchActive = false
        query.setLength(0)
        composing = ""
        searchGen.incrementAndGet()
        runCatching {
            realIcRef.get()?.finishComposingText()
        }
        runCatching {
            panelRef.get()?.let { panel ->
                (panel.parent as? ViewGroup)?.removeView(panel)
            }
        }
        panelRef = WeakReference(null)
        queryTextRef = WeakReference(null)
        countTextRef = WeakReference(null)
        resultsRef = WeakReference(null)
        lastHits = emptyList()
        if (triggerNativeBack) clickNativeBack()
        lcs("LCS-009", "exit_search", "ok", "")
    }

    /** 反射点宿主原生返回键（S34#getBackBtnContainerRl()/getIvBackBtn()）。 */
    private fun clickNativeBack() {
        runCatching {
            val kb = keyboardRef.get() ?: return
            val container = invokeNoArg(kb, "getBackBtnContainerRl") as? View
            val ivBack = invokeNoArg(kb, "getIvBackBtn") as? View
            (ivBack ?: container)?.performClick()
            lcs("LCS-006", "native_back", "ok", "has=${container != null}")
        }.onFailure {
            lcs("LCS-006", "native_back", "skip", "reason=${it.message}")
        }
    }

    private fun invokeNoArg(target: Any, name: String): Any? {
        var cls: Class<*>? = target.javaClass
        while (cls != null) {
            val m = runCatching {
                cls!!.getDeclaredMethod(name)
            }.getOrNull()
            if (m != null) {
                m.isAccessible = true
                return m.invoke(target)
            }
            cls = cls.superclass
        }
        return null
    }

    // ---------- 主题 ----------

    private fun applyTheme(ctx: Context) {
        themeNight = (ctx.resources.configuration.uiMode and
            android.content.res.Configuration.UI_MODE_NIGHT_MASK) ==
            android.content.res.Configuration.UI_MODE_NIGHT_YES
        accentColor = if (themeNight) 0xFF8AB4F8.toInt() else 0xFF1A73E8.toInt()
    }

    private fun textPrimary(): Int =
        if (themeNight) 0xFFF2F2F2.toInt() else 0xFF1F1F1F.toInt()

    private fun textSecondary(): Int =
        if (themeNight) 0x99FFFFFF.toInt() else 0x991F1F1F.toInt()

    private fun hintColor(): Int =
        if (themeNight) 0x80FFFFFF.toInt() else 0x801F1F1F.toInt()

    private fun scrimColor(): Int =
        if (themeNight) 0x26000000 else 0x14000000

    private fun panelBackground(decor: View): Drawable {
        val fallback = GradientDrawable().apply {
            cornerRadius = dp(decor.context, 14).toFloat()
            setColor(if (themeNight) 0xF21C1C1E.toInt() else 0xFFF6F6F8.toInt())
        }
        return runCatching {
            // ViewRootImpl.createBackgroundBlurDrawable 反射链（与美化版运行时调用同构）
            val getVrl = View::class.java.getMethod("getViewRootImpl")
            val vrl = getVrl.invoke(decor) ?: error("ViewRootImpl unavailable")
            val create = vrl.javaClass.methods.firstOrNull {
                it.name == "createBackgroundBlurDrawable" && it.parameterCount == 0
            } ?: error("createBackgroundBlurDrawable unavailable")
            val blur = create.invoke(vrl) as Drawable
            val blurClass = blur.javaClass
            blurClass.methods.firstOrNull { it.name == "setCornerRadius" }
                ?.invoke(blur, dp(decor.context, 14).toFloat())
            blurClass.methods.firstOrNull { it.name == "setBlurRadius" && it.parameterCount == 1 }
                ?.invoke(blur, dp(decor.context, 24))
            blurClass.methods.firstOrNull { it.name == "setColor" && it.parameterCount == 1 }
                ?.invoke(blur, if (themeNight) 0xCC1C1C1E.toInt() else 0xCCF6F6F8.toInt())
            blur
        }.getOrDefault(fallback)
    }

    private fun ripple(ctx: Context): Drawable {
        return android.graphics.drawable.RippleDrawable(
            android.content.res.ColorStateList.valueOf(0x1E000000),
            null,
            GradientDrawable().apply {
                cornerRadius = dp(ctx, 10).toFloat()
                setColor(if (themeNight) 0x14FFFFFF else 0x0A000000)
            }
        )
    }

    private fun dp(ctx: Context, v: Int): Int =
        (v * ctx.resources.displayMetrics.density + 0.5f).toInt()

    // ---------- 图标 ----------

    private abstract class IconDrawable : Drawable() {
        var tint = Color.BLACK
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = 2.4f
            strokeCap = Paint.Cap.ROUND
        }
        private var filter: ColorFilter? = null

        override fun setColorFilter(colorFilter: ColorFilter?) {
            filter = colorFilter
        }

        @Deprecated("Deprecated in Java")
        override fun getOpacity(): Int = PixelFormat.TRANSLUCENT

        override fun setAlpha(alpha: Int) {
            paint.alpha = alpha
        }

        protected fun paintFor(): Paint {
            paint.colorFilter = filter
                ?: if (tint != Color.BLACK) PorterDuffColorFilter(tint, PorterDuff.Mode.SRC_IN) else null
            return paint
        }
    }

    /** 放大镜。 */
    private class MagnifierDrawable : IconDrawable() {
        override fun draw(canvas: Canvas) {
            val w = bounds.width().toFloat()
            val h = bounds.height().toFloat()
            if (w <= 0f || h <= 0f) return
            val p = paintFor()
            val r = min(w, h) * 0.32f
            val cx = w * 0.42f
            val cy = h * 0.42f
            canvas.drawCircle(cx, cy, r, p)
            canvas.drawLine(cx + r * 0.72f, cy + r * 0.72f, w * 0.88f, h * 0.88f, p)
        }
    }

    /** 返回箭头。 */
    private class BackDrawable : IconDrawable() {
        override fun draw(canvas: Canvas) {
            val w = bounds.width().toFloat()
            val h = bounds.height().toFloat()
            if (w <= 0f || h <= 0f) return
            val p = paintFor()
            val mx = w * 0.62f
            val my = h * 0.5f
            canvas.drawLine(mx, my, w * 0.30f, my, p)
            canvas.drawLine(w * 0.30f, my, w * 0.46f, h * 0.28f, p)
            canvas.drawLine(w * 0.30f, my, w * 0.46f, h * 0.72f, p)
        }
    }

    // ---------- 日志（LCS 码对齐美化版） ----------

    private fun lcs(code: String, event: String, status: String, detail: String) {
        Log.i("[$TAG] $code $event $status $detail")
    }
}
