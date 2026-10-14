package com.crowdlevel.node.upload

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AmbientBatcherTest {
    /** 2026-10-08 10:00:00 JST（偶数分・3分の倍数の境界） */
    private val t0 = 1_791_421_200_000L
    private fun min(n: Int) = t0 + n * 60_000L

    @Test
    fun `間隔1分なら窓ごとにそのまま1点`() {
        val b = AmbientBatcher(1)
        assertEquals(listOf(AmbientBatcher.Point(min(0), 5)), b.add(min(0), 5))
        assertEquals(listOf(AmbientBatcher.Point(min(1), 7)), b.add(min(1), 7))
    }

    @Test
    fun `間隔1分で送らない窓は点を作らない`() {
        val b = AmbientBatcher(1)
        assertTrue(b.add(min(0), null).isEmpty())
        assertEquals(listOf(AmbientBatcher.Point(min(1), 3)), b.add(min(1), 3))
    }

    @Test
    fun `間隔2分なら区間の最後の窓で平均を1点にする`() {
        val b = AmbientBatcher(2)
        assertTrue(b.add(min(0), 10).isEmpty())
        assertEquals(listOf(AmbientBatcher.Point(min(0), 12)), b.add(min(1), 13)) // (10 + 13) / 2 = 11.5
    }

    @Test
    fun `平均は四捨五入`() {
        val b = AmbientBatcher(3)
        b.add(min(0), 1)
        b.add(min(1), 2)
        assertEquals(listOf(AmbientBatcher.Point(min(0), 2)), b.add(min(2), 2)) // 5/3 = 1.67
    }

    @Test
    fun `区間の途中から始まっても区間の開始時刻を付ける`() {
        val b = AmbientBatcher(3)
        assertEquals(listOf(AmbientBatcher.Point(min(0), 4)), b.add(min(2), 4))
    }

    @Test
    fun `部分的な窓は平均に入れないが区間は閉じる`() {
        val b = AmbientBatcher(2)
        b.add(min(0), 8)
        assertEquals(listOf(AmbientBatcher.Point(min(0), 8)), b.add(min(1), null))
    }

    @Test
    fun `区間の窓がすべて送らない窓なら点を作らない`() {
        val b = AmbientBatcher(2)
        assertTrue(b.add(min(0), null).isEmpty())
        assertTrue(b.add(min(1), null).isEmpty())
    }

    @Test
    fun `区間の最後の窓が飛んだら次の区間の窓で前の区間を閉じる`() {
        val b = AmbientBatcher(2)
        b.add(min(0), 6)
        // min(1) が来ないまま次の区間へ
        assertEquals(listOf(AmbientBatcher.Point(min(0), 6)), b.add(min(2), 9))
        assertEquals(listOf(AmbientBatcher.Point(min(2), 8)), b.add(min(3), 7)) // (9 + 7) / 2
    }
}
