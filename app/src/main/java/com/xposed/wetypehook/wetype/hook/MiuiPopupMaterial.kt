package com.xposed.wetypehook.wetype.hook

import com.xposed.wetypehook.wetype.settings.GlassMaterialOverrides

/** Share appearance parameters; the popup toggle can enable glass independently. */
internal fun GlassMaterialOverrides.forMiuiPopup(): GlassMaterialOverrides = withGlassEnabled(true)
