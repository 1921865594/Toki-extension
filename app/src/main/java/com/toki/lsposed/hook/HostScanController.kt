package com.toki.lsposed.hook

import android.app.Activity
import android.app.ActivityManager
import android.app.Application
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.Process
import android.util.Log
import java.lang.ref.WeakReference

/** 在宿主前台活动中显示独立 Material 3 窗口，使用模块资源和独立生命周期。 */
internal class HostScanController private constructor(private val app: Application) : Application.ActivityLifecycleCallbacks {
    companion object {
        val session = HostScanSession()
        private var controller: HostScanController? = null

        /**
         * 在宿主主进程订阅活动生命周期；进程级控制器独立调度退出，窗口随前台活动释放。
         * @param app 当前宿主Application。
         * @return Unit。
         * Callers: TokiModule.hookApplicationIfNeeded。
         */
        fun attach(app: Application) {
            if (controller != null) return
            controller = HostScanController(app).also {
                app.registerActivityLifecycleCallbacks(it)
                it.scheduleClose()
            }
        }

        /** 保存成功后通知主线程安排进程退出。@return Unit。Callers: HostSymbols 扫描线程。 */
        fun onScanReady() {
            Handler(Looper.getMainLooper()).post { controller?.scheduleClose() }
        }
    }

    private val main = Handler(Looper.getMainLooper())
    private var foreground = WeakReference<Activity>(null)
    private var dialog: HostScanDialog? = null
    /** 扫描 UI 创建失败时只禁用 UI，不得影响宿主扫描及业务 Hook。 */
    private var dialogCreationFailed = false
    private val refresh = object : Runnable {
        /** 更新可见窗口；无前台活动时停止刷新。@return Unit。Callers: 主线程Handler。 */
        override fun run() {
            render()
            if (dialog != null) main.postDelayed(this, 150)
        }
    }

    /**
     * 更新当前活动的模态窗口，后台扫描不会抢占其他应用前台。
     * @return Unit。
     * Callers: refresh、onActivityResumed。
     */
    private fun render() {
        val activity = foreground.get() ?: return
        if (activity.isFinishing || activity.isDestroyed) return
        scheduleClose()
        val state = session.status
        if (state.phase == HostScanPhase.IDLE) { dismissDialog(); return }
        if (dialog == null && !dialogCreationFailed) {
            runCatching {
                HostScanDialog(activity) { session.dismissFailure(); dismissDialog() }.also { it.show() }
            }.onSuccess { created ->
                dialog = created
                HookRuntime.event("TokiHostScan", "扫描窗口已创建 pid=${Process.myPid()}")
            }.onFailure { error ->
                // 扫描窗口是诊断/适配 UI，不是宿主功能依赖。任何 UI/资源异常都必须被吞掉，
                // 否则异常发生在主线程会直接终止 TikTok。后台 DEX 扫描继续运行。
                dialogCreationFailed = true
                Log.e("TokiHostScan", "扫描窗口创建失败，继续后台扫描，不影响 TikTok", error)
                HookRuntime.failure("HostScanDialog", error, "扫描窗口不可用，已降级为后台扫描")
            }
        }

        runCatching { dialog?.render(state) }.onFailure { error ->
            dialogCreationFailed = true
            Log.e("TokiHostScan", "扫描窗口刷新失败，继续后台扫描，不影响 TikTok", error)
            HookRuntime.failure("HostScanDialog", error, "扫描窗口刷新失败，已降级为后台扫描")
            dismissDialog()
        }
    }

    /**
     * 仅在成功提交后安排一次退出；退出任务不属于窗口刷新，不随 Activity 暂停取消。
     * @return Unit。
     * Callers: attach、onScanReady、render。
     */
    private fun scheduleClose() {
        if (!session.claimClose()) return
        HookRuntime.event("TokiHostScan", "结果已提交，1800ms 后退出主进程 pid=${Process.myPid()}")
        main.postDelayed({ closeHost() }, 1800L)
    }

    /**
     * 移除宿主任务后结束当前主进程，不申请 Root，也不将关闭描述为完整包强制停止。
     * @return Unit；系统拒绝移除任务时公开错误并停止关闭，不将异常当作成功。
     * Callers: scheduleClose。
     */
    private fun closeHost() {
        try {
            app.getSystemService(ActivityManager::class.java).appTasks.forEach { it.finishAndRemoveTask() }
        } catch (error: RuntimeException) {
            Log.e("TokiHostScan", "无法移除宿主任务，自动关闭已停止", error)
            HookRuntime.event("TokiHostScan", "自动关闭失败 pid=${Process.myPid()} error=${error.javaClass.name}")
            HookRuntime.failure("HostSymbols", error, "自动关闭失败")
            session.fail("适配结果已保存，但无法关闭 TikTok：${error.javaClass.simpleName}。请手动关闭后重新打开。")
            return
        }
        dismissDialog()
        HookRuntime.event("TokiHostScan", "已移除自身任务，正在退出主进程 pid=${Process.myPid()}")
        Process.killProcess(Process.myPid())
    }

    /** 释放窗口及其活动引用，保留扫描进度。@return Unit。Callers: 生命周期、失败关闭、render。 */
    private fun dismissDialog() {
        main.removeCallbacks(refresh)
        dialog?.dismiss()
        dialog = null
    }

    /** 活动进入前台后恢复窗口。@param activity 宿主活动。@return Unit。Callers: Android Application。 */
    override fun onActivityResumed(activity: Activity) {
        dismissDialog()
        dialogCreationFailed = false
        foreground = WeakReference(activity)
        main.post(refresh)
    }

    /** 活动离开前台时释放窗口。@param activity 宿主活动。@return Unit。Callers: Android Application。 */
    override fun onActivityPaused(activity: Activity) {
        if (foreground.get() === activity) { dismissDialog(); foreground.clear() }
    }

    /** 销毁活动时清理引用。@param activity 宿主活动。@return Unit。Callers: Android Application。 */
    override fun onActivityDestroyed(activity: Activity) {
        if (foreground.get() === activity) { dismissDialog(); foreground.clear() }
    }

    /** 创建阶段不显示窗口。@param activity 活动。@param savedInstanceState 保存状态。@return Unit。Callers: Android Application。 */
    override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) = Unit
    /** 启动阶段等待恢复回调。@param activity 活动。@return Unit。Callers: Android Application。 */
    override fun onActivityStarted(activity: Activity) = Unit
    /** 暂停阶段已完成窗口释放。@param activity 活动。@return Unit。Callers: Android Application。 */
    override fun onActivityStopped(activity: Activity) = Unit
    /** 扫描状态属于进程，不写入活动状态。@param activity 活动。@param outState 保存状态。@return Unit。Callers: Android Application。 */
    override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) = Unit
}
