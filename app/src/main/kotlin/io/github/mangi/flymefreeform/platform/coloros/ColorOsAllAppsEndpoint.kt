package io.github.mangi.flymefreeform.platform.coloros

import android.app.KeyguardManager
import android.app.Service
import android.content.BroadcastReceiver
import android.content.ComponentCallbacks
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.res.Configuration
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.Message
import android.os.Messenger
import android.os.Process
import android.os.RemoteException
import android.os.SystemClock
import android.os.UserManager
import android.provider.Settings
import android.util.Log
import io.github.mangi.flymefreeform.config.ModuleSettingsSnapshot
import io.github.mangi.flymefreeform.config.SharedSettings
import io.github.mangi.flymefreeform.config.SharedStateProtocol
import io.github.mangi.flymefreeform.config.ToolCatalogCodec
import io.github.mangi.flymefreeform.hook.ProcessConfiguration
import io.github.mangi.flymefreeform.hook.ModuleEnvironmentState
import io.github.mangi.flymefreeform.window.AllAppsActionHandoff

/** 受系统服务权限及 UID 双重约束的打开协议；图标、条目与执行对象始终留在侧边栏进程。 */
internal class ColorOsAllAppsEndpoint(
    val service: Service,
    private val loader: ClassLoader,
    private val configuration: ProcessConfiguration,
    private val log: (Int, String, Throwable?) -> Unit,
) {
    private val handler = Handler(Looper.getMainLooper())
    private val environment = ModuleEnvironmentState(configuration) { code, exception ->
        log(Log.WARN, code, exception)
    }
    private val messenger = Messenger(Handler(Looper.getMainLooper(), ::receive))
    /** 工具目录与执行器；只在侧边栏进程可用（工具是侧边栏内部的 AbsTool 处理器）。 */
    private val toolCatalog by lazy { ColorOsToolCatalog(service, loader, log) }
    private var toolCatalogPublished = false
    /** [encodedToolCatalog] 的缓存；目录可能随侧边栏版本更新变化，进程内视为不变。 */
    private var encodedToolCatalogCache: String? = null
    private var request: Request? = null
    private var lastResult: Pair<String, Int>? = null
    private var disposed = false
    private var watching = false
    private var watchingPackages = false
    private var watchingConfiguration = false
    private var lastFailure = -5_000L
    private val tick = Runnable { advance() }
    private val refresh = Runnable { safely { request?.content?.refresh() } }
    private val settingsObserver: (ModuleSettingsSnapshot) -> Unit = {
        handler.post { if (!environment.isGestureAllowed()) cancel() }
    }
    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) = cancel()
    }
    private val packages = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            handler.removeCallbacks(refresh)
            handler.postDelayed(refresh, 300L)
        }
    }
    private val components = object : ComponentCallbacks {
        override fun onConfigurationChanged(newConfig: Configuration) = cancel()
        @Suppress("OVERRIDE_DEPRECATION") override fun onLowMemory() = Unit
    }

    val binder: IBinder get() = messenger.binder

    init {
        environment.start(service)
        environment.observe { if (!environment.isGestureAllowed()) cancel() }
        configuration.observe(settingsObserver)
        // 侧边栏进程启动即可发布工具目录，让设置界面的「添加应用」能列出工具。
        handler.post(::publishToolCatalog)
    }

    /**
     * 把侧边栏工具目录交给模块 App 落盘（Hook 进程的远端配置只读）。
     *
     * 两条路并行：直发广播（在部分 ROM 上会被厂商后台启动管控拦截，实测 ColorOS 会拦）+
     * 经面板会话的 reply Messenger 交给 system_server 转发（system_server 不受该管控限制）。
     * 失败不阻断面板链路。
     */
    private fun publishToolCatalog(force: Boolean = false) {
        if (toolCatalogPublished && !force) return
        try {
            val records = toolCatalog.build()
            if (records.isEmpty()) return
            val encoded = ToolCatalogCodec.encode(records)
            service.sendBroadcast(
                Intent(SharedStateProtocol.ACTION)
                    .setPackage(SharedStateProtocol.MODULE_PACKAGE)
                    // App 装完处于 stopped 状态时普通广播不会唤醒它，必须显式包含。
                    .addFlags(Intent.FLAG_INCLUDE_STOPPED_PACKAGES)
                    .putExtra(SharedStateProtocol.EXTRA_KIND, SharedStateProtocol.KIND_TOOL_CATALOG)
                    .putExtra(SharedStateProtocol.EXTRA_CATALOG, encoded),
            )
            toolCatalogPublished = true
            request?.reply?.let { reply ->
                runCatching { reply.send(SidebarProtocol.catalogMessage(encoded, Process.SYSTEM_UID)) }
            }
            log(Log.INFO, "TOOL_CATALOG_PUBLISHED count=${records.size}", null)
        } catch (exception: Exception) {
            log(Log.WARN, "TOOL_CATALOG_PUBLISH_FAILED", exception)
        }
    }

    /**
     * 目录编码文本缓存：枚举 22 个工具 + 逐枚 PNG 编码开销不小，
     * RUN_TOOL 补传等高频路径直接复用，首次调用时构建。
     *
     * @return 编码文本；目录为空（反射全失败）时返回 null
     */
    private fun encodedToolCatalog(): String? {
        encodedToolCatalogCache?.let { return it }
        val records = toolCatalog.build()
        if (records.isEmpty()) return null
        val encoded = ToolCatalogCodec.encode(records)
        encodedToolCatalogCache = encoded
        return encoded
    }

    fun onUnbound(id: String?) {
        request?.takeIf { it.id == id && it.phase != Phase.Active }?.let { finish(it, SidebarProtocol.ABORTED) }
    }

    fun onNativeSidebarState(state: String?) {
        if (state != "FLOAT_BAR_SHOWING") cancel()
    }

    fun onAdapterCreated(view: Any?, adapter: Any?) {
        val current = request ?: return
        if (current.content?.view !== view || adapter == null) return
        current.content?.bindAdapter(adapter) { tool, execute ->
            safely {
                if (request !== current || current.phase != Phase.Active || !environmentAllowed()) return@safely
                val window = current.window ?: return@safely
                if (!window.isShown()) return@safely
                when (current.action.select(tool)) {
                    AllAppsActionHandoff.Action.Ignore -> Unit
                    AllAppsActionHandoff.Action.ExecuteNow -> {
                        execute()
                        if (request === current) window.beginItemClick()
                    }
                    AllAppsActionHandoff.Action.ExecuteWhenHidden -> window.beginItemClick {
                        if (request === current && environmentAllowed() && current.action.hidden()) execute()
                    }
                }
            }
        }
    }

    fun dispose() {
        if (disposed) return
        disposed = true
        cancel()
        handler.removeCallbacksAndMessages(null)
        configuration.removeObserver(settingsObserver)
        environment.close()
    }

    private fun receive(message: Message): Boolean {
        if (disposed || !SidebarProtocol.isTrustedPeer(message.sendingUid, Process.SYSTEM_UID, message.arg1)) return true
        safely {
            val data = message.peekData() ?: return@safely
            // 开机目录请求：把当前目录回给请求方（system_server），由它写 Settings.Global。
            if (message.what == SidebarProtocol.REQUEST_TOOL_CATALOG) {
                val replyTo = message.replyTo
                if (replyTo != null && data.getInt(SidebarProtocol.TARGET_UID, -1) == Process.myUid()) {
                    val records = toolCatalog.build()
                    if (records.isNotEmpty()) {
                        runCatching {
                            replyTo.send(SidebarProtocol.catalogMessage(ToolCatalogCodec.encode(records), Process.myUid()))
                        }
                        log(Log.INFO, "TOOL_CATALOG_SERVED count=${records.size}", null)
                    }
                }
                return@safely
            }
            // 免会话的工具执行：扇形里选中的工具条目经此转交，不参与面板会话状态机。
            if (message.what == SidebarProtocol.RUN_TOOL) {
                val alias = data.getString(SidebarProtocol.TOOL_ALIAS)
                if (data.getInt(SidebarProtocol.TARGET_UID, -1) != Process.myUid() ||
                    !SidebarProtocol.isValidToolAlias(alias)
                ) {
                    log(Log.WARN, "TOOL_REQUEST_REJECTED", null)
                    return@safely
                }
                val clickX = data.getFloat(SidebarProtocol.TOOL_CLICK_X, 0f)
                val clickY = data.getFloat(SidebarProtocol.TOOL_CLICK_Y, 0f)
                if (!toolCatalog.run(alias!!, clickX, clickY)) log(Log.WARN, "TOOL_NOT_RUNNABLE $alias", null)
                // 免会话通道顺带把目录回传给 system_server：开机时它拉目录可能赶在
                // 侧边栏就绪前失败（SIDEBAR_TOOL_TARGET_UNAVAILABLE），这里是可靠的补传点。
                val replyTo = message.replyTo
                if (replyTo != null) {
                    val encoded = encodedToolCatalog()
                    if (encoded != null) {
                        runCatching { replyTo.send(SidebarProtocol.catalogMessage(encoded, Process.myUid())) }
                    }
                }
                publishToolCatalog()
                return@safely
            }
            val id = data.getString(SidebarProtocol.REQUEST_ID) ?: return@safely
            if (!SidebarProtocol.isValidRequestId(id) || data.getInt(SidebarProtocol.TARGET_UID, -1) != Process.myUid()) return@safely
            val reply = message.replyTo ?: return@safely
            if (message.what == SidebarProtocol.BACKDROP_HIDDEN) {
                val current = request?.takeIf { it.id == id && it.reply.binder == reply.binder } ?: return@safely
                val pending = current.pendingTool ?: return@safely
                current.pendingTool = null
                handler.removeCallbacks(current.toolTimeout)
                if (environmentAllowed()) pending() else cancel()
                return@safely
            }
            if (message.what == SidebarProtocol.CANCEL) {
                request?.takeIf { it.id == id && it.reply.binder == reply.binder }?.let {
                    finish(it, SidebarProtocol.CLEANED)
                    return@safely
                }
            }
            lastResult?.takeIf { it.first == id }?.let {
                send(reply, id, it.second)
                return@safely
            }
            if (message.what == SidebarProtocol.PREPARE) {
                // @author bomo 呼出方位随 PREPARE 下发：面板内容在 prepare 阶段就已构造
                // （比 OPEN 早），晚了就赶不上 showAllPanel(左/右) 与窗口锚定。
                // 缺键时留 null → 回退原厂标志位，保持旧路径行为。
                val panelLeftSide =
                    if (data.containsKey(SidebarProtocol.PANEL_LEFT_SIDE)) {
                        data.getBoolean(SidebarProtocol.PANEL_LEFT_SIDE)
                    } else {
                        null
                    }
                prepare(id, data.getLong(SidebarProtocol.DEADLINE), panelLeftSide, reply)
                return@safely
            }
            val current = request?.takeIf { it.id == id && it.reply.binder == reply.binder } ?: return@safely
            when (message.what) {
                SidebarProtocol.OPEN -> {
                    val now = SystemClock.uptimeMillis()
                    val deadline = data.getLong(SidebarProtocol.DEADLINE)
                    if (current.phase != Phase.Prepared) return@safely
                    if (now >= current.deadline || !SidebarProtocol.isValidDeadline(deadline, now, SidebarProtocol.OPEN_TIMEOUT_MS) || !environmentAllowed() || current.content?.sidebarHidden() != true) {
                        finish(current, SidebarProtocol.CLEANED)
                        return@safely
                    }
                    current.deadline = deadline
                    current.phase = Phase.Opening
                    val content = current.content ?: return@safely
                    val window = ColorOsAllAppsWindow(service, content,
                        // @author bomo 缩放取自设置界面滑条（App 写入的远端配置，本进程只读）。
                        configuration.snapshot.panelScalePercent,
                        onShown = {
                            if (request === current && current.phase == Phase.Opening) {
                                current.phase = Phase.Shown
                                send(current.reply, current.id, SidebarProtocol.SHOWN)
                            }
                        },
                        onClosed = { finish(current, if (current.phase == Phase.Active || current.exitStarted) SidebarProtocol.ABORTED else SidebarProtocol.CLEANED) },
                        onExitStarted = {
                            current.exitStarted = true
                            send(current.reply, current.id, SidebarProtocol.EXIT_STARTED)
                        },
                        beforeTool = { execute ->
                            if (request === current) {
                                current.pendingTool = execute
                                handler.postDelayed(current.toolTimeout, SidebarProtocol.CLEANUP_TIMEOUT_MS)
                                send(current.reply, current.id, SidebarProtocol.HIDE_BACKDROP)
                            }
                        },
                        onFailure = ::reportFailure,
                    )
                    current.window = window
                    window.show()
                    schedule()
                }
                SidebarProtocol.CONFIRM -> {
                    if (current.phase != Phase.Shown) return@safely
                    if (SystemClock.uptimeMillis() >= current.deadline || !environmentAllowed() || current.window?.isShown() != true) {
                        finish(current, SidebarProtocol.CLEANED)
                    } else {
                        current.phase = Phase.Active
                        handler.removeCallbacks(tick)
                        lastResult = current.id to SidebarProtocol.COMMITTED
                        send(current.reply, current.id, SidebarProtocol.COMMITTED)
                    }
                }
                SidebarProtocol.CANCEL -> finish(current, SidebarProtocol.CLEANED)
            }
        }
        return true
    }

    /**
     * @param panelLeftSide 本次呼出方位（null = 未下发，回退原厂标志位）。
     */
    private fun prepare(id: String, deadline: Long, panelLeftSide: Boolean?, reply: Messenger) {
        if (request != null || !environmentAllowed() || !SidebarProtocol.isValidDeadline(deadline, SystemClock.uptimeMillis(), SidebarProtocol.PREPARE_TIMEOUT_MS)) {
            send(reply, id, SidebarProtocol.ABORTED)
            return
        }
        val next = Request(id, deadline, reply)
        next.leftSide = panelLeftSide
        request = next
        reply.binder.linkToDeath(next.death, 0)
        next.deathLinked = true
        service.registerReceiver(receiver, IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_OFF)
            addAction(SidebarProtocol.ACTION_USER_SWITCHED)
        }, Context.RECEIVER_NOT_EXPORTED)
        watching = true
        service.registerReceiver(packages, IntentFilter().apply {
            addAction(Intent.ACTION_PACKAGE_ADDED)
            addAction(Intent.ACTION_PACKAGE_REMOVED)
            addAction(Intent.ACTION_PACKAGE_CHANGED)
            addDataScheme("package")
        }, Context.RECEIVER_NOT_EXPORTED)
        watchingPackages = true
        service.registerComponentCallbacks(components)
        watchingConfiguration = true
        next.content = ColorOsAllAppsContent(
            loader,
            leftSideOverride = panelLeftSide,
            recentFreeform = { SharedSettings.readRecentFreeform(service) },
            onRecentCandidate = { component ->
                request?.reply?.let { reply ->
                    runCatching {
                        reply.send(
                            SidebarProtocol.recordRecentMessage(component.flattenToString(), Process.SYSTEM_UID),
                        )
                    }
                }
            },
            onLaunchComponent = { component ->
                // 「最近小窗」点击：侧边栏无法自建小窗，交回 system_server 启动。
                request?.reply?.let { reply ->
                    runCatching {
                        reply.send(
                            SidebarProtocol.launchComponentMessage(component.flattenToString(), Process.SYSTEM_UID),
                        )
                    }
                }
            },
            log = log,
        )
        // 每次面板打开都重发：App 可能在上一次发布时还没起来（装完是 stopped 态），
        // 一次性标志会让目录永远补不上。
        publishToolCatalog(force = true)
        advance()
    }

    private fun advance(): Unit = safely {
        handler.removeCallbacks(tick)
        val current = request ?: return@safely
        if (!environmentAllowed()) {
            finish(current, SidebarProtocol.ABORTED)
            return@safely
        }
        if (current.phase == Phase.Active) return@safely
        if (SystemClock.uptimeMillis() >= current.deadline) {
            finish(current, SidebarProtocol.CLEANED)
            return@safely
        }
        val content = current.content
        if (current.phase == Phase.Preparing && content?.ready() == true) {
            if (!content.sidebarHidden()) {
                finish(current, SidebarProtocol.ABORTED)
                return@safely
            }
            content.startLoading()
            current.phase = Phase.Prepared
            send(current.reply, current.id, SidebarProtocol.READY)
        }
        schedule()
    }

    private fun schedule() {
        handler.removeCallbacks(tick)
        if (request != null) handler.postDelayed(tick, 32L)
    }

    private fun environmentAllowed(): Boolean =
        environment.isGestureAllowed(refreshKeyguard = true) &&
            service.getSystemService(UserManager::class.java)?.isUserForeground == true &&
            service.getSystemService(KeyguardManager::class.java)?.isKeyguardLocked == false &&
            Settings.Secure.getInt(service.contentResolver, "edge_panel_toggle", -1) == 1

    private fun cancel() { request?.let { finish(it, SidebarProtocol.ABORTED) } }

    private fun finish(current: Request, result: Int) {
        if (request !== current) return
        request = null
        current.action.cancel()
        current.pendingTool = null
        handler.removeCallbacks(current.toolTimeout)
        handler.removeCallbacks(tick)
        handler.removeCallbacks(refresh)
        var cleaned = true
        fun cleanup(action: () -> Unit) {
            try { action() } catch (exception: Exception) { cleaned = false; reportFailure(exception) }
        }
        cleanup { current.window?.dispose() ?: current.content?.close() }
        if (current.deathLinked) cleanup { current.reply.binder.unlinkToDeath(current.death, 0) }
        if (watching) { watching = false; cleanup { service.unregisterReceiver(receiver) } }
        if (watchingPackages) { watchingPackages = false; cleanup { service.unregisterReceiver(packages) } }
        if (watchingConfiguration) { watchingConfiguration = false; cleanup { service.unregisterComponentCallbacks(components) } }
        val finalResult = if (cleaned && !(current.exitStarted && result == SidebarProtocol.CLEANED)) result else SidebarProtocol.ABORTED
        lastResult = current.id to finalResult
        send(current.reply, current.id, finalResult)
    }

    private fun send(reply: Messenger, id: String, what: Int) {
        try {
            reply.send(SidebarProtocol.message(what, id, 0, Process.myUid()))
        } catch (exception: RemoteException) {
            reportFailure(exception)
            if (request?.id == id) cancel()
        }
    }

    private inline fun safely(action: () -> Unit) {
        try { action() } catch (exception: Exception) {
            reportFailure(exception)
            request?.let { finish(it, SidebarProtocol.CLEANED) }
        }
    }

    private fun reportFailure(exception: Exception) {
        val now = SystemClock.uptimeMillis()
        if (now - lastFailure >= 5_000L) {
            lastFailure = now
            log(Log.WARN, "ALL_APPS_SESSION_FAILED", exception)
        }
    }

    private enum class Phase { Preparing, Prepared, Opening, Shown, Active }
    private inner class Request(val id: String, var deadline: Long, val reply: Messenger) {
        /** @author bomo 本次呼出方位（null = 未下发，回退原厂标志位）。 */
        var leftSide: Boolean? = null
        var phase = Phase.Preparing
        var exitStarted = false
        var pendingTool: (() -> Unit)? = null
        val toolTimeout = Runnable { if (request === this) cancel() }
        val action = AllAppsActionHandoff()
        var content: ColorOsAllAppsContent? = null
        var window: ColorOsAllAppsWindow? = null
        var deathLinked = false
        val death = IBinder.DeathRecipient { handler.post { if (request === this) cancel() } }
    }
}
