package com.toki.lsposed.hook

import android.content.Context
import android.util.Log
import com.toki.lsposed.provider.ConfigClient
import io.github.libxposed.api.XposedModule
import java.lang.reflect.Field
import java.lang.reflect.Method

/**
 * 视频播放倍速跨视频锁定与原生调度 Hook 核心处理器。
 *
 * 深度对接 TikTok 原生倍速控制中枢 [X.08Fw]、视频播放控制器 [PlayerController] 以及底层播放管理器。
 * 实现三大核心能力：
 * 1. 原生倍速中枢状态同步与准入解除：激活 [X.08Fw.LJI] 跨视频保持总开关，放行 [X.08Fw.LIZ] 视频类型限制，
 *    在 [X.08Fw.LIZIZ] 与 [X.08Fw.LIZJ] 统一返回锁定倍速，并在 [X.08Fw.LJI] 切视频恢复入口中主动驱动倍速调度；
 * 2. 用户选速实时感知与即刻生效：拦截 [X.08Fw.LJ] 调度入口，捕获用户在原生菜单中选择的倍速，
 *    即刻向当前活跃播放器派发调速指令并将运行偏好保存在宿主私有存储中；
 * 3. 底层播放管理器防重置守护：在已验证的控制器与播放管理器调速入口
 *    拦截切视频时系统下发的 1.0x 重置指令，改写为锁定的目标倍速。
 */
object PlaybackSpeedHook {

    private const val TAG = "TokiSpeedHook"

    /** 固定倍速功能总开关 */
    @Volatile
    private var isEnabled: Boolean = false

    /** 当前记忆的固定倍速 */
    @Volatile
    private var activeSpeed: Float = 1.0f

    /** 倍速档位拓展功能开关 */
    @Volatile
    private var isExpandEnabled: Boolean = false

    /** 自定义倍速档位列表 */
    @Volatile
    private var customSpeedList: List<Float> = listOf(0.5f, 1.0f, 1.5f, 2.0f)

    /** 宿主私有倍速记忆，不触发管理端服务启动。 */
    @Volatile
    private var speedMemory: PlaybackSpeedMemory? = null

    /** TikTok 原生倍速控制中枢类引用 [X.08Fw] */
    @Volatile
    private var speedManagerClass: Class<*>? = null

    /** 已完成 Hook 的实际声明方法，父类共享实现只注册一次。 */
    private val hookedPlayerManagerMethods = mutableSetOf<Method>()

    /** 由完整 DEX 成员契约确定的控制器调速方法。 */
    private lateinit var controllerSpeedMethod: Method

    /** 由完整 DEX 成员契约确定的播放管理器访问方法。 */
    private lateinit var playerManagerGetter: Method

    /** 播放管理器接口，动态实现必须满足该类型。 */
    private lateinit var playerManagerType: Class<*>

    /** 当前构建已验证的播放管理器调速入口名。 */
    private lateinit var managerSpeedName: String

    /** 最近一次活跃的视频播放控制器弱引用，用于选速时即时应用新倍速 */
    @Volatile
    private var activeControllerRef: java.lang.ref.WeakReference<Any>? = null

    /** 允许设定的最小合法播放倍速（移动端音频 Sonic 重采样算法下限） */
    const val MIN_SPEED: Float = 0.1f

    /** 允许设定的最大合法播放倍速（移动端硬件解码器与 TTVideoEngine 安全上限 3.0x） */
    const val MAX_SPEED: Float = 3.0f

    /**
     * 将浮点数倍速格式化为用户友好的字符串显示形式。
     *
     * 整数倍速去除多余小数位（如 2.0f -> "2"），小数倍速完整保留（如 1.5f -> "1.5"）。
     *
     * Args:
     *     speed (Float): 待格式化的浮点倍速数值。
     *
     * Returns:
     *     String: 格式化后的倍速字符串。
     *
     * Callers:
     *     - `com.toki.lsposed.hook.PlaybackSpeedHook.onUserSelectedSpeed`: 日志记录与数据处理。
     */
    fun formatSpeedText(speed: Float): String {
        return if (speed % 1.0f == 0f) {
            speed.toInt().toString()
        } else {
            speed.toString()
        }
    }

