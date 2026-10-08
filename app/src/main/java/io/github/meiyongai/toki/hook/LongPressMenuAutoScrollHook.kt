package io.github.meiyongai.toki.hook

import android.content.Context
import android.graphics.Color
import android.os.SystemClock
import android.util.Log
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.CompoundButton
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.Switch
import android.widget.TextView
import io.github.libxposed.api.XposedModule
import java.lang.reflect.Method
import java.util.Collections
import java.util.WeakHashMap

/**
 * 长按菜单自动滚动开关：在菜单功能区下方注入开关行，并自实现"播完自动切下一个"。
 *
 * 分两部分：
 * 1. UI 注入：hook View.onAttachedToWindow，识别 RecyclerView 型功能区，
 *    用垂直 LinearLayout 包裹后在其下方追加开关行。
 * 2. 自动滚动：hook VideoViewCell / VideoBaseCell / FullFeedVideoViewHolder
 *    上的"播放完成"回调，在回调里向上查找 VerticalViewPager 并调用
 *    setCurrentItem(current + 1, true)。
 *
 * 之所以不用原生实验放行：实验放行只会让原生菜单项出现，需要用户主动点击
 * 才会激活滚动，无法满足"打开开关即生效"。
 */
object LongPressMenuAutoScrollHook {

    private const val TAG = "TokiLongPressAutoScroll"
    private const val SWITCH_TAG = "io.github.meiyongai.toki.AUTO_SCROLL_SWITCH"
    private const val PREFS_NAME = "toki_long_press_menu"
    private const val KEY_LOCAL_ENABLED = "auto_scroll_enabled"
    private const val SCROLL_COOLDOWN_MS = 1000L

    private const val MATCH = ViewGroup.LayoutParams.MATCH_PARENT
    private const val WRAP = ViewGroup.LayoutParams.WRAP_CONTENT

    private const val VERTICAL_PAGER_CLASS =
        "com.ss.android.ugc.aweme.common.widget.VerticalViewPager"

    /** 用于识别功能区容器的已知动作文本（多语言，首个子项命中即认定）。 */
    private val KNOWN_ACTION_TEXTS = listOf(
        "举报", "Report",
        "下载", "Download", "Save", "保存",
        "收藏", "Favorite", "Favourite",
        "添加到限时动态", "Add to Story", "Add to story",
        "播放速度", "Speed", "倍速",
        "不感兴趣", "Not interested",
        "清屏", "Clear",
        "分享至", "Share to",
        "转发", "Repost",
        "发送", "Send"
    )

    /** 播放完成回调的名字模式；任一命中即尝试 hook。 */
    private val PLAY_COMPLETED_NAMES = listOf(
        "onPlayCompleted", "onVideoCompleted", "onVideoPlayCompleted",
        "onPlayFinish", "onVideoPlayFinish", "onCompleted"
    )

    private val injected: MutableSet<ViewGroup> =
        Collections.newSetFromMap(WeakHashMap())

    /** 已注册 hook 的方法，避免重复。 */
    private val hookedCompletedMethods: MutableSet<Method> =
        Collections.synchronizedSet(mutableSetOf())

    private val scrollCooldownLock = Any()
    @Volatile private var scrollCooldownUntilMs: Long = 0L
    @Volatile private var localEnabled: Boolean = false

    /**
     * 恢复本地开关状态。
     * @param context 宿主应用上下文。
     * @return Unit。
     * Callers: AutoScrollHook.refreshConfig。
     */
    fun refreshConfig(context: Context) {
        localEnabled = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getBoolean(KEY_LOCAL_ENABLED, false)
        Log.i(TAG, "本地开关 = $localEnabled")
    }

    /**
     * 查询本地开关是否开启。
     * @return true 表示自动滚动已打开。
     * Callers: AutoScrollHook.unlocks、gate 拦截器。
     */
    fun isEnabled(): Boolean = localEnabled

    private fun setEnabled(context: Context, value: Boolean) {
        if (localEnabled == value) return
        localEnabled = value
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit().putBoolean(KEY_LOCAL_ENABLED, value).apply()
        HookRuntime.event(TAG, "自动滚动本地开关 = $value")
    }

