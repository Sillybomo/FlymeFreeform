package io.github.mangi.flymefreeform.framework

import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import io.github.mangi.flymefreeform.FlymeFreeformApplication
import io.github.mangi.flymefreeform.config.SharedStateProtocol

/**
 * 接收 Hook 进程广播的共享状态并落盘（**只有模块 App 能写框架远端配置**）。
 *
 * 目前承载两类状态：
 * - 最近小窗应用：system_server 在每次成功打开小窗后广播组件名；
 * - 侧边栏工具目录：侧边栏进程枚举 `ToolEntryHelper` 后广播编码文本。
 *
 * 清单中导出注册（Hook 侧与侧边栏都是其它 UID），因此两类载荷都做校验：
 * 组件必须能被解析、目录必须能通过严格编解码。
 *
 * @author bomo
 */
internal class SharedStateReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != SharedStateProtocol.ACTION) return
        val application = context.applicationContext as? FlymeFreeformApplication ?: return
        val repository = application.frameworkConnectionRepository
        when (intent.getIntExtra(SharedStateProtocol.EXTRA_KIND, 0)) {
            SharedStateProtocol.KIND_RECENT_FREEFORM -> {
                val component =
                    intent.getStringExtra(SharedStateProtocol.EXTRA_COMPONENT)
                        ?.let(ComponentName::unflattenFromString)
                        ?: return
                repository.recordRecentFreeform(component)
            }
            SharedStateProtocol.KIND_TOOL_CATALOG -> {
                // 目录内容只允许「别名/名称/可用性/图标」四段，由 ToolCatalogCodec 严格解析；
                // 伪造者最多让列表多出一条不可用的工具项，落盘后仍由侧边栏侧校验可用性。
                val catalog = intent.getStringExtra(SharedStateProtocol.EXTRA_CATALOG) ?: return
                repository.writeToolCatalog(catalog)
            }
        }
    }

}
