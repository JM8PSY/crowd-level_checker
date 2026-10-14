package com.crowdlevel.node.upload

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * Ambient（ambidata.io）へ送る本文を作る。
 *
 * 複数ノードで1つのチャネル（＝1つのグラフ）を共有するため、ノードごとに d1〜d8 のどれか1つ（スロット）を
 * 割り当て、そこに推定人数だけを入れる。グラフ側で d1〜d8 を表示しておけば、ノードを足すと線が増える。
 * 校正に使う生の集計値は端末内ログ（JSON Lines）に残す。
 */
object AmbientPayload {
    /** Ambient の1チャネルが持てるデータ欄の数（d1〜d8） */
    const val MAX_SLOT = 8

    /** 1点ぶんのデータ。created は "yyyy-MM-dd HH:mm:ss.SSS"（日本時間） */
    data class Point(val created: String, val value: Int)

    private val createdFormat = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSS")

    fun isValidSlot(slot: Int): Boolean = slot in 1..MAX_SLOT

    /** POST /api/v2/channels/{id}/dataarray の本文。各点は created と d{slot} だけを持つ */
    fun toJson(slot: Int, points: List<Point>, writeKey: String): String {
        require(isValidSlot(slot)) { "slot は 1〜$MAX_SLOT: $slot" }
        val key = "d$slot"
        val data = points.map { p ->
            JsonObject(linkedMapOf("created" to JsonPrimitive(p.created), key to JsonPrimitive(p.value)))
        }
        return JsonObject(linkedMapOf("writeKey" to JsonPrimitive(writeKey), "data" to JsonArray(data))).toString()
    }

    /** Ambient の created 形式。Ambient は日本時間として解釈するため、zone には Asia/Tokyo を渡す */
    fun formatCreated(epochMillis: Long, zone: ZoneId): String =
        createdFormat.format(Instant.ofEpochMilli(epochMillis).atZone(zone))
}