    // ==================== UI 注入 ====================

    private fun isRecyclerLike(view: ViewGroup): Boolean =
        view.javaClass.name.contains("RecyclerView")

    /**
     * 尝试向给定的 RecyclerView 型 ViewGroup 注入开关行。
     * @param rv 目标 ViewGroup。
     * @return 是否成功完成一次注入。
     * Callers: init 的 post Runnable。
     */
    fun tryInject(rv: ViewGroup): Boolean {
        if (injected.contains(rv)) return false
        if (!isRecyclerLike(rv)) return false
        if (rv.childCount < 3) return false

        val parent = rv.parent as? ViewGroup ?: return false
        if (parent.findViewWithTag<View>(SWITCH_TAG) != null) {
            injected.add(rv)
            return false
        }

        val firstChild = rv.getChildAt(0) ?: return false
        if (!collectTexts(firstChild).any { text ->
                KNOWN_ACTION_TEXTS.any { text.contains(it, ignoreCase = true) }
            }
        ) return false

        injected.add(rv)
        return try {
            wrapAndInject(rv, parent)
            HookRuntime.event(TAG, "自动滚动开关已注入 parent=${parent.javaClass.simpleName}")
            true
        } catch (error: Throwable) {
            injected.remove(rv)
            HookRuntime.failure(TAG, error, "自动滚动开关注入失败")
            false
        }
    }

    private fun wrapAndInject(rv: ViewGroup, parent: ViewGroup) {
        val indexInParent = (0 until parent.childCount).indexOfFirst { parent.getChildAt(it) === rv }
        require(indexInParent >= 0) { "RecyclerView 不在父容器中" }
        val originalParams = rv.layoutParams
        val rvWidth = originalParams?.width ?: MATCH
        val originalHeight = originalParams?.height ?: WRAP
        val rvHeightInWrapper = if (originalHeight == MATCH) WRAP else originalHeight

        val wrapper = LinearLayout(rv.context).apply { orientation = LinearLayout.VERTICAL }
        parent.removeView(rv)
        val wrapperParams = cloneParamsForWrapper(originalParams, parent)
        parent.addView(wrapper, indexInParent, wrapperParams)
        wrapper.addView(rv, LinearLayout.LayoutParams(MATCH, rvHeightInWrapper))
        wrapper.addView(buildSwitchItem(rv.context), LinearLayout.LayoutParams(MATCH, WRAP))
    }

    private fun cloneParamsForWrapper(
        source: ViewGroup.LayoutParams?,
        parent: ViewGroup
    ): ViewGroup.LayoutParams = when {
        source is FrameLayout.LayoutParams -> FrameLayout.LayoutParams(source.width, WRAP).apply {
            gravity = source.gravity
            leftMargin = source.leftMargin; topMargin = source.topMargin
            rightMargin = source.rightMargin; bottomMargin = source.bottomMargin
        }
        source is LinearLayout.LayoutParams -> LinearLayout.LayoutParams(source.width, WRAP).apply {
            weight = source.weight; gravity = source.gravity
            leftMargin = source.leftMargin; topMargin = source.topMargin
            rightMargin = source.rightMargin; bottomMargin = source.bottomMargin
        }
        source is ViewGroup.MarginLayoutParams -> ViewGroup.MarginLayoutParams(source.width, WRAP).apply {
            leftMargin = source.leftMargin; topMargin = source.topMargin
            rightMargin = source.rightMargin; bottomMargin = source.bottomMargin
        }
        parent is FrameLayout -> FrameLayout.LayoutParams(source?.width ?: MATCH, WRAP)
        parent is LinearLayout -> LinearLayout.LayoutParams(source?.width ?: MATCH, WRAP)
        else -> ViewGroup.LayoutParams(source?.width ?: MATCH, WRAP)
    }

    private fun collectTexts(root: View): List<String> {
        val result = mutableListOf<String>()
        fun walk(v: View) {
            when (v) {
                is TextView -> v.text?.toString()?.takeIf { it.isNotBlank() }?.let(result::add)
                is ViewGroup -> for (i in 0 until v.childCount) walk(v.getChildAt(i))
            }
        }
        walk(root)
        return result
    }

