package com.xposed.wetypehook.wetype.settings

import android.content.Context
import org.xmlpull.v1.XmlPullParser
import org.xmlpull.v1.XmlPullParserFactory
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/**
 * 模块设置备份与恢复。
 *
 * 打包应用私有目录 `shared_prefs` 下全部偏好（`wetype_settings` 等），不含剪贴板数据库——
 * 剪贴板历史请用剪贴板备份功能另行导出。
 *
 * 导出直接读取内存中的偏好（而非读文件），保证刚改完的设置也能进包；
 * 恢复走 `SharedPreferences.Editor` 写入并同步提交，同时刷新本进程缓存，
 * 随后重启 App 进程让全部界面重新读取（Xposed 宿主侧经远端偏好监听自愈，
 * 若输入法内效果未更新，用自带的输入法重启功能重启作用域即可）。
 */
internal object ModuleSettingsBackup {
    const val MIME_ZIP = "application/zip"

    private const val MAX_FILES = 64
    private const val MAX_ENTRY_BYTES = 8L * 1024 * 1024
    private const val MAX_TOTAL_BYTES = 32L * 1024 * 1024
    private const val FILE_NAME_PREFIX = "WeType-3t-settings-"
    private const val FILE_NAME_SUFFIX = ".zip"

    private val ENTRY_NAME_RULE = Regex("^[A-Za-z0-9][A-Za-z0-9._-]*\\.xml$")
    private val FILE_NAME_TIME = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss")
        .withZone(ZoneId.systemDefault())

    data class ExportSummary(val fileCount: Int, val keyCount: Int)
    data class ImportSummary(val restoredFiles: Int, val restoredKeys: Int)

    fun buildFileName(timestamp: Long): String =
        FILE_NAME_PREFIX + FILE_NAME_TIME.format(Instant.ofEpochMilli(timestamp)) + FILE_NAME_SUFFIX

    private fun prefsDir(context: Context): java.io.File =
        java.io.File(context.applicationInfo.dataDir, "shared_prefs")

    /** 已知偏好名即使尚未落盘，也能从内存中导出。 */
    private fun discoverPrefNames(context: Context): List<String> {
        val onDisk = prefsDir(context)
            .listFiles { file -> file.isFile && file.name.endsWith(".xml") && !file.name.endsWith(".bak") }
            ?.map { it.name.removeSuffix(".xml") }.orEmpty()
        return (onDisk + WeTypeSettings.PREF_GROUP).distinct().sorted()
    }

    fun exportToStream(context: Context, output: OutputStream): Result<ExportSummary> = runCatching {
        var files = 0
        var keys = 0
        ZipOutputStream(output).use { zip ->
            for (name in discoverPrefNames(context)) {
                val entries: Map<String, *> =
                    context.getSharedPreferences(name, Context.MODE_PRIVATE).all
                if (entries.isEmpty()) continue
                zip.putNextEntry(ZipEntry("$name.xml"))
                zip.write(serializePrefs(entries))
                zip.closeEntry()
                files++
                keys += entries.size
            }
        }
        check(files > 0) { "没有可备份的设置" }
        ExportSummary(files, keys)
    }

    fun importFromStream(context: Context, input: InputStream): Result<ImportSummary> = runCatching {
        val parsed = LinkedHashMap<String, Map<String, Any>>()
        var totalBytes = 0L
        ZipInputStream(input).use { zip ->
            var entry = zip.nextEntry
            while (entry != null) {
                val name = entry.name
                if (!entry.isDirectory && ENTRY_NAME_RULE.matches(name)) {
                    check(parsed.size < MAX_FILES) { "备份包内文件过多" }
                    val bytes = zip.readBytesCapped(MAX_ENTRY_BYTES)
                    totalBytes += bytes.size
                    check(totalBytes <= MAX_TOTAL_BYTES) { "备份包过大" }
                    parsed[name.removeSuffix(".xml")] = parsePrefs(bytes)
                }
                zip.closeEntry()
                entry = zip.nextEntry
            }
        }
        check(parsed.isNotEmpty()) { "所选文件不是有效的设置备份包" }
        var keys = 0
        val dir = prefsDir(context)
        for ((prefName, values) in parsed) {
            val prefs = context.getSharedPreferences(prefName, Context.MODE_PRIVATE)
            val editor = prefs.edit().clear()
            for ((key, value) in values) {
                when (value) {
                    is String -> editor.putString(key, value)
                    is Int -> editor.putInt(key, value)
                    is Long -> editor.putLong(key, value)
                    is Float -> editor.putFloat(key, value)
                    is Boolean -> editor.putBoolean(key, value)
                    is Set<*> -> {
                        @Suppress("UNCHECKED_CAST")
                        editor.putStringSet(key, LinkedHashSet(value as Set<String>))
                    }
                }
            }
            check(editor.commit()) { "设置写入失败：$prefName" }
            // 删掉可能残留的 .bak，避免 SharedPreferences 优先读取旧备份。
            java.io.File(dir, "$prefName.xml.bak").delete()
            keys += values.size
        }
        ImportSummary(parsed.size, keys)
    }

