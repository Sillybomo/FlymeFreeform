package io.github.mangi.flymefreeform.gesture

import io.github.mangi.flymefreeform.config.ModulePreferences
import io.github.mangi.flymefreeform.config.ModuleSettingsSnapshot
import io.github.mangi.flymefreeform.config.TriggerShape
import kotlin.math.hypot
import kotlin.math.max

/**
 * 角落触发热区（trigger hot zone）的命中几何。
 *
 * @author bomo 统一方向约定：判定时一律传入
 * - `inwardFromEdge`：横向，**离开该侧屏幕边缘**的距离（左侧＝x，右侧＝屏宽−x）
 * - `fromBottom`：纵向，**离开屏幕底边**的距离（屏高−y）
 *
 * 这样左右两侧共用同一套几何，只需一次镜像。
 *
 * @param horizontalPx 三角形横向长度（沿底边，px）
 * @param verticalPx 三角形纵向高度（沿侧边，px）
 * @param radiusPx 扇形半径（px）
 */
internal data class TriggerHitZone(
    val shape: TriggerShape,
    val horizontalPx: Float,
    val verticalPx: Float,
    val radiusPx: Float,
) {
    /**
     * 纵向预筛上限：任何形状的纵向延伸都不会超过「纵向长度」与「半径」中的较大者。
     * 用于在逐点判定前先排掉远离底边的绝大多数触摸事件；
     * 宿主角落输入窗口的边长取它与 [horizontalLimitPx] 的较大者。
     */
    val verticalLimitPx: Float = max(verticalPx, radiusPx)

    /** 横向预筛上限，同理（供宿主决定角落输入窗口要开多大）。 */
    val horizontalLimitPx: Float = max(horizontalPx, radiusPx)

    companion object {
        /**
         * @author bomo 纯扇形热区（半径 px）。扇形不使用横/纵长度，
         * 故三者同值也不会影响命中判定与包围盒。
         */
        fun sector(radiusPx: Float): TriggerHitZone =
            TriggerHitZone(
                shape = TriggerShape.Sector,
                horizontalPx = radiusPx,
                verticalPx = radiusPx,
                radiusPx = radiusPx,
            )
    }

    /**
     * 点是否落在热区内。
     *
     * @return 落在热区内为 true；越界、负值一律 false
     */
    fun contains(inwardFromEdge: Float, fromBottom: Float): Boolean {
        if (fromBottom < 0f || fromBottom > verticalLimitPx) return false
        if (inwardFromEdge < 0f) return false
        return when (shape) {
            TriggerShape.Sector -> hypot(inwardFromEdge, fromBottom) <= radiusPx
            TriggerShape.Triangle ->
                inwardFromEdge <= horizontalPx &&
                    fromBottom <= verticalPx &&
                    // 斜边：x/宽 + y/高 ≤ 1（宽、高均被钳制为 > 0，不存在除零）
                    (inwardFromEdge / horizontalPx + fromBottom / verticalPx) <= 1f
        }
    }
}

/** 左右下角共享的四分之一圆/三角起点区域。 */
internal object CornerTriggerRegion {
    fun radiusPx(rangeDp: Int, density: Float): Float {
        val safeDensity = density.takeIf { it.isFinite() && it > 0f } ?: 1f
        return ModulePreferences.coerceCornerTriggerRangeDp(rangeDp) * safeDensity
    }

    /**
     * 构造热区几何（px）。
     *
     * @param density 屏幕密度；非法值（非有限值或 ≤0）按 1 兜底
     */
    fun zone(
        shape: TriggerShape,
        triggerRangeDp: Int,
        horizontalDp: Int,
        verticalDp: Int,
        density: Float,
    ): TriggerHitZone {
        val safeDensity = density.takeIf { it.isFinite() && it > 0f } ?: 1f
        return TriggerHitZone(
            shape = shape,
            horizontalPx = ModulePreferences.coerceTriggerExtentDp(horizontalDp) * safeDensity,
            verticalPx = ModulePreferences.coerceTriggerExtentDp(verticalDp) * safeDensity,
            radiusPx = ModulePreferences.coerceCornerTriggerRangeDp(triggerRangeDp) * safeDensity,
        )
    }

    /**
     * @author bomo 由配置快照 + 屏幕密度构造热区。
     * 三处宿主（systemui 角落窗口 / launcher 抢占 / system_server 指针监听）共用，
     * 避免「怎么把设置换算成热区」的口径在三个进程里漂移。
     */
    fun of(settings: ModuleSettingsSnapshot, density: Float): TriggerHitZone =
        zone(
            shape = settings.triggerShape,
            triggerRangeDp = settings.cornerTriggerRangeDp,
            horizontalDp = settings.triggerHorizontalDp,
            verticalDp = settings.triggerVerticalDp,
            density = density,
        )

    /**
     * 判断落点属于哪个角落热区。
     *
     * @return 命中的一侧；两侧都不命中（或在热区外）返回 null
     */
    fun detectSide(
        x: Float,
        y: Float,
        displayWidth: Float,
        displayHeight: Float,
        zone: TriggerHitZone,
        leftEnabled: Boolean,
        rightEnabled: Boolean,
    ): CornerSide? {
        val fromBottom = displayHeight - y
        if (leftEnabled && zone.contains(x, fromBottom)) {
            return CornerSide.Left
        }
        if (rightEnabled && zone.contains(displayWidth - x, fromBottom)) {
            return CornerSide.Right
        }
        return null
    }
}