    /**
     * 解析逗号分隔的倍速配置字符串为排序去重后的浮点数列表。
     *
     * 过滤非 [MIN_SPEED]..[MAX_SPEED] 范围的非法数值并升序排序，若解析结果为空则返回标准默认档位列表。
     *
     * Args:
     *     rawStr (String?): 原始逗号分隔字符串。
     *
     * Returns:
     *     List<Float>: 解析并清洗、排序去重后的 Float 列表，若空则返回默认四档倍速。
     *
     * Callers:
     *     - `com.toki.lsposed.ui.MainActivity.MainScreenContent`: UI 层倍速标签列表状态生成。
     */
    fun parseSpeedList(rawStr: String?): List<Float> {
        if (rawStr.isNullOrBlank()) {
            return listOf(0.5f, 1.0f, 1.5f, 2.0f)
        }
        val parsed = rawStr.split(",")
            .mapNotNull { it.trim().toFloatOrNull() }
            .filter { it in MIN_SPEED..MAX_SPEED }
            .distinct()
            .sorted()
        return if (parsed.isNotEmpty()) parsed else listOf(0.5f, 1.0f, 1.5f, 2.0f)
    }

    /**
     * 从框架配置快照读取开关，并从宿主私有存储读取上次选择的倍速。
     *
     * 首次使用以管理端的倍速配置作为基准，后续选择由宿主保存，导入配置后重新应用导入基准。
     *
     * Args:
     *     context (Context): 宿主目标应用的上下文对象。
     *
     * Returns:
     *     Unit: 无返回值。
     *
     * Callers:
     *     - `com.toki.lsposed.hook.TokiModule.hookApplicationIfNeeded`: 宿主进程初始化时同步。
     */
    fun refreshConfig(context: Context) {
        if (speedMemory == null) speedMemory = PlaybackSpeedMemory(
            context.getSharedPreferences("toki_playback", Context.MODE_PRIVATE))
        isEnabled = ConfigClient.getBoolean(context, "fixed_speed_enabled")
        isExpandEnabled = ConfigClient.getBoolean(context, "speed_expand_enabled")
        val speedListStr = ConfigClient.getString(context, "speed_expand_list", defaultValue = "0.5,1.0,1.5,2.0")
        customSpeedList = parseSpeedList(speedListStr)
        Log.i(TAG, "已同步倍速拓展配置 -> 拓展状态: $isExpandEnabled, 档位列表: $customSpeedList")

        if (!isEnabled) {
            Log.i(TAG, "固定播放倍速功能处于停用状态")
            activeSpeed = 1.0f
            syncSpeedManagerFields(1.0f, enablePersist = false)
            return
        }

        activeSpeed = checkNotNull(speedMemory).read(ConfigClient.snapshot())
        Log.i(TAG, "已同步倍速记忆配置 -> 状态: 启用, 记忆倍速: ${activeSpeed}x")

        if (activeSpeed != 1.0f) {
            syncSpeedManagerFields(activeSpeed, enablePersist = true)
        }
    }

    /**
     * 获取倍速档位拓展功能是否处于激活状态。
     *
     * 校验倍速档位拓展开关状态。
     *
     * Args:
     *     无。
     *
     * Returns:
     *     Boolean: 激活返回 true，停用返回 false。
     *
     * Callers:
     *     - `com.toki.lsposed.hook.PlaybackSpeedHook.hookSpeedDialogDataSource`: 动态档位下发时判断。
     *     - `com.toki.lsposed.hook.PlaybackSpeedHook.hookLongPressSpeedDataSource`: 长按面板档位下发时判断。
     */
    fun isExpansionEnabled(): Boolean = isExpandEnabled

    /**
     * 获取当前倍速固定功能是否处于激活状态。
     *
     * 校验全局开关与当前配置状态。
     *
     * Args:
     *     无。
     *
     * Returns:
     *     Boolean: 激活返回 true，停用返回 false。
     *
     * Callers:
     *     - `com.toki.lsposed.hook.PlaybackSpeedHook.hookPlayerManagerSetSpeed`: 底层拦截判断。
     *     - `com.toki.lsposed.hook.PlaybackSpeedHook.hookSpeedManagerMethods`: 中枢调度拦截判断。
     *     - `com.toki.lsposed.hook.PlaybackSpeedHook.hookPlayerControllerMethods`: 控制器拦截判断。
     */
    fun isFeatureEnabled(): Boolean = isEnabled

