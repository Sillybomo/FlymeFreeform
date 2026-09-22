package io.github.mangi.flymefreeform.window

import kotlin.math.PI
import kotlin.math.sin

/** 扇形与图标共享屏幕比例；条目多到挤不下时按弦长收紧图标，而不是互叠或截断。 */
internal object RadialIconGeometry {
    private const val BASE_SHORT_EDGE_DP = 400f

    private const val BASE_RADIUS_DP = 236f
    private const val BASE_ICON_DIAMETER_DP = 47.5f
    private const val BASE_ITEM_PADDING_DP = 1.5f

    /** 与 RadialGeometry.SPAN_DEGREES 一致；槽位角 = SPAN / 条目数。 */
    private const val SPAN_DEGREES = 84f

    /**
     * 图标下限：再小就点不准。到下限后宁可允许轻微互叠，也不继续缩小。
     * 10 槽位（9 应用 + 更多）时弦长约 34.6dp，仍高于该下限。
     */
    private const val MIN_ICON_DIAMETER_DP = 30f

    /**
     * 内圈与外圈图标之间的径向间隙（dp，按图标边缘到边缘计）。
     *
     * @author bomo 由 12dp 逐步调到 18dp（用户 2026-09-22 实机连试 24 / 16 / 20 后定稿：20 偏大、16 略紧）。
     * 该间隙在算法里是**恒等量**（与图标直径无关），改这个值即精确生效。
     *
     * 为什么 12dp 会"粘"住：两圈中心距 = (外圈直径 + 内圈直径) / 2 + 间隙，
     * 而选中判定（`RadialGeometry.selection`）只要手指仍在原选中槽位的
     * `keepRadius`（= 图标直径 × 1.25）内就保持不变。图标同为 47.5dp 时：
     * `中心距 − keepRadius = 间隙 − 0.25 × 直径 = 间隙 − 11.875dp`，
     * 12dp 只余 0.125dp（等于没有，跨圈必然切不过去），18dp 余 6.125dp（手感均衡）。
     * 不宜超过 ~30dp：内圈半径随之间变小，内圈相邻弦长不足会把内圈图标压小。
     */
    private const val RING_GAP_DP = 18f

    fun fit(
        width: Float,
        height: Float,
        density: Float,
        safeInsets: OverlaySafeInsets,
        itemCount: Int,
        innerCount: Int = 0,
    ): RadialVisualMetrics {
        require(width.isFinite() && width > 0f && height.isFinite() && height > 0f)
        require(density.isFinite() && density > 0f)
        require(itemCount >= 1)
        val safeWidth = (width - safeInsets.left - safeInsets.right).coerceAtLeast(0f)
        val safeHeight = (height - safeInsets.top - safeInsets.bottom).coerceAtLeast(0f)
        val windowScale = (minOf(width, height) / density) / BASE_SHORT_EDGE_DP
        val pixelsPerBaseDp = density * windowScale
        // 相邻圆心距 = 2R·sin(槽位角/2)，图标直径 + 留白不得超过它，否则相邻图标互叠、滑选歧义。
        // 7 槽位时弦长 49.34dp，允许 47.5dp；槽位变多则按弦长收紧。
        val slotDegrees = SPAN_DEGREES / itemCount
        // 0.995 系留 0.5% 浮点余量，避免恰好取等时相邻图标判为重叠。
        val chordDp =
            2f * BASE_RADIUS_DP * sin(slotDegrees / 2f * PI.toFloat() / 180f) * 0.995f
        val maxDiameterDp =
            (chordDp - BASE_ITEM_PADDING_DP).coerceAtLeast(MIN_ICON_DIAMETER_DP)
        val diameterDp = BASE_ICON_DIAMETER_DP.coerceAtMost(maxDiameterDp)
        // 为整个四分之一圆弧保留同一外缘，避免增删条目时安全区适配改变半径。
        // 外缘包含入场回摆和选中外圈；选中不改变图标大小。
        val requestedExtent =
            (BASE_RADIUS_DP + diameterDp * 1.05f / 2f + BASE_ITEM_PADDING_DP +
                RadialEntryMotion.HORIZONTAL_OVERSHOOT_DP) * pixelsPerBaseDp
        val fitScale = minOf(1f, safeWidth / requestedExtent, safeHeight / requestedExtent)
        val unit = pixelsPerBaseDp * fitScale
        val diameter = diameterDp * unit
        // 内圈：图标与外圈同尺寸（不缩小），半径 = 外圈半径 − 两圈图标半径和 − 间隙；
        // 外圈完全不动，只把内圈整体内移。内圈唯一收紧手段是自身相邻弦长
        // （内圈条目数增多时弦长变短，先按等大假设估算半径，一轮近似即可）。
        var innerRadius = 0f
        var innerDiameter = 0f
        if (innerCount > 0) {
            val innerSlotDegrees = SPAN_DEGREES / innerCount
            val innerChordDp =
                2f * (BASE_RADIUS_DP - diameterDp - RING_GAP_DP) *
                    sin(innerSlotDegrees / 2f * PI.toFloat() / 180f) * 0.995f
            val innerDiameterDp =
                diameterDp.coerceAtMost(
                    (innerChordDp - BASE_ITEM_PADDING_DP).coerceAtLeast(MIN_ICON_DIAMETER_DP),
                )
            innerRadius = (BASE_RADIUS_DP - (diameterDp + innerDiameterDp) / 2f - RING_GAP_DP) * unit
            innerDiameter = innerDiameterDp * unit
        }
        return RadialVisualMetrics(
            radius = BASE_RADIUS_DP * unit,
            plateDiameter = diameter,
            iconDiameter = diameter,
            selectionEnterRadius = diameter * 0.9f,
            selectionKeepRadius = diameter * 1.25f,
            itemPadding = BASE_ITEM_PADDING_DP * unit,
            pixelsPerBaseDp = unit,
            innerRadius = innerRadius,
            innerIconDiameter = innerDiameter,
        )
    }
}
