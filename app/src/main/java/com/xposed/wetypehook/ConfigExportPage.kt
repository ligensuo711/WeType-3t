package com.xposed.wetypehook

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Process
import android.os.SystemClock
import android.provider.DocumentsContract
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import com.xposed.wetypehook.wetype.settings.ModuleSettingsBackup
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import top.yukonga.miuix.kmp.basic.BasicComponent
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.SmallTitle
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.theme.MiuixTheme

/**
 * 配置导出：备份与恢复全部模块设置（`shared_prefs` 全量，不含剪贴板历史）。
 *
 * 挂在设置页下的独立二级页，仅在模块 App 进程内使用（读写本应用私有偏好）。
 */
/**
 * 注意：不要加 @Composable——调用点在 LazyColumn 的作用域函数体内（非组合上下文），
 * 状态与选择器都记在下方唯一的 item 内部。
 */
fun LazyListScope.ConfigExportItems(context: Context) {
    item {
        ConfigExportBody(context)
    }
}

@Composable
private fun ConfigExportBody(context: Context) {
    val resolver = context.contentResolver
    val scope = rememberCoroutineScope()
    var busy by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf("") }

    fun startTask(block: suspend () -> String) {
        if (busy) return
        busy = true
        message = ""
        scope.launch {
            message = runCatching { withContext(Dispatchers.IO) { block() } }
                .getOrElse { error -> "失败：${error.message ?: error.javaClass.simpleName}" }
            busy = false
        }
    }

    fun exportSettings(treeUri: Uri) {
        startTask {
            val fileName = ModuleSettingsBackup.buildFileName(System.currentTimeMillis())
            // SAF 返回的是 tree URI，创建文件必须用 tree 下的 document URI。
            val parentUri = DocumentsContract.buildDocumentUriUsingTree(
                treeUri,
                DocumentsContract.getTreeDocumentId(treeUri)
            )
            val documentUri = DocumentsContract.createDocument(
                resolver,
                parentUri,
                ModuleSettingsBackup.MIME_ZIP,
                fileName
            ) ?: throw IllegalStateException("无法在所选文件夹创建文件")
            try {
                val output = resolver.openOutputStream(documentUri, "wt")
                    ?: throw IllegalStateException("无法写入所选文件夹")
                val summary = output.use { stream ->
                    ModuleSettingsBackup.exportToStream(context, stream).getOrThrow()
                }
                "导出完成：$fileName\n共 ${summary.fileCount} 个设置文件，${summary.keyCount} 项" +
                    "\n重装/换签名/换机后在本页「导入配置」恢复"
            } catch (t: Throwable) {
                runCatching { DocumentsContract.deleteDocument(resolver, documentUri) }
                throw t
            }
        }
    }

    fun importSettings(uri: Uri) {
        startTask {
            val input = resolver.openInputStream(uri)
                ?: throw IllegalStateException("无法读取所选文件")
            val summary = input.use { stream ->
                ModuleSettingsBackup.importFromStream(context, stream).getOrThrow()
            }
            withContext(Dispatchers.Main) {
                message = "恢复完成：${summary.restoredFiles} 个文件，${summary.restoredKeys} 项" +
                    "\n正在重启 App 使设置生效…"
            }
            delay(2000)
            restartModuleApp(context)
            "恢复完成"
        }
    }

    val treePicker =
        rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
            if (uri != null) {
                runCatching {
                    resolver.takePersistableUriPermission(
                        uri,
                        Intent.FLAG_GRANT_READ_URI_PERMISSION or
                            Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                    )
                }
                exportSettings(uri)
            }
        }
    val filePicker =
        rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            if (uri != null) importSettings(uri)
        }

    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
        SmallTitle(text = "配置导出")
        Card(
            modifier = Modifier.padding(horizontal = 16.dp),
            insideMargin = PaddingValues(16.dp)
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    text = "备份全部模块设置（开关、颜色、手势、Logo、备份账号等），不含剪贴板历史；" +
                        "换机、重装、换签名之前先导出。",
                    style = MiuixTheme.textStyles.body2,
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary
                )
            }
        }
        Card(
            modifier = Modifier.padding(horizontal = 16.dp),
            insideMargin = PaddingValues(0.dp)
        ) {
            Column {
                BasicComponent(
                    title = "导出配置",
                    summary = "选择文件夹并保存为 zip",
                    onClick = { treePicker.launch(null) }
                )
                BasicComponent(
                    title = "导入配置",
                    summary = "从备份包恢复，完成后自动重启 App",
                    onClick = {
                        filePicker.launch(arrayOf("application/zip", "application/octet-stream"))
                    }
                )
            }
        }
        if (busy || message.isNotEmpty()) {
            SmallTitle(text = "任务状态")
            Card(
                modifier = Modifier.padding(horizontal = 16.dp),
                insideMargin = PaddingValues(16.dp)
            ) {
                Text(
                    text = if (busy && message.isEmpty()) "处理中…" else message,
                    style = MiuixTheme.textStyles.body2,
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary
                )
            }
        }
    }
}

/**
 * 设置恢复后重启模块 App 进程，让全部界面重新读取偏好。
 * 用非精确闹钟做兜底拉起（无需 SCHEDULE_EXACT_ALARM 权限），再杀掉本进程；
 * 系统随后用新进程重建任务栈顶 Activity。
 */
private fun restartModuleApp(context: Context) {
    val launch = context.packageManager.getLaunchIntentForPackage(context.packageName)
    if (launch != null) {
        launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
        val pending = PendingIntent.getActivity(
            context,
            0,
            launch,
            PendingIntent.FLAG_CANCEL_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val alarm = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        alarm.set(AlarmManager.ELAPSED_REALTIME_WAKEUP, SystemClock.elapsedRealtime() + 800, pending)
    }
    Process.killProcess(Process.myPid())
}