    /**
     * 递归检索指定类及其父类中的目标反射字段。
     *
     * 逐级向上查找声明的指定名称字段，并设置其访问控制权限为可访问。
     *
     * Args:
     *     targetClass (Class<*>?): 目标反射类。
     *     fieldName (String): 字段名称。
     *
     * Returns:
     *     Field?: 匹配的字段对象，未找到返回 null。
     *
     * Callers:
     *     - `com.toki.lsposed.hook.PlaybackSpeedHook.syncSpeedManagerFields`: 更新原生倍速中枢字段。
     *     - `com.toki.lsposed.hook.PlaybackSpeedHook.hookSpeedManagerMethods`: 检索中枢字段。
     */
    private fun findField(targetClass: Class<*>?, fieldName: String): Field? {
        var current = targetClass
        while (current != null && current != Any::class.java) {
            val declared = current.declaredFields.firstOrNull { it.name == fieldName }
            if (declared != null) {
                declared.isAccessible = true
                return declared
            }
            current = current.superclass
        }
        return null
    }

    /**
     * 按已验证的入口名称解析公开调速方法，支持继承的具体实现并拒绝错误返回类型。
     * @param type 控制器或播放管理器的运行时类型。
     * @param name 扫描契约给出的方法名。
     * @return 可 Hook 的公开实例方法；缺失、抽象、静态或返回类型错误时抛出异常。
     * Callers: hookPlayerControllerMethods、hookPlayerManagerSetSpeed、PlaybackSpeedMethodTest。
     */
    internal fun resolveSpeedMethod(type: Class<*>, name: String): Method {
        val method = type.getMethod(name, java.lang.Float.TYPE)
        check(method.returnType == java.lang.Void.TYPE &&
            !java.lang.reflect.Modifier.isStatic(method.modifiers) &&
            !java.lang.reflect.Modifier.isAbstract(method.modifiers)) {
            "调速入口不是具体实例 void 方法：${type.name}.$name"
        }
        return method.apply { isAccessible = true }
    }

    /**
     * 使用已核实的控制器访问方法获取当前播放管理器。
     * @param controller 视频播放控制器实例。
     * @return 宿主提供的播放管理器；宿主尚未建立播放器时可为空。
     * Callers: hookPlayerControllerMethods。
     */
    private fun resolvePlayerManager(controller: Any): Any? = playerManagerGetter.invoke(controller)

    /**
     * 经控制器向关联播放器应用一次调速指令，避免对同一播放器重复调速。
     * @param controller 已捕获的视频播放控制器。
     * @param speed 目标播放倍速。
     * @return Unit；反射或宿主调用异常交由统一 Hook 诊断报告。
     * Callers: hookPlayerControllerMethods、onUserSelectedSpeed。
     */
    private fun applySpeedToPlayerController(controller: Any, speed: Float) {
        controllerSpeedMethod.invoke(controller, speed)
    }

    /**
     * 将记忆的倍速值与保持开关同步写入 TikTok 原生倍速控制中枢 [X.08Fw] 的状态字段。
     *
     * 对齐中枢内部的跨视频保持开关 [LJI]、目标保持倍速 [LJII] 与当前播放倍速 [LIZLLL]、[LIZJ]。
     *
     * Args:
     *     speed (Float): 待同步的倍速浮点数值。
     *     enablePersist (Boolean): 是否激活原生跨视频保持开关。
     *
     * Returns:
     *     Unit: 无返回值。
     *
     * Callers:
     *     - `com.toki.lsposed.hook.PlaybackSpeedHook.refreshConfig`: 启动配置同步。
     *     - `com.toki.lsposed.hook.PlaybackSpeedHook.onUserSelectedSpeed`: 用户在菜单中选速时更新。
     *     - `com.toki.lsposed.hook.PlaybackSpeedHook.hookSpeedManagerMethods`: 状态重置守护。
     */
    private fun syncSpeedManagerFields(speed: Float, enablePersist: Boolean) {
        val clazz = speedManagerClass ?: return

        findField(clazz, "LJI")?.setBoolean(null, enablePersist)
        findField(clazz, "LJII")?.setFloat(null, speed)
        findField(clazz, "LIZLLL")?.setFloat(null, speed)
        findField(clazz, "LIZJ")?.setFloat(null, speed)

        Log.i(TAG, "已同步至 TikTok 原生倍速中枢 X.08Fw -> speed: ${speed}x, persist: $enablePersist")
    }

