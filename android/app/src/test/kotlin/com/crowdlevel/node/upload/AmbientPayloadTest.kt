package com.crowdlevel.node.upload

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.ZoneId

class AmbientPayloadTest {
    private val t0 = 1_791_422_100_000L
    private val created = "2026-10-08 10:15:00.000"

    @Test
    fun `推定人数だけを指定したスロットのdNに入れて送る`() {
        val body = AmbientPayload.toJson(slot = 3, points = listOf(AmbientPayload.Point(created, 27)), writeKey = "KEY")
        val json = Json.parseToJsonElement(body).jsonObject
        assertEquals(setOf("writeKey", "data"), json.keys)
        assertEquals("KEY", json["writeKey"]!!.jsonPrimitive.content)
        val p = json["data"]!!.jsonArray.single().jsonObject
        assertEquals(setOf("created", "d3"), p.keys)
        assertEquals(created, p["created"]!!.jsonPrimitive.content)
        assertEquals(27, p["d3"]!!.jsonPrimitive.content.toInt())
    }

    @Test
    fun `複数点を古い順のまま1回で送る`() {
        val points = listOf(AmbientPayload.Point("2026-10-08 10:15:00.000", 1), AmbientPayload.Point("2026-10-08 10:16:00.000", 2))
        val data = Json.parseToJsonElement(AmbientPayload.toJson(1, points, "KEY")).jsonObject["data"]!!.jsonArray
        assertEquals(listOf(1, 2), data.map { it.jsonObject["d1"]!!.jsonPrimitive.content.toInt() })
    }

    @Test(expected = IllegalArgumentException::class)
    fun `スロットは1から8まで`() {
        AmbientPayload.toJson(9, emptyList(), "KEY")
    }

    @Test
    fun `スロットの妥当性`() {
        assertFalse(AmbientPayload.isValidSlot(0))
        assertTrue(AmbientPayload.isValidSlot(1))
        assertTrue(AmbientPayload.isValidSlot(8))
        assertFalse(AmbientPayload.isValidSlot(9))
    }

    @Test
    fun `createdの書式はローカル時刻のYYYY-MM-DD HHmmss sss`() {
        assertEquals("2026-10-08 10:15:00.000", AmbientPayload.formatCreated(t0, ZoneId.of("Asia/Tokyo")))
    }
}
