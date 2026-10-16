package com.crowdlevel.node.aggregate

import org.junit.Assert.assertEquals
import org.junit.Test

class AdvertisementInfoTest {
    @Test
    fun `16bitのUUIDは4桁に縮める`() {
        assertEquals("fe9f", AdvertisementInfo.shortUuid("0000FE9F-0000-1000-8000-00805F9B34FB"))
        assertEquals("12345678-1234-1234-1234-123456789abc", AdvertisementInfo.shortUuid("12345678-1234-1234-1234-123456789ABC"))
    }

    @Test
    fun `Appleのデータから種類の番号だけを並べる`() {
        // 0x10（長さ3）と 0x0C（長さ2）
        val data = byteArrayOf(0x10, 0x03, 0x01, 0x02, 0x03, 0x0C, 0x02, 0x11, 0x22)
        assertEquals(listOf(0x10, 0x0C), AdvertisementInfo.appleTypes(data))
    }

    @Test
    fun `形が崩れていたらそこまでで打ち切る`() {
        assertEquals(listOf(0x12), AdvertisementInfo.appleTypes(byteArrayOf(0x12, 0x01, 0x00, 0x07, 0x19, 0x01)))
        assertEquals(emptyList<Int>(), AdvertisementInfo.appleTypes(byteArrayOf()))
        assertEquals(emptyList<Int>(), AdvertisementInfo.appleTypes(byteArrayOf(0x10)))
    }

    @Test
    fun `窓の中で見えた値は端末ごとに和集合になる`() {
        val agg = WindowAggregator(0)
        agg.add(ScanEvent("a", -60, 0x4C, 1_000, appleTypes = listOf(0x10)))
        agg.add(ScanEvent("a", -62, 0x4C, 2_000, appleTypes = listOf(0x12), serviceUuids = listOf("fd6f")))
        val d = agg.flush(60_000)!!.devices.single()
        assertEquals(listOf(0x10, 0x12), d.appleTypes)
        assertEquals(listOf("fd6f"), d.serviceUuids)
    }
}