    /**
     * 响应用户在 TikTok 原生菜单中的倍速选择事件，完成倍速锁定、持久化存储以及当前视频即时调速。
     *
     * Args:
     *     speed (Float): 用户选择的目标倍速。
     *     scene (String?): 选速触发场景标识符。
     *
     * Returns:
     *     Unit: 无返回值。
     *
     * Callers:
     *     - `com.toki.lsposed.hook.PlaybackSpeedHook.hookSpeedManagerMethods`: 拦截 X.08Fw.LJ 用户选速。
     */
    private fun onUserSelectedSpeed(speed: Float, scene: String?) {
        if (!isFeatureEnabled() || speed !in MIN_SPEED..MAX_SPEED) return

        activeSpeed = speed
        Log.i(TAG, "捕获到用户在 TikTok 原生菜单选择倍速 -> 锁定为: ${formatSpeedText(speed)}x, 场景: $scene")

        syncSpeedManagerFields(speed, enablePersist = true)

        activeControllerRef?.get()?.let { controller ->
            applySpeedToPlayerController(controller, speed)
            Log.i(TAG, "已向当前视频播放控制器即时应用新倍速 -> ${speed}x")
        }


        if (!checkNotNull(speedMemory).save(speed, ConfigClient.snapshot())) {
            HookRuntime.failure("PlaybackSpeedHook", IllegalStateException("宿主未确认倍速记忆保存"), "倍速记忆保存失败")
        }
    }

    /**
     * 为播放管理器的真实调速实现注册防重置拦截，父类共享实现只注册一次。
     * @param module 当前 LSPosed 模块。
     * @param managerClass 宿主播放器的具体运行时类型。
     * @return Unit；类型或方法契约不满足时明确报告注册错误。
     * Callers: hookPlayerControllerMethods。
     */
    private fun hookPlayerManagerSetSpeed(module: XposedModule, managerClass: Class<*>) {
        check(playerManagerType.isAssignableFrom(managerClass)) { "播放器实例不满足已验证接口" }
        val method = resolveSpeedMethod(managerClass, managerSpeedName)
        synchronized(hookedPlayerManagerMethods) {
            if (method in hookedPlayerManagerMethods) return
            module.trackHook("PlaybackSpeedHook", method).intercept { chain ->
                val requestedSpeed = chain.args[0] as Float
                if (isFeatureEnabled() && activeSpeed != 1.0f && requestedSpeed == 1.0f) {
                    chain.proceed(arrayOf(activeSpeed))
                } else {
                    chain.proceed()
                }
            }
            hookedPlayerManagerMethods.add(method)
        }
        Log.i(TAG, "已注册播放器调速入口：${method.declaringClass.name}.${method.name}")
    }

