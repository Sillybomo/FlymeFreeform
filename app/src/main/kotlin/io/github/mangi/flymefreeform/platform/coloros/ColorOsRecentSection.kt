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
import java.util.ArrayDeque

/**
 * 「全部」面板的「最近小窗」区块。
 *
 * 数据由 system_server 侧在每次小窗启动成功后经模块 App 落盘
 * （[ModulePreferences.KEY_RECENT_FREEFORM]），本区块在侧边栏进程渲染，
 * 插到原生 `app_list` 之前，不改动原厂适配器与列表状态机。
 * 点击经会话通道交回 system_server 走既有小窗启动链路（侧边栏进程无法自建小窗）。
 *
 * @author bomo
 * @param context 侧边栏主题 Context（与原厂面板同源）
 * @param loader 侧边栏 ClassLoader
 * @param log 诊断日志（失败只降级不崩溃）
 */
internal class ColorOsRecentSection(
    private val context: Context,
    private val loader: ClassLoader,
    private val log: (Int, String, Throwable?) -> Unit,
) {
    private val launcherApps = context.getSystemService(LauncherApps::class.java)
    private var nativeTitleColor: Int? = null

    /**
     * 把区块插入面板容器。
     *
     * @param panelView 原厂面板根视图（layout_all_app 的宿主）
     * @param recents 最近小窗应用，最近的在前
     * @param onLaunchComponent 点击条目后的小窗启动回调（交回 system_server）
     * @param onItemClicked 点击后关闭模块窗口的回调
     * @return 是否插入成功（无数据、结构不符或异常时返回 false）
     */
    fun attach(
        panelView: View,
        recents: List<ComponentName>,
        onLaunchComponent: (ComponentName) -> Unit,
        onItemClicked: () -> Unit,
    ): Boolean {
        val items = recents.take(ModulePreferences.MAX_RECENT_FREEFORM)
        if (items.isEmpty()) return false
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
            val built = buildStrip(panelView, resolved, onLaunchComponent, onItemClicked)
            container.addView(
                built.root,
                index,
                LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                ),
            )
            // @author bomo 标题样式必须与原厂区块标题（「工具」/「应用」）一致（用户 2026-09-22 要求）。
            syncHeaderStyleLater(built.header, panelView)
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

    /** @author bomo 自建区块的根视图与标题 TextView（标题样式需在挂载后再抄原厂）。 */
    private data class BuiltStrip(
        val root: View,
        val header: TextView,
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
        panelView: View,
        items: List<RecentItem>,
        onLaunchComponent: (ComponentName) -> Unit,
        onItemClicked: () -> Unit,
    ): BuiltStrip {
        val textColor = themeColor(panelView)
        val column =
            LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(0, dp(4f), 0, dp(6f))
            }
        val header =
            TextView(context).apply {
                text = HEADER_TEXT
                // 兜底字号：挂载后会被 syncHeaderStyleLater 换成原厂区块标题的真实样式。
                setTextSize(TypedValue.COMPLEX_UNIT_SP, HEADER_TEXT_SP)
                setTextColor(textColor)
                setPadding(dp(16f), dp(2f), dp(16f), dp(2f))
                includeFontPadding = false
            }
        column.addView(header)
        val row =
            LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                setPadding(dp(10f), 0, dp(10f), 0)
            }
        items.forEach { item ->
            row.addView(
                buildItem(item, textColor) {
                    // 侧边栏进程无法自建小窗：经会话通道交回 system_server 启动。
                    onLaunchComponent(item.component)
                    onItemClicked()
                },
            )
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
        return BuiltStrip(column, header)
    }

    /**
     * @author bomo 把「最近小窗」标题的文字样式换成原厂区块标题（「工具」/「应用」）的样式。
     *
     * 原厂 `app_list` 是 RecyclerView，插入这一刻它可能还没 layout（此时取不到标题 view），
     * 所以先等一帧、必要时再等一帧；两帧后仍拿不到就保留兜底字号并打日志便于定位。
     * **逐项复制而非写死数值**：原厂换主题/改字号时会自动跟随。
     */
    private fun syncHeaderStyleLater(header: TextView, panelView: View) {
        header.post {
            if (applyNativeHeaderStyle(header, panelView)) {
                log(Log.INFO, "RECENT_SECTION_HEADER_STYLE_SYNCED", null)
                return@post
            }
            header.post {
                if (applyNativeHeaderStyle(header, panelView)) {
                    log(Log.INFO, "RECENT_SECTION_HEADER_STYLE_SYNCED_LATE", null)
                } else {
                    log(Log.WARN, "RECENT_SECTION_HEADER_STYLE_FALLBACK sp=$HEADER_TEXT_SP", null)
                }
            }
        }
    }

    /**
     * @return 是否成功抄到原厂区块标题样式；取不到返回 false（调用方保留兜底样式）
     */
    private fun applyNativeHeaderStyle(header: TextView, panelView: View): Boolean {
        val native = findNativeSectionHeader(panelView) ?: return false
        header.setTextSize(TypedValue.COMPLEX_UNIT_PX, native.textSize)
        header.typeface = native.typeface
        header.setTextColor(native.currentTextColor)
        header.letterSpacing = native.letterSpacing
        header.textScaleX = native.textScaleX
        header.includeFontPadding = native.includeFontPadding
        return true
    }

    /** 广度优先在原厂面板里找区块标题（先命中「工具」）。 */
    private fun findNativeSectionHeader(panelView: View): TextView? {
        if (panelView !is ViewGroup) return null
        val queue = ArrayDeque<View>()
        queue.add(panelView)
        var visited = 0
        while (queue.isNotEmpty() && visited < MAX_SCAN_VIEWS) {
            visited++
            val node = queue.removeFirst()
            if (node is TextView && node.text?.toString()?.trim() in NATIVE_SECTION_TITLES) {
                return node
            }
            if (node is ViewGroup) {
                for (index in 0 until node.childCount) queue.add(node.getChildAt(index))
            }
        }
        return null
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

    /**
     * 文字颜色：优先抄原厂标题「全部」的 TextView 颜色（随主题/夜间模式自动一致），
     * 找不到再退回主题属性，最后退回黑色。
     */
    private fun themeColor(panelView: View): Int {
        nativeTitleColor?.let { return it }
        findNativeTitleColor(panelView)?.let { color ->
            nativeTitleColor = color
            return color
        }
        val id = resourceId(COLOR_ATTR, "attr")
        val value = TypedValue()
        if (id != 0 && context.theme.resolveAttribute(id, value, true) &&
            value.type >= TypedValue.TYPE_FIRST_COLOR_INT && value.type <= TypedValue.TYPE_LAST_COLOR_INT
        ) {
            return value.data
        }
        return DEFAULT_TEXT_COLOR
    }

    private fun findNativeTitleColor(panelView: View): Int? {
        if (panelView !is ViewGroup) return null
        val queue = ArrayDeque<View>()
        queue.add(panelView)
        var visited = 0
        while (queue.isNotEmpty() && visited < 64) {
            visited++
            val node = queue.removeFirst()
            if (node is TextView && node.text?.toString() == NATIVE_TITLE_TEXT) {
                return node.currentTextColor
            }
            if (node is ViewGroup) {
                for (index in 0 until node.childCount) queue.add(node.getChildAt(index))
            }
        }
        return null
    }

    private fun dp(value: Float): Int = (value * context.resources.displayMetrics.density).toInt()

    private fun dp(value: Int): Int = dp(value.toFloat())

    @Suppress("DiscouragedApi")
    private fun resourceId(name: String, type: String): Int =
        context.resources.getIdentifier(name, type, ColorOsSidebarTarget.PACKAGE_NAME)

    private companion object {
        const val TAG = "FlymeFreeform"

        /** 原厂布局 layout_all_app 中承载搜索栏与列表的垂直容器。 */
        const val CONTAINER_ID = "all_app_search_motion_group"

        /** 原厂应用/工具列表；区块插在它之前。 */
        const val APP_LIST_ID = "app_list"

        /** 与原厂面板标题同源的主题色属性（取不到原厂标题颜色时的回退）。 */
        const val COLOR_ATTR = "couiColorPrimaryTextOnPopup"

        const val HEADER_TEXT = "最近小窗"

        /** 原厂面板标题文本，用于定位其 TextView 颜色。 */
        const val NATIVE_TITLE_TEXT = "全部"

        /**
         * @author bomo 原厂面板里的区块标题文本，用于定位标题样式并保持一致。
         * 顺序即广度优先命中的优先级（先「工具」后「应用」）。
         */
        val NATIVE_SECTION_TITLES = setOf("工具", "应用")

        /** 视图树扫描上限，防止原厂结构变化时遍历无界。 */
        const val MAX_SCAN_VIEWS = 256

        const val HEADER_TEXT_SP = 13f
        const val LABEL_TEXT_SP = 11f
        const val ICON_SIZE_DP = 46
        const val ITEM_WIDTH_DP = 62

        /** 主题属性解析失败时的兜底文字色。 */
        const val DEFAULT_TEXT_COLOR = 0xFF000000.toInt()
    }
}
