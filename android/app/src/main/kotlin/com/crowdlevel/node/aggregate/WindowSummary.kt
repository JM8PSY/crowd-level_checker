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
    /** 調査用。空なら省略する */
    @SerialName("uuids") val serviceUuids: List<String>? = null,
    @SerialName("sdata") val serviceDataUuids: List<String>? = null,
    @SerialName("apple") val appleTypes: List<Int>? = null,
    /** 人数の推定に使わなかった理由（"findmy" / "ibeacon"）。数えた端末では省略する */
    @SerialName("skip") val skipReason: String? = null,
)

/** 窓を確定した時点の端末の状態。サーバの監視（10分ごとの Discord への報告）に使う */
@Serializable
data class NodeDiag(
    /** 画面が点いているか。消えているとフィルタなしのスキャンが止まる */
    @SerialName("screen_on") val screenOn: Boolean,
    /** Supabase への送信待ちの件数（この窓を入れる前） */
    val queued: Int? = null,
    /** スキャン開始からの、定期の再起動と無受信による再起動の回数 */
    val restarts: Int,
    @SerialName("no_result_restarts") val noResultRestarts: Int,
)

/**
 * 1窓の集計結果の JSON 表現。
 * 送信ペイロード（CLAUDE.md の例）と同じキーを持ち、デバッグ時のみ devices を含む。
 */
@Serializable
data class WindowSummary(
    @SerialName("node_id") val nodeId: String,
    /** ノードを置いている部屋。設定されていなければ省略する */
    @SerialName("room_id") val roomId: String? = null,
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
    /** 端末で計算した推定人数（校正前の暫定の式）。端末内ログにだけ載せ、未計算なら省略する */
    @SerialName("estimated_people") val estimatedPeople: Int? = null,
    /** 端末の状態（監視用）。送信する */
    val diag: NodeDiag? = null,
    /** 数えなかった端末の数（理由ごと、例 {"findmy": 3, "ibeacon": 1}）。端末内ログにだけ載せ、なければ省略する */
    val excluded: Map<String, Int>? = null,
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
            roomId: String? = null,
        ): WindowSummary = WindowSummary(
            nodeId = nodeId,
            roomId = roomId,
            observedAt = formatObservedAt(result.windowStartMillis, zone),
            windowSec = result.windowSec,
            partial = if (result.complete) null else true,
            // 集計値は数える端末だけから作る（探すだけ・iBeacon だけの Apple のアドレスは外す。DeviceFilter）
            countsByRssi = Summaries.countsByRssi(result.countedDevices),
            countsByVendor = Summaries.countsByVendor(result.countedDevices),
            rssiStats = Summaries.rssiStats(result.countedDevices),
            advCount = result.advCount,
            battery = battery,
            charging = charging,
            appVersion = appVersion,
            excluded = result.excludedCounts.ifEmpty { null },
            devices = if (includeDevices) {
                result.devices.map {
                    DeviceSummaryJson(
                        it.hash, it.maxRssi, it.advCount, it.companyId,
                        it.serviceUuids.ifEmpty { null }, it.serviceDataUuids.ifEmpty { null }, it.appleTypes.ifEmpty { null },
                        DeviceFilter.excludeReason(it)?.key,
                    )
                }
            } else {
                null
            },
        )

        /** 例: 2026-10-08T10:15:00+09:00 */
        fun formatObservedAt(epochMillis: Long, zone: ZoneId): String =
            DateTimeFormatter.ISO_OFFSET_DATE_TIME.format(Instant.ofEpochMilli(epochMillis).atZone(zone))
    }
}
