package io.github.mangi.flymefreeform.platform.coloros

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import android.util.Log
import io.github.mangi.flymefreeform.config.ToolCatalogCodec
import java.io.ByteArrayOutputStream
import java.lang.reflect.InvocationTargetException

/**
 * 侧边栏工具的枚举与执行（只在侧边栏进程内使用）。
 *
 * 实测结论（ColorOS 17 / 侧边栏 17.9.2）：工具不是独立 App，而是
 * `com.oplus.smartsidebar.panelview.edgepanel.data.entrybeans.models.tools.AbsTool` 的子类
 * （如 `BreenoScreenIdentifyTool` = 小布识屏、`GlobalTranslationTool` = 屏幕翻译），
 * 由 `ToolEntryHelper` 统一持有，执行入口是各自的 `handle()`。
 * 因此：
 * - 枚举必须在侧边栏进程做，导出的别名/名称/图标经框架远端配置给 App 与 system_server；
 * - 执行也必须回到侧边栏进程（扇形侧通过 [SidebarProtocol.RUN_TOOL] 转交）。
 *
 * @author bomo
 * @param context 侧边栏主题 Context
 * @param loader 侧边栏 ClassLoader
 * @param log 诊断日志
 */
internal class ColorOsToolCatalog(
    private val context: Context,
    private val loader: ClassLoader,
    private val log: (Int, String, Throwable?) -> Unit,
) {
    private var helper: Any? = null
    private var helperClass: Class<*>? = null

    /** 原厂工具路由与条目类型（按需加载，失败在调用处降级）。 */
    private val pageRoutClass: Class<*> by lazy { loader.loadClass(PAGE_ROUT_CLASS) }
    private val entryBeanClass: Class<*> by lazy { loader.loadClass(ENTRY_BEAN_CLASS) }

    /**
     * 枚举当前设备上实际可用的工具。
     * 任何反射失败都退化为空列表（不抛异常），由调用方决定是否重试。
     */
    fun build(): List<ToolCatalogCodec.Record> {
        val instance = ensureHelper() ?: return emptyList()
        val tools = readTools(instance) ?: return emptyList()
        return tools.mapNotNull { tool -> toRecord(tool) }
    }

    /**
     * 执行指定别名的工具。
     *
     * 优先走原厂点击路径 `PageRoutUtils.startSysTool(entryBean)` —— 反编译证实原厂点工具
     * 不是裸调 `AbsTool.handle()`，还会做 PageRout 记账（点击坐标等）；
     * 小布识屏这类依赖坐标的工具缺了它就会"有时没反应"（截屏不依赖所以一直正常）。
     * 找不到条目时退回 `handle()` 兜底。
     *
     * @param alias [build] 导出的别名
     * @return 是否成功调用到执行入口
     */
    fun run(alias: String): Boolean {
        val instance = ensureHelper() ?: return false
        // 1) 原厂路径：从工具条目列表里找该别名的 EntryBean，交给 PageRoutUtils。
        val bean = findToolBean(instance, alias)
        if (bean != null) {
            try {
                pageRoutClass
                    .getMethod(PAGE_ROUT_METHOD, entryBeanClass)
                    .invoke(null, bean)
                log(Log.INFO, "TOOL_INVOKED $alias via=${PAGE_ROUT_METHOD}", null)
                return true
            } catch (exception: Exception) {
                log(Log.WARN, "TOOL_PAGE_ROUT_FAILED $alias", unwrap(exception))
            }
        }
        // 2) 兜底：直接 handle()（截屏类不依赖 PageRout 记账，仍然可用）。
        return try {
            val tool =
                helperClass?.getMethod("getToolByAlias", String::class.java)
                    ?.invoke(instance, alias)
                    ?: return false
            tool.javaClass.getMethod("handle").invoke(tool)
            log(Log.INFO, "TOOL_INVOKED $alias via=handle", null)
            true
        } catch (exception: Exception) {
            log(Log.WARN, "TOOL_INVOKE_FAILED $alias", unwrap(exception))
            false
        }
    }

    /** 在原厂工具条目列表里按别名找 EntryBean（工具条目的 activity 字段即别名）。 */
    private fun findToolBean(instance: Any, alias: String): Any? =
        try {
            val helperClass = loader.loadClass(ENTRY_HELPER_CLASS)
            val helper = helperClass.getMethod("getActiveInstance").invoke(null) ?: return null
            val lists =
                listOf("getShownTools", "getAllAppListForTool").mapNotNull { name ->
                    runCatching { helperClass.getMethod(name).invoke(helper) as? List<*> }.getOrNull()
                }
            lists
                .flatten()
                .filterNotNull()
                .firstOrNull { bean ->
                    runCatching {
                        bean.javaClass.getMethod("getActivity").invoke(bean) == alias ||
                            bean.javaClass.getMethod("getIntentString").invoke(bean) == alias
                    }.getOrDefault(false)
                }
        } catch (exception: Exception) {
            log(Log.WARN, "TOOL_BEAN_LOOKUP_FAILED", unwrap(exception))
            null
        }

    private fun ensureHelper(): Any? {
        helper?.let { return it }
        return try {
            val type = loader.loadClass(TOOL_HELPER_CLASS)
            val instance =
                runCatching { type.getMethod("getActiveInstance").invoke(null) }.getOrNull()
                    ?: type.getMethod("createAndGetInstance").invoke(null)
                    ?: return null
            // init(Context) 幂等：重复调用只是刷新内部表；失败仍保留实例供 getToolByAlias 使用。
            runCatching { type.getMethod("init", Context::class.java).invoke(instance, context) }
                .onFailure { exception -> log(Log.WARN, "TOOL_HELPER_INIT_FAILED", unwrap(exception)) }
            helperClass = type
            helper = instance
            instance
        } catch (exception: Exception) {
            log(Log.WARN, "TOOL_HELPER_UNAVAILABLE", unwrap(exception))
            null
        }
    }

    @Suppress("UNCHECKED_CAST")
    private fun readTools(instance: Any): List<Any>? =
        try {
            val field = (helperClass ?: return null).getDeclaredField(TOOLS_FIELD)
            field.isAccessible = true
            (field.get(instance) as? Map<*, *>)?.values?.filterNotNull()
        } catch (exception: Exception) {
            log(Log.WARN, "TOOL_TABLE_UNAVAILABLE", unwrap(exception))
            null
        }

    private fun toRecord(tool: Any): ToolCatalogCodec.Record? =
        try {
            val type = tool.javaClass
            val alias = type.getMethod("getAlias").invoke(tool) as? String ?: return null
            if (alias.isEmpty()) return null
            val label =
                (type.getMethod("getLabel").invoke(tool) as? String)?.takeIf { it.isNotBlank() }
                    ?: (type.getMethod("getChineseName").invoke(tool) as? String)
                    ?: return null
            val available = type.getMethod("isToolAvailable").invoke(tool) as? Boolean ?: false
            ToolCatalogCodec.Record(
                alias = alias,
                label = label.trim(),
                available = available,
                iconPng = loadIcon(tool, type),
            )
        } catch (exception: Exception) {
            log(Log.WARN, "TOOL_ENTRY_SKIPPED", unwrap(exception))
            null
        }

    /** 优先用工具自带图标资源，缺失时回退到它依赖的应用图标；再失败则不带图标。 */
    private fun loadIcon(tool: Any, type: Class<*>): ByteArray? {
        val drawable =
            runCatching {
                val iconRes = type.getMethod("getIconRes").invoke(tool) as? Int ?: 0
                if (iconRes != 0) context.resources.getDrawable(iconRes, context.theme) else null
            }.getOrNull() ?: fallbackIcon(tool, type)
        return drawable?.let(::encodePng)
    }

    private fun fallbackIcon(tool: Any, type: Class<*>): Drawable? =
        runCatching {
            val pkg = type.getMethod("getValidPkg").invoke(tool) as? String ?: return null
            context.packageManager.getApplicationIcon(pkg)
        }.getOrNull()

    private fun encodePng(drawable: Drawable): ByteArray? =
        runCatching {
            val size = TOOL_ICON_PX
            val bitmap =
                if (drawable is BitmapDrawable && drawable.bitmap != null) {
                    Bitmap.createScaledBitmap(drawable.bitmap, size, size, true)
                } else {
                    Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888).also { target ->
                        val canvas = Canvas(target)
                        drawable.setBounds(0, 0, canvas.width, canvas.height)
                        drawable.draw(canvas)
                    }
                }
            ByteArrayOutputStream().use { stream ->
                bitmap.compress(Bitmap.CompressFormat.PNG, 100, stream)
                stream.toByteArray()
            }
        }.getOrNull()

    private fun unwrap(exception: Throwable): Throwable =
        (exception as? InvocationTargetException)?.cause ?: exception

    private companion object {
        const val PAGE_ROUT_CLASS =
            "com.oplus.smartsidebar.panelview.edgepanel.utils.PageRoutUtils"
        const val PAGE_ROUT_METHOD = "startSysTool"
        const val ENTRY_BEAN_CLASS =
            "com.oplus.smartsidebar.panelview.edgepanel.data.entrybeans.models.beans.EntryBean"
        const val ENTRY_HELPER_CLASS =
            "com.oplus.smartsidebar.panelview.edgepanel.data.entrybeans.EntryBeanHelper"
        const val TOOL_HELPER_CLASS =
            "com.oplus.smartsidebar.panelview.edgepanel.data.entrybeans.ToolEntryHelper"

        /** 原厂 ToolEntryHelper 内部持有全部工具的 Map 字段名。 */
        const val TOOLS_FIELD = "mTools"

        /** 图标上限；目录要经框架配置搬运，过大会拖慢写入与读取。 */
        const val TOOL_ICON_PX = 72
    }
}
