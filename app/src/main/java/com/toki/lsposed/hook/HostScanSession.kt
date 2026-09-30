package com.toki.lsposed.hook

/** 扫描和缓存发布的有序阶段；只有 READY 可以发起一次宿主关闭。 */
internal enum class HostScanPhase { IDLE, SCANNING, SAVING, READY, CLOSING, FAILED }

/** 单次发布的不可变进度；单位表示已处理工作，不表示预计耗时。 */
internal data class HostScanStatus(
    val phase: HostScanPhase = HostScanPhase.IDLE,
    val completed: Int = 0,
    val total: Int = 0,
    val detail: String = ""
)

/** 统一约束扫描、保存、失败与关闭顺序，供工作线程和主线程共同使用。 */
internal class HostScanSession {
    @Volatile var status = HostScanStatus()
        private set

    /** 开始唯一扫描。@return Unit。Callers: HostSymbols.initialize、单元测试。 */
    @Synchronized fun begin() {
        check(status.phase == HostScanPhase.IDLE)
        status = HostScanStatus(HostScanPhase.SCANNING, detail = "准备读取 TikTok 代码包")
    }

    /**
     * 发布单调递增的实际扫描工作量。
     * @param completed 已完成的工作单位。
     * @param total 此次扫描总单位。
     * @param detail 当前查找类或方法。
     * @return Unit；不合法或倒退的进度明确报告。
     * Callers: HostDexIndex进度回调、单元测试。
     */
    @Synchronized fun progress(completed: Int, total: Int, detail: String) {
        check(status.phase == HostScanPhase.SCANNING)
        require(total > 0 && completed in status.completed..total)
        require(status.total == 0 || status.total == total)
        status = HostScanStatus(HostScanPhase.SCANNING, completed, total, detail)
    }

    /** 开始保存结果，不能提前发出关闭许可。@return Unit。Callers: HostSymbols、单元测试。 */
    @Synchronized fun saving() {
        check(status.phase == HostScanPhase.SCANNING && status.total > 0 && status.completed == status.total)
        status = status.copy(phase = HostScanPhase.SAVING, detail = "校验代码身份并保存适配结果")
    }

    /**
     * 在缓存原子保存成功且重新读取核对一致后授予关闭许可。
     * @param detail 成功摘要，可包含未匹配的功能数量。
     * @return Unit。
     * Callers: HostSymbols、单元测试。
     */
    @Synchronized fun ready(detail: String) {
        check(status.phase == HostScanPhase.SAVING)
        status = status.copy(phase = HostScanPhase.READY, detail = detail)
    }

    /** 消费一次关闭许可，保留完成摘要供前台展示。@return 是否取得许可。Callers: HostScanController、单元测试。 */
    @Synchronized fun claimClose(): Boolean {
        if (status.phase != HostScanPhase.READY) return false
        status = status.copy(phase = HostScanPhase.CLOSING)
        return true
    }

    /**
     * 公开失败原因并停止自动关闭。
     * @param detail 可供用户采取行动的错误说明。
     * @return Unit。
     * Callers: HostSymbols、HostScanController、单元测试。
     */
    @Synchronized fun fail(detail: String) { status = status.copy(phase = HostScanPhase.FAILED, detail = detail) }

    /** 关闭失败提示，不伪造扫描成功。@return Unit。Callers: HostScanController、单元测试。 */
    @Synchronized fun dismissFailure() {
        check(status.phase == HostScanPhase.FAILED)
        status = HostScanStatus()
    }
}
