package io.github.mangi.flymefreeform.config

import android.content.ComponentName
import android.content.Context
import android.provider.Settings

/**
 * 跨进程共享状态的最终通道：**Settings.Global**。
 *
 * 演进史（均为实测，别再走回头路）：
 * 1. libxposed 远端配置在 Hook 进程只读，App 写入 Hook 读 —— 固定应用可用 ✓；
 * 2. Hook 侧产生的状态（最近小窗 / 工具目录）反向写不进远端配置；
 * 3. 改广播给 App 落盘：被 ColorOS `OplusAppStartupManager` 拦截（侧边栏进程发起）
 *    或 `BootPressureHolder` 延迟启动吞掉（App 冷启动时），且 App 在后台时收广播也不稳定；
 * 4. 最终：system_server（唯一既有写权限又有数据的一方）直接写 Settings.Global，
 *    侧边栏进程 / 模块 App 实时读取（Settings.Provider 每次读取都是最新值，无缓存问题）。
 *
 * @author bomo
 */
internal object SharedSettings {
    const val KEY_TOOL_CATALOG = "flymefreeform_tool_catalog_v1"
    const val KEY_RECENT_FREEFORM = "flymefreeform_recent_freeform_v1"

    /** 读取侧边栏工具目录文本；未写入时返回 null。 */
    fun readToolCatalog(context: Context): String? =
        runCatching {
            Settings.Global.getString(context.contentResolver, KEY_TOOL_CATALOG)
        }.getOrNull()

    /** 写入侧边栏工具目录（仅 system_server 调用）。 */
    fun writeToolCatalog(context: Context, value: String) {
        runCatching {
            Settings.Global.putString(context.contentResolver, KEY_TOOL_CATALOG, value)
        }
    }

    /** 读取最近小窗应用（最近的在前）；未写入或损坏时返回空列表。 */
    fun readRecentFreeform(context: Context): List<ComponentName> {
        val raw =
            runCatching {
                Settings.Global.getString(context.contentResolver, KEY_RECENT_FREEFORM)
            }.getOrNull() ?: return emptyList()
        return PinnedComponentCodec
            .decodeRaw(raw, ModulePreferences.MAX_RECENT_FREEFORM)
            .mapNotNull(ComponentName::unflattenFromString)
    }

    /** 写入最近小窗应用（仅 system_server 调用）。 */
    fun writeRecentFreeform(context: Context, components: List<ComponentName>) {
        val value = components.joinToString("\n", transform = ComponentName::flattenToString)
        runCatching {
            Settings.Global.putString(context.contentResolver, KEY_RECENT_FREEFORM, value)
        }
    }
}
