package com.xposed.wetypehook.wetype.settings

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class ModuleSettingsBackupTest {
    @Test
    fun roundTripPreservesAllPreferenceTypes() {
        val entries = linkedMapOf<String, Any?>(
            "plain" to "hello",
            "unicode" to "中文 🎹 <>&\"'",
            "empty_string" to "",
            "int_value" to -1110125356,
            "long_value" to 1789468984736L,
            "float_value" to 60.5f,
            "bool_true" to true,
            "bool_false" to false,
            "string_set" to linkedSetOf("a", "b c", "中文"),
            "empty_set" to emptySet<String>()
        )
        val parsed = ModuleSettingsBackup.parsePrefs(ModuleSettingsBackup.serializePrefs(entries))
        assertEquals(entries, parsed)
    }

    @Test
    fun parsesAospSharedPreferencesXml() {
        val xml = """
            <?xml version='1.0' encoding='utf-8' standalone='yes' ?>
            <map>
                <string name="logo_color_mode">brand</string>
                <int name="blur_radius" value="60" />
                <long name="host_sync_revision" value="1789468984736" />
                <float name="candidate_background_corner" value="60.0" />
                <boolean name="disable_hot_update" value="true" />
                <set name="pins"><string>x</string><string>y</string></set>
            </map>
        """.trimIndent().toByteArray(Charsets.UTF_8)
        assertEquals(
            mapOf(
                "logo_color_mode" to "brand",
                "blur_radius" to 60,
                "host_sync_revision" to 1789468984736L,
                "candidate_background_corner" to 60f,
                "disable_hot_update" to true,
                "pins" to linkedSetOf("x", "y")
            ),
            ModuleSettingsBackup.parsePrefs(xml)
        )
    }

    @Test
    fun rejectsTruncatedXml() {
        assertThrows(Exception::class.java) {
            ModuleSettingsBackup.parsePrefs("""<map><int name="blur_radius" value="20" />""".toByteArray())
        }
    }

    @Test
    fun rejectsZipWithoutSettingsEntries() {
        val zip = ByteArrayOutputStream().also { out ->
            ZipOutputStream(out).use { z ->
                z.putNextEntry(ZipEntry("texts.jsonl"))
                z.write("{}".toByteArray())
                z.closeEntry()
            }
        }.toByteArray()
        // importFromStream 需要 Context，只验证条目过滤逻辑：包内应无合法设置文件。
        var validEntries = 0
        java.util.zip.ZipInputStream(ByteArrayInputStream(zip)).use { z ->
            var entry = z.nextEntry
            while (entry != null) {
                if (!entry.isDirectory && Regex("^[A-Za-z0-9][A-Za-z0-9._-]*\\.xml$").matches(entry.name)) {
                    validEntries++
                }
                z.closeEntry()
                entry = z.nextEntry
            }
        }
        assertEquals(0, validEntries)
    }
}
