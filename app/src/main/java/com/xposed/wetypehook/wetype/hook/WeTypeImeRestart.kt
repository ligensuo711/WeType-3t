package com.xposed.wetypehook.wetype.hook

import android.app.Activity
import android.app.ActivityManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import com.xposed.wetypehook.xposed.HookEnvironment
import com.xposed.wetypehook.xposed.Log
import com.xposed.wetypehook.xposed.findMethod
import com.xposed.wetypehook.xposed.hookAfter
import com.xposed.wetypehook.xposed.sameAs

/**
 * 输入法进程定点重启（无 Root）：只在 `:hld` 输入法进程内注册（单接收器，回执唯一），
 * 与微信输入法同 uid，可直接 [android.os.Process.sendSignal]。
 *
 * 收到 [ACTION_RESTART_IME] 有序广播后杀掉自身进程（即 `:hld`），主进程不动，
 * 系统会自动重新拉起输入法服务。
 *
 * 触发方（模块 App / 宿主寄生页）发显式广播并等回执：
 * resultCode OK = 已下发 kill；CANCELED + 空 resultData = 本模块未在输入法中生效；
 * CANCELED + 非空 resultData = 已收到但无目标可杀。
 */
internal object WeTypeImeRestart {

    const val ACTION_RESTART_IME = "com.xposed.wetypehook.action.RESTART_IME"

    private const val WETYPE_PACKAGE = "com.tencent.wetype"
    private const val MODULE_PACKAGE = "com.xposed.wetypehook"
    private const val IME_PROCESS_NAME = "com.tencent.wetype:hld"

    @Volatile
    private var installed = false

    @Volatile
    private var receiverRegistered = false

    fun install() {
        if (installed) return
        installed = true
        runCatching {
            findMethod("android.app.Application") {
                name == "attach" && parameterTypes.sameAs(Context::class.java)
            }.hookAfter { param ->
                val context = param.args[0] as? Context ?: return@hookAfter
                registerRestartReceiver(context.applicationContext ?: context)
            }
            Log.i("WeType IME restart receiver installation armed")
        }.onFailure {
            installed = false
            Log.e("WeType IME restart receiver install failed: $it")
        }
    }

    private fun registerRestartReceiver(appContext: Context) {
        if (receiverRegistered) return
        // 只在 :hld 输入法进程注册：单接收器，回执唯一；主进程不动。
        // 注意用 Application.getProcessName() 取当前进程名：
        // applicationInfo.processName 是 manifest 默认值，在 :hld 里也会返回主包名。
        val processName = runCatching { android.app.Application.getProcessName() }.getOrNull()
            ?: runCatching { appContext.applicationInfo.processName }.getOrNull()
            ?: appContext.packageName
        if (processName != IME_PROCESS_NAME) {
            Log.i("WeType IME restart skipped in $processName (only $IME_PROCESS_NAME registers)")
            return
        }
        receiverRegistered = true
        runCatching {
            val filter = IntentFilter(ACTION_RESTART_IME)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                appContext.registerReceiver(restartReceiver, filter, Context.RECEIVER_EXPORTED)
            } else {
                @Suppress("DEPRECATION")
                appContext.registerReceiver(restartReceiver, filter)
            }
            Log.i("WeType IME restart receiver registered in ${appContext.packageName}")
        }.onFailure {
            receiverRegistered = false
            Log.e("WeType IME restart receiver register failed: $it")
        }
    }

    private val restartReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action != ACTION_RESTART_IME) return
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                val senderPackages = runCatching {
                    context.packageManager.getPackagesForUid(sentFromUid)
                }.getOrNull().orEmpty()
                if (MODULE_PACKAGE !in senderPackages && WETYPE_PACKAGE !in senderPackages) {
                    Log.e("WeType IME restart rejected for ${senderPackages.contentToString()}")
                    setResultCode(Activity.RESULT_CANCELED)
                    return
                }
            }
            val killed = killImeProcess(context)
            if (killed) {
                Log.i("WeType IME restart: kill signal sent to $IME_PROCESS_NAME")
                setResult(Activity.RESULT_OK, "killed", null)
            } else {
                Log.e("WeType IME restart: no $IME_PROCESS_NAME target found")
                setResult(Activity.RESULT_CANCELED, "no-target", null)
            }
        }
    }

    private fun killImeProcess(context: Context): Boolean {
        val activityManager =
            context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager ?: return false
        val targets = runCatching { activityManager.runningAppProcesses }.getOrNull().orEmpty()
            .filter { it.processName == IME_PROCESS_NAME }
            .map { it.pid }
            .filter { it > 0 }
        if (targets.isEmpty()) return false
        var killed = false
        targets.forEach { pid ->
            runCatching {
                android.os.Process.sendSignal(pid, android.os.Process.SIGNAL_KILL)
                killed = true
            }.onFailure {
                Log.e("WeType IME restart: sendSignal($pid) failed: $it")
            }
        }
        return killed
    }
}