    /**
     * 挂载 TikTok 原生倍速管理器 [X.08Fw] 的核心拦截点。
     *
     * 涵盖六大核心切面：
     * 1. 激活并守护原生全局倍速保持标记 `LJI = true`；
     * 2. Hook `08Fw.LIZ(Aweme)`：解除 feed 视频类型判定限制，无条件允许跨视频保持；
     * 3. Hook `08Fw.LIZIZ(Aweme)` 与 `08Fw.LIZJ(Aweme)`：查询当前视频倍速时均返回锁定的 `activeSpeed`；
     * 4. Hook `08Fw.LJI(Aweme, String)`：切视频恢复入口拦截，无条件调用 `08Fw.LJ` 以 `activeSpeed` 驱动倍速调度；
     * 5. Hook `08Fw.LJ(float, Aweme, String, String)`：用户选速即时捕获与调度；
     * 6. Hook `08Fw.LJFF`：视频切换状态重置时守护状态字段不被重置为 1.0x。
     *
     * Args:
     *     module (XposedModule): 当前注入的 XposedModule 实例。
     *     classLoader (ClassLoader): 目标应用类加载器。
     *
     * Returns:
     *     Unit: 无返回值。
     *
     * Callers:
     *     - `com.toki.lsposed.hook.PlaybackSpeedHook.init`: 模块初始化流程。
     */
    private fun hookSpeedManagerMethods(module: XposedModule, classLoader: ClassLoader) {
        val clazz = HostSymbols.resolve(classLoader, HostSymbol.SPEED_MANAGER)
        speedManagerClass = clazz

        // 1. 激活官方原生跨视频保持开关 (LJI)
        if (isFeatureEnabled()) {
            val ljiField = findField(clazz, "LJI")
            if (ljiField != null) {
                ljiField.setBoolean(null, true)
                Log.i(TAG, "已激活 TikTok 原生跨视频倍速保持开关 X.08Fw.LJI")
            } else {
                Log.w(TAG, "未在 X.08Fw 中发现跨视频保持布尔字段 LJI")
            }
        }

        // 2. Hook X.08Fw.LIZ(Aweme): 解除保持限制，确保任何视频均允许跨视频固定倍速
        for (method in clazz.declaredMethods) {
            if (method.name == "LIZ" &&
                method.parameterCount == 1 &&
                method.returnType == java.lang.Boolean.TYPE &&
                !method.isSynthetic
            ) {
                method.isAccessible = true
                module.trackHook("PlaybackSpeedHook", method).intercept { chain ->
                    if (isFeatureEnabled() && activeSpeed != 1.0f) {
                        true
                    } else {
                        chain.proceed()
                    }
                }
                Log.i(TAG, "已挂载 TikTok 原生倍速保持准入判定 X.08Fw.LIZ 拦截器")
            }
        }

        // 3. Hook X.08Fw.LIZIZ(Aweme) 与 LIZJ(Aweme): 各组件与菜单获取当前倍速入口
        for (method in clazz.declaredMethods) {
            if ((method.name == "LIZIZ" || method.name == "LIZJ") &&
                method.parameterCount == 1 &&
                method.returnType == java.lang.Float.TYPE &&
                !method.isSynthetic
            ) {
                method.isAccessible = true
                module.trackHook("PlaybackSpeedHook", method).intercept { chain ->
                    if (isFeatureEnabled() && activeSpeed != 1.0f) {
                        activeSpeed
                    } else {
                        chain.proceed()
                    }
                }
                Log.i(TAG, "已挂载 TikTok 原生倍速查询方法 X.08Fw.${method.name} 拦截器")
            }
        }

        // 4. Hook X.08Fw.LJI(Aweme, String): 切换视频时的原生倍速恢复入口
        for (method in clazz.declaredMethods) {
            if (method.name == "LJI" &&
                method.parameterCount == 2 &&
                method.returnType == java.lang.Void.TYPE &&
                !method.isSynthetic
            ) {
                method.isAccessible = true
                module.trackHook("PlaybackSpeedHook", method).intercept { chain ->
                    val aweme = chain.args.getOrNull(0)
                    val str = chain.args.getOrNull(1) as? String ?: ""
                    if (isFeatureEnabled() && activeSpeed != 1.0f && aweme != null) {
                        val ljMethod = clazz.declaredMethods.firstOrNull {
                            it.name == "LJ" && it.parameterCount == 4 && it.parameterTypes[0] == java.lang.Float.TYPE
                        }
                        if (ljMethod != null) {
                            ljMethod.isAccessible = true
                            ljMethod.invoke(null, activeSpeed, aweme, str, "long_press")
                            Log.d(TAG, "切视频原生恢复入口 X.08Fw.LJI 成功驱动倍速 -> ${activeSpeed}x")
                            return@intercept null
                        }
                    }
                    chain.proceed()
                }
                Log.i(TAG, "已挂载 TikTok 原生切视频倍速恢复入口 X.08Fw.LJI 拦截器")
            }
        }

        // 5. Hook X.08Fw.LJ(float, Aweme, String, String): 用户在菜单中选择倍速的调度函数
        for (method in clazz.declaredMethods) {
            if (method.name == "LJ" &&
                method.parameterCount == 4 &&
                method.parameterTypes[0] == java.lang.Float.TYPE &&
                !method.isSynthetic
            ) {
                method.isAccessible = true
                module.trackHook("PlaybackSpeedHook", method).intercept { chain ->
                    val speed = chain.args.getOrNull(0) as? Float
                    val scene = chain.args.getOrNull(3) as? String
                    Log.d(TAG, "08Fw.LJ 捕获选速调度 -> speed: ${speed}x, scene: $scene")

                    if (speed != null) {
                        findField(clazz, "LIZLLL")?.setFloat(null, speed)
                        findField(clazz, "LIZJ")?.setFloat(null, speed)
                        findField(clazz, "LJII")?.setFloat(null, speed)

                        val finalScene = if (scene.isNullOrEmpty()) "long_press" else scene
                        onUserSelectedSpeed(speed, finalScene)
                        if (scene.isNullOrEmpty()) {
                            return@intercept chain.proceed(arrayOf(speed, chain.args.getOrNull(1), chain.args.getOrNull(2), "long_press"))
                        }
                    }
                    chain.proceed()
                }
                Log.i(TAG, "已挂载 TikTok 原生倍速选择调度器 X.08Fw.LJ 拦截器")
            }
        }

        // 6. Hook X.08Fw.LJFF: 视频切换时的状态重置函数，守护状态字段不被重置为 1.0x
        for (method in clazz.declaredMethods) {
            if (method.name == "LJFF" &&
                method.parameterCount == 4 &&
                method.returnType == java.lang.Boolean.TYPE &&
                !method.isSynthetic
            ) {
                method.isAccessible = true
                module.trackHook("PlaybackSpeedHook", method).intercept { chain ->
                    val result = chain.proceed()
                    if (isFeatureEnabled() && activeSpeed != 1.0f) {
                        syncSpeedManagerFields(activeSpeed, enablePersist = true)
                    }
                    result
                }
                Log.i(TAG, "已挂载 TikTok 原生倍速重置守护器 X.08Fw.LJFF 拦截器")
            }
        }
    }

