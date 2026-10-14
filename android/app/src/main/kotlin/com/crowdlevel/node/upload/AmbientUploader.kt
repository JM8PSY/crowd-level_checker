package com.crowdlevel.node.upload

import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.time.ZoneId
import java.util.ArrayDeque

/** 直近の送信結果（UI 表示用） */
data class UploadStatus(
    val lastAttemptMillis: Long = 0,
    val lastSuccessMillis: Long = 0,
    val lastMessage: String? = null,
    /** 未送信の点の数 */
    val queued: Int = 0,
    /** 送信に成功した点の数 */
    val sentCount: Int = 0,
    /** 失敗した送信の回数 */
    val failedCount: Int = 0,
)

/**
 * 推定人数を Ambient のチャネルの d{slot} へ送る。チャネルは複数ノードで共有する前提。
 *
 * - 窓ごとの推定人数は [AmbientBatcher] で intervalMin 分ごとに1点へまとめてからキューに入れる
 * - 送信は1本のコルーチンだけが行い、[AmbientSchedule] がスロットに割り当てた時刻（毎分の数秒間）にだけ送る。
 *   同じチャネルの他のノードと送信時刻がぶつからず、5秒に1回の制限も守れる
 * - 1回の送信でキューの先頭から最大 MAX_BATCH 点をまとめて送る（dataarray）。再送分もまとめて送れる
 * - created には常に区間の開始時刻（日本時間）を付ける。直後の送信と再送で時刻の基準が揃う
 * - 通信失敗・5xx・429 はキューに残して中断し、次の点が入ったときに再開する
 * - 4xx（429 を除く）はキーやチャネル ID の誤りとみなして破棄する（再送しても直らないため）。429 は再送する
 * - キューは MAX_QUEUE 点で打ち切り、古いものから捨てる。Service を止めるとキューは消える
 */
class AmbientUploader(
    scope: CoroutineScope,
    val channelId: String,
    val writeKey: String,
    val slot: Int,
    intervalMin: Int,
) {
    private val client = AmbientClient(channelId)
    private val batcher = AmbientBatcher(intervalMin) // publish のスレッドからだけ触る
    private val lock = Any()
    private val queue = ArrayDeque<AmbientBatcher.Point>() // lock で保護
    private val wakeup = Channel<Unit>(Channel.CONFLATED)
    private val tokyo = ZoneId.of("Asia/Tokyo")
    private var lastPostMillis = Long.MIN_VALUE / 2 // drain のコルーチンからだけ触る

    val intervalMin: Int get() = batcher.intervalMin

    private val _status = MutableStateFlow(UploadStatus())
    val status: StateFlow<UploadStatus> = _status

    init {
        require(AmbientPayload.isValidSlot(slot)) { "slot は 1〜${AmbientPayload.MAX_SLOT}: $slot" }
    }

    private val job: Job = scope.launch(Dispatchers.IO) {
        for (signal in wakeup) drain()
    }

    /**
     * 確定した窓を渡す。呼び出しは1スレッドに直列化すること。
     * @param estimate 推定人数。観測が欠けた窓など、送らない窓は null
     */
    fun submit(windowStartMillis: Long, estimate: Int?) {
        val points = batcher.add(windowStartMillis, estimate)
        if (points.isEmpty()) return
        synchronized(lock) {
            points.forEach(queue::addLast)
            while (queue.size > MAX_QUEUE) queue.removeFirst()
            _status.update { it.copy(queued = queue.size) }
        }
        wakeup.trySend(Unit)
    }

    fun close() {
        job.cancel()
        wakeup.close()
    }

    private suspend fun drain() {
        while (true) {
            if (synchronized(lock) { queue.isEmpty() }) return
            // 同じ分の自分の時間帯に2回送らないよう、前回の送信開始から SEND_WINDOW 以上あける
            val now = System.currentTimeMillis()
            val earliest = maxOf(now, lastPostMillis + AmbientSchedule.SEND_WINDOW_MILLIS)
            val wait = AmbientSchedule.nextSendTime(earliest, slot) - now
            if (wait > 0) delay(wait)

            val batch = synchronized(lock) { queue.take(MAX_BATCH) }
            if (batch.isEmpty()) return
            val points = batch.map { AmbientPayload.Point(AmbientPayload.formatCreated(it.startMillis, tokyo), it.value) }
            val range = if (points.size == 1) points[0].created else "${points.first().created}〜${points.last().created}"
            lastPostMillis = System.currentTimeMillis()
            val result = client.post(AmbientPayload.toJson(slot, points, writeKey))
            val sentAt = System.currentTimeMillis()

            when (result) {
                is AmbientClient.Result.Ok -> {
                    finish(batch, sentAt, "OK (HTTP ${result.httpCode}) d$slot ${points.size}点 $range", success = true)
                    Log.i(TAG, "sent ch=$channelId d$slot n=${points.size} $range last=${points.last().value}")
                }
                is AmbientClient.Result.HttpError -> if (result.httpCode in 400..499 && result.httpCode != 429) {
                    finish(batch, sentAt, "HTTP ${result.httpCode} ${result.body}".trim() + "（${points.size}点を破棄）", success = false)
                    Log.w(TAG, "http ${result.httpCode}, discarded ${points.size}: ${result.body}")
                } else {
                    keep(sentAt, "HTTP ${result.httpCode}（再送待ち）")
                    Log.w(TAG, "http ${result.httpCode}, will retry")
                    return
                }
                is AmbientClient.Result.NetworkError -> {
                    keep(sentAt, "通信失敗: ${result.message}（再送待ち）")
                    Log.w(TAG, "network error, will retry: ${result.message}")
                    return
                }
            }
            // 続きがあれば、次の分の自分の時間帯まで待って送る
        }
    }

    /** 送信が終わった（成功または破棄）点をキューから外す */
    private fun finish(batch: List<AmbientBatcher.Point>, now: Long, message: String, success: Boolean) {
        synchronized(lock) {
            // 送信中に MAX_QUEUE を超えて先頭が捨てられていることがあるため、同じ要素だけを外す
            batch.forEach { p -> queue.removeFirstOccurrence(p) }
            _status.update {
                it.copy(
                    lastAttemptMillis = now,
                    lastSuccessMillis = if (success) now else it.lastSuccessMillis,
                    lastMessage = message,
                    queued = queue.size,
                    sentCount = it.sentCount + if (success) batch.size else 0,
                    failedCount = it.failedCount + if (success) 0 else 1,
                )
            }
        }
    }

    /** 失敗した点はキューに残す */
    private fun keep(now: Long, message: String) {
        synchronized(lock) {
            _status.update {
                it.copy(lastAttemptMillis = now, lastMessage = message, queued = queue.size, failedCount = it.failedCount + 1)
            }
        }
    }

    companion object {
        private const val TAG = "AmbientUploader"
        private const val MAX_QUEUE = 240 // 間隔1分なら4時間ぶん
        private const val MAX_BATCH = 60
    }
}
