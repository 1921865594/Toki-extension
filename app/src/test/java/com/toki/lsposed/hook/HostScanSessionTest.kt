package com.toki.lsposed.hook

import org.junit.Assert.*
import org.junit.Test

/** 验证扫描缓存提交和自动关闭许可的完整顺序。 */
class HostScanSessionTest {
    /** 验证只有保存完成后允许关闭，重复回调最多获得一次许可。@return Unit。Callers: JUnit。 */
    @Test fun closeRequiresSavedCacheAndIsConsumedOnce() {
        val session = HostScanSession()
        assertFalse(session.claimClose())
        session.begin()
        session.progress(0, 100, "开始")
        assertFalse(session.claimClose())
        session.progress(100, 100, "方法已检查")
        session.saving()
        assertFalse(session.claimClose())
        session.ready("保存成功")
        assertTrue(session.claimClose())
        assertFalse(session.claimClose())
        assertEquals(HostScanPhase.CLOSING, session.status.phase)
        assertEquals("保存成功", session.status.detail)
    }

    /** 验证保存失败不会关闭宿主，关闭错误提示不冒充扫描成功。@return Unit。Callers: JUnit。 */
    @Test fun failureNeverGrantsClose() {
        val session = HostScanSession()
        session.begin()
        session.progress(10, 10, "完成扫描")
        session.saving()
        session.fail("存储错误")
        assertFalse(session.claimClose())
        assertEquals("存储错误", session.status.detail)
        session.dismissFailure()
        assertEquals(HostScanPhase.IDLE, session.status.phase)
        assertFalse(session.claimClose())
    }

    /** 验证进度倒退明确失败，不显示虚构进度。@return Unit。Callers: JUnit。 */
    @Test(expected = IllegalArgumentException::class) fun backwardProgressIsRejected() {
        val session = HostScanSession()
        session.begin()
        session.progress(20, 100, "当前")
        session.progress(10, 100, "倒退")
    }

    /** 验证扫描未结束时不能进入缓存提交阶段。@return Unit。Callers: JUnit。 */
    @Test(expected = IllegalStateException::class) fun incompleteScanCannotBeSaved() {
        val session = HostScanSession()
        session.begin()
        session.progress(99, 100, "未完成")
        session.saving()
    }
}
