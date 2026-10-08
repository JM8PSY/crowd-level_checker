package com.crowdlevel.node.upload

import com.crowdlevel.node.aggregate.WindowSummary
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * WindowSummary を Ambient（ambidata.io）の d1〜d8 に割り当てる。
 *
 * | 項目 | 内容 |
 * |---|---|
 * | d1 | 最大RSSI ≥ -60 dBm の端末数 |
 * | d2 | ≥ -70 dBm |
 * | d3 | ≥ -80 dBm（推定の初期特徴量） |
 * | d4 | ≥ -90 dBm |
 * | d5 | 全端末数 |
 * | d6 | Apple 端末数 |
 * | d7 | RSSI 中央値 [dBm]（端末ゼロなら省略） |
 * | d8 | 受信パケット数 |
 */
object AmbientPayload {
    private val createdFormat = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSS")

    /**
     * @param created Ambient の created（"yyyy-MM-dd HH:mm:ss.SSS"、日本時間）。窓の開始時刻を渡す
     */
    fun toJson(summary: WindowSummary, writeKey: String, created: String): String {
        val fields = LinkedHashMap<String, JsonPrimitive>()
        fields["writeKey"] = JsonPrimitive(writeKey)
        fields["created"] = JsonPrimitive(created)
        fields["d1"] = JsonPrimitive(summary.countsByRssi["-60"] ?: 0)
        fields["d2"] = JsonPrimitive(summary.countsByRssi["-70"] ?: 0)
        fields["d3"] = JsonPrimitive(summary.countsByRssi["-80"] ?: 0)
        fields["d4"] = JsonPrimitive(summary.countsByRssi["-90"] ?: 0)
        fields["d5"] = JsonPrimitive(summary.countsByRssi["all"] ?: 0)
        fields["d6"] = JsonPrimitive(summary.countsByVendor["apple"] ?: 0)
        summary.rssiStats?.let { fields["d7"] = JsonPrimitive(it.median) }
        fields["d8"] = JsonPrimitive(summary.advCount)
        return JsonObject(fields).toString()
    }

    /** Ambient の created 形式。Ambient は日本時間として解釈するため、zone には Asia/Tokyo を渡す */
    fun formatCreated(epochMillis: Long, zone: ZoneId): String =
        createdFormat.format(Instant.ofEpochMilli(epochMillis).atZone(zone))
}
