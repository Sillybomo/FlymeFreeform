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

    /**
     * 工具目录载荷：侧边栏进程 → system_server。
     * 侧边栏直接广播给模块 App 会被 ColorOS 的后台启动管控拦下
     * （实测 `OplusAppStartupManager: prevent start ... by broadcast com.coloros.smartsidebar`），
     * 因此目录经面板会话的 reply Messenger 交给 system_server，再由 system_server 转发给 App。
     */
    const val TOOL_CATALOG_PAYLOAD = 19
    const val TOOL_CATALOG_TEXT = "tool_catalog_text"
    const val REQUEST_ID = "request_id"
    const val DEADLINE = "deadline_uptime"
    const val TARGET_UID = "target_uid"
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
                }
        }

    /** 工具执行消息：不带会话 id 与截止时间，属于免会话的一次性请求。 */
    fun toolMessage(alias: String, targetUid: Int): Message =
        Message.obtain().apply {
            what = RUN_TOOL
            arg1 = VERSION
            data =
                Bundle().apply {
                    putString(TOOL_ALIAS, alias)
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
