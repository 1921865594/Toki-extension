package com.toki.lsposed.provider

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** 诊断监听只允许 TikTok 主进程及其子进程，避免把 Runtime 崩溃 Hook 扩散到其它进程。 */
class TikTokCrashDiagnosticsTest {
    @Test fun acceptsTikTokMainAndChildProcesses() {
        assertTrue(TikTokCrashDiagnostics.isTikTokProcess("com.zhiliaoapp.musically"))
        assertTrue(TikTokCrashDiagnostics.isTikTokProcess("com.zhiliaoapp.musically:push"))
        assertTrue(TikTokCrashDiagnostics.isTikTokProcess("com.ss.android.ugc.trill"))
        assertTrue(TikTokCrashDiagnostics.isTikTokProcess("com.ss.android.ugc.trill:tools"))
    }

    @Test fun rejectsLookalikeOrOtherPackages() {
        assertFalse(TikTokCrashDiagnostics.isTikTokProcess("com.zhiliaoapp.musically.fake"))
        assertFalse(TikTokCrashDiagnostics.isTikTokProcess("com.toki.lsposed"))
        assertFalse(TikTokCrashDiagnostics.isTikTokProcess("android"))
    }
}
