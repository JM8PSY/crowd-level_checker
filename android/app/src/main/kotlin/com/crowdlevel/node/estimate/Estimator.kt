package com.crowdlevel.node.estimate

import com.crowdlevel.node.aggregate.Summaries
import kotlin.math.roundToInt

/**
 * 推定人数 = slope × 特徴量 + intercept を最も近い整数に丸めたもの（0未満は0）。
 *
 * 校正前の暫定の推定で、Ambient への送信と画面表示に使う。係数は設定画面で変えられる。
 * Phase 2 以降は、サーバが calibrations テーブルの係数で推定する。
 *
 * @param featureKey 特徴量。counts_by_rssi のキー（[FEATURES] のいずれか）
 */
data class Estimator(val featureKey: String, val slope: Double, val intercept: Double) {

    /** @return 推定人数。特徴量が集計にないときは null */
    fun estimate(countsByRssi: Map<String, Int>): Int? {
        val x = countsByRssi[featureKey] ?: return null
        return (slope * x + intercept).roundToInt().coerceAtLeast(0)
    }

    /** 例: "0.35 × (-80 dBm以上の端末数) + 1.0" */
    fun describe(): String = "$slope × (${featureLabel(featureKey)}) + $intercept"

    companion object {
        /** 選べる特徴量。集計の counts_by_rssi と同じキー */
        val FEATURES: List<String> = Summaries.RSSI_THRESHOLDS.map { it.toString() } + "all"

        fun featureLabel(key: String): String = if (key == "all") "全端末数" else "$key dBm以上の端末数"
    }
}
