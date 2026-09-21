package io.github.mangi.flymefreeform.platform.coloros

import android.content.ComponentName
import android.content.Context
import android.content.pm.LauncherApps
import android.graphics.drawable.Drawable
import android.os.Process
import android.util.Log
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import io.github.mangi.flymefreeform.config.ModulePreferences

/**
 * 「全部」面板的「最近小窗」区块。
 *
 * 数据由 system_server 侧在每次小窗启动成功后写入框架远端配置（[ModulePreferences.KEY_RECENT_FREEFORM]），
 * 本区块在侧边栏进程渲染，插到原生 `app_list` 之前，因此不改动原厂适配器与列表状态机。
 * 点击复用原厂点击路径（EntryBean → AppLabelData → AllAppDataHandlerImpl.onAppItemClicked）。
 *
 * @author bomo
 * @param context 侧边栏主题 Context（与原厂面板同源）
 * @param loader 侧边栏 ClassLoader，用于反射原厂点击链路
 * @param log 诊断日志（失败只降级不崩溃）
 */
internal class ColorOsRecentSection(
    private val context: Context,
    private val loader: ClassLoader,
    private val log: (Int, String, Throwable?) -> Unit,
) {
    private val launcherApps = context.getSystemService(LauncherApps::class.java)
    private var clicker: NativeClicker? = null
    private var clickUnavailableLogged = false

    /**
     * 把区块插入面板容器。
     *
     * @param panelView 原厂面板根视图（layout_all_app 的宿主）
     * @param recents 最近小窗应用，最近的在前
     * @param onItemClicked 点击后关闭模块窗口的回调
     * @return 是否插入成功（无数据、结构不符或异常时返回 false）
     */
    fun attach(
        panelView: View,
        recents: List<ComponentName>,
        onItemClicked: () -> Unit,
    ): Boolean {
        val items = recents.take(ModulePreferences.MAX_RECENT_FREEFORM)
        if (items.isEmpty()) return false
        Log.i(TAG, "RECENT_SECTION_BUILD count=${items.size}")
        return try {
            val container =
                panelView.findViewById<ViewGroup>(resourceId(CONTAINER_ID, "id"))
                    ?: run {
                        log(Log.WARN, "RECENT_SECTION_CONTAINER_MISSING", null)
                        return false
                    }
            val appList =
                panelView.findViewById<View>(resourceId(APP_LIST_ID, "id"))
                    ?: run {
                        log(Log.WARN, "RECENT_SECTION_LIST_MISSING", null)
                        return false
                    }
            val index = container.indexOfChild(appList).coerceAtLeast(0)
            val resolved = items.mapNotNull { component -> resolve(component) }
            if (resolved.isEmpty()) return false
            val strip = buildStrip(resolved, onItemClicked)
            container.addView(
                strip,
                index,
                LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                ),
            )
            log(Log.INFO, "RECENT_SECTION_ATTACHED count=${resolved.size}", null)
            true
        } catch (exception: Exception) {
            log(Log.WARN, "RECENT_SECTION_ATTACH_FAILED", exception)
            false
        }
    }

    private data class RecentItem(
        val component: ComponentName,
        val label: String,
        val icon: Drawable,
    )

    private fun resolve(component: ComponentName): RecentItem? =
        try {
            val activities = launcherApps?.getActivityList(component.packageName, Process.myUserHandle())
            val info = activities?.firstOrNull { it.componentName == component } ?: return null
            RecentItem(
                component = component,
                label = info.label?.toString()?.trim().orEmpty().ifEmpty { component.packageName },
                icon = info.getIcon(context.resources.displayMetrics.densityDpi),
            )
        } catch (_: RuntimeException) {
            null
        }

    private fun buildStrip(
        items: List<RecentItem>,
        onItemClicked: () -> Unit,
    ): View {
        val textColor = themeColor()
        val column =
            LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(0, dp(4f), 0, dp(6f))
            }
        column.addView(
            TextView(context).apply {
                text = HEADER_TEXT
                setTextSize(TypedValue.COMPLEX_UNIT_SP, HEADER_TEXT_SP)
                setTextColor(textColor)
                setPadding(dp(16f), dp(2f), dp(16f), dp(2f))
                includeFontPadding = false
            },
        )
        val row =
            LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                setPadding(dp(10f), 0, dp(10f), 0)
            }
        items.forEach { item ->
            row.addView(buildItem(item, textColor) {
                onClick(item.component)
                onItemClicked()
            })
        }
        column.addView(
            HorizontalScrollView(context).apply {
                isHorizontalScrollBarEnabled = false
                overScrollMode = View.OVER_SCROLL_NEVER
                addView(
                    row,
                    ViewGroup.LayoutParams(
                        ViewGroup.LayoutParams.WRAP_CONTENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT,
                    ),
                )
            },
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ),
        )
        return column
    }

    private fun buildItem(item: RecentItem, textColor: Int, onClick: () -> Unit): View {
        val holder =
            LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER_HORIZONTAL
                isClickable = true
                isFocusable = true
                setPadding(dp(4f), dp(4f), dp(4f), dp(2f))
                layoutParams =
                    LinearLayout.LayoutParams(dp(ITEM_WIDTH_DP), ViewGroup.LayoutParams.WRAP_CONTENT)
                setOnClickListener { onClick() }
            }
        holder.addView(
            ImageView(context).apply {
                setImageDrawable(item.icon)
                layoutParams =
                    LinearLayout.LayoutParams(dp(ICON_SIZE_DP), dp(ICON_SIZE_DP))
            },
        )
        holder.addView(
            TextView(context).apply {
                text = item.label
                setTextSize(TypedValue.COMPLEX_UNIT_SP, LABEL_TEXT_SP)
                setTextColor(textColor)
                maxLines = 1
                ellipsize = android.text.TextUtils.TruncateAt.END
                gravity = Gravity.CENTER
                layoutParams =
                    LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT,
                    )
            },
        )
        return holder
    }

    /** 复用原厂点击链路；链路不可用时只打一次诊断码，不影响区块展示。 */
    private fun onClick(component: ComponentName) {
        val target = clicker ?: createClicker()?.also { clicker = it }
        if (target == null) {
            if (!clickUnavailableLogged) {
                clickUnavailableLogged = true
                log(Log.WARN, "RECENT_SECTION_CLICK_UNAVAILABLE", null)
            }
            return
        }
        try {
            if (!target.click(component)) {
                log(Log.WARN, "RECENT_SECTION_TARGET_NOT_IN_CATALOG ${component.flattenToString()}", null)
            }
        } catch (exception: Exception) {
            log(Log.WARN, "RECENT_SECTION_CLICK_FAILED", exception)
        }
    }

    private fun createClicker(): NativeClicker? =
        try {
            val entryHelperClass = loader.loadClass(ENTRY_HELPER_CLASS)
            val activeInstance = entryHelperClass.getMethod("getActiveInstance").invoke(null)
                ?: entryHelperClass.getMethod("createAndGetInstance").invoke(null)
                ?: return null
            val lists =
                listOf("getAllAppListLocal", "getAllAppListForApp", "getShownApps")
                    .mapNotNull { name ->
                        runCatching { entryHelperClass.getMethod(name).invoke(activeInstance) as? List<*> }
                            .getOrNull()
                    }
            NativeClicker(
                loader = loader,
                entryBeans = lists.flatten().filterNotNull(),
                log = log,
            )
        } catch (exception: Exception) {
            log(Log.WARN, "RECENT_SECTION_CATALOG_UNAVAILABLE", exception)
            null
        }

    private fun themeColor(): Int {
        val id = resourceId(COLOR_ATTR, "attr")
        val value = TypedValue()
        return if (id != 0 && context.theme.resolveAttribute(id, value, true) &&
            value.type >= TypedValue.TYPE_FIRST_COLOR_INT && value.type <= TypedValue.TYPE_LAST_COLOR_INT
        ) {
            value.data
        } else {
            DEFAULT_TEXT_COLOR
        }
    }

    private fun dp(value: Float): Int = (value * context.resources.displayMetrics.density).toInt()

    private fun dp(value: Int): Int = dp(value.toFloat())

    @Suppress("DiscouragedApi")
    private fun resourceId(name: String, type: String): Int =
        context.resources.getIdentifier(name, type, ColorOsSidebarTarget.PACKAGE_NAME)

    /**
     * 用原厂 EntryBeanHelper 的条目构造 AppLabelData，并走原厂 onAppItemClicked，
     * 使「最近小窗」的点击与原厂面板条目完全同路径。
     */
    private class NativeClicker(
        loader: ClassLoader,
        private val entryBeans: List<Any>,
        private val log: (Int, String, Throwable?) -> Unit,
    ) {
        private val dataClass = loader.loadClass(DATA_CLASS)
        private val dataCtor = dataClass.getConstructor(entryBeanClass(loader))
        private val pkgGetter = dataCtor.parameterTypes[0].getMethod("getPkg")
        private val activityGetter = dataCtor.parameterTypes[0].getMethod("getActivity")
        private val handlerClass = loader.loadClass(HANDLER_CLASS)
        private val handler = handlerClass.getField("INSTANCE").get(null)
        private val clickMethod = handlerClass.getMethod("onAppItemClicked", dataClass, Boolean::class.javaPrimitiveType)

        fun click(component: ComponentName): Boolean {
            val bean =
                entryBeans.firstOrNull { candidate ->
                    (pkgGetter.invoke(candidate) as? String) == component.packageName &&
                        (activityGetter.invoke(candidate) as? String) == component.className
                } ?: return false
            val labelData = dataCtor.newInstance(bean)
            clickMethod.invoke(handler, labelData, false)
            return true
        }

        private companion object {
            fun entryBeanClass(loader: ClassLoader): Class<*> =
                loader.loadClass("com.oplus.smartsidebar.panelview.edgepanel.data.entrybeans.models.beans.EntryBean")
        }
    }

    private companion object {
        const val TAG = "FlymeFreeform"

        /** 原厂布局 layout_all_app 中承载搜索栏与列表的垂直容器。 */
        const val CONTAINER_ID = "all_app_search_motion_group"

        /** 原厂应用/工具列表；区块插在它之前。 */
        const val APP_LIST_ID = "app_list"

        const val ENTRY_HELPER_CLASS =
            "com.oplus.smartsidebar.panelview.edgepanel.data.entrybeans.EntryBeanHelper"

        const val DATA_CLASS = "com.oplus.smartsidebar.panelview.edgepanel.data.AppLabelData"

        const val HANDLER_CLASS =
            "com.oplus.smartsidebar.panelview.edgepanel.data.viewdatahandlers.AllAppDataHandlerImpl"

        /** 与原厂面板标题同源的主题色属性。 */
        const val COLOR_ATTR = "couiColorPrimaryTextOnPopup"

        const val HEADER_TEXT = "最近小窗"
        const val HEADER_TEXT_SP = 13f
        const val LABEL_TEXT_SP = 11f
        const val ICON_SIZE_DP = 46
        const val ITEM_WIDTH_DP = 62

        /** 主题属性解析失败时的兜底文字色。 */
        const val DEFAULT_TEXT_COLOR = 0xFF000000.toInt()
    }
}
