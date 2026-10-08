package com.crowdlevel.node.aggregate

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SummariesTest {
    private fun dev(hash: String, rssi: Int, cid: Int? = null) =
        DeviceSummary(hash = hash, maxRssi = rssi, advCount = 1, companyId = cid)

    @Test
    fun `RSSI閾値別カウントは累積で、allを含む`() {
        val devices = listOf(
            dev("a", -55), dev("b", -60), dev("c", -65), dev("d", -70),
            dev("e", -79), dev("f", -85), dev("g", -95),
        )
        val counts = Summaries.countsByRssi(devices)
        assertEquals(2, counts["-60"])   // -55, -60
        assertEquals(4, counts["-70"])   // + -65, -70
        assertEquals(5, counts["-80"])   // + -79
        assertEquals(6, counts["-90"])   // + -85
        assertEquals(7, counts["all"])
        assertEquals(listOf("-60", "-70", "-80", "-90", "all"), counts.keys.toList())
    }

    @Test
    fun `メーカー別カウントは全カテゴリのキーを持つ`() {
        val devices = listOf(
            dev("a", -70, 0x004C), dev("b", -70, 0x004C), dev("c", -70, 0x00E0),
            dev("d", -70, 0x9999), dev("e", -70, null),
        )
        val counts = Summaries.countsByVendor(devices)
        assertEquals(2, counts["apple"])
        assertEquals(1, counts["google"])
        assertEquals(0, counts["microsoft"])
        assertEquals(0, counts["samsung"])
        assertEquals(1, counts["other"])
        assertEquals(1, counts["none"])
        assertEquals(listOf("apple", "google", "microsoft", "samsung", "other", "none"), counts.keys.toList())
    }

    @Test
    fun `RSSI統計は端末ごとの最大RSSIから計算する`() {
        val devices = (0..10).map { dev("d$it", -100 + it * 4) } // -100, -96, ..., -60
        val stats = Summaries.rssiStats(devices)!!
        assertEquals(-80, stats.median)
        assertEquals(-96, stats.p10)
        assertEquals(-64, stats.p90)
    }

    @Test
    fun `端末が1台なら全ての統計がその値`() {
        val stats = Summaries.rssiStats(listOf(dev("a", -73)))!!
        assertEquals(-73, stats.median)
        assertEquals(-73, stats.p10)
        assertEquals(-73, stats.p90)
    }

    @Test
    fun `端末が2台なら中央値は線形補間して丸める`() {
        val stats = Summaries.rssiStats(listOf(dev("a", -80), dev("b", -61)))!!
        assertEquals(-70, stats.median) // -70.5 → -70（四捨五入は 0 方向ではなく最近接）
    }

    @Test
    fun `端末がなければ統計はnull`() {
        assertNull(Summaries.rssiStats(emptyList()))
    }
}
