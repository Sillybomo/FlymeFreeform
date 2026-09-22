package io.github.mangi.flymefreeform.framework

import android.content.ComponentName
import android.content.SharedPreferences
import android.util.Log
import io.github.libxposed.service.XposedService
import io.github.libxposed.service.XposedServiceHelper
import io.github.mangi.flymefreeform.config.ModulePreferences
import io.github.mangi.flymefreeform.config.ModuleSettingsSnapshot
import io.github.mangi.flymefreeform.config.OutsideTapCloseMode
import io.github.mangi.flymefreeform.config.PinnedComponentCodec
import java.util.IdentityHashMap
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Service 102 的进程级所有者；所有同步 Binder 调用都串行限制在单一后台线程。 */
internal class FrameworkConnectionRepository {
    private val started = AtomicBoolean(false)
    private val worker = Executors.newSingleThreadExecutor { task -> Thread(task, WORKER_THREAD_NAME) }
    private val liveServices = IdentityHashMap<XposedService, Unit>()
    private val diedBeforeBind = IdentityHashMap<XposedService, Unit>()
    private val pendingServices = IdentityHashMap<XposedService, Unit>()
    private val unavailableServices = IdentityHashMap<XposedService, FrameworkConnectionState>()
    private var activeConnection: ActiveConnection? = null

    /** 连接建立前排队等待落盘的共享状态（单线程 worker 内访问）。 */
    private val pendingRecent = ArrayDeque<ComponentName>()
    private var pendingCatalog: String? = null

    private val mutableState = MutableStateFlow(FrameworkConnectionState())
    val state: StateFlow<FrameworkConnectionState> = mutableState.asStateFlow()

    private val serviceListener =
        object : XposedServiceHelper.OnServiceListener {
            override fun onServiceBind(service: XposedService) {
                worker.execute { handleServiceBind(service) }
            }

            override fun onServiceDied(service: XposedService) {
                worker.execute { handleServiceDied(service) }
            }
        }

    fun start() {
        check(started.compareAndSet(false, true)) { "Xposed Service listener is already registered" }
        XposedServiceHelper.registerListener(serviceListener)
    }

    fun setModuleEnabled(enabled: Boolean) =
        updateSettings { it.copy(enabled = enabled) }

    fun setLeftCornerEnabled(enabled: Boolean) =
        updateSettings { it.copy(leftCornerEnabled = enabled) }

    fun setRightCornerEnabled(enabled: Boolean) =
        updateSettings { it.copy(rightCornerEnabled = enabled) }

    fun setCornerTriggerRangeDp(rangeDp: Int) =
        updateSettings {
            it.copy(
                cornerTriggerRangeDp =
                    ModulePreferences.coerceCornerTriggerRangeDp(rangeDp),
            )
        }

    /**
     * @author bomo 设置「全部」面板的整体缩放百分比。
     * 只写配置；面板在**下次打开**时读取，因此不需要重启或重装。
     */
    fun setPanelScalePercent(percent: Int) =
        updateSettings {
            it.copy(
                panelScalePercent = ModulePreferences.coercePanelScalePercent(percent),
            )
        }

    fun setOutsideTapCloseMode(mode: OutsideTapCloseMode) =
        updateSettings { it.copy(outsideTapCloseMode = mode) }

    fun setHandleSwipeUpToMiniEnabled(enabled: Boolean) =
        updateSettings { it.copy(handleSwipeUpToMiniEnabled = enabled) }

    fun setPauseInLandscape(enabled: Boolean) =
        updateSettings { it.copy(pauseInLandscape = enabled) }

    fun setPauseInGameMode(enabled: Boolean) =
        updateSettings { it.copy(pauseInGameMode = enabled) }

    /** 写入内圈固定应用（独立的第二圈，上限 MAX_INNER_APPS）。 */
    fun setInnerPinnedComponents(components: List<ComponentName>) =
        updateSettings { settings ->
            settings.copy(
                innerPinsSaved = true,
                innerPinnedComponents =
                    components.distinct().take(ModulePreferences.MAX_INNER_APPS),
            )
        }

    fun setPinnedComponents(components: List<ComponentName>) =        updateSettings {
            it.copy(
                pinsSaved = true,
                pinnedComponents =
                    components.distinct().take(ModulePreferences.MAX_PINNED_APPS),
            )
        }

