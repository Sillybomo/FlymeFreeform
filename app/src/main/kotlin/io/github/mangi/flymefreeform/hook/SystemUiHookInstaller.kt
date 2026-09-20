package io.github.mangi.flymefreeform.hook

import android.annotation.SuppressLint
import android.content.Context
import android.util.Log
import io.github.libxposed.api.XposedInterface
import io.github.libxposed.api.XposedModule
import java.util.concurrent.atomic.AtomicBoolean

@SuppressLint("PrivateApi")
internal class SystemUiHookInstaller(
    private val module: XposedModule,
    private val configuration: ProcessConfiguration,
) {
    private val bound = AtomicBoolean(false)
    private var inputMonitor: SystemUiCornerInputMonitor? = null

    fun install(classLoader: ClassLoader) {
        try {
            // ColorOS 17 把 SystemUI 的 Application 迁到 application.impl 包下；
            // 按候选顺序解析，并用 getMethod 沿层次查找 onCreate。
            val applicationClass =
                SYSTEM_UI_APPLICATION_CLASSES
                    .firstNotNullOfOrNull { name ->
                        runCatching { classLoader.loadClass(name) }.getOrNull()
                    }
                    ?: throw ClassNotFoundException(SYSTEM_UI_APPLICATION_CLASSES.first())
            val onCreate = applicationClass.getMethod("onCreate")
            module
                .hook(onCreate)
                .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
                .setId("flymefreeform.systemui.corner_spy_owner")
                .intercept { chain ->
                    val result = chain.proceed()
                    val context = chain.thisObject as? Context
                    if (context != null && bound.compareAndSet(false, true)) {
                        startInputMonitor(context)
                    }
                    result
                }
            module.log(Log.INFO, TAG, "SYSTEMUI_CORNER_SPY_HOOK_INSTALLED")
        } catch (exception: ReflectiveOperationException) {
            module.log(Log.WARN, TAG, "SYSTEMUI_APPLICATION_TARGET_UNAVAILABLE", exception)
        } catch (exception: LinkageError) {
            module.log(Log.WARN, TAG, "SYSTEMUI_APPLICATION_TARGET_LINKAGE_FAILED", exception)
        }
    }

    private fun startInputMonitor(context: Context) {
        try {
            val monitor = SystemUiCornerInputMonitor(context, module, configuration)
            inputMonitor = monitor
            monitor.start()
            module.log(Log.INFO, TAG, "SYSTEMUI_CORNER_SPY_READY")
        } catch (exception: ReflectiveOperationException) {
            module.log(Log.WARN, TAG, "SYSTEMUI_CORNER_SPY_API_UNAVAILABLE", exception)
        } catch (exception: RuntimeException) {
            module.log(Log.WARN, TAG, "SYSTEMUI_CORNER_SPY_START_FAILED", exception)
        } catch (exception: LinkageError) {
            module.log(Log.WARN, TAG, "SYSTEMUI_CORNER_SPY_LINKAGE_FAILED", exception)
        }
    }

    private companion object {
        const val TAG = "FlymeFreeform"
        val SYSTEM_UI_APPLICATION_CLASSES =
            listOf(
                // ColorOS 17
                "com.android.systemui.application.impl.SystemUIApplicationImpl",
                // ColorOS 16 及更早
                "com.android.systemui.SystemUIApplication",
            )
    }
}
