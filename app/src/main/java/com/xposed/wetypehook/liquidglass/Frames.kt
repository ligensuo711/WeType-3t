package com.xposed.wetypehook.liquidglass

// Coroutine frame helper (mirrors the catalog's expect/actual awaitFrame()).

import androidx.compose.runtime.withFrameNanos

suspend fun awaitFrame() {
    withFrameNanos { }
}
