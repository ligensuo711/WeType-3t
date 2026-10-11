package com.xposed.wetypehook.wetype.clipboard

import android.content.Context
import android.database.sqlite.SQLiteDatabase

/**
 * 剪贴板历史直读（WeType Buff / 美化版同源机制）。
 *
 * 数据源：微信输入法进程私有 SQLite `ime_database` 的 `clipboard_record` 表，
 * 按 createTime 倒序取全量，过滤空串。只读打开，不落锁不写回。
 */
internal object ClipboardDb {

    private const val DB_NAME = "ime_database"
    private const val SQL =
        "SELECT content, createTime FROM clipboard_record ORDER BY createTime DESC"

    data class Record(val createTime: Long, val content: String)

    /** 进程内缓存：读取结果 + 读取时刻，供搜索时复用并按需刷新。 */
    @Volatile
    private var cached: List<Record> = emptyList()
    @Volatile
    private var cachedAt: Long = 0

    fun current(): List<Record> = cached

    /**
     * 读取（或按 TTL 复用）剪贴板历史。
     * @param ttlMs 缓存有效期，0 表示强制重读
     */
    fun query(context: Context, ttlMs: Long = 2000): List<Record> {
        val now = android.os.SystemClock.elapsedRealtime()
        if (ttlMs > 0 && cached.isNotEmpty() && now - cachedAt < ttlMs) {
            return cached
        }
        val fresh = runCatching { readAll(context) }.getOrDefault(emptyList())
        if (fresh.isNotEmpty()) {
            cached = fresh
            cachedAt = now
        }
        return fresh
    }

    private fun readAll(context: Context): List<Record> {
        val dbFile = runCatching { context.getDatabasePath(DB_NAME) }.getOrNull()
            ?: return emptyList()
        if (!dbFile.exists()) return emptyList()
        val db = SQLiteDatabase.openDatabase(dbFile.path, null, SQLiteDatabase.OPEN_READONLY)
        try {
            val out = ArrayList<Record>(256)
            db.rawQuery(SQL, null).use { cursor ->
                val contentIdx = cursor.getColumnIndexOrThrow("content")
                val timeIdx = cursor.getColumnIndex("createTime")
                while (cursor.moveToNext()) {
                    val content = cursor.getString(contentIdx) ?: continue
                    if (content.isEmpty()) continue
                    val time = if (timeIdx >= 0 && !cursor.isNull(timeIdx)) {
                        cursor.getLong(timeIdx)
                    } else 0L
                    out.add(Record(time, content))
                }
            }
            return out
        } finally {
            runCatching { db.close() }
        }
    }
}
