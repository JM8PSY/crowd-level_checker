package com.crowdlevel.node.scan

/**
 * スキャンを再起動すべきかを判定する（Android 非依存）。
 *
 * 背景（2026-10-08、Pixel 9a / Android 16 で確認）:
 * - フィルタなしのスキャン（空の ScanFilter も含む）は、開始から DeviceConfig の
 *   bluetooth/scan_timeout_millis が経つと OPPORTUNISTIC に移され、結果が届かなくなる
 * - AOSP の既定は30分だが、この端末では 300000（5分）だった
 * - stop してから start し直すとタイマーが最初から数え直される
 *
 * そのため、5分より十分短い間隔で定期的に再起動する。
 * 想定外の理由で結果が途絶えたときのために、無受信が続いたら再起動する安全策も持つ。
 * どちらも 30秒に5回までという startScan の制限には触れない頻度にしている。
 */
object RestartPolicy {
    enum class Reason { PERIODIC, NO_RESULTS }

    /** 定期再起動の間隔 */
    const val PERIODIC_MILLIS = 3 * 60 * 1000L

    /** この時間、結果が1件も届かなければ再起動する */
    const val NO_RESULT_MILLIS = 30 * 1000L

    /**
     * @param lastStartMillis 最後に startScan した時刻
     * @param lastResultMillis 最後に結果を受け取った時刻
     * @return 再起動すべきならその理由。不要なら null
     */
    fun decide(
        nowMillis: Long,
        scanning: Boolean,
        lastStartMillis: Long,
        lastResultMillis: Long,
    ): Reason? {
        if (!scanning) return null
        if (nowMillis - lastStartMillis >= PERIODIC_MILLIS) return Reason.PERIODIC
        // 再起動直後は、結果がまだなくても閾値まで待つ
        val lastActivity = maxOf(lastStartMillis, lastResultMillis)
        if (nowMillis - lastActivity >= NO_RESULT_MILLIS) return Reason.NO_RESULTS
        return null
    }
}
