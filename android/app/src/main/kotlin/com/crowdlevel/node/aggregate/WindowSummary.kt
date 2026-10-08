package com.crowdlevel.node.aggregate

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/** デバッグログ・送信ペイロードに載せる端末要約。キーは短くしてログ容量を抑える */
@Serializable
data class DeviceSummaryJson(
    @SerialName("h") val hash: String,
    @SerialName("max_rssi") val maxRssi: Int,
    @SerialName("n") val advCount: Int,
    @SerialName("cid") val companyId: Int?,
)

/**
 * 1窓の集計結果の JSON 表現。
 * 送信ペイロード（CLAUDE.md の例）と同じキーを持ち、デバッグ時のみ devices を含む。
 */
@Serializable
data class WindowSummary(
    @SerialName("node_id") val nodeId: String,
    @SerialName("observed_at") val observedAt: String,
    @SerialName("window_sec") val windowSec: Int,
    /** 観測が欠けた窓（起動直後、スキャン停止中など）のときだけ true。完全な窓では省略する */
    val partial: Boolean? = null,
    @SerialName("counts_by_rssi") val countsByRssi: Map<String, Int>,
    @SerialName("counts_by_vendor") val countsByVendor: Map<String, Int>,
    @SerialName("rssi_stats") val rssiStats: RssiStats?,
    @SerialName("adv_count") val advCount: Int,
    val battery: Int? = null,
    val charging: Boolean? = null,
    @SerialName("app_version") val appVersion: String,
    val devices: List<DeviceSummaryJson>? = null,
) {
    /** 校正や送信から除外すべき窓か */
    val isPartial: Boolean get() = partial == true

    /** 1行の JSON（改行なし） */
    fun toJson(): String = json.encodeToString(serializer(), this)

    companion object {
        /**
         * null の扱い:
         * - battery / charging / devices は既定値 null を持ち、encodeDefaults = false なので未取得なら省略される
         * - rssi_stats と cid は既定値を持たないため、null のときも null として出力される
         */
        private val json = Json { encodeDefaults = false }

        fun fromJson(text: String): WindowSummary = json.decodeFromString(serializer(), text)

        fun from(
            result: WindowResult,
            nodeId: String,
            zone: ZoneId,
            battery: Int?,
            charging: Boolean?,
            appVersion: String,
            includeDevices: Boolean,
        ): WindowSummary = WindowSummary(
            nodeId = nodeId,
            observedAt = formatObservedAt(result.windowStartMillis, zone),
            windowSec = result.windowSec,
            partial = if (result.complete) null else true,
            countsByRssi = Summaries.countsByRssi(result.devices),
            countsByVendor = Summaries.countsByVendor(result.devices),
            rssiStats = Summaries.rssiStats(result.devices),
            advCount = result.advCount,
            battery = battery,
            charging = charging,
            appVersion = appVersion,
            devices = if (includeDevices) {
                result.devices.map { DeviceSummaryJson(it.hash, it.maxRssi, it.advCount, it.companyId) }
            } else {
                null
            },
        )

        /** 例: 2026-10-08T10:15:00+09:00 */
        fun formatObservedAt(epochMillis: Long, zone: ZoneId): String =
            DateTimeFormatter.ISO_OFFSET_DATE_TIME.format(Instant.ofEpochMilli(epochMillis).atZone(zone))
    }
}
