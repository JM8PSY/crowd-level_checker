package com.crowdlevel.node.aggregate

/**
 * 1パケット分の観測。MACアドレスはこの段階で既にハッシュ化済みで、生の値は含まない。
 *
 * @param hash ソルト付きSHA-256の先頭16文字
 * @param rssi 受信強度 [dBm]
 * @param companyId manufacturerSpecificData の Company ID。メーカーデータがなければ null
 * @param epochMillis 受信時刻（UTC エポックミリ秒）
 * @param serviceUuids 広告に載っていたサービス UUID（16bit は4桁の短縮形）。調査用
 * @param serviceDataUuids サービスデータの UUID。調査用
 * @param appleTypes Apple のメーカーデータに並んでいる種類の番号（[AdvertisementInfo.appleTypes]）。調査用
 */
data class ScanEvent(
    val hash: String,
    val rssi: Int,
    val companyId: Int?,
    val epochMillis: Long,
    val serviceUuids: List<String> = emptyList(),
    val serviceDataUuids: List<String> = emptyList(),
    val appleTypes: List<Int> = emptyList(),
)
