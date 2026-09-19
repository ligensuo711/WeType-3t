package com.xposed.wetypehook

import android.app.Activity
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.TimeUnit

/**
 * 微信输入法进程重启器：设置保存后调用，让 Hook 在新进程里读到最新配置。
 *
 * 双通道（只杀 `:hld` 输入法进程，主进程保留，系统会自动重新拉起输入法服务）：
 * 1. 有 Root：直接 `su` 查出 `:hld` 的 pid 并 `kill -9`，不依赖 hook 接收器；
 * 2. 无 Root / 未授权：发显式有序广播，由跑在输入法进程内（同 uid）的
 *    [com.xposed.wetypehook.wetype.hook.WeTypeImeRestart] 接收器下发 kill。
 * 广播回执区分三种结果：OK = 已下发；CANCELED + 空 data = 模块未在输入法中生效；
 * CANCELED + 非空 data = 已收到但无目标可杀；超时同样视为未生效。
 */
object ImeRestarter {

    const val WETYPE_PACKAGE = "com.tencent.wetype"
    const val ACTION_RESTART_IME = "com.xposed.wetypehook.action.RESTART_IME"

    private const val IME_PROCESS_NAME = "com.tencent.wetype:hld"
    private const val RESULT_TIMEOUT_MILLIS = 3000L
    private const val SU_TIMEOUT_SECONDS = 10L
    private const val ROOT_PROBE_TIMEOUT_SECONDS = 8L

    sealed interface Result {
        data object Restarted : Result
        data object ModuleInactive : Result
        data object Failed : Result
    }

    suspend fun restartWeType(context: Context): Result {
        if (hasRoot() && rootKillIme()) return Result.Restarted
        return restartViaBroadcast(context)
    }

    private suspend fun rootKillIme(): Boolean = withContext(Dispatchers.IO) {
        val pids = runCatching {
            val process = Runtime.getRuntime().exec(arrayOf("su", "-c", "ps -A -o PID,NAME"))
            if (!process.waitFor(SU_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                runCatching { process.destroyForcibly() }
                return@withContext false
            }
            if (process.exitValue() != 0) return@withContext false
            process.inputStream.bufferedReader().readText().lines().mapNotNull { line ->
                val parts = line.trim().split(Regex("\\s+"))
                if (parts.size >= 2 && parts[1] == IME_PROCESS_NAME) {
                    parts[0].toIntOrNull()?.takeIf { it > 0 }
                } else {
                    null
                }
            }
        }.getOrDefault(emptyList())
        if (pids.isEmpty()) return@withContext false
        runCatching {
            val kill = Runtime.getRuntime()
                .exec(arrayOf("su", "-c", "kill -9 ${pids.joinToString(" ")}"))
            if (!kill.waitFor(SU_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                runCatching { kill.destroyForcibly() }
                return@withContext false
            }
            kill.exitValue() == 0
        }.getOrDefault(false)
    }

    private suspend fun restartViaBroadcast(context: Context): Result {
        val appContext = context.applicationContext ?: context
        val installed = runCatching {
            appContext.packageManager.getLaunchIntentForPackage(WETYPE_PACKAGE)
        }.getOrNull() != null
        if (!installed) return Result.ModuleInactive
        return withTimeoutOrNull(RESULT_TIMEOUT_MILLIS) {
            suspendCancellableCoroutine { continuation ->
                val resultReceiver = object : BroadcastReceiver() {
                    override fun onReceive(receiverContext: Context, intent: Intent) {
                        if (!continuation.isActive) return
                        if (resultCode == Activity.RESULT_OK) {
                            continuation.resume(Result.Restarted) {}
                        } else if (resultData.isNullOrEmpty()) {
                            continuation.resume(Result.ModuleInactive) {}
                        } else {
                            continuation.resume(Result.Failed) {}
                        }
                    }
                }
                val delivered = runCatching {
                    appContext.sendOrderedBroadcast(
                        Intent(ACTION_RESTART_IME).setPackage(WETYPE_PACKAGE),
                        null,
                        resultReceiver,
                        Handler(Looper.getMainLooper()),
                        Activity.RESULT_CANCELED,
                        null,
                        null
                    )
                }.isSuccess
                if (!delivered && continuation.isActive) {
                    continuation.resume(Result.Failed) {}
                }
            }
        } ?: Result.ModuleInactive
    }

    private fun hasRoot(): Boolean = runCatching {
        val process = Runtime.getRuntime().exec(arrayOf("su", "-c", "id"))
        if (!process.waitFor(ROOT_PROBE_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
            runCatching { process.destroyForcibly() }
            return false
        }
        if (process.exitValue() != 0) return false
        val output = process.inputStream.bufferedReader().readText()
        output.contains("uid=0")
    }.getOrDefault(false)
}
