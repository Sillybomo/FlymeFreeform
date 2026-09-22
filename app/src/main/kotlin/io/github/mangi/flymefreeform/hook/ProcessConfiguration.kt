package io.github.mangi.flymefreeform.hook

import android.content.ComponentName
import android.content.SharedPreferences
import android.util.Log
import io.github.mangi.flymefreeform.config.ModulePreferences
import io.github.mangi.flymefreeform.config.ModuleSettingsSnapshot
import io.github.mangi.flymefreeform.config.PinnedComponentCodec
import java.util.concurrent.CopyOnWriteArrayList
/** Hook 进程内只读配置快照；任何类型损坏都会让该进程整体失败关闭。 */
internal class ProcessConfiguration(
    private val log: (priority: Int, code: String, throwable: Throwable?) -> Unit,
) {
    @Volatile
    var snapshot: ModuleSettingsSnapshot = FAIL_CLOSED
        private set

    @Volatile
    var isAvailable: Boolean = false
        private set

    private val listeners = CopyOnWriteArrayList<(ModuleSettingsSnapshot) -> Unit>()
    private var preferences: SharedPreferences? = null
    private val preferenceListener =
        SharedPreferences.OnSharedPreferenceChangeListener { changed, _ -> refresh(changed, false) }

    fun start(remotePreferences: SharedPreferences) {
        preferences?.unregisterOnSharedPreferenceChangeListener(preferenceListener)
        preferences = remotePreferences
        remotePreferences.registerOnSharedPreferenceChangeListener(preferenceListener)
        refresh(remotePreferences, true)
    }

    fun observe(listener: (ModuleSettingsSnapshot) -> Unit) {
        listeners += listener
        listener(snapshot)
    }

    /**
     * @author bomo 主动重读远端配置（不依赖跨进程变更推送）。
     *
     * LSPosed 远端偏好的 `OnSharedPreferenceChangeListener` **不会跨进程回调**
     * （2026-09-22 实测：App 进程写入后，Hook 进程侧无任何 `MODULE_STATE_*` 记录，
     * 快照永远停留在 start() 时的值），因此需要「不重启即生效」的设置
     * （如面板缩放）必须在消费点之前由本方法主动拉取。
     * 读取本身走 provider，是实时的；刷新后照常通知 [listeners]。
     */
    fun refreshNow() {
        val store = preferences ?: return
        refresh(store, false)
    }

    fun removeObserver(listener: (ModuleSettingsSnapshot) -> Unit) {
        listeners -= listener
    }

    /**
     * 读取最近小窗应用；未写入或内容损坏时返回空列表。
     * 写入方是模块 App（Hook 进程的远端配置只读），见 `SharedStateProtocol`。
     */
    fun readRecentFreeform(): List<ComponentName> {
        val store = preferences ?: return emptyList()
        val raw =
            runCatching {
                store.getString(ModulePreferences.KEY_RECENT_FREEFORM, "") ?: ""
            }.getOrDefault("")
        return PinnedComponentCodec
            .decodeRaw(raw, ModulePreferences.MAX_RECENT_FREEFORM)
            .mapNotNull(ComponentName::unflattenFromString)
    }

    /** 读取侧边栏工具目录（由模块 App 落盘）；未写入时返回 null。 */
    fun readToolCatalog(): String? {
        val store = preferences ?: return null
        return runCatching {
            store.getString(ModulePreferences.KEY_TOOL_CATALOG, null)
        }.getOrNull()
    }

    private fun refresh(preferences: SharedPreferences, initial: Boolean) {
        val next =
            try {
                ModuleSettingsSnapshot.readFrom(preferences).also { isAvailable = true }
            } catch (exception: ClassCastException) {
                isAvailable = false
                log(Log.ERROR, "MODULE_CONFIG_TYPE_MISMATCH", exception)
                FAIL_CLOSED
            } catch (exception: RuntimeException) {
                isAvailable = false
                log(Log.ERROR, "MODULE_CONFIG_READ_FAILED", exception)
                FAIL_CLOSED
            }
        val changed = next != snapshot
        snapshot = next
        if (initial || changed) {
            log(
                Log.INFO,
                if (next.enabled && isAvailable) "MODULE_STATE_ENABLED" else "MODULE_STATE_DISABLED",
                null,
            )
            listeners.forEach { listener -> listener(next) }
        }
    }

    private companion object {
        val FAIL_CLOSED =
            ModuleSettingsSnapshot(
                enabled = false,
                leftCornerEnabled = false,
                rightCornerEnabled = false,
            )
    }
}
