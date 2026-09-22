package io.github.mangi.flymefreeform.gesture

import io.github.mangi.flymefreeform.config.ModulePreferences
import io.github.mangi.flymefreeform.config.TriggerShape
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CornerTriggerRegionTest {
    @Test
    fun radiusUsesClampedDpAndDensity() {
        assertEquals(48f, CornerTriggerRegion.radiusPx(8, 2f), 0f)
        assertEquals(168f, CornerTriggerRegion.radiusPx(84, 2f), 0f)
        assertEquals(320f, CornerTriggerRegion.radiusPx(200, 2f), 0f)
    }

    @Test
    fun detectsMirroredQuarterCirclesAndHonorsSideSwitches() {
        val zone = TriggerHitZone.sector(50f)
        assertEquals(
            CornerSide.Left,
            CornerTriggerRegion.detectSide(30f, 1960f, 1000f, 2000f, zone, true, true),
        )
        assertEquals(
            CornerSide.Right,
            CornerTriggerRegion.detectSide(970f, 1960f, 1000f, 2000f, zone, true, true),
        )
        assertNull(
            CornerTriggerRegion.detectSide(30f, 1960f, 1000f, 2000f, zone, false, true),
        )
        assertNull(
            CornerTriggerRegion.detectSide(40f, 1960f, 1000f, 2000f, zone, true, true),
        )
    }

    /** @author bomo 三角形：横长纵短时，可沿底边横向拉出很远，但纵向很快收窄出区。 */
    @Test
    fun triangleSpansWideBottomEdgeButStaysShortVertically() {
        val zone =
            CornerTriggerRegion.zone(
                shape = TriggerShape.Triangle,
                triggerRangeDp = 84,
                horizontalDp = 160,
                verticalDp = 40,
                density = 2f,
            )
        // 横向 160dp×2 = 320px、纵向 40dp×2 = 80px；斜边 x/320 + y/80 ≤ 1
        assertTrue(zone.contains(200f, 20f))
        assertFalse(zone.contains(300f, 20f))
        assertTrue(zone.contains(0f, 80f))
        assertFalse(zone.contains(10f, 80f))
        // 超过纵向高度直接出区（即使贴着侧边）
        assertFalse(zone.contains(0f, 80.5f))
    }

    /** @author bomo 包围盒：宿主开窗尺寸取 max(横向, 纵向, 半径)，否则宽高形同虚设。 */
    @Test
    fun boundingBoxCoversBothExtentsAndRadius() {
        val zone =
            CornerTriggerRegion.zone(
                shape = TriggerShape.Triangle,
                triggerRangeDp = 84,
                horizontalDp = 160,
                verticalDp = 40,
                density = 2f,
            )

        assertEquals(320f, zone.horizontalLimitPx, 0f)
        assertEquals(168f, zone.verticalLimitPx, 0f)
    }

    /** @author bomo 横向 / 纵向上限与扇形半径上限一致（用户 2026-09-22 定），越界值钳到上限。 */
    @Test
    fun extentLimitsMatchSectorRadiusLimits() {
        assertEquals(
            ModulePreferences.MAX_CORNER_TRIGGER_RANGE_DP,
            ModulePreferences.MAX_TRIGGER_EXTENT_DP,
        )
        assertEquals(
            ModulePreferences.MIN_CORNER_TRIGGER_RANGE_DP,
            ModulePreferences.MIN_TRIGGER_EXTENT_DP,
        )
        assertEquals(
            ModulePreferences.MAX_TRIGGER_EXTENT_DP,
            ModulePreferences.coerceTriggerExtentDp(ModulePreferences.MAX_TRIGGER_EXTENT_DP + 999),
        )
        assertEquals(
            ModulePreferences.MIN_TRIGGER_EXTENT_DP,
            ModulePreferences.coerceTriggerExtentDp(0),
        )
    }

    /** @author bomo 未知存储值必须回退扇形，避免热区形状损坏导致手势整体失效。 */
    @Test
    fun unknownShapeFallsBackToSector() {
        assertEquals(TriggerShape.Sector, TriggerShape.fromStoredValue(99))
        assertEquals(TriggerShape.Triangle, TriggerShape.fromStoredValue(1))
    }
}