    fun requestMissingScopes() {
        worker.execute {
            val connection = activeConnection ?: return@execute
            val current = mutableState.value
            if (!current.canRequestScope) return@execute
            val requested = current.missingScopes.toList()
            val requesting = current.copy(isRequestingScope = true, issue = null)
            activeConnection = connection.copy(confirmedState = requesting)
            mutableState.value = requesting
            try {
                connection.service.requestScope(
                    requested,
                    object : XposedService.OnScopeEventListener {
                        override fun onScopeRequestApproved(approved: List<String>) {
                            worker.execute { refreshAfterScopeRequest(connection) }
                        }

                        override fun onScopeRequestFailed(message: String) {
                            worker.execute { finishScopeRequestWithError(connection) }
                        }
                    },
                )
            } catch (exception: XposedService.ServiceException) {
                finishScopeRequestWithError(connection)
            } catch (exception: UnsupportedOperationException) {
                finishScopeRequestWithError(connection)
            } catch (exception: SecurityException) {
                finishScopeRequestWithError(connection)
            } catch (exception: RuntimeException) {
                finishScopeRequestWithError(connection)
            }
        }
    }

    /** 读取侧边栏工具目录（由侧边栏进程广播、App 落盘）；未连接或未发布时返回 null。 */
    fun readToolCatalog(): String? =
        activeConnection?.preferences?.let { preferences ->
            runCatching { preferences.getString(ModulePreferences.KEY_TOOL_CATALOG, null) }.getOrNull()
        }

    /**
     * 记录一次「小窗打开」：最近的在最前，去重后截断。
     *
     * 由 Hook 侧广播驱动（Hook 进程的远端配置只读，只有 App 能写），
     * 供「全部」面板的「最近小窗」区块读取。
     * 广播会唤醒刚启动的 App，此时框架连接尚未建立（[activeConnection] 为 null），
     * 因此先入队、连上后由 [flushPendingSharedState] 补写。
     */
    fun recordRecentFreeform(component: ComponentName) {
        worker.execute {
            val preferences = activeConnection?.preferences
            if (preferences == null) {
                pendingRecent.remove(component)
                pendingRecent.addFirst(component)
                while (pendingRecent.size > ModulePreferences.MAX_RECENT_FREEFORM) pendingRecent.removeLast()
                Log.i(TAG, "SHARED_STATE_RECENT_DEFERRED ${component.flattenToString()}")
                return@execute
            }
            writeRecent(component, preferences)
        }
    }

    /** 写入侧边栏工具目录；未连接时排队，内容未变化时不重复落盘。 */
    fun writeToolCatalog(catalog: String) {
        if (catalog.isEmpty()) return
        worker.execute {
            val preferences = activeConnection?.preferences
            if (preferences == null) {
                pendingCatalog = catalog
                Log.i(TAG, "SHARED_STATE_CATALOG_DEFERRED")
                return@execute
            }
            writeCatalog(catalog, preferences)
        }
    }

    /** 框架连接建立后补写等待中的共享状态；由 [activateService] 触发。 */
    private fun flushPendingSharedState() {
        worker.execute {
            val preferences = activeConnection?.preferences ?: return@execute
            val catalog = pendingCatalog
            if (catalog != null) {
                pendingCatalog = null
                writeCatalog(catalog, preferences)
            }
            val deferred = pendingRecent.toList()
            pendingRecent.clear()
            // 队列头部是最新的，倒序补写才能保持"最近的在最前"。
            deferred.reversed().forEach { component -> writeRecent(component, preferences) }
            if (catalog != null || deferred.isNotEmpty()) {
                Log.i(TAG, "SHARED_STATE_FLUSHED recent=${deferred.size} catalog=${catalog != null}")
            }
        }
    }

    private fun writeRecent(component: ComponentName, preferences: SharedPreferences) {
        val current =
            PinnedComponentCodec
                .decodeRaw(
                    preferences.getString(ModulePreferences.KEY_RECENT_FREEFORM, "") ?: "",
                    ModulePreferences.MAX_RECENT_FREEFORM,
                )
                .mapNotNull(ComponentName::unflattenFromString)
        val next =
            (listOf(component) + current.filterNot { it == component })
                .take(ModulePreferences.MAX_RECENT_FREEFORM)
        runCatching {
            preferences
                .edit()
                .putString(
                    ModulePreferences.KEY_RECENT_FREEFORM,
                    next.joinToString("\n", transform = ComponentName::flattenToString),
                )
                .apply()
        }.onFailure { exception ->
            Log.w(TAG, "SHARED_STATE_RECENT_WRITE_FAILED", exception)
        }
    }

