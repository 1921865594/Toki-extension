package com.toki.lsposed.provider

import android.app.Application
import android.content.ContentValues
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.util.Log
import com.toki.lsposed.hook.HookRuntime
import io.github.libxposed.api.XposedInterface
import io.github.libxposed.api.XposedModule
import java.io.File
import java.io.StringWriter
import java.io.PrintWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean

/**
 * TikTok 崩溃诊断，仅挂 RuntimeInit 的最终未捕获异常处理器，不修改 TikTok 业务方法。
 * Android 10+ 通过 MediaStore 直接把完整 Java 崩溃栈写入公共 Download/Toki/Crash。
 */
internal object TikTokCrashDiagnostics {
    private const val TAG = "TokiCrashDiagnostics"
    private const val GLOBAL_PACKAGE = "com.zhiliaoapp.musically"
    private const val TRILL_PACKAGE = "com.ss.android.ugc.trill"
    private val installedProcesses = mutableSetOf<String>()
    private val writeGuard = AtomicBoolean(false)

    /**
     * 只在 TikTok 自身进程注册诊断 Hook；其它 LSPosed 作用域进程直接忽略。
     */
    @Synchronized
    fun install(module: XposedModule, processName: String) {
        if (!isTikTokProcess(processName) || !installedProcesses.add(processName)) return

        val method = runCatching {
            Class.forName("com.android.internal.os.RuntimeInit\$KillApplicationHandler", false, null)
                .getDeclaredMethod("uncaughtException", Thread::class.java, Throwable::class.java)
                .apply { isAccessible = true }
        }.getOrElse { error ->
            HookRuntime.failure("CrashDiagnostics", error, "Runtime 崩溃处理器不可用，交由 Root 日志监听")
            return
        }

        runCatching {
            module.hook(method)
                .setExceptionMode(XposedInterface.ExceptionMode.PASSTHROUGH)
                .intercept { chain ->
                    try {
                        val throwable = chain.args.getOrNull(1) as? Throwable
                        if (throwable != null) writeCrashReport(processName, throwable)
                    } catch (error: Throwable) {
                        // 诊断失败不能阻断宿主原始崩溃处理链。
                        Log.e(TAG, "写入 TikTok 崩溃报告失败", error)
                    }
                    chain.proceed()
                }
            HookRuntime.state("CrashDiagnostics", "已挂载 Runtime 崩溃监听")
            HookRuntime.event("TokiCrashDiagnostics", "已为 $processName 注册 Runtime 未捕获异常监听")
        }.onFailure { error ->
            HookRuntime.failure("CrashDiagnostics", error, "崩溃监听注册失败，交由 Root 日志监听")
        }
    }

    fun isTikTokProcess(processName: String): Boolean =
        processName == GLOBAL_PACKAGE || processName.startsWith("$GLOBAL_PACKAGE:") ||
            processName == TRILL_PACKAGE || processName.startsWith("$TRILL_PACKAGE:")

    private fun currentApplication(): Application? = runCatching {
        Class.forName("android.app.ActivityThread", false, null)
            .getDeclaredMethod("currentApplication")
            .apply { isAccessible = true }
            .invoke(null) as? Application
    }.getOrNull()

    private fun writeCrashReport(processName: String, throwable: Throwable) {
        if (!writeGuard.compareAndSet(false, true)) return
        try {
            val app = currentApplication() ?: return
            val now = System.currentTimeMillis()
            val stamp = SimpleDateFormat("yyyyMMdd_HHmmss_SSS", Locale.US).format(Date(now))
            val fileName = "Toki_TikTok_Crash_${stamp}_${android.os.Process.myPid()}.txt"
            val report = buildReport(app, processName, now, throwable)

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                writeWithMediaStore(app, fileName, report)
            } else {
                writeLegacyDownload(fileName, report)
            }
        } finally {
            writeGuard.set(false)
        }
    }

    private fun buildReport(
        app: Application,
        processName: String,
        time: Long,
        throwable: Throwable
    ): String = buildString {
        appendLine("Toki TikTok Crash Report")
        appendLine("time=$time")
        appendLine("package=${app.packageName}")
        appendLine("process=$processName")
        appendLine("pid=${android.os.Process.myPid()}")
        appendLine("android=${Build.VERSION.RELEASE} (api ${Build.VERSION.SDK_INT})")
        runCatching {
            val info = app.packageManager.getPackageInfo(app.packageName, 0)
            appendLine("versionName=${info.versionName}")
            @Suppress("DEPRECATION")
            val versionCode = if (Build.VERSION.SDK_INT >= 28) info.longVersionCode else info.versionCode.toLong()
            appendLine("versionCode=$versionCode")
        }
        appendLine("thread=${Thread.currentThread().name}")
        appendLine()
        appendLine("--- uncaught exception ---")
        val stack = StringWriter()
        PrintWriter(stack).use { writer -> throwable.printStackTrace(writer) }
        append(stack.toString())
    }

    private fun writeWithMediaStore(app: Application, fileName: String, report: String) {
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, fileName)
            put(MediaStore.MediaColumns.MIME_TYPE, "text/plain")
            put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/Toki/Crash")
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                put(MediaStore.MediaColumns.IS_PENDING, 1)
            }
        }
        val uri = app.contentResolver.insert(
            MediaStore.Downloads.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY),
            values
        ) ?: error("MediaStore insert returned null")

        try {
            app.contentResolver.openOutputStream(uri, "w").use { output ->
                requireNotNull(output) { "无法打开崩溃报告输出流" }
                output.write(report.toByteArray(Charsets.UTF_8))
                output.flush()
            }
            ContentValues().apply {
                put(MediaStore.MediaColumns.IS_PENDING, 0)
            }.also { app.contentResolver.update(uri, it, null, null) }
        } catch (error: Throwable) {
            runCatching { app.contentResolver.delete(uri, null, null) }
            throw error
        }
        Log.e(TAG, "TikTok 崩溃报告已写入 Download/Toki/Crash/$fileName")
    }

    @Suppress("DEPRECATION")
    private fun writeLegacyDownload(fileName: String, report: String) {
        val directory = File(
            Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS),
            "Toki/Crash"
        )
        if (!directory.exists() && !directory.mkdirs()) {
            error("无法创建 Download/Toki/Crash")
        }
        File(directory, fileName).writeText(report, Charsets.UTF_8)
    }
}
