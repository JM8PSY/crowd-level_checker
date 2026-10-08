package com.crowdlevel.node.aggregate

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.ZoneId

class WindowSummaryTest {
    private val t0 = 1_791_422_100_000L // 2026-10-08T10:15:00+09:00
    private val tokyo = ZoneId.of("Asia/Tokyo")

    private fun result() = WindowResult(
        windowStartMillis = t0,
        windowSec = 60,
        devices = listOf(
            DeviceSummary("aaaa", -64, 37, 0x004C),
            DeviceSummary("bbbb", -85, 2, null),
        ),
        advCount = 39,
    )

    private fun summary(includeDevices: Boolean) = WindowSummary.from(
        result = result(),
        nodeId = "room-3A-01",
        zone = tokyo,
        battery = 100,
        charging = true,
        appVersion = "0.1.0",
        includeDevices = includeDevices,
    )

    @Test
    fun `observed_atは窓の開始時刻をオフセット付きISO8601で表す`() {
        assertEquals("2026-10-08T10:15:00+09:00", summary(false).observedAt)
    }

    @Test
    fun `送信用JSONは仕様のキーを持ち、devicesを含まない`() {
        val json = Json.parseToJsonElement(summary(false).toJson()).jsonObject
        assertEquals("room-3A-01", json["node_id"]!!.jsonPrimitive.content)
        assertEquals(60, json["window_sec"]!!.jsonPrimitive.content.toInt())
        assertEquals(1, json["counts_by_rssi"]!!.jsonObject["-70"]!!.jsonPrimitive.content.toInt())
        assertEquals(2, json["counts_by_rssi"]!!.jsonObject["all"]!!.jsonPrimitive.content.toInt())
        assertEquals(1, json["counts_by_vendor"]!!.jsonObject["apple"]!!.jsonPrimitive.content.toInt())
        assertEquals(1, json["counts_by_vendor"]!!.jsonObject["none"]!!.jsonPrimitive.content.toInt())
        assertEquals(-66, json["rssi_stats"]!!.jsonObject["p90"]!!.jsonPrimitive.content.toInt()) // -85 と -64 の線形補間（0.9）
        assertEquals(39, json["adv_count"]!!.jsonPrimitive.content.toInt())
        assertEquals(100, json["battery"]!!.jsonPrimitive.content.toInt())
        assertEquals("true", json["charging"]!!.jsonPrimitive.content)
        assertEquals("0.1.0", json["app_version"]!!.jsonPrimitive.content)
        assertFalse(json.containsKey("devices"))
    }

    @Test
    fun `デバッグ用JSONはdevicesを短いキーで含む`() {
        val json = Json.parseToJsonElement(summary(true).toJson()).jsonObject
        val devices = json["devices"]!!.jsonArray
        assertEquals(2, devices.size)
        val first = devices[0].jsonObject
        assertEquals("aaaa", first["h"]!!.jsonPrimitive.content)
        assertEquals(-64, first["max_rssi"]!!.jsonPrimitive.content.toInt())
        assertEquals(37, first["n"]!!.jsonPrimitive.content.toInt())
        assertEquals(76, first["cid"]!!.jsonPrimitive.content.toInt())
        assertTrue(devices[1].jsonObject["cid"]!!.jsonPrimitive.content == "null")
    }

    @Test
    fun `バッテリー情報が取れない場合はキーを省略する`() {
        val s = WindowSummary.from(result(), "n", tokyo, battery = null, charging = null, appVersion = "0.1.0", includeDevices = false)
        val json = Json.parseToJsonElement(s.toJson()).jsonObject
        assertFalse(json.containsKey("battery"))
        assertFalse(json.containsKey("charging"))
    }

    @Test
    fun `端末ゼロの窓はrssi_statsがnullになる`() {
        val empty = WindowResult(t0, 60, emptyList(), 0)
        val s = WindowSummary.from(empty, "n", tokyo, null, null, "0.1.0", false)
        val json = Json.parseToJsonElement(s.toJson()).jsonObject
        assertEquals("null", json["rssi_stats"]!!.jsonPrimitive.content)
        assertEquals(0, json["counts_by_rssi"]!!.jsonObject["all"]!!.jsonPrimitive.content.toInt())
    }

    @Test
    fun `toJsonは1行で改行を含まない`() {
        assertFalse(summary(true).toJson().contains('\n'))
    }

    @Test
    fun `完全な窓はpartialを含まない`() {
        val json = Json.parseToJsonElement(summary(false).toJson()).jsonObject
        assertFalse(json.containsKey("partial"))
        assertFalse(summary(false).isPartial)
    }

    @Test
    fun `観測が欠けた窓はpartial=trueを含む`() {
        val partial = result().copy(complete = false)
        val s = WindowSummary.from(partial, "n", tokyo, null, null, "0.1.0", false)
        val json = Json.parseToJsonElement(s.toJson()).jsonObject
        assertEquals("true", json["partial"]!!.jsonPrimitive.content)
        assertTrue(s.isPartial)
    }
}
