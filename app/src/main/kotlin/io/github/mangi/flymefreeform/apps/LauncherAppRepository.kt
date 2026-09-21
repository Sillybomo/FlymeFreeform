package io.github.mangi.flymefreeform.apps

import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.LauncherApps
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import android.os.Process
import android.os.SystemClock
import io.github.mangi.flymefreeform.config.ModulePreferences
import io.github.mangi.flymefreeform.config.SharedStateProtocol
import io.github.mangi.flymefreeform.config.ToolCatalogCodec
import java.text.Collator
import java.util.Locale
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

internal data class InstalledLauncherApp(
    val component: ComponentName,
    val label: String,
    val icon: Bitmap,
)

internal class LauncherAppRepository(
    private val context: Context,
    /**
     * 侧边栏工具目录（文本协议，由 Hook 侧广播后经模块 App 落盘）。
     * 工具不是可启动应用，`LauncherApps` 枚举不到，需单独并入候选列表，
     * 固定后用伪包名 [ModulePreferences.TOOL_PACKAGE] 标识。
     */
    private val toolCatalog: () -> String? = { null },
) {
    private val worker =
        ThreadPoolExecutor(
            1,
            1,
            0L,
            TimeUnit.MILLISECONDS,
            ArrayBlockingQueue<Runnable>(1),
            { task -> Thread(task, THREAD_NAME) },
            ThreadPoolExecutor.DiscardOldestPolicy(),
        )
    private val mutableApps = MutableStateFlow<List<InstalledLauncherApp>>(emptyList())
    val apps: StateFlow<List<InstalledLauncherApp>> = mutableApps.asStateFlow()
    private var cachedApps: List<InstalledLauncherApp> = emptyList()
    private var cachedCatalog: String? = null
    private var cachedTools: List<InstalledLauncherApp> = emptyList()

    private val packageReceiver =
        object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                refresh()
            }
        }

    init {
        val filter =
            IntentFilter().apply {
                addAction(Intent.ACTION_PACKAGE_ADDED)
                addAction(Intent.ACTION_PACKAGE_CHANGED)
                addAction(Intent.ACTION_PACKAGE_REMOVED)
                addAction(Intent.ACTION_PACKAGE_REPLACED)
                addDataScheme("package")
            }
        // Repository 由 Application 持有到进程结束，因此接收器也只注册一次。
        context.registerReceiver(packageReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
    }

    fun refresh() {
        worker.execute {
            val launcherApps = context.getSystemService(LauncherApps::class.java) ?: return@execute
            val collator = Collator.getInstance(Locale.getDefault())
            cachedApps =
                launcherApps
                    .getActivityList(null, Process.myUserHandle())
                    .asSequence()
                    .filterNot { it.componentName.packageName == context.packageName }
                    .mapNotNull { info ->
                        try {
                            InstalledLauncherApp(
                                component = info.componentName,
                                label = info.label?.toString()?.trim().orEmpty().ifEmpty { info.componentName.packageName },
                                icon = info.getIcon(context.resources.displayMetrics.densityDpi).toBitmap(),
                            )
                        } catch (_: RuntimeException) {
                            null
                        }
                    }
                    .distinctBy(InstalledLauncherApp::component)
                    .sortedWith { first, second -> collator.compare(first.label, second.label) }
                    .toList()
            publish()
        }
    }

    /**
     * 工具目录到达或变化后重算列表。
     * 目录未变化时只复用缓存，避免每次配置回调都重新解码图标。
     */
    fun refreshTools() {
        worker.execute { publish() }
    }

    private fun publish() {
        val catalog = runCatching { toolCatalog() }.getOrNull()
        if (catalog.isNullOrEmpty() && shouldRequestTools()) {
            // 目录缺失：向 system_server 索要（App 打开设置时主动拉一次，绕开冷启动延迟）。
            runCatching {
                context.sendBroadcast(Intent(SharedStateProtocol.ACTION_REQUEST_TOOLS))
                // system_server 收到请求后写 Settings.Global，这里延迟重读两次把目录捡回来。
                val handler = android.os.Handler(android.os.Looper.getMainLooper())
                for (delay in longArrayOf(600L, 1_800L)) {
                    handler.postDelayed({ worker.execute { publish() } }, delay)
                }
            }
        }
        if (catalog != cachedCatalog) {
            cachedCatalog = catalog
            cachedTools = decodeTools(catalog)
        }
        mutableApps.value =
            if (cachedTools.isEmpty()) cachedApps else cachedTools.sortedBy { it.label } + cachedApps
    }

    /** 只保留当前设备实际可用的工具；图标缺失时用中性占位方块，保证条目可见可点。 */
    private fun decodeTools(catalog: String?): List<InstalledLauncherApp> =
        ToolCatalogCodec.decode(catalog)
            .filter(ToolCatalogCodec.Record::available)
            .mapNotNull { record ->
                try {
                    InstalledLauncherApp(
                        component = ComponentName(ModulePreferences.TOOL_PACKAGE, record.alias),
                        label = record.label,
                        icon = record.iconPng?.let { bytes ->
                            BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                        } ?: placeholderIcon(),
                    )
                } catch (_: RuntimeException) {
                    null
                }
            }

    /** 索要目录的节流：10 秒内只发一次，避免目录始终缺失时打爆广播。 */
    private fun shouldRequestTools(): Boolean {
        val now = SystemClock.elapsedRealtime()
        if (now - lastToolRequestAt < TOOL_REQUEST_INTERVAL_MS) return false
        lastToolRequestAt = now
        return true
    }

    private fun placeholderIcon(): Bitmap {
        val size = (ICON_CACHE_SIZE_DP * context.resources.displayMetrics.density).toInt().coerceAtLeast(1)
        return Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888).also { bitmap ->
            val canvas = Canvas(bitmap)
            val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0x33FFFFFF }
            canvas.drawRoundRect(0f, 0f, size.toFloat(), size.toFloat(), size / 4f, size / 4f, paint)
        }
    }

    private fun Drawable.toBitmap(): Bitmap {
        val targetSize = (ICON_CACHE_SIZE_DP * context.resources.displayMetrics.density).toInt().coerceAtLeast(1)
        if (this is BitmapDrawable && bitmap != null) {
            if (bitmap.width == targetSize && bitmap.height == targetSize) return bitmap
            return Bitmap.createScaledBitmap(bitmap, targetSize, targetSize, true)
        }
        val result = Bitmap.createBitmap(targetSize, targetSize, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(result)
        setBounds(0, 0, canvas.width, canvas.height)
        draw(canvas)
        return result
    }

    private var lastToolRequestAt = 0L

    private companion object {
        const val THREAD_NAME = "FlymeFreeform-AppCatalog"
        const val TOOL_REQUEST_INTERVAL_MS = 10_000L
        const val ICON_CACHE_SIZE_DP = 48f
    }
}
