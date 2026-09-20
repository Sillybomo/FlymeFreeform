package io.github.mangi.flymefreeform.window

/** 扇形与图标共享屏幕比例；条目数量只改变角度，不改变半径。 */
internal object RadialIconGeometry {
    private const val BASE_SHORT_EDGE_DP = 400f

    // 7 条目 84° 弧、槽位间隔 12°：相邻圆心距 = 2R·sin(6°) ≈ 0.209R。
    // 硬约束 d + 留白 ≤ 0.209R，即图标/半径比上限约 0.209（原值 44/242 = 0.182）。
    // 236/47.5/1.5 组合：弦长 49.34dp ≥ 47.5+1.5，图标比 0.201（相对 +11%），
    // 图标绝对 +8%、容器 −2.5%；留白 1.5dp 是选中圈外扩的最小安全距离，再小会贴到相邻图标。
    private const val BASE_RADIUS_DP = 236f
    private const val BASE_ICON_DIAMETER_DP = 47.5f
    private const val BASE_ITEM_PADDING_DP = 1.5f

    fun fit(
        width: Float,
        height: Float,
        density: Float,
        safeInsets: OverlaySafeInsets,
        itemCount: Int,
    ): RadialVisualMetrics {
        require(width.isFinite() && width > 0f && height.isFinite() && height > 0f)
        require(density.isFinite() && density > 0f)
        require(itemCount in 1..7)
        val safeWidth = (width - safeInsets.left - safeInsets.right).coerceAtLeast(0f)
        val safeHeight = (height - safeInsets.top - safeInsets.bottom).coerceAtLeast(0f)
        val windowScale = (minOf(width, height) / density) / BASE_SHORT_EDGE_DP
        val pixelsPerBaseDp = density * windowScale
        // 为整个四分之一圆弧保留同一外缘，避免增删条目时安全区适配改变半径。
        // 外缘包含入场回摆和选中外圈；选中不改变图标大小。
        val requestedExtent =
            (BASE_RADIUS_DP + BASE_ICON_DIAMETER_DP * 1.05f / 2f + BASE_ITEM_PADDING_DP +
                RadialEntryMotion.HORIZONTAL_OVERSHOOT_DP) * pixelsPerBaseDp
        val fitScale = minOf(1f, safeWidth / requestedExtent, safeHeight / requestedExtent)
        val unit = pixelsPerBaseDp * fitScale
        val diameter = BASE_ICON_DIAMETER_DP * unit
        return RadialVisualMetrics(
            radius = BASE_RADIUS_DP * unit,
            plateDiameter = diameter,
            iconDiameter = diameter,
            selectionEnterRadius = diameter * 0.9f,
            selectionKeepRadius = diameter * 1.25f,
            itemPadding = BASE_ITEM_PADDING_DP * unit,
            pixelsPerBaseDp = unit,
        )
    }
}
