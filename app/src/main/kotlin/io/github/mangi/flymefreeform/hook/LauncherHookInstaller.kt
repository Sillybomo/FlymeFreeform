package io.github.mangi.flymefreeform.hook

import android.annotation.SuppressLint
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.view.InputDevice
import android.view.InputEvent
import android.view.MotionEvent
import android.view.ViewConfiguration
import io.github.libxposed.api.XposedInterface
import io.github.libxposed.api.XposedModule
import io.github.mangi.flymefreeform.gesture.AdaptiveCornerGestureConfig
import io.github.mangi.flymefreeform.gesture.CornerGestureConfig
import io.github.mangi.flymefreeform.gesture.CornerGestureEngine
import io.github.mangi.flymefreeform.gesture.CornerTriggerRegion
import io.github.mangi.flymefreeform.gesture.GestureAction
import io.github.mangi.flymefreeform.gesture.GesturePhase
import java.lang.reflect.Field
import java.lang.reflect.Method

/** ColorOS Quickstep 输入入口；命中角落的指针流从 DOWN 起不再进入系统手势链。 */
@SuppressLint("DiscouragedPrivateApi", "PrivateApi")
internal class LauncherHookInstaller(
    private val module: XposedModule,
    private val configuration: ProcessConfiguration,
) {
    private val gestureEngine = CornerGestureEngine()
    private val mainHandler = Handler(Looper.getMainLooper())
    private val environmentState = ModuleEnvironmentState(configuration) { code, exception ->
        module.log(Log.WARN, TAG, code, exception)
    }
    private var environmentStarted = false
    private var activeConfig: CornerGestureConfig? = null
    private var activePointerId = -1
    private var suppressUntilTerminal = false
    private var pilfered = false
    private var inputMonitorField: Field? = null
    private var inputMonitorInnerField: Field? = null
    private var forcePilferPointersMethod: Method? = null
    private var lastClaimLogAt = -CLAIM_LOG_INTERVAL_MS
    private var lastPilferFailureAt = -PILFER_FAILURE_LOG_INTERVAL_MS

    fun install(classLoader: ClassLoader) {
        try {
            val serviceClass = classLoader.loadClass(TOUCH_SERVICE_CLASS)
            val inputMethod =
                resolveInputEntry(serviceClass)
                    ?: throw ReflectiveOperationException(
                        "$TOUCH_SERVICE_CLASS#$INPUT_METHOD_NAME unavailable",
                    )
            inputMethod.isAccessible = true
            resolveInputMonitor(serviceClass)

            module
                .hook(inputMethod)
                .setPriority(XposedInterface.PRIORITY_HIGHEST)
                .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
                .setId("flymefreeform.launcher.corner_input_owner")
                .intercept { chain ->
                    val event = chain.getArg(0) as? MotionEvent
                        ?: return@intercept chain.proceed()
                    val owner = chain.thisObject as? Context
                        ?: return@intercept chain.proceed()
                    if (shouldSuppress(owner, event)) {
                        null
                    } else {
                        chain.proceed()
                    }
                }
            module.log(Log.INFO, TAG, "LAUNCHER_CORNER_INPUT_HOOK_INSTALLED")
        } catch (exception: ReflectiveOperationException) {
            module.log(Log.WARN, TAG, "LAUNCHER_CORNER_INPUT_TARGET_UNAVAILABLE", exception)
        } catch (exception: LinkageError) {
            module.log(Log.WARN, TAG, "LAUNCHER_CORNER_INPUT_TARGET_LINKAGE_FAILED", exception)
        }
    }

    /**
     * ColorOS 16 的入口方法名固定；ColorOS 17 起桌面方法名被混淆（如 `K`），
     * 因此按签名在类层次里定位唯一接收 InputEvent 的实例方法，优先取最派生声明。
     */
    private fun resolveInputEntry(serviceClass: Class<*>): Method? {
        val candidates = mutableListOf<Method>()
        var current: Class<*>? = serviceClass
        while (current != null && current != Any::class.java) {
            runCatching {
                current.declaredMethods
                    .filter { method ->
                        method.returnType == Void.TYPE &&
                            method.parameterTypes.size == 1 &&
                            method.parameterTypes[0] == InputEvent::class.java
                    }
                    .forEach { candidates += it }
            }
            current = current.superclass
        }
        return candidates.firstOrNull { it.name == INPUT_METHOD_NAME } ?: candidates.firstOrNull()
    }

    /**
     * 定位输入监视器。ColorOS 16 的 `mInputMonitorCompat` 自身声明 `forcePilferPointers`；
     * ColorOS 17 移除了该类，触摸服务改为持有包装对象（如 `ab.i`），真正的
     * `android.view.InputMonitor` 在其字段上，抢指针走 `pilferPointers()`。
     * 解析失败不阻断入口 Hook：只跳过抢指针，角落手势仍由 SystemUI 热区接管。
     */
    private fun resolveInputMonitor(serviceClass: Class<*>) {
        var current: Class<*>? = serviceClass
        while (current != null && current != Any::class.java) {
            val fields = runCatching { current.declaredFields }.getOrNull().orEmpty()
            for (field in fields) {
                pilferMethodOf(field.type)?.let { pilfer ->
                    field.isAccessible = true
                    inputMonitorField = field
                    forcePilferPointersMethod = pilfer
                    return
                }
                val monitorType = inputMonitorType ?: continue
                val inner = runCatching { field.type.declaredFields }.getOrNull().orEmpty()
                    .firstOrNull { it.type == monitorType }
                    ?: continue
                val pilfer = pilferMethodOf(monitorType) ?: continue
                field.isAccessible = true
                inner.isAccessible = true
                inputMonitorField = field
                inputMonitorInnerField = inner
                forcePilferPointersMethod = pilfer
                return
            }
            current = current.superclass
        }
    }

    /** 沿类型层次查找抢指针方法；混淆包装类可能把实现放在父类上。 */
    /** `android.view.InputMonitor` 是 @hide 类，不在公开 SDK 中，只能按名字解析。 */
    private val inputMonitorType: Class<*>? by lazy {
        runCatching { Class.forName(INPUT_MONITOR_CLASS) }.getOrNull()
    }

    private fun pilferMethodOf(type: Class<*>): Method? =
        PILFER_METHOD_NAMES.firstNotNullOfOrNull { name ->
            runCatching { type.getMethod(name) }.getOrNull()
                ?.takeIf { it.parameterCount == 0 }
                ?.apply { isAccessible = true }
        }

    private fun shouldSuppress(owner: Context, event: MotionEvent): Boolean {
        if (event.actionMasked == MotionEvent.ACTION_DOWN) {
            resetStream()
            val config = createEligibleConfig(owner, event) ?: return false
            activePointerId = event.getPointerId(0)
            gestureEngine.down(activePointerId, event.rawX, event.rawY, config)
            if (gestureEngine.phase == GesturePhase.Armed) {
                activeConfig = config
                suppressUntilTerminal = true
                return true
            }
            resetStream()
            return false
        }
        if (!suppressUntilTerminal) return false

        if (!environmentState.isGestureAllowed()) cancelClaim()

        when (event.actionMasked) {
            MotionEvent.ACTION_MOVE -> {
                val config = activeConfig
                val pointerIndex = event.findPointerIndex(activePointerId)
                if (config != null && pointerIndex >= 0) {
                    val action =
                        gestureEngine.move(
                            pointerId = activePointerId,
                            pointerCount = event.pointerCount,
                            x = event.getRawX(pointerIndex),
                            y = event.getRawY(pointerIndex),
                            config = config,
                        )
                    if (!pilfered && action is GestureAction.Activate) {
                        forcePilferPointers(owner)
                        pilfered = true
                        logClaimed()
                    }
                } else {
                    gestureEngine.cancel()
                }
            }

            MotionEvent.ACTION_POINTER_DOWN -> gestureEngine.cancel()

            MotionEvent.ACTION_UP -> {
                gestureEngine.up(event.getPointerId(event.actionIndex))
                resetStream()
            }

            MotionEvent.ACTION_CANCEL -> resetStream()

            else -> Unit
        }
        return true
    }

    private fun createEligibleConfig(
        owner: Context,
        event: MotionEvent,
    ): CornerGestureConfig? {
        if (event.actionMasked != MotionEvent.ACTION_DOWN) return null
        val settings = configuration.snapshot
        if (!configuration.isAvailable || !settings.enabled) return null
        if (!event.isFromSource(InputDevice.SOURCE_TOUCHSCREEN)) return null
        if (event.pointerCount != 1 || event.getToolType(0) != MotionEvent.TOOL_TYPE_FINGER) return null
        startEnvironment(owner)
        if (!environmentState.isGestureAllowed(refreshKeyguard = true)) return null

        val metrics = owner.resources.displayMetrics
        val width = metrics.widthPixels.toFloat()
        val height = metrics.heightPixels.toFloat()
        if (width <= 0f || height <= 0f) return null
        return AdaptiveCornerGestureConfig.create(
            displayWidth = width,
            displayHeight = height,
            touchSlop = ViewConfiguration.get(owner).scaledTouchSlop.toFloat(),
            density = metrics.density,
            // @author bomo 热区随设置的形状/宽高变化（三角形可横长纵短）。
            triggerZone = CornerTriggerRegion.of(settings, metrics.density),
            leftEnabled = settings.leftCornerEnabled,
            rightEnabled = settings.rightCornerEnabled,
        ).takeIf { config ->
            CornerTriggerRegion.detectSide(
                x = event.rawX,
                y = event.rawY,
                displayWidth = config.displayWidth,
                displayHeight = config.displayHeight,
                zone = config.triggerZone,
                leftEnabled = config.leftEnabled,
                rightEnabled = config.rightEnabled,
            ) != null
        }
    }

    private fun resetStream() {
        gestureEngine.cancel()
        activeConfig = null
        activePointerId = -1
        suppressUntilTerminal = false
        pilfered = false
    }

    private fun startEnvironment(owner: Context) {
        if (environmentStarted) return
        environmentStarted = true
        val start = {
            environmentState.start(owner)
            environmentState.observe {
                if (!environmentState.isGestureAllowed()) cancelClaim()
            }
            configuration.observe {
                mainHandler.post { if (!environmentState.isGestureAllowed()) cancelClaim() }
            }
        }
        if (Looper.myLooper() == Looper.getMainLooper()) start() else mainHandler.post { start() }
    }

    private fun cancelClaim() {
        gestureEngine.cancel()
        activeConfig = null
        // 已拦下 DOWN 的旧流继续收尾，不能把缺少 DOWN 的 MOVE/UP 交回桌面。
    }

    private fun forcePilferPointers(owner: Context) {
        try {
            val holder = inputMonitorField?.get(owner) ?: return
            val monitor = inputMonitorInnerField?.get(holder) ?: holder
            forcePilferPointersMethod?.invoke(monitor)
        } catch (exception: ReflectiveOperationException) {
            logPilferFailure(exception)
        } catch (exception: RuntimeException) {
            logPilferFailure(exception)
        }
    }

    private fun logPilferFailure(exception: Throwable) {
        val now = SystemClock.uptimeMillis()
        if (now - lastPilferFailureAt < PILFER_FAILURE_LOG_INTERVAL_MS) return
        lastPilferFailureAt = now
        module.log(Log.WARN, TAG, "LAUNCHER_CORNER_INPUT_PILFER_FAILED", exception)
    }

    private fun logClaimed() {
        val now = SystemClock.uptimeMillis()
        if (now - lastClaimLogAt < CLAIM_LOG_INTERVAL_MS) return
        lastClaimLogAt = now
        module.log(Log.INFO, TAG, "LAUNCHER_CORNER_INPUT_CLAIMED")
    }

    private companion object {
        const val TAG = "FlymeFreeform"
        const val TOUCH_SERVICE_CLASS = "com.android.quickstep.OplusBaseTouchInteractionService"
        const val INPUT_METHOD_NAME = "onInputEventInternal"
        const val INPUT_MONITOR_CLASS = "android.view.InputMonitor"
        val PILFER_METHOD_NAMES = listOf("forcePilferPointers", "pilferPointers")
        const val CLAIM_LOG_INTERVAL_MS = 2_000L
        const val PILFER_FAILURE_LOG_INTERVAL_MS = 10_000L
    }
}
