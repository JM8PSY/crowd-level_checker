package com.crowdlevel.node.upload

import com.crowdlevel.node.aggregate.RssiStats
import com.crowdlevel.node.aggregate.WindowSummary
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.ZoneId

class AmbientPayloadTest {
    private val summary = WindowSummary(
        nodeId = "node-01",
        observedAt = "2026-10-08T10:15:00+09:00",
        windowSec = 60,
        countsByRssi = linkedMapOf("-60" to 4, "-70" to 11, "-80" to 23, "-90" to 31, "all" to 33),
        countsByVendor = linkedMapOf("apple" to 14, "google" to 3, "microsoft" to 2, "samsung" to 1, "other" to 5, "none" to 8),
        rssiStats = RssiStats(median = -78, p10 = -91, p90 = -62),
        advCount = 1842,
        battery = 100,
        charging = true,
        appVersion = "0.1.0",
    )
    private val t0 = 1_791_422_100_000L

    private val created = "2026-10-08 10:15:00.000"

    @Test
    fun `d1からd8に集計値を割り当てる`() {
        val json = Json.parseToJsonElement(AmbientPayload.toJson(summary, "KEY", created)).jsonObject
        assertEquals("KEY", json["writeKey"]!!.jsonPrimitive.content)
        assertEquals(4, json["d1"]!!.jsonPrimitive.content.toInt())
        assertEquals(11, json["d2"]!!.jsonPrimitive.content.toInt())
        assertEquals(23, json["d3"]!!.jsonPrimitive.content.toInt())
        assertEquals(31, json["d4"]!!.jsonPrimitive.content.toInt())
        assertEquals(33, json["d5"]!!.jsonPrimitive.content.toInt())
        assertEquals(14, json["d6"]!!.jsonPrimitive.content.toInt())
        assertEquals(-78, json["d7"]!!.jsonPrimitive.content.toInt())
        assertEquals(1842, json["d8"]!!.jsonPrimitive.content.toInt())
    }

    @Test
    fun `createdを常に含む`() {
        val json = Json.parseToJsonElement(AmbientPayload.toJson(summary, "KEY", created)).jsonObject
        assertEquals(created, json["created"]!!.jsonPrimitive.content)
    }

    @Test
    fun `node_idやハッシュなど識別に使える値を含まない`() {
        val json = Json.parseToJsonElement(AmbientPayload.toJson(summary, "KEY", created)).jsonObject
        assertEquals(setOf("writeKey", "created", "d1", "d2", "d3", "d4", "d5", "d6", "d7", "d8"), json.keys)
    }

    @Test
    fun `RSSI統計がない窓ではd7を省略する`() {
        val empty = summary.copy(rssiStats = null)
        val json = Json.parseToJsonElement(AmbientPayload.toJson(empty, "KEY", created)).jsonObject
        assertFalse(json.containsKey("d7"))
        assertTrue(json.containsKey("d1"))
    }

    @Test
    fun `createdの書式はローカル時刻のYYYY-MM-DD HHmmss sss`() {
        assertEquals("2026-10-08 10:15:00.000", AmbientPayload.formatCreated(t0, ZoneId.of("Asia/Tokyo")))
    }
}
