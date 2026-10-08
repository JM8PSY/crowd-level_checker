package com.crowdlevel.node.aggregate

import kotlinx.serialization.Serializable
import kotlin.math.roundToInt

@Serializable
data class RssiStats(val median: Int, val p10: Int, val p90: Int)

/** DeviceSummary の一覧から集計値を導く純関数群 */
object Summaries {
    /** 累積カウントの閾値 [dBm]。この順で JSON に出す */
    val RSSI_THRESHOLDS = listOf(-60, -70, -80, -90)

    /** 最大RSSIが閾値以上の端末数（累積）。末尾に "all" を付ける */
    fun countsByRssi(devices: List<DeviceSummary>): Map<String, Int> {
        val result = LinkedHashMap<String, Int>()
        for (threshold in RSSI_THRESHOLDS) {
            result[threshold.toString()] = devices.count { it.maxRssi >= threshold }
        }
        result["all"] = devices.size
        return result
    }

    /** メーカーカテゴリ別の端末数。全カテゴリのキーを Vendor の宣言順で持つ */
    fun countsByVendor(devices: List<DeviceSummary>): Map<String, Int> {
        val result = LinkedHashMap<String, Int>()
        for (vendor in Vendor.entries) result[vendor.key] = 0
        for (device in devices) {
            val key = VendorClassifier.classify(device.companyId).key
            result[key] = result.getValue(key) + 1
        }
        return result
    }

    /** 端末ごとの最大RSSIに対する中央値・p10・p90。端末がなければ null */
    fun rssiStats(devices: List<DeviceSummary>): RssiStats? {
        if (devices.isEmpty()) return null
        val sorted = devices.map { it.maxRssi }.sorted()
        return RssiStats(
            median = percentile(sorted, 0.5),
            p10 = percentile(sorted, 0.1),
            p90 = percentile(sorted, 0.9),
        )
    }

    /** 線形補間による分位点（numpy の既定と同じ）。最近接の整数に丸める */
    private fun percentile(sorted: List<Int>, p: Double): Int {
        if (sorted.size == 1) return sorted[0]
        val pos = p * (sorted.size - 1)
        val lower = pos.toInt()
        val upper = minOf(lower + 1, sorted.size - 1)
        val frac = pos - lower
        val value = sorted[lower] + (sorted[upper] - sorted[lower]) * frac
        return value.roundToInt()
    }
}