    /**
     * 注册控制器调速入口和播放器生命周期，所有成员由当前代码集合的契约确定。
     * @param module 当前 LSPosed 模块。
     * @param classLoader 宿主最终类加载器。
     * @return Unit；契约缺失使本功能注册事务明确失败，不以日志代替成功注册。
     * Callers: init。
     */
    private fun hookPlayerControllerMethods(module: XposedModule, classLoader: ClassLoader) {
        val clazz = HostSymbols.resolve(classLoader, HostSymbol.PLAYER_CONTROLLER)
        playerManagerType = HostSymbols.resolve(classLoader, HostSymbol.PLAYER_MANAGER)
        managerSpeedName = HostSymbols.member(HostSymbol.PLAYER_MANAGER, "setSpeed")
        val managerContract = playerManagerType.getDeclaredMethod(managerSpeedName, java.lang.Float.TYPE)
        check(playerManagerType.isInterface && managerContract.returnType == java.lang.Void.TYPE)
        controllerSpeedMethod = resolveSpeedMethod(clazz, HostSymbols.member(HostSymbol.PLAYER_CONTROLLER, "setSpeed"))
        playerManagerGetter = clazz.getDeclaredMethod(HostSymbols.member(HostSymbol.PLAYER_CONTROLLER, "getManager"))
            .apply { isAccessible = true }
        check(playerManagerGetter.returnType == playerManagerType)
        val setManager = clazz.getDeclaredMethod(
            HostSymbols.member(HostSymbol.PLAYER_CONTROLLER, "setManager"), playerManagerType
        ).apply { isAccessible = true }
        check(setManager.returnType == java.lang.Void.TYPE)
        val renderReady = clazz.declaredMethods.single {
            it.name == HostSymbols.member(HostSymbol.PLAYER_CONTROLLER, "renderReady")
        }.apply { isAccessible = true }
        check(renderReady.returnType == java.lang.Void.TYPE && renderReady.parameterCount == 1)

        module.trackHook("PlaybackSpeedHook", setManager).intercept { chain ->
            val controller = checkNotNull(chain.thisObject)
            activeControllerRef = java.lang.ref.WeakReference(controller)
            val manager = chain.args[0]
            if (manager != null) hookPlayerManagerSetSpeed(module, manager.javaClass)
            val result = chain.proceed()
            if (manager != null && isFeatureEnabled() && activeSpeed != 1.0f) {
                applySpeedToPlayerController(controller, activeSpeed)
            }
            result
        }

        module.trackHook("PlaybackSpeedHook", controllerSpeedMethod).intercept { chain ->
            activeControllerRef = java.lang.ref.WeakReference(checkNotNull(chain.thisObject))
            val requestedSpeed = chain.args[0] as Float
            if (isFeatureEnabled() && activeSpeed != 1.0f && requestedSpeed == 1.0f) {
                chain.proceed(arrayOf(activeSpeed))
            } else {
                chain.proceed()
            }
        }

        module.trackHook("PlaybackSpeedHook", renderReady).intercept { chain ->
            val result = chain.proceed()
            val controller = checkNotNull(chain.thisObject)
            activeControllerRef = java.lang.ref.WeakReference(controller)
            val manager = resolvePlayerManager(controller)
            if (manager != null) {
                hookPlayerManagerSetSpeed(module, manager.javaClass)
                if (isFeatureEnabled() && activeSpeed != 1.0f) applySpeedToPlayerController(controller, activeSpeed)
            }
            result
        }
        Log.i(TAG, "已注册播放控制器调速入口：${clazz.name}.${controllerSpeedMethod.name}")
    }

