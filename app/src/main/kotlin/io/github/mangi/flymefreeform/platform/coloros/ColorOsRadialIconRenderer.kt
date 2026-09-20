package io.github.mangi.flymefreeform.platform.coloros

import android.content.res.Resources
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Rect
import android.graphics.drawable.AdaptiveIconDrawable
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import android.util.Log
import java.lang.reflect.InvocationTargetException
import kotlin.math.roundToInt

/** 只在目录工作线程配置独立 Drawable；形状与图层绘制由 ColorOS 完成。 */
internal class ColorOsRadialIconRenderer(
    private val resources: Resources,
    private val logger: (Int, String, Throwable?) -> Unit,
) {
    private var failureReported = false
    private val access: NativeAccess? by lazy {
        try {
            NativeAccess()
        } catch (exception: ReflectiveOperationException) {
            reportFailure(exception)
            null
        } catch (exception: RuntimeException) {
            reportFailure(exception)
            null
        } catch (error: LinkageError) {
            reportFailure(error)
            null
        }
    }

    fun shapedIcon(source: Drawable): Drawable {
        // 普通位图没有可重新塑形的自适应图层，保留系统结果。
        if (source !is AdaptiveIconDrawable) {
            // @author bomo：ColorOS 的 LauncherActivityInfo.getIcon() 实测返回 BitmapDrawable
            // 而非自适应图层（诊断码 RADIAL_ICON_SKIP 全部命中 BitmapDrawable）。
            // 这类位图带透明安全区留白，原样丢进圆形容器会四周露白 →
            // 视觉上从"圆形"退化成"菱形"。改为裁掉留白并放大填满。
            return fillNonAdaptive(source)
        }
        val native = access ?: return source
        val state = source.constantState ?: return source
        return try {
            val originalExtension = native.extension.get(source)
            val config = native.config.get(originalExtension)
            val icon = state.newDrawable(resources).mutate() as AdaptiveIconDrawable
            val extension = native.extension.get(icon)
            val maximumSize = native.maximumSize.invoke(null, resources) as Int
            check(maximumSize > 0)
            // 保留系统加载器对应用图层的缩放和分类，仅将外框填满绘制区域。
            val foregroundScale = config?.let { native.foregroundScale.invoke(it) as Float } ?: 1f
            val platform = config?.let { native.platform.invoke(it) as Boolean } ?: false
            val adaptive = config?.let { native.adaptive.invoke(it) as Boolean } ?: true
            val radius = MASK_VIEWPORT / 2f
            val shape = Path().apply {
                addRoundRect(0f, 0f, MASK_VIEWPORT, MASK_VIEWPORT, radius, radius, Path.Direction.CW)
            }
            // @author bomo：诊断——记录实际取到的厂商量值，用于定位"图标偏小/方圆不一致"的根因。
            logger(
                Log.INFO,
                "RADIAL_ICON_METRICS max=$maximumSize fgScale=$foregroundScale " +
                    "platform=$platform adaptive=$adaptive viewport=$MASK_VIEWPORT " +
                    "fillScale=$ICON_FILL_SCALE",
                null,
            )
            // @author bomo：原实现传 (maximumSize * foregroundScale)，
            // 即保留 ColorOS 给前景图层预留的安全区缩放（实测通常 < 1）。
            // 后果：方形图标内容只占外框的一部分，放进圆形容器后四周留白 →
            // 视觉上从"圆形"退化成了"菱形"。这里改为按 ICON_FILL_SCALE 填满外框，
            // 由 mask 负责把内容裁成圆形，使方形图标也能圆润填满。
            native.build.invoke(
                extension,
                resources,
                maximumSize,
                (maximumSize * ICON_FILL_SCALE).roundToInt(),
                shape,
                platform,
                adaptive,
            )
            icon
        } catch (exception: InvocationTargetException) {
            val cause = exception.targetException
            if (cause is Error) throw cause
            reportFailure(cause)
            source
        } catch (exception: ReflectiveOperationException) {
            reportFailure(exception)
            source
        } catch (exception: RuntimeException) {
            reportFailure(exception)
            source
        }
    }

    /**
     * @author bomo 处理非自适应图标（ColorOS 实测返回 BitmapDrawable）。
     * 这类位图带有透明安全区留白，直接放进圆形容器会四周露白、视觉上像"菱形"；
     * 这里裁掉透明边缘并按内容长边等比放大，使图标内容填满外框，再由上层圆形裁剪收圆。
     *
     * @param source 原始图标 Drawable（可能是任意类型）
     * @return 填充后的 Drawable；无法处理时原样返回，绝不向上抛异常
     * 异常/边界：位图不可读（HARDWARE）、全透明或已贴边时原样返回
     */
    private fun fillNonAdaptive(source: Drawable): Drawable {
        val bitmap = (source as? BitmapDrawable)?.bitmap ?: return source
        return try {
            val filled = trimToContent(bitmap)
            if (filled === bitmap) source else BitmapDrawable(resources, filled)
        } catch (error: RuntimeException) {
            reportFailure(error)
            source
        } catch (error: LinkageError) {
            reportFailure(error)
            source
        }
    }

    /**
     * @author bomo 扫描 alpha 通道求内容外接框，裁掉透明留白后居中放大到正方形画布。
     *
     * @param bitmap 原始位图
     * @return 填充后的位图；内容已贴边或不可读时返回入参本身
     * 异常/边界：HARDWARE 位图先拷贝为 ARGB_8888 再读像素；全透明位图原样返回
     */
    private fun trimToContent(bitmap: Bitmap): Bitmap {
        val width = bitmap.width
        val height = bitmap.height
        if (width <= 0 || height <= 0) return bitmap
        val readable =
            if (bitmap.config == Bitmap.Config.HARDWARE) {
                bitmap.copy(Bitmap.Config.ARGB_8888, false) ?: return bitmap
            } else {
                bitmap
            }
        val pixels = IntArray(width * height)
        readable.getPixels(pixels, 0, width, 0, 0, width, height)
        var minX = width
        var minY = height
        var maxX = -1
        var maxY = -1
        for (y in 0 until height) {
            val row = y * width
            for (x in 0 until width) {
                if (pixels[row + x] ushr 24 > CONTENT_ALPHA_THRESHOLD) {
                    if (x < minX) minX = x
                    if (x > maxX) maxX = x
                    if (y < minY) minY = y
                    if (y > maxY) maxY = y
                }
            }
        }
        if (maxX < minX || maxY < minY) return bitmap
        val contentWidth = maxX - minX + 1
        val contentHeight = maxY - minY + 1
        // 内容已经铺满整张位图，无需处理。
        if (contentWidth >= width && contentHeight >= height) return bitmap
        val side = maxOf(contentWidth, contentHeight)
        val output = Bitmap.createBitmap(side, side, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(output)
        val left = (side - contentWidth) / 2
        val top = (side - contentHeight) / 2
        canvas.drawBitmap(
            readable,
            Rect(minX, minY, maxX + 1, maxY + 1),
            Rect(left, top, left + contentWidth, top + contentHeight),
            Paint(Paint.FILTER_BITMAP_FLAG),
        )
        logger(
            Log.INFO,
            "RADIAL_ICON_FILL $width x $height -> content $contentWidth x $contentHeight -> $side",
            null,
        )
        return output
    }

    private fun reportFailure(cause: Throwable) {
        if (!failureReported) {
            failureReported = true
            logger(Log.WARN, "RADIAL_NATIVE_ICON_SHAPE_UNAVAILABLE", cause)
        }
    }

    private class NativeAccess {
        val extension = AdaptiveIconDrawable::class.java.getField("mIconDrawableExt")
        private val extensionClass = Class.forName("android.graphics.drawable.AdaptiveIconDrawableExtImpl")
        val config = extensionClass.getDeclaredField("mConfig").apply { isAccessible = true }
        private val configClass = Class.forName("android.app.uxicons.CustomAdaptiveIconConfig")
        val foregroundScale = configClass.getMethod("getForegroundScalePercent")
        val platform = configClass.getMethod("getIsPlatformDrawable")
        val adaptive = configClass.getMethod("getIsAdaptiveIconDrawable")
        val maximumSize = Class.forName("com.oplus.util.UxScreenUtil")
            .getMethod("getMaxIconSize", Resources::class.java)
        val build = extensionClass.getMethod(
            "buildAdaptiveIconDrawable",
            Resources::class.java,
            Int::class.javaPrimitiveType,
            Int::class.javaPrimitiveType,
            Path::class.java,
            Boolean::class.javaPrimitiveType,
            Boolean::class.javaPrimitiveType,
        )
    }

    private companion object {
        const val MASK_VIEWPORT = 150f

        /** @author bomo 判定为"内容"的最小 alpha（0-255）；低于此值视为透明安全区留白。 */
        const val CONTENT_ALPHA_THRESHOLD = 8

        /**
         * @author bomo 图标内容填充系数。
         * 1f = 不再保留 ColorOS 的前景安全区缩放，让图标内容填满外框（再由 mask 裁成圆形），
         * 解决"方形图标在圆形容器内偏小、视觉上像菱形"的问题。
         * 若仍需继续放大，可上调此值（>1 会开始裁掉图标边缘内容，注意与选中圈留白冲突）。
         */
        const val ICON_FILL_SCALE = 1f
    }
}
