package com.toki.lsposed.hook

import android.util.Log

/** 已核实样本的规则生成名称；运行时只使用特征扫描结果，不尝试这些固定名称。 */
internal enum class HostSymbol(val play: String, val mirror: String) {
    COMMENT_TRANSLATION("X.08Ut", "X.09Br"),
    TRANSLATION_REVERSE("X.08W5", "X.09As"),
    SPEED_MANAGER("X.08Fw", "X.0Rrz"),
    PLAYER_CONTROLLER("com.ss.android.ugc.aweme.feed.controller.PlayerController", "com.ss.android.ugc.aweme.feed.controller.PlayerController"),
    PLAYER_MANAGER("X.038V", "X.0MDj"),
    SETTINGS("X.033O", "X.09dC"),
    SEARCH_AUTO_SCROLL("X.03cj", "X.0PYx"),
    COLD_FEED("X.04Ld", "X.0MMJ"),
    PRELOADED_FEED("X.06Lm", "X.15Px"),
    DARK_LAYER("X.05vo", "X.1CqE"),
    SEEK_BAR("X.06hm", "X.15ep"),
    SEEK_CONTROLLER("X.06jj", "X.15en"),
    OFFLINE_RECOVERY("X.08is", "X.18Ax"),
    AUTHOR_LOCATION("X.08vJ", "X.0SiI"),
    RESERVED_AREA("X.0BaM", "X.0BE0"),
    THREE_TIMES_SPEED("X.0CzW", "X.09Tz"),
    PHOTO_EXPANSION("X.0MiS", "X.19tc"),
    FEED_ADAPTER("X.07kj", "X.0RkB"),
    RECOMMEND_MODEL("X.07st", "X.15Ks"),
    RECOMMEND_ADAPTER("X.07mb", "X.0RkC"),
    COMMENT_CLIP("X.0rXy", "X.0lfF"),
    COMMENT_ACTION("X.1JL9", "X.0lO0"),
    COMMENT_MENU("com.ss.android.ugc.aweme.commentv2.commentlist.viewmodel.CommentActionMenuVM", "com.ss.android.ugc.aweme.commentv2.commentlist.viewmodel.CommentActionMenuVM"),
    COMMENT_BINDER("X.0G8O", "X.0l9u"),
    COMMENT_DISPLAY("X.189S", "X.0lBD"),
    PINCH("com.ss.android.ugc.feed.platform.cell.pinch.PinchComponent", "com.ss.android.ugc.feed.platform.cell.pinch.PinchComponent"),
    CLEAN("com.ss.android.ugc.feed.platform.panel.clean.FeedCleanComponent", "com.ss.android.ugc.feed.platform.panel.clean.FeedCleanComponent"),
    ADAPTION("com.ss.android.ugc.feed.platform.cell.component.adaption.CellAdaptionComponentV2", "com.ss.android.ugc.feed.platform.cell.component.adaption.CellAdaptionComponentV2"),
    FEED_ADAPTION("com.ss.android.ugc.feed.platform.cell.component.adaption.FeedCellAdaptionComponentV2", "com.ss.android.ugc.feed.platform.cell.component.adaption.FeedCellAdaptionComponentV2"),
    SPEED_OPTIONS("not.present.in.play.base0", "X.09U0"),
    SPEED_LAMBDA11("not.present.in.play.base", "kotlin.jvm.internal.AFwS211S0000000_11"),
    SPEED_LAMBDA21("not.present.in.play.base2", "kotlin.jvm.internal.AFwS225S0000000_21"),
    SPEED_LAMBDA31("not.present.in.play.base3", "kotlin.jvm.internal.AFwS236S0000000_31"),
    MUTE_INFO("com.ss.android.ugc.aweme.feed.model.AwemeStatus\$VideoMuteInfo", "com.ss.android.ugc.aweme.feed.model.AwemeStatus\$VideoMuteInfo"),
    CELL_CLEAN("com.ss.android.ugc.feed.platform.cell.clean.CellCleanComponent", "com.ss.android.ugc.feed.platform.cell.clean.CellCleanComponent"),
    VIDEO_CELL("com.ss.android.ugc.aweme.feed.adapter.VideoViewCell", "com.ss.android.ugc.aweme.feed.adapter.VideoViewCell"),
    VIDEO_BASE_CELL("com.ss.android.ugc.aweme.feed.adapter.VideoBaseCell", "com.ss.android.ugc.aweme.feed.adapter.VideoBaseCell")
}

