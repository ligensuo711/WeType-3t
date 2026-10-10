package com.xposed.wetypehook.wetype.settings

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class HostPreferencesFileTest {
    @get:Rule val temporaryFolder = TemporaryFolder()

    @Test
    fun readsExistingAppearanceValuesAndIgnoresUnrelatedEntries() {
        val file = temporaryFolder.newFile("wetype_settings.xml")
        file.writeText("""
            <map>
                <int name="light_color" value="-1110125356" />
                <float name="candidate_background_corner" value="60.0" />
                <boolean name="disable_hot_update" value="true" />
                <long name="host_sync_revision" value="1789468984736" />
                <string name="settings_write_token">ignored</string>
                <set name="unrelated"><string>ignored</string></set>
            </map>
        """.trimIndent())
        assertEquals(mapOf(
            "light_color" to -1110125356,
            "candidate_background_corner" to 60f,
            "disable_hot_update" to true
        ), HostPreferencesFile(file).read())
    }

    @Test
    fun readsAnotherWritersReplacementWithTheSameReader() {
        val file = temporaryFolder.newFile("wetype_settings.xml")
        val reader = HostPreferencesFile(file)
        file.writeText("""<map><int name="blur_radius" value="60" /></map>""")
        assertEquals(60, reader.read()["blur_radius"])
        val replacement = temporaryFolder.newFile("replacement.xml")
        replacement.writeText("""<map><int name="blur_radius" value="20" /></map>""")
        java.nio.file.Files.move(replacement.toPath(), file.toPath(), java.nio.file.StandardCopyOption.REPLACE_EXISTING)
        assertEquals(20, reader.read()["blur_radius"])
    }

    @Test
    fun usesTheCommittedBackupDuringAnInterruptedWrite() {
        val file = temporaryFolder.newFile("wetype_settings.xml")
        val reader = HostPreferencesFile(file)
        val backup = File("${file.path}.bak")
        backup.writeText("""<map><int name="blur_radius" value="60" /></map>""")
        file.writeText("<map><int")
        assertEquals(60, reader.read()["blur_radius"])
        file.writeText("""<map><int name="blur_radius" value="20" /></map>""")
        check(backup.delete())
        assertEquals(20, reader.read()["blur_radius"])
    }

    @Test
    fun rejectsPartialXmlInsteadOfPublishingAPartialSnapshot() {
        val file = temporaryFolder.newFile("wetype_settings.xml")
        file.writeText("""<map><int name="blur_radius" value="20" />""")
        assertThrows(Exception::class.java) { HostPreferencesFile(file).read() }
    }

    @Test
    fun missingFileHasNoSavedSettings() {
        assertEquals(emptyMap<String, Any>(), HostPreferencesFile(
            File(temporaryFolder.root, "wetype_settings.xml")
        ).read())
    }
}
