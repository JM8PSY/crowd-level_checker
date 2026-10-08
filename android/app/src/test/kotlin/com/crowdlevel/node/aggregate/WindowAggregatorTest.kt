package com.crowdlevel.node.aggregate

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class WindowAggregatorTest {
    // 2026-10-08T10:15:00+09:00 = 2026-10-08T01:15:00Z
    private val t0 = 1_791_422_100_000L
    private val minute = 60_000L

    private fun ev(hash: String, rssi: Int, cid: Int? = null, at: Long = t0) =
        ScanEvent(hash = hash, rssi = rssi, companyId = cid, epochMillis = at)

    @Test
    fun `窓の開始時刻は分境界に揃う`() {
        assertEquals(t0, WindowAggregator.windowStartOf(t0 + 37_500, minute))
        assertEquals(t0, WindowAggregator.windowStartOf(t0, minute))
        assertEquals(t0 + minute, WindowAggregator.windowStartOf(t0 + minute, minute))
    }

    @Test
    fun `窓の途中では何も確定しない`() {
        val agg = WindowAggregator(startMillis = t0)
        assertNull(agg.add(ev("a", -70)))
        assertNull(agg.add(ev("b", -80, at = t0 + 30_000)))
        assertNull(agg.flush(t0 + 59_999))
    }

    @Test
    fun `flushで境界を越えると窓が確定する`() {
        val agg = WindowAggregator(startMillis = t0)
        agg.add(ev("a", -70))
        agg.add(ev("b", -80))
        val result = agg.flush(t0 + minute)
        assertNotNull(result)
        result!!
        assertEquals(t0, result.windowStartMillis)
        assertEquals(60, result.windowSec)
        assertEquals(2, result.devices.size)
        assertEquals(2, result.advCount)
        assertEquals(t0 + minute, agg.currentWindowStart)
    }

    @Test
    fun `同じ端末は最大RSSIと受信回数でまとめる`() {
        val agg = WindowAggregator(startMillis = t0)
        agg.add(ev("a", -80))
        agg.add(ev("a", -65))
        agg.add(ev("a", -90))
        val result = agg.flush(t0 + minute)!!
        assertEquals(1, result.devices.size)
        assertEquals(-65, result.devices[0].maxRssi)
        assertEquals(3, result.devices[0].advCount)
        assertEquals(3, result.advCount)
    }

    @Test
    fun `端末はRSSI降順に並ぶ`() {
        val agg = WindowAggregator(startMillis = t0)
        agg.add(ev("weak", -90))
        agg.add(ev("strong", -55))
        agg.add(ev("mid", -75))
        val hashes = agg.flush(t0 + minute)!!.devices.map { it.hash }
        assertEquals(listOf("strong", "mid", "weak"), hashes)
    }

    @Test
    fun `Company IDはメーカーデータを含む最後の観測を採用し、なしでは上書きしない`() {
        val agg = WindowAggregator(startMillis = t0)
        agg.add(ev("a", -70, cid = 0x004C))
        agg.add(ev("a", -70, cid = null))
        agg.add(ev("b", -70, cid = 0x0006))
        agg.add(ev("b", -70, cid = 0x0075))
        val devices = agg.flush(t0 + minute)!!.devices.associateBy { it.hash }
        assertEquals(0x004C, devices["a"]!!.companyId)
        assertEquals(0x0075, devices["b"]!!.companyId)
    }

    @Test
    fun `境界を越えたイベントのaddは前の窓を確定し、そのイベントは新しい窓に入る`() {
        val agg = WindowAggregator(startMillis = t0)
        agg.add(ev("a", -70))
        val closed = agg.add(ev("b", -60, at = t0 + minute + 5_000))
        assertNotNull(closed)
        assertEquals(listOf("a"), closed!!.devices.map { it.hash })
        assertEquals(t0 + minute, agg.currentWindowStart)
        val next = agg.flush(t0 + 2 * minute)!!
        assertEquals(listOf("b"), next.devices.map { it.hash })
    }

    @Test
    fun `空の窓もゼロ件として確定する`() {
        val agg = WindowAggregator(startMillis = t0)
        val result = agg.flush(t0 + minute)
        assertNotNull(result)
        assertTrue(result!!.devices.isEmpty())
        assertEquals(0, result.advCount)
    }

    @Test
    fun `複数窓ぶん時間が飛んだら現在時刻の窓に移る`() {
        val agg = WindowAggregator(startMillis = t0)
        agg.flush(t0 + 5 * minute + 10_000)
        assertEquals(t0 + 5 * minute, agg.currentWindowStart)
    }

    @Test
    fun `現在の窓より古いイベントは現在の窓に入れる`() {
        val agg = WindowAggregator(startMillis = t0)
        assertNull(agg.add(ev("old", -70, at = t0 - 10_000)))
        assertEquals(1, agg.flush(t0 + minute)!!.devices.size)
    }

    @Test
    fun `通常の窓はcompleteになる`() {
        val agg = WindowAggregator(startMillis = t0)
        agg.add(ev("a", -70))
        assertTrue(agg.flush(t0 + minute)!!.complete)
    }

    @Test
    fun `markIncompleteした窓はcompleteがfalseになり、次の窓では元に戻る`() {
        val agg = WindowAggregator(startMillis = t0)
        agg.add(ev("a", -70))
        agg.markIncomplete()
        assertFalse(agg.flush(t0 + minute)!!.complete)
        assertTrue(agg.flush(t0 + 2 * minute)!!.complete)
    }

    @Test
    fun `addで窓が切り替わってもmarkIncompleteは閉じた窓にだけ効く`() {
        val agg = WindowAggregator(startMillis = t0)
        agg.markIncomplete()
        val closed = agg.add(ev("b", -60, at = t0 + minute + 1_000))!!
        assertFalse(closed.complete)
        assertTrue(agg.flush(t0 + 2 * minute)!!.complete)
    }
}