/** 以完整代码集合和规则摘要验证私有持久结果，唯一匹配后才允许宿主符号注册。 */
internal object HostSymbols {
    private var resolved = java.util.Properties()
    private val rules: String by lazy {
        checkNotNull(HostSymbols::class.java.getResourceAsStream("/toki-host-rules.tsv")) {
            "模块缺少 DEX 特征规则"
        }.bufferedReader().use { it.readText() }
    }

    /**
     * 读取私有持久结果并验证代码与规则；未准备好时扫描，保存核对后独立安排关闭主进程。
     * @param info 宿主基础包、全部 Split 和私有数据目录。
     * @param scanAllowed 仅主进程允许生成缓存，避免多进程同时扫描。
     * @return 是否已经存在与完整代码集合及规则一致的缓存。
     * Callers: TokiModule.onPackageReady。
     */
    fun initialize(info: android.content.pm.ApplicationInfo, scanAllowed: Boolean): Boolean {
        val paths = listOf(info.sourceDir) + info.splitSourceDirs.orEmpty()
        val identity = HostDexIndex.identity(paths)
        val rulesIdentity = HostDexIndex.digest(rules)
        val key = HostDexIndex.digest("index-v5\n$identity\n" + rulesIdentity)
        val cache = storageFile(java.io.File(info.dataDir))
        val stored = HostSymbolCache.read(cache)
        if (stored.getProperty("cache.key") == key) {
            resolved = stored
            val failures = stored.stringPropertyNames().filter { it.startsWith("error.") }
            HookRuntime.adaptation("适配缓存有效\n代码标识：$identity\n未匹配目标：${failures.joinToString().ifEmpty { "无" }}")
            HookRuntime.state("HostSymbols", "缓存已验证")
            HookRuntime.event("TokiHostSymbols", "缓存已验证 pid=${android.os.Process.myPid()} key=$key scanPid=${stored.getProperty("cache.scanPid")} code=$identity rules=$rulesIdentity")
            return true
        }
        val reason = HostCacheReason.describe(stored, identity, rulesIdentity)
        HookRuntime.event("TokiHostSymbols", "需要查找：$reason；pid=${android.os.Process.myPid()} allowed=$scanAllowed oldKey=${stored.getProperty("cache.key")} newKey=$key code=$identity rules=$rulesIdentity apkCount=${paths.size}")
        HookRuntime.adaptation(if (scanAllowed) "正在扫描全部代码包 · $identity" else "等待主进程扫描完成后重启")
        HookRuntime.state("HostSymbols", if (scanAllowed) "正在扫描" else "等待主进程准备")
        if (scanAllowed) {
            HostScanController.session.begin()
            Thread({
                try {
                    val result = HostDexIndex.scan(paths, rules) { completed, total, target ->
                        HostScanController.session.progress(completed, total, target)
                    }
                    HostScanController.session.saving()
                    check(HostDexIndex.identity(paths) == identity) { "扫描期间宿主代码集合发生变化，缓存未发布" }
                    result.setProperty("cache.key", key)
                    result.setProperty("cache.identity", identity)
                    result.setProperty("cache.rules", rulesIdentity)
                    result.setProperty("cache.scanPid", android.os.Process.myPid().toString())
                    result.setProperty("cache.createdAt", System.currentTimeMillis().toString())
                    result.setProperty("cache.reason", reason)
                    HostSymbolCache.write(cache, result)
                    HookRuntime.event("TokiHostSymbols", "结果保存并核对成功 pid=${android.os.Process.myPid()} key=$key")
                    val failures = result.stringPropertyNames().filter { it.startsWith("error.") }
                    HookRuntime.adaptation("扫描结果已保存，请关闭后重新打开 TikTok\n代码标识：$identity\n未匹配目标：${failures.joinToString().ifEmpty { "无" }}")
                    HookRuntime.state("HostSymbols", "扫描完成，等待重新打开")
                    HostScanController.session.ready("适配结果已保存；未匹配目标 ${failures.size} 项。即将关闭 TikTok，请重新打开以应用结果。")
                    HostScanController.onScanReady()
                } catch (error: Exception) {
                    HookRuntime.event("TokiHostSymbols", "扫描未完成 pid=${android.os.Process.myPid()} key=$key error=${error.javaClass.name}")
                    HookRuntime.failure("HostSymbols", error, "扫描失败，未发布缓存")
                    HookRuntime.adaptation("适配扫描失败：${error.javaClass.simpleName}；请检查 TokiHostSymbols 日志")
                    Log.e("TokiHostSymbols", "宿主符号扫描失败", error)
                    HostScanController.session.fail("查找方法失败：${error.javaClass.simpleName}。结果未保存，请在 Toki 查看适配诊断。")
                }
            }, "TokiSymbolScan").apply { priority = Thread.MIN_PRIORITY }.start()
        }
        return false
    }

