package com.xposed.wetypehook.wetype.clipboard

import android.icu.text.Transliterator
import java.util.Locale

/**
 * 剪贴板搜索引擎（复刻 WeType 美化 1.28.4 ClipboardSearchHooks 的匹配机制）。
 *
 * 机制要点（逆向自 miuiime/ƌȑЫҜი; 日志码 LCS-001~017）：
 * - 查询串按非字母切词并小写化；空查询返回全量
 * - 逐条做「跨字符序列匹配」：先定位首字符，其后每个字符允许与前一个命中位置间隔 ≤4；
 *   整条匹配须落在记录前 260 字符窗口内
 * - 拼音兜底：查询含 ASCII 字母且拼音索引就绪时，对无内容命中的中文记录
 *   在其 Han→Latin 转写文本上重跑序列匹配（预热线程：clipboard-pinyin-index /
 *   clipboard-search-prewarm，样本「微信输入法剪贴板搜索」）
 * - 命中区间合并为高亮 span；每 32 条检查一次耗时预算（>120ms 提前收工返回部分结果）
 */
internal object BeautyClipboardSearch {

    private const val MATCH_WINDOW = 260
    private const val MATCH_GAP = 4
    private const val BUDGET_CHECK_EVERY = 32
    private const val BUDGET_MS = 120L

    /** 命中：记录 + 得分 + 高亮区间（含头不含尾）。 */
    data class Hit(val record: ClipboardDb.Record, val score: Int, val spans: List<IntRange>)

    @Volatile
    var transliteratorReady = false
        private set

    private var transliterator: Transliterator? = null

    /** 拼音索引：record 在缓存列表中的下标 -> 规范化拼音串。 */
    private val pinyinIndex = HashMap<Int, String>()
    @Volatile
    private var pinyinIndexReady = false

    /** 预热：构造 Transliterator 并转写样本（对应 LCS-017 prewarm）。 */
    fun prewarm() {
        if (transliteratorReady) return
        runCatching {
            val t = Transliterator.getInstance("Han-Latin; Latin-ASCII")
            t.transliterate(PREWARM_SAMPLE)
            transliterator = t
            transliteratorReady = true
        }
    }

    /** 查询含 ASCII 字母时后台构建拼音索引（对应 clipboard-pinyin-index 线程）。 */
    fun ensurePinyinIndex(records: List<ClipboardDb.Record>, onReady: () -> Unit) {
        if (pinyinIndexReady || !transliteratorReady) return
        val t = transliterator ?: return
        Thread({
            runCatching {
                synchronized(pinyinIndex) {
                    if (pinyinIndexReady) return@runCatching
                    val start = android.os.SystemClock.elapsedRealtime()
                    for (i in records.indices) {
                        if (i % 64 == 0 && android.os.SystemClock.elapsedRealtime() - start > 800) break
                        val content = records[i].content
                        if (content.length > 64) continue
                        val py = normalizePinyin(t.transliterate(content))
                        if (py.isNotEmpty()) pinyinIndex[i] = py
                    }
                    pinyinIndexReady = true
                }
                onReady()
            }
        }, "clipboard-pinyin-index").apply { isDaemon = true }.start()
    }

    fun resetPinyinIndex() {
        synchronized(pinyinIndex) {
            pinyinIndex.clear()
            pinyinIndexReady = false
        }
    }

    /** 查询串按非字母切词 + 小写化。 */
    fun tokenize(query: String): List<String> {
        if (query.isEmpty()) return emptyList()
        val tokens = ArrayList<String>(4)
        val sb = StringBuilder()
        for (c in query) {
            if (c.isLetter()) {
                sb.append(c)
            } else {
                if (sb.isNotEmpty()) tokens.add(sb.toString().lowercase(Locale.ROOT))
                sb.setLength(0)
            }
        }
        if (sb.isNotEmpty()) tokens.add(sb.toString().lowercase(Locale.ROOT))
        return tokens
    }