    private fun writeCatalog(catalog: String, preferences: SharedPreferences) {
        if (preferences.getString(ModulePreferences.KEY_TOOL_CATALOG, null) == catalog) return
        runCatching {
            preferences.edit().putString(ModulePreferences.KEY_TOOL_CATALOG, catalog).apply()
        }.onSuccess {
            Log.i(TAG, "SHARED_STATE_CATALOG_WRITTEN")
        }.onFailure { exception ->
            Log.w(TAG, "SHARED_STATE_CATALOG_WRITE_FAILED", exception)
        }
    }

    private fun updateSettings(transform: (ModuleSettingsSnapshot) -> ModuleSettingsSnapshot) {        worker.execute {
            val connection = activeConnection ?: return@execute
            val previous = mutableState.value
            if (!previous.canChangeSettings) return@execute
            val next = transform(previous.settings)
            if (next == previous.settings) return@execute
            mutableState.value = previous.copy(isUpdating = true, issue = null)
            val committed = commitSettings(connection.preferences, next)
            if (activeConnection?.service !== connection.service) return@execute
            if (committed) {
                val confirmed = previous.copy(settings = next, isUpdating = false, issue = null)
                activeConnection = connection.copy(confirmedState = confirmed)
                mutableState.value = confirmed
            } else {
                isolateFailedConnection(connection, previous)
            }
        }
    }

    private fun handleServiceBind(service: XposedService) {
        if (diedBeforeBind.remove(service) != null) return
        if (liveServices.put(service, Unit) != null) return
        pendingServices[service] = Unit
        reconcileServices()
    }

    private fun handleServiceDied(service: XposedService) {
        if (liveServices.remove(service) == null) {
            diedBeforeBind[service] = Unit
            return
        }
        pendingServices.remove(service)
        unavailableServices.remove(service)
        if (activeConnection?.service === service) activeConnection = null
        reconcileServices()
    }

    private fun reconcileServices() {
        if (liveServices.size > 1) {
            mutableState.value = multipleServicesState()
            return
        }
        val service = liveServices.keys.firstOrNull()
        if (service == null) {
            activeConnection = null
            mutableState.value = FrameworkConnectionState()
            return
        }
        activeConnection?.takeIf { it.service === service }?.let { connection ->
            mutableState.value = connection.confirmedState
            return
        }
        unavailableServices[service]?.let { state ->
            mutableState.value = state
            return
        }
        if (pendingServices.remove(service) != null) {
            activateService(service)
            return
        }
        mutableState.value =
            FrameworkConnectionState(
                status = FrameworkConnectionStatus.Error,
                issue = FrameworkConnectionIssue.ConnectionFailed,
            )
    }

    private fun activateService(service: XposedService) {
        val result = probeService(service)
        if (result.connection != null) {
            unavailableServices.remove(service)
            activeConnection = result.connection
            flushPendingSharedState()
        } else {
            activeConnection = null
            unavailableServices[service] = result.state
        }
        mutableState.value = result.state
    }

    private fun probeService(service: XposedService): ProbeResult {
        var frameworkName: String? = null
        var frameworkVersion: String? = null
        var apiVersion: Int? = null
        return try {
            apiVersion = service.apiVersion
            frameworkName = service.frameworkName.normalizedMetadata()
            frameworkVersion = service.frameworkVersion.normalizedMetadata()
            val properties = service.frameworkProperties
            when {
                apiVersion < XposedService.API_102 ->
                    ProbeResult.failure(frameworkName, frameworkVersion, apiVersion, FrameworkConnectionIssue.ServiceApiTooOld)
                properties and XposedService.PROP_CAP_REMOTE == 0L ->
                    ProbeResult.failure(frameworkName, frameworkVersion, apiVersion, FrameworkConnectionIssue.RemoteCapabilityMissing)
                properties and XposedService.PROP_CAP_SYSTEM == 0L ->
                    ProbeResult.failure(frameworkName, frameworkVersion, apiVersion, FrameworkConnectionIssue.SystemCapabilityMissing)
                else -> {
                    val preferences = service.getRemotePreferences(ModulePreferences.GROUP)
                    val settings = readOrRepairSettings(preferences) ?: return ProbeResult.error(
                        frameworkName,
                        frameworkVersion,
                        apiVersion,
                        FrameworkConnectionIssue.WriteFailed,
                    )
                    val scopes = service.scope.filterTo(linkedSetOf()) { it in FrameworkConnectionState.REQUIRED_SCOPES }
                    ProbeResult.connected(service, preferences, frameworkName, frameworkVersion, apiVersion, settings, scopes)
                }
            }
        } catch (exception: XposedService.ServiceException) {
            ProbeResult.error(frameworkName, frameworkVersion, apiVersion)
        } catch (exception: UnsupportedOperationException) {
            ProbeResult.failure(frameworkName, frameworkVersion, apiVersion, FrameworkConnectionIssue.RemoteCapabilityMissing)
        } catch (exception: SecurityException) {
            ProbeResult.error(frameworkName, frameworkVersion, apiVersion)
        } catch (exception: RuntimeException) {
            ProbeResult.error(frameworkName, frameworkVersion, apiVersion)
        }
    }

