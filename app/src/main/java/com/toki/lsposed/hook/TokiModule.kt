package com.toki.lsposed.hook

import android.app.Application
import android.app.Instrumentation
import android.util.Log
import com.toki.lsposed.provider.ConfigClient
import io.github.libxposed.api.XposedInterface
import io.github.libxposed.api.XposedModule
import io.github.libxposed.api.XposedModuleInterface.ModuleLoadedParam
import io.github.libxposed.api.XposedModuleInterface.PackageLoadedParam
import io.github.libxposed.api.XposedModuleInterface.PackageReadyParam

/**
 * LSPosed 现代化 Hook 入口模块。
 *
 * 继承自 [XposedModule]，符合 libxposed API 102 规范。
 * 框架在目标进程启动与包加载时自动实例化本类并依序调用生命周期回调。
 *
 * 模块注册配置：`resources/META-INF/xposed/java_init.list`
 * 作用域配置：`resources/META-INF/xposed/scope.list`
 */
class TokiModule : XposedModule() {
    private var processName = ""
    private var configurationAvailableAtLoad = false

    companion object {
        private const val TAG = "TokiModule"

        /** TikTok 国际版主包名 */
        const val PKG_TIKTOK_GLOBAL = "com.zhiliaoapp.musically"

        /** TikTok 国际版 Trill 渠道包名 */
        const val PKG_TIKTOK_TRILL = "com.ss.android.ugc.trill"

        private val SUPPORTED_PACKAGES = setOf(PKG_TIKTOK_GLOBAL, PKG_TIKTOK_TRILL)
    }

    /**
     * 模块被注入并加载到进程时的生命周期回调。
     *
     * @param param 包含当前进程名称等注入上下文信息的载荷对象。
     * @return Unit。
     *
     * Callers:
     * - `io.github.libxposed.api.XposedModule.attachFramework`: LSPosed 框架完成运行时桥接后的底层通知。
     */
    override fun onModuleLoaded(param: ModuleLoadedParam) {
        HookRuntime.start(param.processName) { tag, message -> log(Log.INFO, tag, message) }
        processName = param.processName
        configurationAvailableAtLoad = ConfigClient.initHost({ getRemotePreferences(ConfigClient.FRAMEWORK_GROUP) }) {
            val error = ConfigClient.syncError
            if (error != null) HookRuntime.failure("ConfigClient", error, "配置未就绪，功能暂停")
            else HookRuntime.configurationReady(if (configurationAvailableAtLoad) "配置已就绪" else "配置就绪，等待重新打开")
        }
        log(Log.INFO, TAG, "框架配置初始化 ready=$configurationAvailableAtLoad；宿主不访问 Toki 配置组件")
        log(Log.INFO, TAG, "模块加载成功，当前进程名称: ${param.processName}")
    }

    /**
     * 在宿主 onCreate 前应用已验证配置；诊断和方法查找独立于配置连接状态。
     *
     * @return Unit。
     *
     * Callers:
     * - `com.toki.lsposed.hook.TokiModule.onPackageLoaded`: 目标包加载时触发。
     */
    private fun hookApplication() {
        val onCreateMethod = Instrumentation::class.java.getMethod("callApplicationOnCreate", Application::class.java)
        hook(onCreateMethod).setExceptionMode(XposedInterface.ExceptionMode.PASSTHROUGH).intercept { chain ->
            val app = chain.args[0] as Application
            HookRuntime.attach(app)
            if (processName == app.packageName) HostScanController.attach(app)
            if (configurationAvailableAtLoad && ConfigClient.isReady) {
                refreshConfiguration(app)
            }
            chain.proceed()
        }
        log(Log.INFO, TAG, "Application 配置应用与独立诊断入口已注册")
    }

    /**
     * 逐功能应用完整配置，各功能错误明确报告且不影响其它功能或宿主启动。
     * @param app 宿主应用实例。
     * @return Unit。
     * Callers: hookApplication；运行期更新由各功能的配置监听负责。
     */
    private fun refreshConfiguration(app: Application) {
        HookRuntime.configure("SimHook") { SimHook.refreshConfig(app) }
        HookRuntime.configure("LocaleHook") { LocaleHook.refreshConfig(app); LocaleHook.applyToApplication(app) }
        HookRuntime.configure("TimeZoneHook") { TimeZoneHook.refreshConfig(app); TimeZoneHook.applyToApplication(app) }
        HookRuntime.configure("GpsHook") { GpsHook.refreshConfig(app) }
        HookRuntime.configure("PlaybackSpeedHook") { PlaybackSpeedHook.refreshConfig(app) }
        HookRuntime.configure("CommentTranslateHook") { CommentTranslateHook.refreshConfig(app) }
        HookRuntime.configure("VideoTranslateHook") { VideoTranslateHook.refreshConfig(app) }
        HookRuntime.configure("CommentCopyHook") { CommentCopyHook.refreshConfig(app) }
        HookRuntime.configure("AuthorLocationHook") { AuthorLocationHook.refreshConfig(app) }
        HookRuntime.configure("ImmersiveFullScreenHook") { ImmersiveFullScreenHook.refreshConfig(app) }
        HookRuntime.configure("AutoScrollHook") { AutoScrollHook.refreshConfig(app) }
        HookRuntime.configure("FeedFilterHook") { FeedFilterHook.refreshConfig(app) }
        HookRuntime.configure("DownloadHook") { DownloadHook.refreshConfig(app) }
        HookRuntime.configure("MusicUnlockHook") { MusicUnlockHook.refreshConfig(app) }
        HookRuntime.configure("StatusBarHook") { StatusBarHook.refreshConfig(app) }
        HookRuntime.configure("VideoDurationAlertHook") { VideoDurationAlertHook.refreshConfig(app) }
    }

