package io.github.mangi.flymefreeform.config

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 工具目录编解码的纯逻辑测试。
 * 注意：`android.util.Base64` 在单元测试里是未被 mock 的空实现，因此这里只做
 * 「不含图标」与「非法行」的往返校验，图标字段单独断言为空。
 */
class ToolCatalogCodecTest {
    @Test
    fun roundTripsAliasLabelAndAvailability() {
        val encoded =
            ToolCatalogCodec.encode(
                listOf(
                    ToolCatalogCodec.Record("screen_identify", "小布识屏", true, null),
                    ToolCatalogCodec.Record("global_translation", "屏幕翻译", false, null),
                ),
            )
        val decoded = ToolCatalogCodec.decode(encoded)
        assertEquals(2, decoded.size)
        assertEquals("screen_identify", decoded[0].alias)
        assertEquals("小布识屏", decoded[0].label)
        assertTrue(decoded[0].available)
        assertEquals("屏幕翻译", decoded[1].label)
        assertEquals(false, decoded[1].available)
    }

    @Test
    fun skipsMalformedLinesAndDuplicateAliases() {
        val raw =
            listOf(
                "a\t工具A\t1\t",
                "broken-line",
                "a\t重复\t1\t",
                "\t空别名\t1\t",
                "b\t工具B\t0\t",
            ).joinToString("\n")
        val decoded = ToolCatalogCodec.decode(raw)
        assertEquals(listOf("a", "b"), decoded.map { it.alias })
    }

    @Test
    fun emptyOrBrokenContentDecodesToEmptyList() {
        assertEquals(emptyList<ToolCatalogCodec.Record>(), ToolCatalogCodec.decode(null))
        assertEquals(emptyList<ToolCatalogCodec.Record>(), ToolCatalogCodec.decode(""))
    }

    @Test
    fun labelSanitizesTabsAndNewlines() {
        val encoded =
            ToolCatalogCodec.encode(
                listOf(ToolCatalogCodec.Record("alias", "行内\t制表", true, null)),
            )
        assertTrue(encoded.count { it == '\t' } == 3)
        assertEquals("行内 制表", ToolCatalogCodec.decode(encoded).first().label)
    }
}
