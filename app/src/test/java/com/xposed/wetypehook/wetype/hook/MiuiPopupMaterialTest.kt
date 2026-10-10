package com.xposed.wetypehook.wetype.hook

import com.xposed.wetypehook.wetype.settings.GlassMaterialOverrides
import org.junit.Assert.*
import org.junit.Test

class MiuiPopupMaterialTest {
    @Test fun appearanceParametersAreSharedWithoutPopupLimits() {
        val keyboard = GlassMaterialOverrides().withGlassEnabled(true)
        val popup = keyboard.forMiuiPopup()
        assertEquals(50f, keyboard.glass!![19])
        assertEquals(keyboard.glass, popup.glass)
        assertEquals(keyboard.bloom, popup.bloom)
        assertEquals(keyboard.blurRadii, popup.blurRadii)
        assertEquals(1, popup.materialType)
    }
    @Test fun customOpticsAndTintArePreserved() {
        val keyboard = GlassMaterialOverrides().withGlassEnabled(true).let { it.copy(
            glass = it.glass!!.toMutableList().apply {
                this[19] = 3f; this[21] = 20f; this[28] = 0.05f; this[32] = 1.05f
                this[14] = 1f; this[36] = 0.8f
            }) }
        val popup = keyboard.forMiuiPopup()
        for (index in listOf(19, 21, 28, 32)) assertEquals(keyboard.glass!![index], popup.glass!![index])
        assertEquals(1f, popup.glass!![14])
        assertEquals(0.8f, popup.glass!![36])
        assertEquals(0.8f, keyboard.glass!![36])
    }
}