    /**
     * 目标应用包完成加载时的生命周期回调。
     *
     * 用于过滤匹配目标包名并触发核心 Hook 逻辑的初始化。
     *
     * @param param 包含目标包名、类加载器及初次加载标记的载荷对象。
     * @return Unit。
     *
     * Callers:
     * - `io.github.libxposed.api.XposedModule`: LSPosed 框架在目标包类加载器就绪后分发事件。
     */
    override fun onPackageLoaded(param: PackageLoadedParam) {
        if (param.packageName !in SUPPORTED_PACKAGES || !param.isFirstPackage) {
            return
        }

        log(
            Log.INFO,
            TAG,
            "目标应用包已加载 -> ${param.packageName} (isFirstPackage=${param.isFirstPackage})"
        )

        // 优先注册宿主应用 Application 生命周期监听，确保跨进程配置中枢即刻就绪
        hookApplication()

        if (!configurationAvailableAtLoad) return

        // 注册 SIM 卡与国家代码伪装 Hook
        HookRuntime.install("SimHook") { SimHook.init(this) }

        // 注册系统语言与 Locale 伪装 Hook
        HookRuntime.install("LocaleHook") { LocaleHook.init(this) }

        // 注册系统时区伪装 Hook
        HookRuntime.install("TimeZoneHook") { TimeZoneHook.init(this) }

        // 注册系统 GPS 定位伪装 Hook
        HookRuntime.install("GpsHook") { GpsHook.init(this) }

    }

    /**
     * 在最终应用类加载器就绪后按真实 DEX 构建注册宿主 Hook。
     * @param param 已完成 AppComponentFactory 类加载器调整的包信息。
     * @return Unit；未知构建不尝试混淆类匹配，具体摘要写入模块日志。
     * Callers: LSPosed onPackageReady 生命周期。
     */
    override fun onPackageReady(param: PackageReadyParam) {
        if (param.packageName !in SUPPORTED_PACKAGES || !param.isFirstPackage) return
        if (!HostSymbols.initialize(param.applicationInfo, processName == param.packageName)) return
        if (!configurationAvailableAtLoad) return
        HookRuntime.install("FeedFilterHook") { FeedFilterHook.init(this, param.classLoader) }
        // 注册固定播放倍速 Hook
        HookRuntime.install("PlaybackSpeedHook") { PlaybackSpeedHook.init(this, param.classLoader) }

        // 注册评论区一键翻译 Hook
        HookRuntime.install("CommentTranslateHook") { CommentTranslateHook.init(this, param.classLoader) }

        // 注册视频正文描述原生翻译 Hook
        HookRuntime.install("VideoTranslateHook") { VideoTranslateHook.init(this, param.classLoader) }

        // 注册评论复制仅复制正文 Hook
        HookRuntime.install("CommentCopyHook") { CommentCopyHook.init(this, param.classLoader) }

        // 注册作者地理位置显示 Hook
        HookRuntime.install("AuthorLocationHook") { AuthorLocationHook.init(this, param.classLoader) }

        // 注册视频进度条常显 Hook
        HookRuntime.install("ProgressBarHook") { ProgressBarHook.init(this, param.classLoader) }

        // 注册首页 Feed 自动清屏 Hook
        HookRuntime.install("AutoCleanModeHook") { AutoCleanModeHook.init(this, param.classLoader) }

        // 注册全屏沉浸播放与视口贯通 Hook
        HookRuntime.install("ImmersiveFullScreenHook") { ImmersiveFullScreenHook.init(this, param.classLoader) }

        // 注册 For You 流自动滚动地区解锁 Hook
        HookRuntime.install("AutoScrollHook") { AutoScrollHook.init(this, param.classLoader) }

        // 注册视频保存增强（下载解锁 / 无水印 / 自定义路径）Hook
        HookRuntime.install("DownloadHook") { DownloadHook.init(this, param.classLoader) }

        // 注册音频限制解锁（音乐级限制 / 视频级静音）Hook
        HookRuntime.install("MusicUnlockHook") { MusicUnlockHook.init(this, param.classLoader) }

        // 注册系统状态栏隐藏 Hook（播放页特征：视频画布可见即隐藏）
        HookRuntime.install("StatusBarHook") { StatusBarHook.init(this, param.classLoader) }

        // 注册长视频播放时长 Toast 提示 Hook
        HookRuntime.install("VideoDurationAlertHook") { VideoDurationAlertHook.init(this, param.classLoader) }
    }
}