    private fun readOrRepairSettings(preferences: SharedPreferences): ModuleSettingsSnapshot? =
        try {
            ModuleSettingsSnapshot.readFrom(preferences)
        } catch (exception: ClassCastException) {
            ModuleSettingsSnapshot().takeIf { defaults -> commitSettings(preferences, defaults) }
        }

    private fun commitSettings(preferences: SharedPreferences, settings: ModuleSettingsSnapshot): Boolean =
        try {
            settings.writeTo(preferences.edit()).commit()
        } catch (exception: XposedService.ServiceException) {
            false
        } catch (exception: UnsupportedOperationException) {
            false
        } catch (exception: SecurityException) {
            false
        } catch (exception: RuntimeException) {
            false
        }

    private fun isolateFailedConnection(connection: ActiveConnection, previous: FrameworkConnectionState) {
        activeConnection = null
        val failed =
            previous.copy(
                status = FrameworkConnectionStatus.Error,
                isUpdating = false,
                isRequestingScope = false,
                issue = FrameworkConnectionIssue.WriteFailed,
            )
        unavailableServices[connection.service] = failed
        mutableState.value = failed
    }

    private fun refreshAfterScopeRequest(connection: ActiveConnection) {
        if (activeConnection?.service !== connection.service) return
        pendingServices[connection.service] = Unit
        activeConnection = null
        reconcileServices()
    }

    private fun finishScopeRequestWithError(connection: ActiveConnection) {
        if (activeConnection?.service !== connection.service) return
        val failed =
            connection.confirmedState.copy(
                isRequestingScope = false,
                issue = FrameworkConnectionIssue.ScopeRequestFailed,
            )
        activeConnection = connection.copy(confirmedState = failed)
        mutableState.value = if (liveServices.size > 1) multipleServicesState() else failed
    }

    private fun multipleServicesState() =
        FrameworkConnectionState(
            status = FrameworkConnectionStatus.Incompatible,
            issue = FrameworkConnectionIssue.MultipleServices,
        )

    private fun String.normalizedMetadata(): String? = trim().take(MAX_METADATA_LENGTH).ifEmpty { null }

    private data class ActiveConnection(
        val service: XposedService,
        val preferences: SharedPreferences,
        val confirmedState: FrameworkConnectionState,
    )

    private data class ProbeResult(val connection: ActiveConnection?, val state: FrameworkConnectionState) {
        companion object {
            fun connected(
                service: XposedService,
                preferences: SharedPreferences,
                frameworkName: String?,
                frameworkVersion: String?,
                apiVersion: Int,
                settings: ModuleSettingsSnapshot,
                scopes: Set<String>,
            ): ProbeResult {
                val state =
                    FrameworkConnectionState(
                        status = FrameworkConnectionStatus.Connected,
                        frameworkName = frameworkName,
                        frameworkVersion = frameworkVersion,
                        apiVersion = apiVersion,
                        settings = settings,
                        grantedScopes = scopes,
                    )
                return ProbeResult(ActiveConnection(service, preferences, state), state)
            }

            fun failure(name: String?, version: String?, api: Int?, issue: FrameworkConnectionIssue) =
                ProbeResult(
                    null,
                    FrameworkConnectionState(
                        status = FrameworkConnectionStatus.Incompatible,
                        frameworkName = name,
                        frameworkVersion = version,
                        apiVersion = api,
                        issue = issue,
                    ),
                )

            fun error(
                name: String?,
                version: String?,
                api: Int?,
                issue: FrameworkConnectionIssue = FrameworkConnectionIssue.ConnectionFailed,
            ) =
                ProbeResult(
                    null,
                    FrameworkConnectionState(
                        status = FrameworkConnectionStatus.Error,
                        frameworkName = name,
                        frameworkVersion = version,
                        apiVersion = api,
                        issue = issue,
                    ),
                )
        }
    }

    private companion object {
        const val WORKER_THREAD_NAME = "FlymeFreeform-Service"
        const val MAX_METADATA_LENGTH = 80

        /** 共享状态写入失败的日志标签。 */
        const val TAG = "FlymeFreeform"
    }
}
