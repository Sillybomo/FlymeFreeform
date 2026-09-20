package io.github.mangi.flymefreeform.platform.coloros

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.PixelFormat
import android.os.SystemClock
import android.provider.Settings
import android.view.Gravity
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.ViewTreeObserver
import android.view.WindowInsets
import android.view.WindowManager
import android.view.inputmethod.InputMethodManager
import android.widget.FrameLayout
import android.window.OnBackInvokedCallback
import android.window.OnBackInvokedDispatcher
import io.github.mangi.flymefreeform.window.AllAppsPanelGeometry
import io.github.mangi.flymefreeform.window.AllAppsPanelMotion
import java.lang.reflect.Method

/** 独立窗口只承载本次新建的全部内容；不挂接原侧栏父视图，也不改变其状态机。 */
internal class ColorOsAllAppsWindow(
    private val context: Context,
    val content: ColorOsAllAppsContent,
    private val onShown: () -> Unit,
    private val onClosed: () -> Unit,
    private val onExitStarted: () -> Unit,
    private val beforeTool: (() -> Unit) -> Unit,
    private val onFailure: (Exception) -> Unit,
) {
    private val manager = context.getSystemService(WindowManager::class.java)
    private val card = content.createCard()
    private var attached = false
    private var closed = false
    private var exiting = false
    private var entering = true
    private var animationStart = 0L
    private var durationScale = 1f
    private var notified = false
    private var hiddenAction: (() -> Unit)? = null
    private val dimensions = content.dimensions()
    private val mode = content.mode()
    private val leftSide = content.leftSide()
    private var insets: WindowInsets? = null
    private var displayWidth = 0
    private var displayHeight = 0
    private var displaySafe = IntArray(4)
    private var windowParams: WindowManager.LayoutParams? = null

    /** `FLAG_BLUR_BEHIND` 与 `setBlurBehindRadius` 未在公开 SDK 导出，按名字反射获取。 */
    private val blurBehindFlag: Int? =
        runCatching {
            WindowManager.LayoutParams::class.java.getField("FLAG_BLUR_BEHIND").getInt(null)
        }.getOrNull()
    private val blurRadiusSetter: Method? =
        runCatching {
            WindowManager.LayoutParams::class.java
                .getMethod("setBlurBehindRadius", Int::class.javaPrimitiveType)
        }.getOrNull()
    private var backDispatcher: OnBackInvokedDispatcher? = null
    private val backCallback = OnBackInvokedCallback { back() }
    private val animation = Runnable { animateFrame() }
    private val timeout = Runnable { dismiss() }
    private val firstFrame = ViewTreeObserver.OnPreDrawListener { prepareFirstFrame() }
    private var waitingForFirstFrame = true
    private val root = object : FrameLayout(context) {
        override fun dispatchTouchEvent(event: MotionEvent): Boolean {
            if (exiting || entering) return true
            resetTimeout()
            return super.dispatchTouchEvent(event)
        }

        override fun dispatchKeyEvent(event: KeyEvent): Boolean {
            if (event.keyCode == KeyEvent.KEYCODE_BACK) {
                if (event.action == KeyEvent.ACTION_UP) back()
                return true
            }
            return super.dispatchKeyEvent(event)
        }

        override fun onAttachedToWindow() {
            super.onAttachedToWindow()
            backDispatcher = findOnBackInvokedDispatcher()?.also {
                it.registerOnBackInvokedCallback(OnBackInvokedDispatcher.PRIORITY_DEFAULT, backCallback)
            }
        }

        override fun onDetachedFromWindow() {
            backDispatcher?.unregisterOnBackInvokedCallback(backCallback)
            backDispatcher = null
            super.onDetachedFromWindow()
            if (!closed) dispose()
        }

        override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
            super.onSizeChanged(w, h, oldw, oldh)
            placeCard()
        }
    }

    init {
        root.setBackgroundColor(0)
        root.isFocusableInTouchMode = true
        root.clipChildren = false
        card.alpha = 0f
        card.visibility = View.INVISIBLE
        content.view.alpha = 0f
        card.isClickable = true
        val metrics = manager.currentWindowMetrics
        displayWidth = metrics.bounds.width()
        displayHeight = metrics.bounds.height()
        val safe =
            metrics.windowInsets.getInsets(
                WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout(),
            )
        displaySafe = intArrayOf(safe.left, safe.top, safe.right, safe.bottom)
        val initial = AllAppsPanelGeometry.calculate(
            displayWidth, displayHeight, dimensions, mode, leftSide,
            safe.left, safe.top, safe.right, safe.bottom,
        )
        // 窗口收缩成面板大小：窗口模糊才会只作用于面板背后，而不是整屏。
        // 面板入场/退场缩放范围为 0.3~1.0，不会超出窗口被裁切。
        windowParams = WindowManager.LayoutParams(
            initial.width, initial.height, WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = initial.left
            y = initial.top
            layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
            softInputMode = WindowManager.LayoutParams.SOFT_INPUT_ADJUST_NOTHING
            setFitInsetsTypes(0)
            title = "FlymeFreeformAllApps"
            blurBehindFlag?.let { flags = flags or it }
            blurRadiusSetter?.let { setter ->
                runCatching { setter.invoke(this, tunedInt(BLUR_RADIUS_KEY, BLUR_BEHIND_RADIUS_PX)) }
            }
        }
        root.addView(card, FrameLayout.LayoutParams(-1, -1))
        root.setOnTouchListener { _, event ->
            if (event.actionMasked == MotionEvent.ACTION_UP) dismiss()
            true
        }
        root.setOnApplyWindowInsetsListener { _, value ->
            insets = value
            placeCard()
            value
        }
        content.bindClose { dismiss() }
    }

    fun show() {
        check(!attached && !closed)
        val params = windowParams ?: error("ALL_APPS_WINDOW_PARAMS_UNAVAILABLE")
        manager.addView(root, params)
        attached = true
        root.viewTreeObserver.addOnPreDrawListener(firstFrame)
        root.requestApplyInsets()
        root.requestFocus()
    }

    private fun prepareFirstFrame(): Boolean {
        if (closed) return true
        if (!waitingForFirstFrame) return true
        return try {
            // 卡片填满窗口，尺寸与窗口一致；不能再拿 layoutParams 比较（那是 MATCH_PARENT = -1）。
            if (root.width <= 0 || root.height <= 0 || insets == null ||
                card.isLayoutRequested || card.width != root.width || card.height != root.height ||
                !content.attachBlur()
            ) return false
            // 平台背景模糊已生效时关闭窗口级模糊，避免双重模糊与额外开销。
            if (content.platformGlass) disableWindowBlur()
            card.pivotX = card.width / 2f
            card.pivotY = card.height / 2f
            val initial = AllAppsPanelMotion.enter(0f)
            card.scaleX = initial.scale
            card.scaleY = initial.scale
            content.view.alpha = initial.alpha
            card.alpha = 1f
            card.visibility = View.VISIBLE
            waitingForFirstFrame = false
            root.viewTreeObserver.removeOnPreDrawListener(firstFrame)
            startAnimation()
            resetTimeout()
            true
        } catch (exception: Exception) {
            onFailure(exception)
            dispose()
            true
        }
    }

    fun isShown(): Boolean = attached && !closed && !exiting && !entering &&
        root.isShown && card.width > 0 && content.view.childCount > 0

    fun beginItemClick(afterHidden: (() -> Unit)? = null): Boolean {
        if (!isShown()) return false
        hiddenAction = afterHidden
        dismiss()
        return true
    }

    fun dismiss() {
        if (closed || exiting) return
        exiting = true
        entering = false
        onExitStarted()
        root.removeCallbacks(timeout)
        if (waitingForFirstFrame) {
            dispose()
            return
        }
        context.getSystemService(InputMethodManager::class.java)?.hideSoftInputFromWindow(root.windowToken, 0)
        startAnimation()
    }

    fun dispose() {
        if (closed) return
        closed = true
        if (root.viewTreeObserver.isAlive) root.viewTreeObserver.removeOnPreDrawListener(firstFrame)
        hiddenAction = null
        root.removeCallbacks(animation)
        root.removeCallbacks(timeout)
        try {
            content.close()
        } catch (exception: Exception) {
            onFailure(exception)
        } finally {
            if (attached) {
                attached = false
                try {
                    manager.removeViewImmediate(root)
                } catch (_: IllegalArgumentException) {
                    // 系统已移除窗口。
                }
            }
            root.removeAllViews()
            onClosed()
        }
    }

    private fun back() = safely {
        if (!exiting && !content.leaveSearch()) dismiss()
    }

    /** 窗口本身即面板，尺寸/位置直接写在窗口参数上；卡片始终填满窗口。 */
    private fun placeCard() {
        if (closed || displayWidth <= 0 || displayHeight <= 0) return
        val ime = insets?.getInsets(WindowInsets.Type.ime())?.bottom ?: 0
        val bounds = AllAppsPanelGeometry.calculate(
            displayWidth, displayHeight, dimensions, mode, leftSide,
            displaySafe[0], displaySafe[1], displaySafe[2], maxOf(displaySafe[3], ime),
        )
        val params = windowParams ?: return
        if (params.width == bounds.width && params.height == bounds.height &&
            params.x == bounds.left && params.y == bounds.top
        ) return
        params.width = bounds.width
        params.height = bounds.height
        params.x = bounds.left
        params.y = bounds.top
        if (attached) manager.updateViewLayout(root, params)
    }

    private var windowBlurDisabled = false

    /** 平台玻璃接管后，清掉窗口参数里的 BLUR_BEHIND 标志并立即生效（只执行一次）。 */
    private fun disableWindowBlur() {
        if (windowBlurDisabled) return
        windowBlurDisabled = true
        val flag = blurBehindFlag ?: return
        val params = windowParams ?: return
        if (params.flags and flag == 0) return
        params.flags = params.flags and flag.inv()
        if (attached) runCatching { manager.updateViewLayout(root, params) }
    }

    private fun startAnimation() {
        root.removeCallbacks(animation)
        durationScale = ValueAnimator.getDurationScale()
        animationStart = SystemClock.uptimeMillis()
        if (!ValueAnimator.areAnimatorsEnabled() || durationScale <= 0f) {
            if (exiting) finishExit() else finishEnter()
            return
        }
        root.postOnAnimation(animation)
    }

    private fun animateFrame(): Unit = safely {
        if (closed) return@safely
        val elapsed = (SystemClock.uptimeMillis() - animationStart) / durationScale
        val frame = if (exiting) AllAppsPanelMotion.exit(elapsed / 1_000f) else AllAppsPanelMotion.enter(elapsed / 1_000f)
        // 材质底色不参与入场渐变，只有内容淡入；避免暗色桌面透入后再变亮。
        card.alpha = if (exiting) frame.alpha else 1f
        content.view.alpha = if (exiting) 1f else frame.alpha
        card.pivotX = card.width / 2f
        card.pivotY = card.height / 2f
        card.scaleX = frame.scale
        card.scaleY = frame.scale
        content.updateBlur(if (exiting) frame.alpha else 1f)
        if (exiting && (frame.alpha <= AllAppsPanelMotion.EXIT_ALPHA_THRESHOLD || elapsed >= AllAppsPanelMotion.MAX_DURATION_MS)) {
            finishExit()
        } else if (!exiting && ((frame.alpha >= 0.997f && frame.scale >= 0.997f) || elapsed >= AllAppsPanelMotion.MAX_DURATION_MS)) {
            finishEnter()
        } else {
            root.postOnAnimation(animation)
        }
    }

    private fun finishEnter() {
        entering = false
        card.alpha = 1f
        content.view.alpha = 1f
        card.scaleX = 1f
        card.scaleY = 1f
        content.updateBlur(1f)
        if (!notified) {
            notified = true
            onShown()
        }
    }

    private fun finishExit() {
        card.alpha = 0f
        root.setBackgroundColor(0)
        content.updateBlur(0f)
        val action = hiddenAction
        hiddenAction = null
        if (action == null) {
            dispose()
            return
        }
        // 先提交不可见帧，再执行原厂工具；短暂保留窗口所有权供系统判断前台发起者。
        root.postOnAnimation {
            safely {
                if (closed) return@safely
                beforeTool {
                    safely {
                        if (closed) return@safely
                        action()
                        root.postOnAnimation { if (!closed) dispose() }
                    }
                }
            }
        }
    }

    private fun resetTimeout() {
        root.removeCallbacks(timeout)
        root.postDelayed(timeout, 120_000L)
    }

    private inline fun safely(action: () -> Unit) {
        try {
            action()
        } catch (exception: Exception) {
            onFailure(exception)
            dispose()
        }
    }

    /**
     * 读调参覆盖值。改 `Settings.Global` 后下次打开面板即生效，无需重编译或重启：
     * `adb shell settings put global flymefreeform_blur_radius 200`
     */
    private fun tunedInt(key: String, fallback: Int): Int =
        runCatching {
            Settings.Global.getInt(context.contentResolver, key, fallback)
        }.getOrDefault(fallback)

    private companion object {
        /** 窗口模糊回退路径的模糊半径（像素）默认值；对齐原生 integer/edit_panel_platform_blur_radius=300。 */
        const val BLUR_BEHIND_RADIUS_PX = 300
        const val BLUR_RADIUS_KEY = "flymefreeform_blur_radius"
    }
}
