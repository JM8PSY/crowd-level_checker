package com.crowdlevel.node.aggregate

/**
 * 1パケット分の観測。MACアドレスはこの段階で既にハッシュ化済みで、生の値は含まない。
 *
 * @param hash ソルト付きSHA-256の先頭16文字
 * @param rssi 受信強度 [dBm]
 * @param companyId manufacturerSpecificData の Company ID。メーカーデータがなければ null
 * @param epochMillis 受信時刻（UTC エポックミリ秒）
 */
data class ScanEvent(
    val hash: String,
    val rssi: Int,
    val companyId: Int?,
    val epochMillis: Long,
)
