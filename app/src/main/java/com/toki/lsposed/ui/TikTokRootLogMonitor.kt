package com.toki.lsposed.ui

import android.util.Log
import java.io.IOException
import java.util.concurrent.Executors

/**
 * Root 侧独立诊断监听。
 *
 * 监听 Android crash/events/system 日志中的 Java crash、native fatal signal 与 ANR 事件，
 * 不向 TikTok 进程注入额外代码。Root shell 脱离 Toki 管理端后持续写入公共 Download。
 */
internal object TikTokRootLogMonitor {
    private const val TAG = "TokiRootLogMonitor"
    private const val LOG_FILE = "/sdcard/Download/Toki/TikTok_Runtime.log"
    private const val PID_FILE = "/sdcard/Download/Toki/.tiktok_monitor.pid"
    private val executor = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "TokiTikTokLogMonitor").apply { isDaemon = true }
    }

    /** 异步尝试启动 Root 监听；无 Root 时安静失败，不影响 Toki 或 TikTok。 */
    fun ensureStarted() {
        executor.execute {
            runCatching { start() }
                .onFailure { Log.i(TAG, "Root TikTok 诊断监听未启动：${it.message}") }
        }
    }

    /** 停止已有 Root 监听，供后续诊断控制使用。 */
    fun stopAsync() {
        executor.execute {
            runCatching { runSu(stopCommand()) }
                .onFailure { Log.w(TAG, "停止 Root TikTok 诊断监听失败", it) }
        }
    }

    private fun start() {
        runSu(startCommand())
    }

    private fun startCommand(): String {
        val d = '$'
        val script = """
            mkdir -p '$LOG_DIR'
            if [ -f '$PID_FILE' ]; then
                old_pid=${d}(cat '$PID_FILE' 2>/dev/null)
                if [ -n "${d}old_pid" ] && kill -0 "${d}old_pid" 2>/dev/null; then
                    exit 0
                fi
                rm -f '$PID_FILE'
            fi
            (
                printf '\n=== Toki TikTok crash/ANR monitor started %s ===\n' "${d}(date '+%Y-%m-%d %H:%M:%S')" >> '$LOG_FILE'
                capture=0
                buffer='$LOG_FILE.capture.'${d}${d}
                rm -f "${d}buffer"
                trap 'rm -f "${d}buffer"' EXIT HUP INT TERM
                logcat -v threadtime -b crash -b events -b system 2>/dev/null | while IFS= read -r line; do
                    case "${d}line" in
                        *"FATAL EXCEPTION"*|*"Fatal signal"*|*"SIGSEGV"*|*"SIGABRT"*|*"SIGBUS"*|*"am_crash"*|*"am_anr"*|*"ANR in"*|*"Application Not Responding"*|*"not responding"*)
                            : > "${d}buffer"
                            capture=80
                            ;;
                    esac
                    if [ "${d}capture" -gt 0 ]; then
                        printf '%s\n' "${d}line" >> "${d}buffer"
                        capture=${d}((capture - 1))
                        if [ "${d}capture" -eq 0 ]; then
                            if grep -Eq 'com\.zhiliaoapp\.musically|com\.ss\.android\.ugc\.trill' "${d}buffer" 2>/dev/null; then
                                printf '%s\n' "" >> '$LOG_FILE'
                                printf '=== TikTok crash/ANR trigger ===\n' >> '$LOG_FILE'
                                cat "${d}buffer" >> '$LOG_FILE'
                            fi
                            : > "${d}buffer"
                        fi
                    fi
                done
            ) &
            echo ${d}! > '$PID_FILE'
        """.trimIndent()
        return "nohup sh -c ${shellQuote(script)} >/dev/null 2>&1 &"
    }

    private const val LOG_DIR = "/sdcard/Download/Toki"

    private fun stopCommand(): String =
        "if [ -f '$PID_FILE' ]; then p=\$(cat '$PID_FILE' 2>/dev/null); [ -n \"\$p\" ] && kill \"\$p\" 2>/dev/null; rm -f '$PID_FILE'; fi"

    private fun runSu(command: String) {
        val process = try {
            ProcessBuilder("su", "-c", command)
                .redirectErrorStream(true)
                .start()
        } catch (error: IOException) {
            throw IllegalStateException("无法启动 su", error)
        }
        val output = process.inputStream.bufferedReader().use { it.readText() }
        val code = process.waitFor()
        if (code != 0) {
            throw IllegalStateException("su exit=$code output=$output")
        }
    }

    /** 单引号 Shell 字符串转义，脚本本身不包含任何用户输入。 */
    private fun shellQuote(value: String): String =
        "'" + value.replace("'", "'\"'\"'") + "'"
}
