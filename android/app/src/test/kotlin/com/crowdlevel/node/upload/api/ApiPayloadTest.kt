package com.crowdlevel.node.upload.api

import com.crowdlevel.node.aggregate.DeviceSummary
import com.crowdlevel.node.aggregate.WindowResult
import com.crowdlevel.node.aggregate.WindowSummary
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.ZoneId

class ApiPayloadTest {
    private val summary = WindowSummary.from(
        result = WindowResult(
            windowStartMillis = 1_791_422_100_000L, // 2026-10-08T10:15:00+09:00
            windowSec = 60,
            devices = listOf(DeviceSummary("aaaa", -64, 37, 0x004C), DeviceSummary("bbbb", -85, 2, null)),
            advCount = 39,
        ),
        nodeId = "room-3A-01",
        zone = ZoneId.of("Asia/Tokyo"),
        battery = 100,
        charging = true,
        appVersion = "0.1.0",
        includeDevices = true,
    ).copy(estimatedPeople = 2)

    @Test
    fun `端末ごとの要約と端末内だけの値は送らない`() {
        val json = ApiPayload.observationJson(summary)
        val obj = Json.parseToJsonElement(json).jsonObject
        assertFalse("devices" in obj)
        assertFalse("adv_count" in obj)
        assertFalse("estimated_people" in obj)
        // ハッシュが文字列として紛れ込んでいないこと
        assertFalse(json.contains("aaaa"))
    }

    @Test
    fun `サーバが必要とするキーは残す`() {
        val obj = Json.parseToJsonElement(ApiPayload.observationJson(summary)).jsonObject
        assertEquals("room-3A-01", obj["node_id"]!!.jsonPrimitive.content)
        assertEquals("2026-10-08T10:15:00+09:00", obj["observed_at"]!!.jsonPrimitive.content)
        assertEquals(60, obj["window_sec"]!!.jsonPrimitive.int)
        assertEquals(1, obj["counts_by_rssi"]!!.jsonObject["-70"]!!.jsonPrimitive.int)
        assertEquals(1, obj["counts_by_vendor"]!!.jsonObject["apple"]!!.jsonPrimitive.int)
        assertTrue("rssi_stats" in obj)
        assertEquals(100, obj["battery"]!!.jsonPrimitive.int)
    }

    @Test
    fun `RPCの本文はpayloadの配列にまとめる`() {
        val one = ApiPayload.observationJson(summary)
        val body = Json.parseToJsonElement(ApiPayload.rpcBody(listOf(one, one))).jsonObject
        assertEquals(2, body["payload"]!!.jsonArray.size)
        assertEquals(Json.parseToJsonElement(one), body["payload"]!!.jsonArray[0])
    }
}
