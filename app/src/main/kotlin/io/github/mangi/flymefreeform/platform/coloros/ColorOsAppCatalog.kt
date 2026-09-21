package io.github.mangi.flymefreeform.platform.coloros

import android.app.ActivityManager
import android.content.ComponentName
import android.content.Context
import android.content.pm.LauncherActivityInfo
import android.content.pm.LauncherApps
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import android.os.Process
import android.util.Log
import io.github.mangi.flymefreeform.apps.AppSelectionPolicy
import io.github.mangi.flymefreeform.config.ModulePreferences
import io.github.mangi.flymefreeform.config.ModuleSettingsSnapshot
import io.github.mangi.flymefreeform.config.ToolCatalogCodec
import java.text.Collator
import java.util.Locale
import java.util.concurrent.Executor
import java.util.concurrent.atomic.AtomicLong

internal data class RadialAppEntry(
    val component: ComponentName,
    val label: String,
    val icon: Bitmap,
)

internal data class AppCatalogSnapshot(
    val radialApps: List<RadialAppEntry> = emptyList(),
    val panelApps: List<RadialAppEntry> = emptyList(),
    val settings: ModuleSettingsSnapshot = ModuleSettingsSnapshot(),
    /** 外圈应用数；双圈布局以此划分外/内（内圈 = radialApps.drop(outerCount)）。 */
    val outerCount: Int = 0,
) {
    fun matches(settings: ModuleSettingsSnapshot): Boolean =
        this.settings.pinsSaved == settings.pinsSaved &&
            this.settings.pinnedComponents == settings.pinnedComponents &&
            this.settings.innerPinsSaved == settings.innerPinsSaved &&
            this.settings.innerPinnedComponents == settings.innerPinnedComponents
}

