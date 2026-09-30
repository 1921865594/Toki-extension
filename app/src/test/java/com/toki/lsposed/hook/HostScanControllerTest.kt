package com.toki.lsposed.hook

import android.app.Activity
import android.os.Looper
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode
import org.robolectric.shadows.ShadowProcess
import java.time.Duration

/** 使用 Android 主线程调度验证扫描结束后的退出，不终止测试 JVM。 */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [35])
@LooperMode(LooperMode.Mode.PAUSED)
class HostScanControllerTest {
    private val events = mutableListOf<String>()

    /** 初始化框架日志接收器和模拟进程，隔离测试进程的静态会话。@return Unit；无入参。Callers: JUnit。 */
    @Before fun prepare() {
        HostScanController::class.java.getDeclaredField("controller").apply { isAccessible = true }.set(null, null)
        HostScanController.session.fail("测试会话重置")
        HostScanController.session.dismissFailure()
        HookRuntime.start("test.host") { tag, message -> events.add("$tag:$message") }
        ShadowProcess.setPid(4703)
    }

    /** 完成一次模拟缓存提交，不创建窗口。@return Unit；无入参。Callers: 本类测试。 */
    private fun commitScan() {
        HostScanController.session.apply {
            begin()
            progress(1, 1, "完成")
            saving()
            ready("保存成功")
        }
    }

    /**
     * 验证倒计时启动后 Activity 暂停、销毁仍准时退出，并且重复通知不重复退出。
     * @return Unit；无入参。
     * Callers: JUnit。
     */
    @Test fun activityPauseDoesNotCancelScheduledExit() {
        val app = RuntimeEnvironment.getApplication()
        HostScanController.attach(app)
        commitScan()
        HostScanController.onScanReady()
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(HostScanPhase.CLOSING, HostScanController.session.status.phase)
        val activity = Robolectric.buildActivity(Activity::class.java).create().start().resume()
        activity.pause()
        activity.stop().destroy()
        HostScanController.onScanReady()
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(1799))
        assertFalse(ShadowProcess.wasKilled(4703))
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(1))
        assertTrue(ShadowProcess.wasKilled(4703))
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(3))
        assertEquals(1, events.count { it.contains("正在退出主进程") })
    }

    /**
     * 验证扫描早于 Application 接入时，接入后无需任何 Activity 也会安排退出。
     * @return Unit；无入参。
     * Callers: JUnit。
     */
    @Test fun committedBeforeApplicationAttachmentStillExits() {
        commitScan()
        HostScanController.onScanReady()
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(HostScanPhase.READY, HostScanController.session.status.phase)
        HostScanController.attach(RuntimeEnvironment.getApplication())
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(1800))
        assertTrue(ShadowProcess.wasKilled(4703))
        assertEquals(1, events.count { it.contains("正在退出主进程") })
    }

    /** 验证保存未完成或失败不能结束进程。@return Unit；无入参。Callers: JUnit。 */
    @Test fun failedScanDoesNotExit() {
        HostScanController.attach(RuntimeEnvironment.getApplication())
        HostScanController.session.begin()
        HostScanController.session.fail("保存失败")
        HostScanController.onScanReady()
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(5))
        assertFalse(ShadowProcess.wasKilled(4703))
        assertTrue(events.isEmpty())
    }
}