    /** 与 AOSP SharedPreferences 落盘格式一致，便于排查。 */
    internal fun serializePrefs(entries: Map<String, *>): ByteArray {
        val out = ByteArrayOutputStream()
        val serializer = XmlPullParserFactory.newInstance().newSerializer()
        serializer.setOutput(out, "UTF-8")
        serializer.startDocument("UTF-8", true)
        serializer.startTag(null, "map")
        for ((key, value) in entries) {
            when (value) {
                is String -> {
                    serializer.startTag(null, "string")
                    serializer.attribute(null, "name", key)
                    serializer.text(value)
                    serializer.endTag(null, "string")
                }
                is Int -> scalar(serializer, "int", key, value.toString())
                is Long -> scalar(serializer, "long", key, value.toString())
                is Float -> scalar(serializer, "float", key, value.toString())
                is Boolean -> scalar(serializer, "boolean", key, value.toString())
                is Set<*> -> {
                    serializer.startTag(null, "set")
                    serializer.attribute(null, "name", key)
                    for (item in value) {
                        serializer.startTag(null, "string")
                        serializer.text(item as String)
                        serializer.endTag(null, "string")
                    }
                    serializer.endTag(null, "set")
                }
                else -> {
                    // null 或未知类型直接跳过，不进包。
                }
            }
        }
        serializer.endTag(null, "map")
        serializer.endDocument()
        serializer.flush()
        return out.toByteArray()
    }

    private fun scalar(
        serializer: org.xmlpull.v1.XmlSerializer,
        tag: String,
        key: String,
        value: String
    ) {
        serializer.startTag(null, tag)
        serializer.attribute(null, "name", key)
        serializer.attribute(null, "value", value)
        serializer.endTag(null, tag)
    }

    internal fun parsePrefs(bytes: ByteArray): Map<String, Any> {
        val result = LinkedHashMap<String, Any>()
        val parser = XmlPullParserFactory.newInstance().newPullParser()
        parser.setInput(bytes.inputStream(), "UTF-8")
        check(parser.nextTag() == XmlPullParser.START_TAG && parser.name == "map") { "设置文件格式错误" }
        var closedMap = false
        while (parser.next() != XmlPullParser.END_DOCUMENT) {
            if (parser.eventType == XmlPullParser.END_TAG && parser.depth == 1 && parser.name == "map") {
                closedMap = true
            }
            if (parser.eventType != XmlPullParser.START_TAG || parser.depth != 2) continue
            val name = parser.getAttributeValue(null, "name") ?: continue
            when (parser.name) {
                "string" -> result[name] = parser.nextText()
                "int" -> parser.getAttributeValue(null, "value")?.toIntOrNull()?.let { result[name] = it }
                "long" -> parser.getAttributeValue(null, "value")?.toLongOrNull()?.let { result[name] = it }
                "float" -> parser.getAttributeValue(null, "value")?.toFloatOrNull()?.let { result[name] = it }
                "boolean" -> result[name] = parser.getAttributeValue(null, "value").toBoolean()
                "set" -> result[name] = readStringSet(parser)
            }
        }
        check(closedMap) { "设置文件不完整" }
        return result
    }

    private fun readStringSet(parser: XmlPullParser): Set<String> {
        val set = LinkedHashSet<String>()
        var type = parser.next()
        while (type != XmlPullParser.END_DOCUMENT &&
            !(type == XmlPullParser.END_TAG && parser.depth == 2 && parser.name == "set")
        ) {
            if (type == XmlPullParser.START_TAG && parser.name == "string") {
                set.add(parser.nextText())
            }
            type = parser.next()
        }
        check(type != XmlPullParser.END_DOCUMENT) { "设置文件不完整" }
        return set
    }

    private fun ZipInputStream.readBytesCapped(cap: Long): ByteArray {
        val out = ByteArrayOutputStream()
        val buffer = ByteArray(8192)
        var count = 0L
        while (true) {
            val read = read(buffer, 0, buffer.size)
            if (read < 0) break
            count += read
            check(count <= cap) { "备份包内单个文件过大" }
            out.write(buffer, 0, read)
        }
        return out.toByteArray()
    }
}
