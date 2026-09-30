package com.toki.lsposed.hook

import android.app.Activity
import android.content.ContextWrapper
import android.content.res.Resources
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.view.Window
import android.view.WindowManager
import androidx.activity.ComponentDialog
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.toki.lsposed.ui.tokiColorScheme

/**
 * 使用 Toki 资源与独立生命周期的 Material 3 扫描窗口，尺寸不随扫描内容变化。
 * @param activity 当前前台宿主活动，只在可见期间持有。
 * @param onFailureDismiss 关闭失败提示的回调。
 * Callers: HostScanController.render。
 */
internal class HostScanDialog(activity: Activity, onFailureDismiss: () -> Unit) :
    ComponentDialog(ScanContext(activity), android.R.style.Theme_Material_Light_Dialog_NoActionBar) {
    private var status by mutableStateOf(HostScanStatus())
    private val composeView = ComposeView(context).apply {
        setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
        setContent {
            MaterialTheme(colorScheme = tokiColorScheme(LocalContext.current, isSystemInDarkTheme())) {
                ScanContent(status, onFailureDismiss)
            }
        }
    }

    init {
        requestWindowFeature(Window.FEATURE_NO_TITLE)
        setCancelable(false)
        setCanceledOnTouchOutside(false)
        // ComponentDialog 在 setContentView 中安装自身的 Lifecycle / SavedState 所有者。
        setContentView(composeView)
        checkNotNull(window).apply {
            setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
            setDimAmount(0.48f)
        }
    }

    /**
     * 使用紧凑的 320×340dp 固定目标尺寸，空间不足时缩小，内容在窗口内部滚动。
     * @return Unit。
     * Callers: HostScanController.render。
     */
    override fun show() {
        super.show()
        val configuration = context.resources.configuration
        val density = context.resources.displayMetrics.density
        val width = minOf(320, (configuration.screenWidthDp - 48).coerceAtLeast(1))
        val height = minOf(340, (configuration.screenHeightDp - 48).coerceAtLeast(1))
        checkNotNull(window).setLayout((width * density).toInt(), (height * density).toInt())
    }

    /**
     * 发布真实扫描进度，不修改窗口尺寸或使用模拟进度。
     * @param state 扫描会话的不可变状态。
     * @return Unit。
     * Callers: HostScanController.render。
     */
    fun render(state: HostScanStatus) { status = state }
}

/**
 * 保留宿主活动的窗口服务，隔离 Compose 读取的模块资源和主题。
 * @param activity 宿主前台活动。
 * Callers: HostScanDialog 构造函数。
 */
private class ScanContext(activity: Activity) : ContextWrapper(activity) {
    private val moduleContext = activity.createPackageContext("io.github.meiyongai.toki", 0)
        .createConfigurationContext(activity.resources.configuration)
    private val moduleTheme = moduleContext.resources.newTheme().apply {
        applyStyle(android.R.style.Theme_Material_Light_Dialog_NoActionBar, true)
    }
    /** 提供模块资源。@return Toki 资源集合。Callers: Compose、ComponentDialog。 */
    override fun getResources(): Resources = moduleContext.resources
    /** 提供模块资产。@return Toki 资产管理器。Callers: Android 字体与资源加载。 */
    override fun getAssets(): android.content.res.AssetManager = moduleContext.assets
    /** 提供与模块资源一致的主题。@return 窗口主题。Callers: ComponentDialog。 */
    override fun getTheme(): Resources.Theme = moduleTheme
    /** 解析模块 Compose 类型。@return 模块加载器。Callers: Android 视图系统。 */
    override fun getClassLoader(): ClassLoader = checkNotNull(HostScanDialog::class.java.classLoader)
}

/**
 * 渲染紧凑 Material 3 窗口；说明和方法区域内部滚动，进度与状态固定在底部。
 * @param state 当前阶段和工作量。
 * @param onFailureDismiss 失败提示的关闭回调。
 * @return Unit。
 * Callers: HostScanDialog 的 ComposeView。
 */
@Composable
private fun ScanContent(state: HostScanStatus, onFailureDismiss: () -> Unit) {
    val failed = state.phase == HostScanPhase.FAILED
    val complete = state.phase == HostScanPhase.READY || state.phase == HostScanPhase.CLOSING
    val scanning = state.phase == HostScanPhase.SCANNING
    val heading = when {
        failed -> "适配未完成"
        complete -> "适配结果已保存"
        else -> "正在适配 TikTok"
    }
    val description = when {
        failed -> "流程已停止，不会自动关闭 TikTok。请查看下方原因。"
        complete -> "即将关闭 TikTok，请重新打开以应用结果。"
        state.phase == HostScanPhase.SAVING -> "正在保存并核对查找结果，请稍候。"
        else -> "正在查找功能所需的方法，请保持 TikTok 在前台。完成后将关闭应用。"
    }
    Surface(modifier = Modifier.fillMaxSize(), shape = RoundedCornerShape(28.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHigh, tonalElevation = 6.dp) {
        Column(Modifier.padding(24.dp)) {
            Column(Modifier.weight(1f).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(heading, style = MaterialTheme.typography.headlineSmall)
                Box(Modifier.fillMaxWidth().height(60.dp).verticalScroll(rememberScrollState())) {
                    Text(description, style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Surface(shape = MaterialTheme.shapes.medium, color = MaterialTheme.colorScheme.surfaceContainerHighest) {
                    Box(Modifier.fillMaxWidth().height(80.dp).padding(12.dp).verticalScroll(rememberScrollState())) {
                        Text(state.detail, style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace)
                    }
                }
            }
            Spacer(Modifier.height(8.dp))
            Box(Modifier.fillMaxWidth().height(8.dp), contentAlignment = Alignment.Center) {
                if (!failed) {
                    if (complete || (scanning && state.total > 0)) {
                        LinearProgressIndicator(progress = { if (complete) 1f else state.completed.toFloat() / state.total },
                            modifier = Modifier.fillMaxWidth())
                    } else LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                }
            }
            Spacer(Modifier.height(8.dp))
            Row(Modifier.fillMaxWidth().height(48.dp), verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(when {
                    failed -> "可关闭此窗口，在 Toki 查看功能诊断。"
                    complete -> "100% · 已保存并核对"
                    scanning && state.total > 0 -> "${state.completed * 100L / state.total}% · 正在查找方法"
                    state.phase == HostScanPhase.SAVING -> "正在核对适配结果"
                    else -> "正在读取代码包"
                }, modifier = Modifier.weight(1f).verticalScroll(rememberScrollState()),
                    style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
                if (failed) TextButton(onClick = onFailureDismiss) { Text("关闭") }
            }
        }
    }
}
