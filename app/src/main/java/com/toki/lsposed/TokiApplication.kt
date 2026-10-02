package com.toki.lsposed

import android.app.Application
import com.toki.lsposed.provider.ConfigClient
import com.toki.lsposed.util.LSPosedStatusHelper
import com.toki.lsposed.ui.TikTokRootLogMonitor

/** 管理端进程入口，在界面创建前准备本地设置和框架发布通道。 */
class TokiApplication : Application() {
    /**
     * 初始化进程内唯一配置服务和框架连接监听，不启动宿主应用。
     * @return Unit；无入参。
     * Callers: Android Application 生命周期。
     */
    override fun onCreate() {
        super.onCreate()
        ConfigClient.init(this)
        LSPosedStatusHelper.init()
        // Root 诊断在 Toki 管理端独立运行，不向 TikTok 注入监控线程。
        TikTokRootLogMonitor.ensureStarted()
    }
}
