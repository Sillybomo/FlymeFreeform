package io.github.mangi.flymefreeform.hook

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.PixelFormat
import android.hardware.input.InputManager
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.view.Gravity
import android.view.InputDevice
import android.view.InputEvent
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.WindowManager
import io.github.libxposed.api.XposedModule
import io.github.mangi.flymefreeform.config.ModuleSettingsSnapshot
import io.github.mangi.flymefreeform.gesture.AdaptiveCornerGestureConfig
import io.github.mangi.flymefreeform.gesture.CornerGestureConfig
import io.github.mangi.flymefreeform.gesture.CornerGestureEngine
import io.github.mangi.flymefreeform.gesture.CornerSide
import io.github.mangi.flymefreeform.gesture.CornerTriggerRegion
import io.github.mangi.flymefreeform.gesture.GestureAction
import io.github.mangi.flymefreeform.gesture.GesturePhase
import io.github.mangi.flymefreeform.gesture.TriggerHitZone
import java.lang.reflect.Field
import java.lang.reflect.Method
import kotlin.math.hypot
import kotlin.math.max

/** SystemUI 中的受信任 SPY 热区；只抢占触摸，菜单状态仍由 system_server 持有。 */
@SuppressLint("DiscouragedPrivateApi", "PrivateApi", "RtlHardcoded", "WrongConstant")
internal class SystemUiCornerInputMonitor(
    private val context: Context,
    private val module: XposedModule,
    private val configuration: ProcessConfiguration,
) {
    private val mainHandler = Handler(Looper.getMainLooper())
    private val windowManager =
        context.getSystemService(WindowManager::class.java)
            ?: error("WindowManager unavailable")
    private val inputManager =
        context.getSystemService(InputManager::class.java)
            ?: error("InputManager unavailable")
    private val environmentState = ModuleEnvironmentState(configuration) { code, exception ->
        module.log(Log.WARN, TAG, code, exception)
    }
    private val inputFeaturesField: Field =
        WindowManager.LayoutParams::class.java.getField("inputFeatures").apply {
            isAccessible = true
        }
    private val setTrustedOverlayMethod: Method =
        WindowManager.LayoutParams::class.java.getMethod("setTrustedOverlay").apply {
            isAccessible = true
        }
    private val getViewRootImplMethod: Method =
        View::class.java.getDeclaredMethod("getViewRootImpl").apply {
            isAccessible = true
        }
    private val pilferPointersMethod: Method =
        InputManager::class.java.getDeclaredMethod("pilferPointers", IBinder::class.java).apply {
            isAccessible = true
        }
    private val injectInputEventMethod: Method? =
        try {
            InputManager::class.java.getMethod(
                "injectInputEvent",
                InputEvent::class.java,
                Int::class.javaPrimitiveType,
            )
        } catch (_: ReflectiveOperationException) {
            null
        }
    private val bindings = linkedMapOf<CornerSide, CornerBinding>()

    private var settings = ModuleSettingsSnapshot(enabled = false)
    private var pendingSettings: ModuleSettingsSnapshot? = null
    private var lastPilferFailureAt = -PILFER_FAILURE_LOG_INTERVAL_MS
    private var lastClaimLogAt = -CLAIM_LOG_INTERVAL_MS
    private var lastTapReplayLogAt = -CLAIM_LOG_INTERVAL_MS

    fun start() {
        environmentState.start(context)
        environmentState.observe { applySettings(configuration.snapshot) }
        configuration.observe { snapshot ->
            if (Looper.myLooper() == Looper.getMainLooper()) {
                acceptSettings(snapshot)
            } else {
                mainHandler.post { acceptSettings(snapshot) }
            }
        }
    }

    private fun acceptSettings(snapshot: ModuleSettingsSnapshot) {
        ensureMainThread()
        if (environmentState.isGestureAllowed() && bindings.values.any { it.view.streamActive }) {
            pendingSettings = snapshot
            return
        }
        applySettings(snapshot)
    }

    private fun applySettings(snapshot: ModuleSettingsSnapshot) {
        ensureMainThread()
        settings = snapshot
        pendingSettings = null
        val allowed = environmentState.isGestureAllowed(refreshKeyguard = true)
        updateSide(CornerSide.Left, allowed && snapshot.leftCornerEnabled)
        updateSide(CornerSide.Right, allowed && snapshot.rightCornerEnabled)
    }

    private fun updateSide(side: CornerSide, shouldExist: Boolean) {
        val existing = bindings[side]
        if (!shouldExist) {
            if (existing != null) removeBinding(side, existing)
            return
        }

        // @author bomo 窗口尺寸必须覆盖整个热区的包围盒：三角形可以「横向长、纵向短」，
        // 若仍只按扇形半径开窗，落在窗口外的触摸根本送不到这里，宽高就形同虚设。
        val zone =
            CornerTriggerRegion.of(settings, context.resources.displayMetrics.density)
        val size =
            (maxOf(zone.horizontalLimitPx, zone.verticalLimitPx) + 0.5f)
                .toInt()
                .coerceAtLeast(1)
        if (existing == null) {
            addBinding(side, size, zone)
        } else if (existing.size != size || existing.view.zone != zone) {
            existing.size = size
            existing.view.zone = zone
            try {
                windowManager.updateViewLayout(existing.view, createLayoutParams(side, size))
            } catch (exception: ReflectiveOperationException) {
                module.log(Log.WARN, TAG, "SYSTEMUI_CORNER_SPY_UPDATE_FAILED", exception)
                removeBinding(side, existing)
            } catch (exception: RuntimeException) {
                module.log(Log.WARN, TAG, "SYSTEMUI_CORNER_SPY_UPDATE_FAILED", exception)
                removeBinding(side, existing)
            }
        }
    }

    private fun addBinding(side: CornerSide, size: Int, zone: TriggerHitZone) {
        val view =
            CornerGestureView(
                context = context,
                side = side,
                zone = zone,
                canClaim = { canClaim(side) },
                pilfer = ::pilfer,
                onClaimed = ::logClaimed,
                onTapReplay = ::replayTap,
                onStreamFinished = ::finishStream,
            )
        try {
            windowManager.addView(view, createLayoutParams(side, size))
            bindings[side] = CornerBinding(view, size)
        } catch (exception: ReflectiveOperationException) {
            module.log(Log.WARN, TAG, "SYSTEMUI_CORNER_SPY_ADD_FAILED", exception)
        } catch (exception: RuntimeException) {
            module.log(Log.WARN, TAG, "SYSTEMUI_CORNER_SPY_ADD_FAILED", exception)
        }
    }

    private fun removeBinding(side: CornerSide, binding: CornerBinding) {
        bindings.remove(side)
        binding.view.resetTracking()
        try {
            windowManager.removeViewImmediate(binding.view)
        } catch (_: IllegalArgumentException) {
            return
        } catch (exception: RuntimeException) {
            module.log(Log.WARN, TAG, "SYSTEMUI_CORNER_SPY_REMOVE_FAILED", exception)
        }
    }

    private fun canClaim(side: CornerSide): Boolean =
        settings.enabled &&
            when (side) {
                CornerSide.Left -> settings.leftCornerEnabled
                CornerSide.Right -> settings.rightCornerEnabled
            } &&
            environmentState.isGestureAllowed(refreshKeyguard = true)

    private fun pilfer(view: View): Boolean =
        try {
            val viewRoot = getViewRootImplMethod.invoke(view) ?: return false
            val inputToken =
                viewRoot.javaClass.getMethod("getInputToken").invoke(viewRoot) as? IBinder
                    ?: return false
            pilferPointersMethod.invoke(inputManager, inputToken)
            true
        } catch (exception: ReflectiveOperationException) {
            logPilferFailure(exception)
            false
        } catch (exception: RuntimeException) {
            logPilferFailure(exception)
            false
        }

    private fun logPilferFailure(exception: Throwable) {
        val now = SystemClock.uptimeMillis()
        if (now - lastPilferFailureAt < PILFER_FAILURE_LOG_INTERVAL_MS) return
        lastPilferFailureAt = now
        module.log(Log.WARN, TAG, "SYSTEMUI_CORNER_SPY_PILFER_FAILED", exception)
    }

    /** 提前抢断的取证日志；限流，只用于区分「没抢」和「抢了但系统仍起来」。 */
    private fun logClaimed() {
        val now = SystemClock.uptimeMillis()
        if (now - lastClaimLogAt < CLAIM_LOG_INTERVAL_MS) return
        lastClaimLogAt = now
        module.log(Log.INFO, TAG, "SYSTEMUI_CORNER_SPY_CLAIMED", null)
    }

    /**
     * 未被选中的角落点击：我们把指针流从应用手里抢走了，这里把这一下原样补发回去，
     * 让角落里的普通点击仍然生效（DOWN 即抢断的前提）。
     * 补发事件的 toolType 为 UNKNOWN，热区只认 FINGER，所以不会被自己再次抢断。
     */
    private fun replayTap(rawX: Float, rawY: Float) {
        val method = injectInputEventMethod ?: return
        val downTime = SystemClock.uptimeMillis()
        val down = MotionEvent.obtain(downTime, downTime, MotionEvent.ACTION_DOWN, rawX, rawY, 0)
        val up =
            MotionEvent.obtain(
                downTime,
                downTime + TAP_REPLAY_DURATION_MS,
                MotionEvent.ACTION_UP,
                rawX,
                rawY,
                0,
            )
        var failure: Throwable? = null
        try {
            down.source = InputDevice.SOURCE_TOUCHSCREEN
            up.source = InputDevice.SOURCE_TOUCHSCREEN
            method.invoke(inputManager, down, INJECT_MODE_ASYNC)
            method.invoke(inputManager, up, INJECT_MODE_ASYNC)
        } catch (exception: ReflectiveOperationException) {
            failure = exception
        } catch (exception: RuntimeException) {
            failure = exception
        } finally {
            down.recycle()
            up.recycle()
        }
        logTapReplay(failure)
    }

    private fun logTapReplay(failure: Throwable?) {
        val now = SystemClock.uptimeMillis()
        if (now - lastTapReplayLogAt < CLAIM_LOG_INTERVAL_MS) return
        lastTapReplayLogAt = now
        module.log(
            if (failure == null) Log.INFO else Log.WARN,
            TAG,
            if (failure == null) "SYSTEMUI_CORNER_SPY_TAP_REPLAYED" else "SYSTEMUI_CORNER_SPY_TAP_REPLAY_FAILED",
            failure,
        )
    }

    private fun finishStream() {
        mainHandler.post {
            if (bindings.values.any { it.view.streamActive }) return@post
            pendingSettings?.let(::applySettings)
        }
    }

    private fun createLayoutParams(side: CornerSide, size: Int): WindowManager.LayoutParams =
        WindowManager.LayoutParams(
            size,
            size,
            CORNER_GESTURE_WINDOW_TYPE,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_SPLIT_TOUCH,
            PixelFormat.TRANSPARENT,
        ).apply {
            gravity =
                Gravity.BOTTOM or
                    if (side == CornerSide.Left) Gravity.LEFT else Gravity.RIGHT
            title = "$CORNER_INPUT_CHANNEL_TITLE-${side.name}"
            layoutInDisplayCutoutMode =
                WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
            setFitInsetsTypes(0)
            setTrustedOverlayMethod.invoke(this)
            inputFeaturesField.setInt(
                this,
                inputFeaturesField.getInt(this) or INPUT_FEATURE_SPY,
            )
        }

    private fun ensureMainThread() {
        check(Looper.myLooper() == Looper.getMainLooper()) {
            "Corner input windows must run on the SystemUI main thread"
        }
    }

    private data class CornerBinding(
        val view: CornerGestureView,
        var size: Int,
    )

    @SuppressLint("ClickableViewAccessibility")
    private class CornerGestureView(
        context: Context,
        private val side: CornerSide,
        /** @author bomo 热区几何；随设置变化由宿主整体替换（data class 相等即无变化）。 */
        var zone: TriggerHitZone,
        private val canClaim: () -> Boolean,
        private val pilfer: (View) -> Boolean,
        private val onClaimed: () -> Unit,
        private val onTapReplay: (Float, Float) -> Unit,
        private val onStreamFinished: () -> Unit,
    ) : View(context) {
        private val gestureEngine = CornerGestureEngine()
        private val touchSlop = ViewConfiguration.get(context).scaledTouchSlop.toFloat()
        private val density = context.resources.displayMetrics.density
        private val claimInwardPx = CornerTriggerRegion.claimInwardPx(density)
        private var activeConfig: CornerGestureConfig? = null
        private var activePointerId = -1
        private var tracking = false
        private var downX = 0f
        private var downY = 0f
        private var downRawX = 0f
        private var downRawY = 0f
        private var maxDisplacement = 0f
        private var activated = false
        private var claimPilfered = false
        private var activatePilfered = false

        val streamActive: Boolean
            get() = tracking

        init {
            importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
            setWillNotDraw(true)
        }

        override fun onTouchEvent(event: MotionEvent): Boolean {
            if (!event.isFromSource(InputDevice.SOURCE_TOUCHSCREEN)) {
                val wasActive = tracking
                resetTracking()
                if (wasActive) onStreamFinished()
                return true
            }

            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    resetTracking()
                    val eligible =
                        event.pointerCount == 1 &&
                            event.getToolType(0) == MotionEvent.TOOL_TYPE_FINGER &&
                            canClaim() &&
                            CornerTriggerRegion.detectSide(
                                x = event.x,
                                y = event.y,
                                displayWidth = width.toFloat(),
                                displayHeight = height.toFloat(),
                                zone = zone,
                                leftEnabled = side == CornerSide.Left,
                                rightEnabled = side == CornerSide.Right,
                            ) == side
                    if (eligible) {
                        val config =
                            AdaptiveCornerGestureConfig.create(
                                displayWidth = width.toFloat(),
                                displayHeight = height.toFloat(),
                                touchSlop = touchSlop,
                                density = density,
                                triggerZone = zone,
                                leftEnabled = side == CornerSide.Left,
                                rightEnabled = side == CornerSide.Right,
                            )
                        activePointerId = event.getPointerId(0)
                        downX = event.x
                        downY = event.y
                        downRawX = event.rawX
                        downRawY = event.rawY
                        maxDisplacement = 0f
                        activated = false
                        gestureEngine.down(activePointerId, event.x, event.y, config)
                        if (gestureEngine.phase == GesturePhase.Armed) {
                            activeConfig = config
                            tracking = true
                            // 第一帧独占：DOWN 就抢断，桌面的上滑手势状态机来不及启动，
                            // 竞速从原理上消失（未被选中的点击稍后原样补发）。
                            claimPilfered = pilfer(this)
                            if (claimPilfered) onClaimed()
                        }
                    }
                }

                MotionEvent.ACTION_MOVE -> {
                    if (tracking) {
                        if (!canClaim()) {
                            resetTracking()
                            onStreamFinished()
                            return true
                        }
                        val config = activeConfig
                        val pointerIndex = event.findPointerIndex(activePointerId)
                        if (config == null || pointerIndex < 0) {
                            gestureEngine.cancel()
                        } else {
                            val x = event.getX(pointerIndex)
                            val y = event.getY(pointerIndex)
                            maxDisplacement = max(maxDisplacement, hypot(x - downX, y - downY))
                            val action =
                                gestureEngine.move(
                                    pointerId = activePointerId,
                                    pointerCount = event.pointerCount,
                                    x = x,
                                    y = y,
                                    config = config,
                                )
                            if (action is GestureAction.Activate) activated = true
                            // 兜底：万一 DOWN 时抢断没成功，一出现内向位移就补抢；
                            // 纯竖直滑动不抢，仍留给系统上滑。
                            if (!claimPilfered &&
                                CornerTriggerRegion.inwardDistance(side, downX, x) >= claimInwardPx
                            ) {
                                claimPilfered = pilfer(this)
                                if (claimPilfered) onClaimed()
                            }
                            // 到达展开阈值时再抢一次，防止中途被系统监视器后手夺走。
                            if (!activatePilfered && action is GestureAction.Activate) {
                                activatePilfered = true
                                pilfer(this)
                            }
                        }
                    }
                }

                MotionEvent.ACTION_POINTER_DOWN -> {
                    if (tracking) gestureEngine.cancel()
                }

                MotionEvent.ACTION_UP,
                MotionEvent.ACTION_CANCEL -> {
                    // 抢了流但根本没展开菜单、且全程没怎么动 → 这是一次普通点击，原样补发。
                    val tapToReplay =
                        if (event.actionMasked == MotionEvent.ACTION_UP &&
                            tracking &&
                            claimPilfered &&
                            !activated &&
                            CornerTriggerRegion.tapReplayable(maxDisplacement, touchSlop)
                        ) {
                            downRawX to downRawY
                        } else {
                            null
                        }
                    if (event.actionMasked == MotionEvent.ACTION_UP && tracking) {
                        gestureEngine.up(event.getPointerId(event.actionIndex))
                    }
                    resetTracking()
                    onStreamFinished()
                    tapToReplay?.let { (rawX, rawY) -> onTapReplay(rawX, rawY) }
                }

                else -> Unit
            }
            return true
        }

        fun resetTracking() {
            gestureEngine.cancel()
            activeConfig = null
            activePointerId = -1
            tracking = false
            downX = 0f
            downY = 0f
            downRawX = 0f
            downRawY = 0f
            maxDisplacement = 0f
            activated = false
            claimPilfered = false
            activatePilfered = false
        }
    }

    private companion object {
        const val TAG = "FlymeFreeform"
        const val INPUT_FEATURE_SPY = 1 shl 2
        const val CORNER_GESTURE_WINDOW_TYPE = 2024
        const val CORNER_INPUT_CHANNEL_TITLE = "FlymeFreeform-corner-input"
        const val PILFER_FAILURE_LOG_INTERVAL_MS = 10_000L
        const val CLAIM_LOG_INTERVAL_MS = 2_000L
        const val INJECT_MODE_ASYNC = 0
        const val TAP_REPLAY_DURATION_MS = 40L
    }
}
