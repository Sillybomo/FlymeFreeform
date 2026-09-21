package io.github.mangi.flymefreeform.config

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
     * 扇形最多固定几个应用（不含「更多」）。
     * 9 个 + 「更多」= 10 个槽位，此时槽位角 8.4°、弦长 34.6dp，
     * 图标按弦长收紧到约 33dp —— 已是可点选的下限，再加会明显互叠。
     */
    const val MAX_PINNED_APPS = 9

    /** 「最近小窗」区块最多展示几个最近应用；超出按时间截断（最近的在最左）。 */
    const val MAX_RECENT_FREEFORM = 8

    fun coerceCornerTriggerRangeDp(value: Int): Int =
        value.coerceIn(MIN_CORNER_TRIGGER_RANGE_DP, MAX_CORNER_TRIGGER_RANGE_DP)
}
