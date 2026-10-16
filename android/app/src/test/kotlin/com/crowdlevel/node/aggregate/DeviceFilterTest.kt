package com.crowdlevel.node.aggregate

import com.crowdlevel.node.aggregate.DeviceFilter.Reason
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.ZoneId

class DeviceFilterTest {
    private val apple = 0x004C

    @Test
    fun `探すだけ・iBeaconだけのAppleのアドレスは数えない`() {
        assertEquals(Reason.FIND_MY, DeviceFilter.excludeReason(apple, listOf(0x12)))
        assertEquals(Reason.IBEACON, DeviceFilter.excludeReason(apple, listOf(0x02)))
        assertEquals(Reason.FIND_MY, DeviceFilter.excludeReason(apple, listOf(0x02, 0x12)))
    }

    @Test
    fun `ほかの種類も出していれば数える`() {
        assertNull(DeviceFilter.excludeReason(apple, listOf(0x10)))
        assertNull(DeviceFilter.excludeReason(apple, listOf(0x10, 0x12)))
        assertNull(DeviceFilter.excludeReason(apple, listOf(0x16)))
    }

    @Test
    fun `種類が分からない端末とApple以外は数える`() {
        assertNull(DeviceFilter.excludeReason(apple, emptyList()))
        assertNull(DeviceFilter.excludeReason(null, emptyList()))
        assertNull(DeviceFilter.excludeReason(0x0006, listOf(0x12)))
    }

    @Test
    fun `7aの実測の窓では8台が4台になり、外した端末はログに理由付きで残る`() {
        // 2026-10-17 01:10 の窓の Apple の端末（電波の強さ, 受信回数, 種類）
        val devices = listOf(
            DeviceSummary("a1", -57, 144, apple, appleTypes = listOf(0x10)),
            DeviceSummary("a2", -58, 25, apple, appleTypes = listOf(0x12)),
            DeviceSummary("b1", -67, 114, apple, appleTypes = listOf(0x10)),
            DeviceSummary("b2", -68, 25, apple, appleTypes = listOf(0x12)),
            DeviceSummary("c1", -74, 115, apple, appleTypes = listOf(0x00, 0x01, 0x10)),
            DeviceSummary("c2", -74, 22, apple, appleTypes = listOf(0x12)),
            DeviceSummary("d1", -76, 24, apple, appleTypes = listOf(0x16)),
            DeviceSummary("e1", -88, 266, apple, appleTypes = listOf(0x02)),
            DeviceSummary("x1", -70, 30, null),
        )
        val result = WindowResult(0, 60, devices, advCount = 765)
        assertEquals(mapOf("findmy" to 3, "ibeacon" to 1), result.excludedCounts)

        val s = WindowSummary.from(result, "n", ZoneId.of("Asia/Tokyo"), null, null, "0.1.0", includeDevices = true)
        assertEquals(4, s.countsByVendor["apple"])
        assertEquals(5, s.countsByRssi["all"])
        assertEquals(mapOf("findmy" to 3, "ibeacon" to 1), s.excluded)
        val logged = Json.parseToJsonElement(s.toJson()).jsonObject
        assertEquals(9, logged["devices"]!!.jsonArray.size)
        assertEquals(3, logged["excluded"]!!.jsonObject["findmy"]!!.jsonPrimitive.int)
        assertEquals("findmy", s.devices!!.first { it.hash == "a2" }.skipReason)
        assertNull(s.devices!!.first { it.hash == "a1" }.skipReason)
    }

    @Test
    fun `暫定の端末数も外した端末を数えない`() {
        val agg = WindowAggregator(0)
        agg.add(ScanEvent("a", -60, apple, 1_000, appleTypes = listOf(0x10)))
        agg.add(ScanEvent("b", -60, apple, 1_000, appleTypes = listOf(0x12)))
        agg.add(ScanEvent("c", -60, null, 1_000))
        assertEquals(2, agg.currentDeviceCount)
    }
}
