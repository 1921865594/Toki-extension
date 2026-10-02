package com.toki.lsposed.hook

import android.app.Application
import android.util.Log
import com.toki.lsposed.provider.ConfigClient
import com.toki.lsposed.provider.ConfigSchema
import com.toki.lsposed.provider.TikTokCrashDiagnostics
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
        TikTokCrashDiagnostics.install(this, param.processName)
        configurationAvailableAtLoad = ConfigClient.initHost({ getRemotePreferences(ConfigClient.FRAMEWORK_GROUP) }) {
            val error = ConfigClient.syncError
            if (error != null) HookRuntime.failure("ConfigClient", error, "配置未就绪，功能暂停")
            else HookRuntime.configurationReady(if (configurationAvailableAtLoad) "配置已就绪" else "配置就绪，等待重新打开")
        }
        log(Log.INFO, TAG, "框架配置初始化 ready=$configurationAvailableAtLoad；宿主不访问 Toki 配置组件")
        log(Log.INFO, TAG, "模块加载成功，当前进程名称: ${param.processName}")
    }

    /**
     * 仅在确实存在已开启功能时监听 Application 创建。
     *
     * 所有功能关闭时不注册任何宿主 Application Hook，从源头消除模块对 TikTok
     * 启动早期 Instrumentation 调用链的改动。
     */
    private fun hookApplicationIfNeeded() {
        if (!needsApplicationLifecycle()) return

        val onCreateMethod = runCatching {
            Class.forName(
                "android.app.Instrumentation",
                false,
                Application::class.java.classLoader
            ).getMethod("callApplicationOnCreate", Application::class.java)
        }.getOrElse { error ->
            HookRuntime.failure("ApplicationLifecycle", error, "Application Hook 不可用，宿主不被阻断")
            return
        }

        hook(onCreateMethod)
            .setExceptionMode(XposedInterface.ExceptionMode.PASSTHROUGH)
            .intercept { chain ->
                // 诊断/扫描 UI 均不得成为宿主启动失败源。
                runCatching {
                    val app = chain.args[0] as? Application ?: return@runCatching
                    HookRuntime.attach(app)
                    if (processName == app.packageName) HostScanController.attach(app)
                    refreshEnabledConfiguration(app)
                }.onFailure { error ->
                    HookRuntime.failure(
                        "ApplicationLifecycle",
                        error,
                        "宿主启动辅助逻辑失败，已放行原调用"
                    )
                }
                chain.proceed()
            }
        HookRuntime.event("TokiModule", "仅因存在已开启功能才注册 Application 生命周期 Hook")
    }

    /**
     * 判断是否有功能需要 Application 上下文或宿主扫描窗口。
     */
    private fun needsApplicationLifecycle(): Boolean =
        APPLICATION_LIFECYCLE_FEATURES.any(::featureEnabled) || HOST_SYMBOL_FEATURES.any(::featureEnabled)

    /**
     * 判断指定功能是否至少有一个真实开关处于开启。
     */
    private fun featureEnabled(feature: String): Boolean =
        ConfigSchema.featureSwitches[feature]?.any { key -> ConfigClient.getBoolean(key) } == true

    /**
     * 应用配置仅覆盖已注册且已启用的功能；关闭的功能不会触发初始化读取。
     */
    private fun refreshEnabledConfiguration(app: Application) {
        HookRuntime.configure("SimHook") { if (featureEnabled("SimHook")) SimHook.refreshConfig(app) }
        HookRuntime.configure("LocaleHook") { if (featureEnabled("LocaleHook")) { LocaleHook.refreshConfig(app); LocaleHook.applyToApplication(app) } }
        HookRuntime.configure("TimeZoneHook") { if (featureEnabled("TimeZoneHook")) { TimeZoneHook.refreshConfig(app); TimeZoneHook.applyToApplication(app) } }
        HookRuntime.configure("GpsHook") { if (featureEnabled("GpsHook")) GpsHook.refreshConfig(app) }
        HookRuntime.configure("PlaybackSpeedHook") { if (featureEnabled("PlaybackSpeedHook")) PlaybackSpeedHook.refreshConfig(app) }
        HookRuntime.configure("CommentTranslateHook") { if (featureEnabled("CommentTranslateHook")) CommentTranslateHook.refreshConfig(app) }
        HookRuntime.configure("VideoTranslateHook") { if (featureEnabled("VideoTranslateHook")) VideoTranslateHook.refreshConfig(app) }
        HookRuntime.configure("CommentCopyHook") { if (featureEnabled("CommentCopyHook")) CommentCopyHook.refreshConfig(app) }
        HookRuntime.configure("AuthorLocationHook") { if (featureEnabled("AuthorLocationHook")) AuthorLocationHook.refreshConfig(app) }
        HookRuntime.configure("ImmersiveFullScreenHook") { if (featureEnabled("ImmersiveFullScreenHook")) ImmersiveFullScreenHook.refreshConfig(app) }
        HookRuntime.configure("AutoScrollHook") { if (featureEnabled("AutoScrollHook")) AutoScrollHook.refreshConfig(app) }
        HookRuntime.configure("FeedFilterHook") { if (featureEnabled("FeedFilterHook")) FeedFilterHook.refreshConfig(app) }
        HookRuntime.configure("DownloadHook") { if (featureEnabled("DownloadHook")) DownloadHook.refreshConfig(app) }
        HookRuntime.configure("MusicUnlockHook") { if (featureEnabled("MusicUnlockHook")) MusicUnlockHook.refreshConfig(app) }
        HookRuntime.configure("StatusBarHook") { if (featureEnabled("StatusBarHook")) StatusBarHook.refreshConfig(app) }
        HookRuntime.configure("VideoDurationAlertHook") { if (featureEnabled("VideoDurationAlertHook")) VideoDurationAlertHook.refreshConfig(app) }
    }

    /**
     * 有 Application 上下文需求的功能。其它功能通过 ConfigClient 快照实时读取。
     */
    private val APPLICATION_LIFECYCLE_FEATURES = setOf(
        "SimHook", "LocaleHook", "TimeZoneHook", "GpsHook", "PlaybackSpeedHook"
    )

    /**
     * 会访问 HostSymbols 的功能。没有这些功能开启时绝不触发 DEX 扫描。
     */
    private val HOST_SYMBOL_FEATURES = setOf(
        "PlaybackSpeedHook", "CommentTranslateHook", "VideoTranslateHook",
        "CommentCopyHook", "AuthorLocationHook", "ProgressBarHook",
        "AutoCleanModeHook", "ImmersiveFullScreenHook", "AutoScrollHook",
        "FeedFilterHook", "MusicUnlockHook"
    )

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

        if (!configurationAvailableAtLoad) return

        // 只有明确开启的系统级功能才修改 Android/Java 框架方法。
        installIfEnabled("SimHook") { SimHook.init(this) }
        installIfEnabled("LocaleHook") { LocaleHook.init(this) }
        installIfEnabled("TimeZoneHook") { TimeZoneHook.init(this) }
        installIfEnabled("GpsHook") { GpsHook.init(this) }

        // Application Hook 也只在必要时注册；全部关闭时目标进程保持零业务注入。
        hookApplicationIfNeeded()

    }

    /**
     * 在最终应用类加载器就绪后按真实 DEX 构建注册宿主 Hook。
     * @param param 已完成 AppComponentFactory 类加载器调整的包信息。
     * @return Unit；未知构建不尝试混淆类匹配，具体摘要写入模块日志。
     * Callers: LSPosed onPackageReady 生命周期。
     */
    override fun onPackageReady(param: PackageReadyParam) {
        if (param.packageName !in SUPPORTED_PACKAGES || !param.isFirstPackage) return
        if (!configurationAvailableAtLoad) return

        // 没有需要符号解析的功能时，连 DEX 扫描都不要触发。
        if (HOST_SYMBOL_FEATURES.any(::featureEnabled) &&
            !HostSymbols.initialize(param.applicationInfo, processName == param.packageName)
        ) return
        installIfEnabled("FeedFilterHook") { FeedFilterHook.init(this, param.classLoader) }
        // 注册固定播放倍速 Hook
        installIfEnabled("PlaybackSpeedHook") { PlaybackSpeedHook.init(this, param.classLoader) }

        // 注册评论区一键翻译 Hook
        installIfEnabled("CommentTranslateHook") { CommentTranslateHook.init(this, param.classLoader) }

        // 注册视频正文描述原生翻译 Hook
        installIfEnabled("VideoTranslateHook") { VideoTranslateHook.init(this, param.classLoader) }

        // 注册评论复制仅复制正文 Hook
        installIfEnabled("CommentCopyHook") { CommentCopyHook.init(this, param.classLoader) }

        // 注册作者地理位置显示 Hook
        installIfEnabled("AuthorLocationHook") { AuthorLocationHook.init(this, param.classLoader) }

        // 注册视频进度条常显 Hook
        installIfEnabled("ProgressBarHook") { ProgressBarHook.init(this, param.classLoader) }

        // 注册首页 Feed 自动清屏 Hook
        installIfEnabled("AutoCleanModeHook") { AutoCleanModeHook.init(this, param.classLoader) }

        // 注册全屏沉浸播放与视口贯通 Hook
        installIfEnabled("ImmersiveFullScreenHook") { ImmersiveFullScreenHook.init(this, param.classLoader) }

        // 注册 For You 流自动滚动地区解锁 Hook
        installIfEnabled("AutoScrollHook") { AutoScrollHook.init(this, param.classLoader) }

        // 注册视频保存增强（下载解锁 / 无水印 / 自定义路径）Hook
        installIfEnabled("DownloadHook") { DownloadHook.init(this, param.classLoader) }

        // 注册音频限制解锁（音乐级限制 / 视频级静音）Hook
        installIfEnabled("MusicUnlockHook") { MusicUnlockHook.init(this, param.classLoader) }

        // 注册系统状态栏隐藏 Hook（播放页特征：视频画布可见即隐藏）
        installIfEnabled("StatusBarHook") { StatusBarHook.init(this, param.classLoader) }

        // 注册长视频播放时长 Toast 提示 Hook
        installIfEnabled("VideoDurationAlertHook") { VideoDurationAlertHook.init(this, param.classLoader) }
    }

    /**
     * 配置关闭等同于“不注册”。这比在拦截器内部再判断开关更安全，避免关闭功能仍修改宿主关键启动/框架调用链。
     */
    private fun installIfEnabled(feature: String, install: () -> Unit) {
        if (featureEnabled(feature)) {
            HookRuntime.install(feature, install)
        } else {
            HookRuntime.state(feature, "已关闭，未注册")
        }
    }
}
