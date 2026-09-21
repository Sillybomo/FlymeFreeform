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

    fun removeObserver(listener: (ModuleSettingsSnapshot) -> Unit) {
        listeners -= listener
    }

    /**
     * 记录一次「小窗打开」，最近的在最前，去重后截断到 MAX_RECENT_FREEFORM。
     * 供「全部」面板的「最近小窗」区块读取（跨进程，经框架远端配置）。
     */
    fun recordRecentFreeform(component: ComponentName) {
        val store = preferences ?: return
        val next =
            (listOf(component) + readRecentFreeform().filterNot { it == component })
                .take(ModulePreferences.MAX_RECENT_FREEFORM)
        runCatching {
            store
                .edit()
                .putString(
                    ModulePreferences.KEY_RECENT_FREEFORM,
                    next.joinToString("\n", transform = ComponentName::flattenToString),
                )
                .apply()
        }.onFailure { exception ->
            log(Log.WARN, "RECENT_FREEFORM_WRITE_FAILED", exception)
        }
    }

    /** 读取最近小窗应用；未写入或内容损坏时返回空列表。 */
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