/** 目录查询只在后台执行；发布后的 Bitmap 与列表供手势热路径只读。 */
internal class ColorOsAppCatalog(
    private val context: Context,
    private val executor: Executor,
    private val logger: (Int, String, Throwable?) -> Unit,
    /**
     * 侧边栏工具目录（文本协议，由侧边栏进程写入）。
     * 工具不是可启动应用，`LauncherApps` 查不到，只能用这份目录解析固定项里的工具条目。
     */
    private val toolCatalog: () -> String? = { null },
    private val publish: (AppCatalogSnapshot) -> Unit,
) {
    private val iconRenderer = ColorOsRadialIconRenderer(context.resources, logger)

    private val contentRevision = AtomicLong()
    private var cachedContent: CatalogContent? = null // 仅目录工作线程访问。

    fun refresh(settings: ModuleSettingsSnapshot, reloadApps: Boolean = true) {
        if (reloadApps) contentRevision.incrementAndGet()
        executor.execute {
            val revision = contentRevision.get()
            val cached = cachedContent
            val content =
                if (cached != null && cached.revision == revision &&
                    cached.settings.pinsSaved == settings.pinsSaved &&
                    cached.settings.pinnedComponents == settings.pinnedComponents &&
                    cached.settings.innerPinsSaved == settings.innerPinsSaved &&
                    cached.settings.innerPinnedComponents == settings.innerPinnedComponents
                ) {
                    cached
                } else {
                    loadContent(settings, revision) ?: return@execute
                }
            cachedContent = content
            val shapedRadial = content.radialApps.map { entry ->
                val drawable = content.radialSources[entry.component]
                if (drawable == null) entry else {
                    try {
                        entry.copy(
                            icon = iconRenderer
                                .shapedIcon(drawable)
                                .toBitmap(),
                        )
                    } catch (_: RuntimeException) {
                        entry
                    }
                }
            }
            shapedRadial.forEach { it.icon.prepareToDraw() }
            publish(
                AppCatalogSnapshot(
                    shapedRadial,
                    content.panelApps,
                    settings,
                    outerCount = content.radialOuterCount,
                ),
            )
        }
    }

    private fun loadContent(settings: ModuleSettingsSnapshot, revision: Long): CatalogContent? {
        val launcherApps = context.getSystemService(LauncherApps::class.java) ?: return null
        val user = Process.myUserHandle()
        val activities = launcherApps.getActivityList(null, user)
        val entries =
            activities
                .asSequence()
                .filterNot { info -> info.componentName.packageName == MODULE_PACKAGE }
                .mapNotNull(::toEntry)
                .distinctBy(RadialAppEntry::component)
                .toList()
        val byComponent = entries.associateBy(RadialAppEntry::component)
        val recents = recentComponents().mapNotNull(byComponent::get).distinctBy(RadialAppEntry::component)
        val collator = Collator.getInstance(Locale.getDefault())
        val alphabetical =
            entries.sortedWith { first, second ->
                collator.compare(first.label, second.label)
            }
        val byPackage = entries.groupBy { entry -> entry.component.packageName }
        val tools = ToolCatalogCodec.decode(toolCatalog()).associateBy(ToolCatalogCodec.Record::alias)
        val radial =
            if (settings.pinsSaved || settings.innerPinsSaved) {
                // 双圈：外圈在前、内圈在后，两个圈的固定顺序各自保留。
                resolvePins(settings.pinnedComponents, byComponent, byPackage, tools) +
                    resolvePins(settings.innerPinnedComponents, byComponent, byPackage, tools)
            } else {
                AppSelectionPolicy.radialItems(
                    pinsSaved = false,
                    availablePins = emptyList(),
                    recent = recents,
                    all = alphabetical,
                    identity = RadialAppEntry::component,
                    limit = io.github.mangi.flymefreeform.config.ModulePreferences.MAX_PINNED_APPS,
                )
            }
        val excluded = radial.mapTo(HashSet(), RadialAppEntry::component)
        val panel =
            AppSelectionPolicy.panelItems(
                recent = recents,
                all = alphabetical,
                excluded = excluded,
                identity = RadialAppEntry::component,
            )
        val activityByComponent = activities.associateBy(LauncherActivityInfo::getComponentName)
        val sources = radial.mapNotNull { entry ->
            val info = activityByComponent[entry.component] ?: return@mapNotNull null
            try {
                entry.component to info.getIcon(context.resources.displayMetrics.densityDpi)
            } catch (_: RuntimeException) {
                null
            }
        }.toMap()
        panel.forEach { it.icon.prepareToDraw() }
        return CatalogContent(
            revision,
            settings,
            radial,
            radialOuterCount = if (settings.pinsSaved || settings.innerPinsSaved) {
                resolvePins(settings.pinnedComponents, byComponent, byPackage, tools).size
            } else {
                radial.size
            },
            panel,
            sources,
        )
    }

    private data class CatalogContent(
        val revision: Long,
        val settings: ModuleSettingsSnapshot,
        val radialApps: List<RadialAppEntry>,
        val radialOuterCount: Int,
        val panelApps: List<RadialAppEntry>,
        val radialSources: Map<ComponentName, Drawable>,
    )

    /**
     * 固定项按组件精确匹配；ColorOS 的启动组件会随应用更新或别名变化漂移，
     * 因此退化为「同包唯一启动项」兜底，仍无法解析则打诊断码 —— 避免"设置里加了、扇形里看不到"却无线索。
     * 工具固定项（伪包名 `ModulePreferences.TOOL_PACKAGE`）改从侧边栏工具目录解析。
     */
    private fun resolvePins(
        pins: List<ComponentName>,
        byComponent: Map<ComponentName, RadialAppEntry>,
        byPackage: Map<String, List<RadialAppEntry>>,
        tools: Map<String, ToolCatalogCodec.Record>,
    ): List<RadialAppEntry> =
        pins.mapNotNull { pin ->
            if (ModulePreferences.isToolComponent(pin)) {
                return@mapNotNull resolveToolPin(pin, tools)
            }
            byComponent[pin]?.let { return@mapNotNull it }
            val samePackage = byPackage[pin.packageName]
            val remapped = samePackage?.takeIf { it.size == 1 }?.first()
            if (remapped != null) {
                logger(
                    Log.INFO,
                    "PINNED_APP_COMPONENT_REMAPPED ${pin.packageName} -> ${remapped.component.className}",
                    null,
                )
                remapped
            } else {
                logger(Log.WARN, "PINNED_APP_UNRESOLVED ${pin.flattenToString()}", null)
                null
            }
        }

    /** 工具固定项：别名即伪组件的类名；目录缺失或工具在当前设备不可用时打诊断码并跳过。 */
    private fun resolveToolPin(
        pin: ComponentName,
        tools: Map<String, ToolCatalogCodec.Record>,
    ): RadialAppEntry? {
        val record = tools[pin.className]
        if (record == null) {
            logger(Log.WARN, "PINNED_TOOL_UNRESOLVED ${pin.className}", null)
            return null
        }
        if (!record.available) {
            logger(Log.WARN, "PINNED_TOOL_UNAVAILABLE ${pin.className}", null)
            return null
        }
        return RadialAppEntry(
            component = pin,
            label = record.label,
            icon = decodeIcon(record.iconPng) ?: toolPlaceholderIcon(),
        )
    }

    private fun decodeIcon(png: ByteArray?): Bitmap? =
        png?.let { bytes ->
            runCatching {
                BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
            }.getOrNull()
        }

    /** 目录里没有图标时的占位：中性圆形方块，保证固定项仍然可见、可滑选。 */
    private fun toolPlaceholderIcon(): Bitmap {
        val size = (TOOL_ICON_DP * context.resources.displayMetrics.density).toInt().coerceAtLeast(1)
        return Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888).also { bitmap ->
            val canvas = Canvas(bitmap)
            val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = PLACEHOLDER_COLOR }
            canvas.drawRoundRect(0f, 0f, size.toFloat(), size.toFloat(), size / 4f, size / 4f, paint)
        }
    }

    private fun recentComponents(): List<ComponentName> {
        val activityManager = context.getSystemService(ActivityManager::class.java) ?: return emptyList()
        return try {
            @Suppress("DEPRECATION")
            activityManager.getRecentTasks(RECENT_LIMIT, ActivityManager.RECENT_WITH_EXCLUDED)
                .mapNotNull { task -> task.origActivity ?: task.baseIntent?.component }
                .filterNot { component -> component.packageName == context.packageName }
        } catch (_: SecurityException) {
            emptyList()
        } catch (_: RuntimeException) {
            emptyList()
        }
    }

    private fun toEntry(info: LauncherActivityInfo): RadialAppEntry? =
        try {
            RadialAppEntry(
                component = info.componentName,
                label = info.label?.toString()?.trim().orEmpty().ifEmpty { info.componentName.packageName },
                icon = info.getIcon(context.resources.displayMetrics.densityDpi).toBitmap(),
            )
        } catch (_: RuntimeException) {
            null
        }

    private fun Drawable.toBitmap(): Bitmap {
        val systemIconSize =
            context.getSystemService(ActivityManager::class.java)?.launcherLargeIconSize ?: 0
        val targetSize =
            (systemIconSize.takeIf { it > 0 }
                ?: maxOf(intrinsicWidth, intrinsicHeight, 1)).coerceAtLeast(1)
        if (this is BitmapDrawable && bitmap != null) {
            if (bitmap.width == targetSize && bitmap.height == targetSize) return bitmap
            return Bitmap.createScaledBitmap(bitmap, targetSize, targetSize, true)
        }
        return Bitmap.createBitmap(targetSize, targetSize, Bitmap.Config.ARGB_8888).also { bitmap ->
            val canvas = Canvas(bitmap)
            setBounds(0, 0, canvas.width, canvas.height)
            draw(canvas)
        }
    }

    private companion object {
        const val RECENT_LIMIT = 48
        const val MODULE_PACKAGE = "io.github.mangi.flymefreeform"

        /** 工具图标占位尺寸（dp）与颜色。 */
        const val TOOL_ICON_DP = 48f
        const val PLACEHOLDER_COLOR = 0x33FFFFFF
    }
}
