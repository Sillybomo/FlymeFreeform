package io.github.mangi.flymefreeform.config

import android.content.ComponentName

/** App 与 Hook 进程共享的框架配置协议；已发布的名称和类型不可随意复用。 */
internal object ModulePreferences {
    const val GROUP = "module_runtime"
    const val KEY_MODULE_ENABLED = "enabled"
    const val KEY_LEFT_CORNER_ENABLED = "corner_left_enabled"
    const val KEY_RIGHT_CORNER_ENABLED = "corner_right_enabled"
    const val KEY_CORNER_TRIGGER_RANGE_DP = "corner_trigger_range_dp"
    const val KEY_CORNER_PINS = "corner_pins_v1"
    const val KEY_OUTSIDE_TAP_CLOSE_MODE = "outside_tap_close_mode_v1"
    const val KEY_HANDLE_SWIPE_UP_TO_MINI_ENABLED = "handle_swipe_up_to_mini_enabled"
    const val KEY_PAUSE_IN_LANDSCAPE = "pause_in_landscape"
    const val KEY_PAUSE_IN_GAME_MODE = "pause_in_game_mode"

    /** 最近以小窗打开的应用（system_server 写入，侧边栏进程读取后渲染「最近小窗」区块）。 */
    const val KEY_RECENT_FREEFORM = "recent_freeform_v1"

    /** 内圈固定应用（v2 配置，与外圈 KEY_CORNER_PINS 相互独立）。 */
    const val KEY_CORNER_INNER_PINS = "corner_inner_pins_v1"

    /**
     * 侧边栏工具目录（侧边栏进程写入，App 与 system_server 读取）。
     * 工具（小布识屏 / 屏幕翻译 / 截屏等）不是独立应用，无法用 LauncherApps 枚举，
     * 只能由侧边栏进程从原厂 ToolEntryHelper 导出。
     */
    const val KEY_TOOL_CATALOG = "tool_catalog_v1"
    const val DEFAULT_ENABLED = false
    const val DEFAULT_CORNER_ENABLED = true
    const val DEFAULT_CORNER_TRIGGER_RANGE_DP = 84
    val DEFAULT_OUTSIDE_TAP_CLOSE_MODE = OutsideTapCloseMode.SingleTap
    const val DEFAULT_HANDLE_SWIPE_UP_TO_MINI_ENABLED = true
    const val DEFAULT_PAUSE_IN_LANDSCAPE = true
    const val DEFAULT_PAUSE_IN_GAME_MODE = true
    const val MIN_CORNER_TRIGGER_RANGE_DP = 24
    const val MAX_CORNER_TRIGGER_RANGE_DP = 160

    /**
     * 扇形最多固定几个应用（不含「更多」）：外圈最多 6 个 + 内圈最多 5 个 = 11。
     * 固定顺序即圈层顺序：前 6 个在外圈，第 7~11 个进内圈。
     */
    const val MAX_PINNED_APPS = 11

    /** 外圈应用上限。 */
    const val OUTER_PINNED_APPS = 6

    /** 内圈应用上限。 */
    const val MAX_INNER_APPS = 5

    /** 「最近小窗」区块最多展示几个最近应用；超出按时间截断（最近的在最左）。 */
    const val MAX_RECENT_FREEFORM = 8

    /**
     * 工具在固定列表里的伪包名：`ComponentName(TOOL_PACKAGE, alias)`。
     * 复用既有 ComponentName 存储与去重逻辑，不新增一套固定项格式。
     */
    const val TOOL_PACKAGE = "io.github.mangi.flymefreeform.tool"

    fun isToolComponent(component: ComponentName): Boolean =
        component.packageName == TOOL_PACKAGE && component.className.isNotEmpty()

    fun coerceCornerTriggerRangeDp(value: Int): Int =
        value.coerceIn(MIN_CORNER_TRIGGER_RANGE_DP, MAX_CORNER_TRIGGER_RANGE_DP)
}
