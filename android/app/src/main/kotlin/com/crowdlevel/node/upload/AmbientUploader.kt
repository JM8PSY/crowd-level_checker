package com.crowdlevel.node.upload

import android.util.Log
import com.crowdlevel.node.aggregate.WindowSummary
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
    val queued: Int = 0,
    val sentCount: Int = 0,
    val failedCount: Int = 0,
)

/**
 * 確定した窓を Ambient へ送る。
 *
 * - 送信は1本のコルーチンだけが行い、キューの先頭から順に送る
 * - created には常に窓の開始時刻（日本時間）を付ける。直後の送信と再送で時刻の基準が揃う
 * - 通信失敗と 5xx はキューに残して中断し、次の窓が確定したときに再開する
 * - 4xx はキーやチャネル ID の誤りとみなして破棄する（再送しても直らないため）
 * - Ambient は1チャネルあたり5秒に1回までのため、送信の間隔を常に SEND_GAP_MILLIS 以上空ける
 * - キューは MAX_QUEUE 件で打ち切り、古いものから捨てる。Service を止めるとキューは消える
 */
class AmbientUploader(
    scope: CoroutineScope,
    val channelId: String,
    val writeKey: String,
) {
    private data class Pending(val summary: WindowSummary, val windowStartMillis: Long)

    private val client = AmbientClient(channelId)
    private val lock = Any()
    private val queue = ArrayDeque<Pending>() // lock で保護
    private val wakeup = Channel<Unit>(Channel.CONFLATED)
    private val tokyo = ZoneId.of("Asia/Tokyo")
    private var lastPostMillis = 0L

    private val _status = MutableStateFlow(UploadStatus())
    val status: StateFlow<UploadStatus> = _status

    private val job: Job = scope.launch(Dispatchers.IO) {
        for (signal in wakeup) drain()
    }

    fun enqueue(summary: WindowSummary, windowStartMillis: Long) {
        synchronized(lock) {
            queue.addLast(Pending(summary, windowStartMillis))
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
            val item = synchronized(lock) { queue.peekFirst() } ?: return
            val wait = lastPostMillis + SEND_GAP_MILLIS - System.currentTimeMillis()
            if (wait > 0) delay(wait)

            val created = AmbientPayload.formatCreated(item.windowStartMillis, tokyo)
            val result = client.post(AmbientPayload.toJson(item.summary, writeKey, created))
            val now = System.currentTimeMillis()
            lastPostMillis = now

            val proceed = when (result) {
                is AmbientClient.Result.Ok -> {
                    finish(item, now, "OK (HTTP ${result.httpCode}) $created", success = true)
                    Log.i(TAG, "sent ch=$channelId created=$created")
                    true
                }
                is AmbientClient.Result.HttpError -> if (result.httpCode in 400..499) {
                    finish(item, now, "HTTP ${result.httpCode} ${result.body}".trim() + "（破棄）", success = false)
                    Log.w(TAG, "http ${result.httpCode}, discarded: ${result.body}")
                    true
                } else {
                    keep(now, "HTTP ${result.httpCode}（再送待ち）")
                    Log.w(TAG, "http ${result.httpCode}, will retry")
                    false
                }
                is AmbientClient.Result.NetworkError -> {
                    keep(now, "通信失敗: ${result.message}（再送待ち）")
                    Log.w(TAG, "network error, will retry: ${result.message}")
                    false
                }
            }
            if (!proceed) return
        }
    }

    /** 送信が終わった（成功または破棄）要素をキューから外す */
    private fun finish(item: Pending, now: Long, message: String, success: Boolean) {
        synchronized(lock) {
            queue.remove(item)
            _status.update {
                it.copy(
                    lastAttemptMillis = now,
                    lastSuccessMillis = if (success) now else it.lastSuccessMillis,
                    lastMessage = message,
                    queued = queue.size,
                    sentCount = it.sentCount + if (success) 1 else 0,
                    failedCount = it.failedCount + if (success) 0 else 1,
                )
            }
        }
    }

    /** 失敗した要素はキューに残す */
    private fun keep(now: Long, message: String) {
        synchronized(lock) {
            _status.update {
                it.copy(lastAttemptMillis = now, lastMessage = message, queued = queue.size, failedCount = it.failedCount + 1)
            }
        }
    }

    companion object {
        private const val TAG = "AmbientUploader"
        private const val MAX_QUEUE = 240 // 4時間ぶん
        private const val SEND_GAP_MILLIS = 5_500L
    }
}