    private fun buildSwitchItem(context: Context): View {
        val enabled = localEnabled
        val title = TextView(context).apply {
            text = if (enabled) "全局自动滚动（已开启）" else "全局自动滚动"
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
            setTextColor(Color.parseColor("#333333"))
        }
        val toggle = Switch(context).apply {
            isChecked = enabled
            setOnCheckedChangeListener { _: CompoundButton, checked: Boolean ->
                setEnabled(context, checked)
                title.text = if (checked) "全局自动滚动（已开启）" else "全局自动滚动"
            }
        }
        return LinearLayout(context).apply {
            tag = SWITCH_TAG
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(context, 16), dp(context, 12), dp(context, 16), dp(context, 12))
            addView(title, LinearLayout.LayoutParams(0, WRAP, 1f))
            addView(toggle, LinearLayout.LayoutParams(WRAP, WRAP))
        }
    }

    private fun dp(context: Context, value: Int): Int =
        (value * context.resources.displayMetrics.density + 0.5f).toInt()

    // ==================== 自实现自动滚动 ====================

    /**
     * 动态查找宿主视频卡片上的"播放完成"回调并注册 hook。
     * 播放完成时如果 [localEnabled] 为真，就把当前 VerticalViewPager 切到下一个 item。
     *
     * @param module 当前 LSPosed 模块。
     * @param classLoader 宿主类加载器。
     * @return Unit。
     * Callers: init。
     */
    private fun installPlayCompletedHook(module: XposedModule, classLoader: ClassLoader) {
        val candidateClassNames = listOf(
            "com.ss.android.ugc.aweme.feed.adapter.VideoViewCell",
            "com.ss.android.ugc.aweme.feed.adapter.VideoBaseCell",
            "com.ss.android.ugc.aweme.feed.adapter.FullFeedVideoViewHolder"
        )
        var total = 0
        for (className in candidateClassNames) {
            val clazz = try { classLoader.loadClass(className) } catch (_: Throwable) { continue }
            var current: Class<*>? = clazz
            while (current != null && current != Any::class.java) {
                for (method in current.declaredMethods) {
                    if (method in hookedCompletedMethods) continue
                    if (method.returnType != Void.TYPE) continue
                    if (method.isSynthetic || method.isBridge) continue
                    if (method.parameterCount > 3) continue
                    if (PLAY_COMPLETED_NAMES.none { method.name.contains(it, ignoreCase = true) }) continue
                    try {
                        method.isAccessible = true
                        val m = method
                        module.trackHook(TAG, m).intercept { chain ->
                            val result = chain.proceed()
                            if (localEnabled) {
                                try {
                                    scrollToNext(chain.thisObject)
                                } catch (error: Throwable) {
                                    HookRuntime.failure(TAG, error, "自动滚动切换失败")
                                }
                            }
                            result
                        }
                        hookedCompletedMethods.add(m)
                        total++
                        Log.i(TAG, "已注册播放完成回调：${m.declaringClass.name}.${m.name}")
                    } catch (error: Throwable) {
                        Log.w(TAG, "注册失败：${method.declaringClass.name}.${method.name}", error)
                    }
                }
                current = current.superclass
            }
        }
        HookRuntime.event(TAG, "播放完成回调注册数 = $total")
        Log.i(TAG, "播放完成回调注册数 = $total")
    }

    /**
     * 从视频卡片向上查找 VerticalViewPager，并切到下一个 item。
     * @param cell 触发播放完成的接收者（VideoViewCell / VideoBaseCell / ViewHolder）。
     * @return Unit。
     * Callers: installPlayCompletedHook 的拦截器。
     */
    private fun scrollToNext(cell: Any?) {
        if (cell == null) return
        val view = resolveRootView(cell) ?: return
        val pager = findVerticalPager(view) ?: return
        val currentItem = try {
            pager.javaClass.getMethod("getCurrentItem").invoke(pager) as Int
        } catch (_: Throwable) { return }
        val count = readAdapterCount(pager)
        val target = currentItem + 1
        if (count > 0 && target >= count) {
            HookRuntime.event(TAG, "已到最后一个 item，不再滚动")
            return
        }
        val setter: Method = try {
            pager.javaClass.getMethod(
                "setCurrentItem",
                Int::class.javaPrimitiveType,
                Boolean::class.javaPrimitiveType
            )
        } catch (_: Throwable) {
            try {
                pager.javaClass.getMethod("setCurrentItem", Int::class.javaPrimitiveType)
            } catch (_: Throwable) { return }
        }
        synchronized(scrollCooldownLock) {
            val now = SystemClock.uptimeMillis()
            if (now < scrollCooldownUntilMs) {
                HookRuntime.event(TAG, "自动滚动冷却中，剩余=${scrollCooldownUntilMs - now}ms")
                return
            }
            try {
                if (setter.parameterCount == 2) {
                    setter.invoke(pager, target, true)
                } else {
                    setter.invoke(pager, target)
                }
                scrollCooldownUntilMs = SystemClock.uptimeMillis() + SCROLL_COOLDOWN_MS
                HookRuntime.event(TAG, "全局自动滚动 $currentItem -> $target，冷却 ${SCROLL_COOLDOWN_MS}ms")
            } catch (error: Throwable) {
                scrollCooldownUntilMs = 0L
                HookRuntime.failure(TAG, error, "setCurrentItem 调用失败")
            }
        }
    }

    /** 读取 ViewPager 的 Adapter itemCount，失败返回 -1。 */
    private fun readAdapterCount(pager: Any): Int {
        return try {
            val adapter = pager.javaClass.getMethod("getAdapter").invoke(pager) ?: return -1
            adapter.javaClass.getMethod("getItemCount").invoke(adapter) as Int
        } catch (_: Throwable) {
            try {
                val adapter = pager.javaClass.getMethod("getAdapter").invoke(pager) ?: return -1
                adapter.javaClass.getMethod("getCount").invoke(adapter) as Int
            } catch (_: Throwable) { -1 }
        }
    }

    /** 从各种卡片类型解析出根视图。 */
    private fun resolveRootView(cell: Any): View? {
        if (cell is View) return cell
        // ViewHolder 常见方法名
        for (name in listOf("getRootView", "itemView", "getView")) {
            try {
                val result = when (name) {
                    "itemView" -> cell.javaClass.getField("itemView").get(cell)
                    else -> cell.javaClass.getMethod(name).invoke(cell)
                }
                if (result is View) return result
            } catch (_: Throwable) { /* 继续 */ }
        }
        return null
    }

    /** 沿父链查找 VerticalViewPager。 */
    private fun findVerticalPager(start: View): Any? {
        var current: View? = start
        while (current != null) {
            if (current.javaClass.name == VERTICAL_PAGER_CLASS) return current
            val parent = current.parent
            current = parent as? View
        }
        return null
    }

    // ==================== 入口注册 ====================

    /**
     * 注册 UI 注入与自实现自动滚动。
     *
     * @param module 当前 LSPosed 模块。
     * @param classLoader 宿主类加载器。
     * @return Unit。
     * Callers: AutoScrollHook.init。
     */
    fun init(module: XposedModule, classLoader: ClassLoader) {
        HookRuntime.onDispose(TAG) {
            injected.clear()
            hookedCompletedMethods.clear()
            synchronized(scrollCooldownLock) { scrollCooldownUntilMs = 0L }
        }

        // 1. UI 注入：hook View.onAttachedToWindow，只处理 RecyclerView。
        val attach = View::class.java.getDeclaredMethod("onAttachedToWindow")
        module.trackHook(TAG, attach).intercept { chain ->
            val result = chain.proceed()
            val view = chain.thisObject as? ViewGroup ?: return@intercept result
            if (!isRecyclerLike(view)) return@intercept result
            view.post {
                try { tryInject(view) } catch (error: Throwable) {
                    HookRuntime.failure(TAG, error, "自动滚动开关注入失败")
                }
            }
            result
        }

        // 2. 自实现自动滚动：hook 播放完成回调。
        installPlayCompletedHook(module, classLoader)

        Log.i(TAG, "长按菜单自动滚动入口已注册")
    }
}