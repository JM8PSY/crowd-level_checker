package com.crowdlevel.node.upload

/**
 * Ambient へ送ってよい時刻を決める（Android 非依存）。
 *
 * Ambient は1チャネルあたり5秒に1回までしか書き込めない。同じチャネルを共有するノードが
 * 窓の確定直後（毎分0秒すぎ）に一斉に送るとぶつかるため、スロットごとに1分の中の送信時刻をずらす。
 *
 * - スロット k は毎分 FIRST_OFFSET + (k-1) × SLOT_GAP 秒から SEND_WINDOW の間だけ送る
 *   （d1 = 2秒、d2 = 9秒、… d8 = 51秒）
 * - 隣のスロットとの間隔は SLOT_GAP − SEND_WINDOW = 6秒以上空く。端末どうしの時計のずれが
 *   1秒程度までなら5秒の制限を守れる
 * - 1回の送信で未送信分をまとめて送る（dataarray）ため、1ノードの送信は1分に1回で足りる
 */
object AmbientSchedule {
    const val MINUTE_MILLIS = 60_000L
    const val FIRST_OFFSET_MILLIS = 2_000L
    const val SLOT_GAP_MILLIS = 7_000L
    const val SEND_WINDOW_MILLIS = 1_000L

    /** スロットの送信時刻（毎分の何ミリ秒目か） */
    fun offsetOf(slot: Int): Long {
        require(AmbientPayload.isValidSlot(slot)) { "slot は 1〜${AmbientPayload.MAX_SLOT}: $slot" }
        return FIRST_OFFSET_MILLIS + (slot - 1) * SLOT_GAP_MILLIS
    }

    /** now 以降で、このスロットが送ってよい最初の時刻。いま送ってよければ now を返す */
    fun nextSendTime(nowMillis: Long, slot: Int): Long {
        val sliceStart = nowMillis - Math.floorMod(nowMillis, MINUTE_MILLIS) + offsetOf(slot)
        return when {
            nowMillis < sliceStart -> sliceStart
            nowMillis < sliceStart + SEND_WINDOW_MILLIS -> nowMillis
            else -> sliceStart + MINUTE_MILLIS
        }
    }

    /** 1チャネルの1日の上限（3,000件）に収まるか。nodes 台が intervalMin 分ごとに1点送る場合 */
    fun pointsPerDay(nodes: Int, intervalMin: Int): Int = nodes * (24 * 60 / intervalMin)

    const val DAILY_LIMIT = 3_000
}