    /**
     * 注册按构建核实的倍速档位数据源，不监听全局 ClassLoader 或猜测 Lambda 编号。
     * @param module LSPosed 模块。
     * @param classLoader 宿主最终类加载器。
     * @return Unit；Play 基础包未包含菜单数据源时明确记录所需的 Split 验证。
     * Callers: init。
     */
    private fun hookSpeedDialogMethods(module: XposedModule, classLoader: ClassLoader) {
        val gate = HostSymbols.resolve(classLoader, HostSymbol.THREE_TIMES_SPEED).getDeclaredMethod("LIZ")
        check(gate.returnType == Boolean::class.javaPrimitiveType)
        module.trackHook("PlaybackSpeedHook", gate).intercept { chain ->
            if (ConfigClient.getBoolean("speed_expand_enabled")) true else chain.proceed()
        }
        val sources = listOf(HostSymbol.SPEED_OPTIONS to "options",
            HostSymbol.SPEED_LAMBDA11 to "optionsPrimary", HostSymbol.SPEED_LAMBDA11 to "optionsSecondary",
            HostSymbol.SPEED_LAMBDA21 to "options", HostSymbol.SPEED_LAMBDA31 to "options")
        if (sources.any { !HostSymbols.available(it.first) }) {
            HookRuntime.state("SpeedOptions", "倍速菜单数据源未唯一解析；固定倍速独立注册")
            Log.w(TAG, "倍速菜单数据源未唯一解析，未注册扩展档位入口")
            return
        }
        for ((symbol, role) in sources) {
            val type = HostSymbols.resolve(classLoader, symbol)
            val methodName = HostSymbols.member(symbol, role)
            val method = if (symbol == HostSymbol.SPEED_OPTIONS) type.getDeclaredMethod(methodName)
                else type.getDeclaredMethod(methodName, type)
            check(java.lang.reflect.Modifier.isStatic(method.modifiers))
            check(method.returnType == List::class.java || method.returnType == Any::class.java)
            method.isAccessible = true
            module.trackHook("PlaybackSpeedHook", method).intercept { chain ->
                val snapshot = ConfigClient.snapshot()
                if (snapshot.boolean("speed_expand_enabled")) parseSpeedList(snapshot.string("speed_expand_list"))
                else chain.proceed()
            }
        }
        HookRuntime.state("SpeedOptions", "菜单数据源已注册；调用次数计入播放倍速")
        Log.i(TAG, "已注册当前构建的倍速档位数据源")
    }

    /**
     * 模块初始化入口，按序挂载倍速中枢调度、底层播放器与控制器锁速拦截以及倍速弹窗档位拓展。
     *
     * Args:
     *     module (XposedModule): 当前注入的 XposedModule 实例。
     *     classLoader (ClassLoader): 宿主目标应用类加载器。
     *
     * Returns:
     *     Unit: 无返回值。
     *
     * Callers:
     *     - `com.toki.lsposed.hook.TokiModule.onPackageLoaded`: 目标包加载时触发。
     */
    fun init(module: XposedModule, classLoader: ClassLoader) {
        hookPlayerControllerMethods(module, classLoader)
        hookSpeedManagerMethods(module, classLoader)
        hookSpeedDialogMethods(module, classLoader)
        Log.i(TAG, "固定播放倍速子系统全链路就绪（中枢调度挂载 + 准入放行 + 控制器拦截 + 底层锁速 + 档位拓展）")
    }
}
