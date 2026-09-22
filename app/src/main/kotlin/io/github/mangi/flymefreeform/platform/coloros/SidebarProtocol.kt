package io.github.mangi.flymefreeform.platform.coloros

import android.os.Bundle
import android.os.Message
import android.os.Messenger
import java.util.UUID

/** 跨进程只传框架类型，不把系统视图、配置快照或应用列表放入协议。 */
internal object SidebarProtocol {
    const val VERSION = 1
    const val DESCRIPTOR = "android.os.IMessenger"
    const val PREPARE = 1
    const val OPEN = 2
    const val CONFIRM = 3
    const val CANCEL = 4
    const val READY = 10
    const val SHOWN = 11
    const val COMMITTED = 12
    const val CLEANED = 13
    const val ABORTED = 14
    const val EXIT_STARTED = 15
    const val HIDE_BACKDROP = 16
    const val BACKDROP_HIDDEN = 17

    /**
     * 免会话执行侧边栏工具（小布识屏 / 屏幕翻译等）。
     * 工具是侧边栏进程内的 `AbsTool` 处理器，不是可启动的 Activity，
     * 因此只能在侧边栏进程里调用其 `handle()`，扇形的工具条目经此消息转交。
     */
    const val RUN_TOOL = 18
    const val TOOL_ALIAS = "tool_alias"
    const val TOOL_CLICK_X = "tool_click_x"
    const val TOOL_CLICK_Y = "tool_click_y"

    /**
     * 工具目录载荷：侧边栏进程 → system_server。
     * 侧边栏直接广播给模块 App 会被 ColorOS 的后台启动管控拦下
     * （实测 `OplusAppStartupManager: prevent start ... by broadcast com.coloros.smartsidebar`），
     * 因此目录经面板会话的 reply Messenger 交给 system_server，再由 system_server 转发给 App。
     */
    const val TOOL_CATALOG_PAYLOAD = 19
    const val TOOL_CATALOG_TEXT = "tool_catalog_text"

    /**
     * 「最近小窗」区块的点击转小窗启动：侧边栏进程无法自建小窗，
     * 点击经会话 reply 交给 system_server 走既有启动链路。
     */
    const val LAUNCH_COMPONENT = 20
    const val LAUNCH_COMPONENT_EXTRA = "launch_component"

    /**
     * 面板条目点击记录：用户在「全部」面板里点开的应用同样计入「最近小窗」。
     * 侧边栏只上报组件名，是否记录（去重/截断/落盘）由 system_server 决定。
     */
    const val RECORD_RECENT = 21
    const val RECORD_RECENT_EXTRA = "record_recent"

    /**
     * 开机目录请求：system_server 主动向侧边栏要目录（回包走 TOOL_CATALOG_PAYLOAD）。
     * 有了它，Settings 副本不再依赖"用户先开一次面板"。
     */
    const val REQUEST_TOOL_CATALOG = 22
    const val REQUEST_ID = "request_id"
    const val DEADLINE = "deadline_uptime"
    const val TARGET_UID = "target_uid"

    /**
     * @author bomo 面板会话携带的「呼出方位」。
     * 原厂 `MainPanelMainView.getMIsLeft()` 只在原厂自己的面板流程里被写入，
     * 本模块自建面板从不经过它，读到的恒为默认值（于是左右都贴左）。
     * 因此改由 system_server 按手势真实方位随消息下发，覆盖该标志位。
     */
    const val PANEL_LEFT_SIDE = "panel_left_side"
    // 原生常驻端允许绑定等待 5 秒，额外留出框架加载与桥接握手时间。
    const val PREPARE_TIMEOUT_MS = 7_000L
    const val OPEN_TIMEOUT_MS = 3_000L
    const val CLEANUP_TIMEOUT_MS = 1_500L
    const val ACTION_USER_SWITCHED = "android.intent.action.USER_SWITCHED"

    fun isTrustedPeer(sendingUid: Int, expectedUid: Int, version: Int): Boolean =
        expectedUid >= 0 && sendingUid == expectedUid && version == VERSION

    fun isValidRequestId(id: String): Boolean =
        id.length == 36 &&
            try {
                UUID.fromString(id).toString() == id
            } catch (_: IllegalArgumentException) {
                false
            }

    fun isValidDeadline(deadline: Long, now: Long, maximumLifetime: Long): Boolean =
        now >= 0 && deadline > now && deadline - now <= maximumLifetime

    fun message(
        what: Int,
        id: String,
        deadline: Long,
        targetUid: Int,
        replyTo: Messenger? = null,
        /**
         * @author bomo 面板呼出方位（仅面板会话相关消息携带；null 表示本条消息无关，
         * 不写入 Bundle，接收方回退到原厂标志位）。
         */
        panelLeftSide: Boolean? = null,
    ): Message =
        Message.obtain().apply {
            this.what = what
            arg1 = VERSION
            this.replyTo = replyTo
            data =
                Bundle().apply {
                    putString(REQUEST_ID, id)
                    putLong(DEADLINE, deadline)
                    putInt(TARGET_UID, targetUid)
                    panelLeftSide?.let { putBoolean(PANEL_LEFT_SIDE, it) }
                }
        }

    /**
     * 工具执行消息：不带会话 id 与截止时间，属于免会话的一次性请求。
     *
     * @param replyTo 回执通道：侧边栏处理完顺带回传工具目录
     * （TOOL_CATALOG_PAYLOAD）；目录经 system_server 落 Settings，扇形才能拿到图标
     */
    fun toolMessage(
        alias: String,
        targetUid: Int,
        clickX: Float = 0f,
        clickY: Float = 0f,
        replyTo: Messenger? = null,
    ): Message =
        Message.obtain().apply {
            what = RUN_TOOL
            arg1 = VERSION
            this.replyTo = replyTo
            data =
                Bundle().apply {
                    putString(TOOL_ALIAS, alias)
                    putFloat(TOOL_CLICK_X, clickX)
                    putFloat(TOOL_CLICK_Y, clickY)
                    putInt(TARGET_UID, targetUid)
                }
        }

    /** 面板点击记录消息：侧边栏 → system_server，免会话方向。 */
    fun recordRecentMessage(component: String, targetUid: Int): Message =
        Message.obtain().apply {
            what = RECORD_RECENT
            arg1 = VERSION
            data =
                Bundle().apply {
                    putString(RECORD_RECENT_EXTRA, component)
                    putInt(TARGET_UID, targetUid)
                }
        }

    /** 小窗启动请求消息：侧边栏 → system_server，免会话方向。 */
    fun launchComponentMessage(component: String, targetUid: Int): Message =
        Message.obtain().apply {
            what = LAUNCH_COMPONENT
            arg1 = VERSION
            data =
                Bundle().apply {
                    putString(LAUNCH_COMPONENT_EXTRA, component)
                    putInt(TARGET_UID, targetUid)
                }
        }

    /** 工具目录载荷消息：免会话方向（侧边栏 → system_server），只需目标 UID。 */
    fun catalogMessage(text: String, targetUid: Int): Message =
        Message.obtain().apply {
            what = TOOL_CATALOG_PAYLOAD
            arg1 = VERSION
            data =
                Bundle().apply {
                    putString(TOOL_CATALOG_TEXT, text)
                    putInt(TARGET_UID, targetUid)
                }
        }

    /** 工具别名只允许来自侧边栏自身的别名表，限制长度与字符集以防伪造消息注入。 */
    fun isValidToolAlias(alias: String?): Boolean =
        alias != null && alias.isNotEmpty() && alias.length <= 64 &&
            alias.all { character -> character.isLetterOrDigit() || character == '_' || character == '-' || character == '.' }
}
