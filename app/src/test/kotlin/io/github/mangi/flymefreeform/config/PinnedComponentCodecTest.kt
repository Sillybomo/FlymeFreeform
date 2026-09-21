package io.github.mangi.flymefreeform.config

import org.junit.Assert.assertEquals
import org.junit.Test

class PinnedComponentCodecTest {
    @Test
    fun ignoresMalformedAndDuplicateLinesAndCapsAtMaxPinned() {
        val raw = """
            a/.A
            invalid
            a/.A
            b/.B
            c/.C
            d/.D
            e/.E
            f/.F
            g/.G
            h/.H
            i/.I
            j/.J
            k/.K
        """.trimIndent()
        // 上限随之变为 9（9 个固定应用 + 「更多」= 10 个槽位）。
        assertEquals(
            listOf("a/.A", "b/.B", "c/.C", "d/.D", "e/.E", "f/.F", "g/.G", "h/.H", "i/.I"),
            PinnedComponentCodec.decodeRaw(raw),
        )
    }

    @Test
    fun explicitLimitOverridesThePinnedCap() {
        val raw = listOf("a/.A", "b/.B", "c/.C").joinToString("\n")
        assertEquals(listOf("a/.A", "b/.B"), PinnedComponentCodec.decodeRaw(raw, limit = 2))
    }

    @Test
    fun anExistingEmptyValueRemainsAnExplicitEmptyList() {
        assertEquals(emptyList<String>(), PinnedComponentCodec.decodeRaw(""))
    }
}