    /**
     * 跨字符序列匹配：返回每个 token 字符的命中位置数组（拼接），失败返回 null。
     * 匹配窗口与间隔约束见类注释。
     */
    fun matchSequence(text: String, token: String): IntArray? {
        val n = token.length
        if (n == 0 || text.isEmpty()) return null
        val lower = text.lowercase(Locale.ROOT)
        val limit = minOf(lower.length, MATCH_WINDOW)
        var first = lower.indexOf(token[0])
        while (first in 0 until limit) {
            val positions = IntArray(n)
            positions[0] = first
            var prev = first
            var ok = true
            for (i in 1 until n) {
                val from = prev + 1
                var next = lower.indexOf(token[i], from)
                if (next < 0 || next >= limit || next - prev - 1 > MATCH_GAP) {
                    ok = false
                    break
                }
                positions[i] = next
                prev = next
            }
            if (ok) return positions
            first = lower.indexOf(token[0], first + 1)
        }
        return null
    }

    /** 命中位置 → 合并相邻（间隔 0）的高亮区间。 */
    fun positionsToSpans(positions: IntArray): List<IntRange> {
        if (positions.isEmpty()) return emptyList()
        val spans = ArrayList<IntRange>(positions.size)
        var start = positions[0]
        var prev = positions[0]
        for (i in 1 until positions.size) {
            val p = positions[i]
            if (p == prev + 1) {
                prev = p
            } else {
                spans.add(start until prev + 1)
                start = p
                prev = p
            }
        }
        spans.add(start until prev + 1)
        return spans
    }

    private fun normalizePinyin(raw: String): String {
        val sb = StringBuilder(raw.length)
        for (c in raw) if (c.isLetter()) sb.append(c)
        return sb.toString().lowercase(Locale.ROOT)
    }

    /**
     * 执行搜索。
     * @param pinyinFallback true 时启用拼音兜底（索引就绪才生效）
     * @return 命中列表（已按得分 + 时间排序）；预算耗尽时返回已算出的部分结果
     */
    fun search(
        records: List<ClipboardDb.Record>,
        query: String,
        pinyinFallback: Boolean = true
    ): List<Hit> {
        val tokens = tokenize(query)
        if (tokens.isEmpty()) {
            return records.map { Hit(it, 0, emptyList()) }
        }
        val usePinyin = pinyinFallback &&
            tokens.any { t -> t.any { c -> c in 'a'..'z' } }
        val pinyin: Map<Int, String> = if (usePinyin && pinyinIndexReady) {
            synchronized(pinyinIndex) { HashMap(pinyinIndex) }
        } else {
            emptyMap()
        }
        val started = android.os.SystemClock.elapsedRealtime()
        val hits = ArrayList<Hit>(minOf(records.size, 64))
        for ((index, record) in records.withIndex()) {
            if (hits.size >= 96) break
            if (index % BUDGET_CHECK_EVERY == 0 &&
                android.os.SystemClock.elapsedRealtime() - started > BUDGET_MS
            ) {
                break // 预算耗尽：返回部分结果，与美化版行为一致
            }
            // 1) 内容序列匹配（所有 token 命中才算）
            var allSpans = ArrayList<IntRange>(tokens.size)
            var contentMatched = true
            for (t in tokens) {
                val pos = matchSequence(record.content, t)
                if (pos == null) {
                    contentMatched = false
                    break
                }
                allSpans.addAll(positionsToSpans(pos))
            }
            if (contentMatched) {
                hits.add(Hit(record, scoreFor(allSpans, record.content.length), allSpans))
                continue
            }
            // 2) 拼音兜底
            val py = pinyin[index] ?: continue
            var pyMatched = true
            for (t in tokens) {
                if (matchSequence(py, t) == null) {
                    pyMatched = false
                    break
                }
            }
            if (pyMatched) {
                val len = record.content.length
                val head = 0 until minOf(len, 12)
                hits.add(Hit(record, 50, listOf(head)))
            }
        }
        hits.sortWith(
            compareByDescending<Hit> { it.score }
                .thenByDescending { it.record.createTime }
        )
        return hits
    }

    private fun scoreFor(spans: List<IntRange>, contentLength: Int): Int {
        var covered = 0
        for (s in spans) covered += s.last - s.first + 1
        // 紧凑、靠前的命中得分更高（美化版语义：序列紧凑度优先）
        val firstPos = spans.minOfOrNull { it.first } ?: 0
        val spread = spans.maxOfOrNull { it.last } ?: 0
        return 1000 - firstPos / 4 - covered / 2 - spread / 8
    }

    private const val PREWARM_SAMPLE = "微信输入法剪贴板搜索"
}