    /**
     * 并发安全地创建不参与备份、不会随宿主缓存清理的唯一目录，并返回结果文件位置。
     * 初始化早于 Application Context 可用时机；目录结构与 Android ContextImpl 的
     * getNoBackupFilesDir 一致。清除宿主应用数据或卸载宿主会删除此目录。
     * @param dataDirectory ApplicationInfo 提供的宿主私有数据目录。
     * @return 交由 AtomicFile 读写的符号结果文件。
     * @throws IllegalStateException 目录创建失败或路径被普通文件占用。
     * Callers: HostSymbols.initialize、HostSymbolStorageTest。
     */
    internal fun storageFile(dataDirectory: java.io.File): java.io.File {
        val directory = java.io.File(dataDirectory, "no_backup/toki")
        check(directory.mkdirs() || directory.isDirectory) { "无法创建宿主符号结果目录：$directory" }
        return java.io.File(directory, "toki-host-symbols.properties")
    }

    /**
     * 返回唯一候选的原始类名；缺失或歧义必须由功能注册事务明确报告。
     * @param symbol 业务符号。
     * @return DEX 二进制类名。
     * Callers: 各业务 Hook。
     */
    fun name(symbol: HostSymbol): String = checkNotNull(resolved.getProperty(symbol.name)) {
        "${symbol.name}: ${resolved.getProperty("error.${symbol.name}", "尚未扫描")}"
    }.substringBefore('|')

    /**
     * 按业务角色读取已通过完整 DEX 契约验证的成员名称，不依据渠道或版本猜测。
     * @param symbol 方法或字段所属的业务符号。
     * @param role 规则中声明的业务成员角色。
     * @return 当前代码集合中对应的实际方法名或字段名。
     * Callers: FeedFilterHook、AutoScrollHook、AutoCleanModeHook、ImmersiveFullScreenHook、
     * ProgressBarHook、PlaybackSpeedHook、MusicUnlockHook。
     */
    fun member(symbol: HostSymbol, role: String): String {
        name(symbol)
        return checkNotNull(resolved.getProperty("member.${symbol.name}.$role")) {
            "${symbol.name}.$role: 缺少已验证的成员契约"
        }.substringBefore('(').substringBefore(':')
    }

    /** 检查符号是否唯一解析。@param symbol 业务符号。@return 是否可用。Callers: PlaybackSpeedHook。 */
    fun available(symbol: HostSymbol): Boolean = resolved.containsKey(symbol.name)

    /**
     * 使用最终类加载器获取宿主类，不触发静态初始化。
     * @param loader 宿主最终类加载器。
     * @param symbol 业务符号。
     * @return 经扫描定位的 Class。
     * Callers: 各业务 Hook.init。
     */
    fun resolve(loader: ClassLoader, symbol: HostSymbol): Class<*> = Class.forName(name(symbol), false, loader)
}
