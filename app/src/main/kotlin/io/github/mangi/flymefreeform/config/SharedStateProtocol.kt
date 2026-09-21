package io.github.mangi.flymefreeform.config

/**
 * Hook 进程 → 模块 App 的单向状态通道。
 *
 * 背景（实测）：libxposed 的 `RemotePreferences` 在 Hook 进程里是**只读**的
 * （`edit()` 抛 `UnsupportedOperationException: Read only implementation`），
 * 只有模块 App 自身能写。因此凡是 Hook 侧产生的共享状态（最近小窗、侧边栏工具目录），
 * 一律通过显式广播交给 App 落盘，Hook 侧再按只读方式读取。
 *
 * @author bomo
 */
internal object SharedStateProtocol {
    const val ACTION = "io.github.mangi.flymefreeform.action.SHARED_STATE"
    const val EXTRA_KIND = "kind"
    const val EXTRA_COMPONENT = "component"
    const val EXTRA_CATALOG = "catalog"

    /** 最近小窗应用：每次成功打开一个小窗发一条。 */
    const val KIND_RECENT_FREEFORM = 1

    /** 侧边栏工具目录：侧边栏进程启动与面板打开时发布。 */
    const val KIND_TOOL_CATALOG = 2

    /** 侧边栏包名；用于校验目录广播的来源。 */
    const val SIDEBAR_PACKAGE = "com.coloros.smartsidebar"

    /** 模块自身包名：广播必须显式指定，避免 Hook 进程（system_server）的 context 包名不是模块包。 */
    const val MODULE_PACKAGE = "io.github.mangi.flymefreeform"
}
