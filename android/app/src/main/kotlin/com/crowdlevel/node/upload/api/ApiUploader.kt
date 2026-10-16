package com.crowdlevel.node.upload.api

import android.util.Log
import com.crowdlevel.node.aggregate.WindowSummary
import com.crowdlevel.node.estimate.Estimator
import com.crowdlevel.node.upload.UploadStatus
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * 確定した窓の集計値を Supabase（submit_observations）へ送る。
 *
 * - 窓はまず端末内のキュー（Room）に入れ、送信に成功したら消す。Service や端末を再起動しても未送信分は残る
 * - 送信は1本のコルーチンだけが行い、キューの古い順に最大 MAX_BATCH 件をまとめて送る
 * - 前回の送信から sendIntervalSec 経つまでは送らずに溜める（既定 60 秒なので、毎分の窓ごとに送る）
 * - 失敗の扱いは [ApiRetryPolicy]。再送は次の窓が確定したとき（約1分後）に行う
 * - サーバは同じ (node_id, observed_at) を重複として無視するので、送信成功の応答を受け取れずに再送しても二重にならない
 * - 部分的な窓はキューに入れない
 */
class ApiUploader(
    scope: CoroutineScope,
    private val dao: PendingDao,
    val nodeId: String,
    val baseUrl: String,
    val apiKey: String,
    val nodeToken: String,
    val sendIntervalSec: Int,
) {
    private val client = ApiClient(baseUrl, apiKey, nodeToken)
    private val wakeup = Channel<Unit>(Channel.CONFLATED)
    private var lastAttemptMillis = 0L // drain のコルーチンからだけ触る

    private val _status = MutableStateFlow(UploadStatus())
    val status: StateFlow<UploadStatus> = _status

    private val job: Job = scope.launch(Dispatchers.IO) {
        val dropped = dao.deleteOtherNodes(nodeId)
        if (dropped > 0) Log.w(TAG, "ノード ID が変わったため、前の ID の未送信 $dropped 件を破棄")
        _status.update { it.copy(queued = dao.count()) }
        // 起動直後に、前回から残っている分を送る
        drain(force = true)
        for (signal in wakeup) drain(force = false)
    }

    /**
     * 確定した窓を渡す。部分的な窓は送らない
     * @param estimator その窓の推定に使った式。観測値に添えて送り、サーバも同じ式で推定する
     */
    suspend fun submit(summary: WindowSummary, windowStartMillis: Long, estimator: Estimator) {
        if (!summary.isPartial) {
            dao.insert(PendingObservation(nodeId = summary.nodeId, windowStartMillis = windowStartMillis, json = ApiPayload.observationJson(summary, estimator)))
            dao.trimTo(MAX_QUEUE)
            _status.update { it.copy(queued = dao.count()) }
        }
        wakeup.trySend(Unit)
    }

    fun close() {
        job.cancel()
        wakeup.close()
    }

    private suspend fun drain(force: Boolean) {
        // 送信間隔より早ければ溜めておく（窓の確定の揺らぎを見込んで5秒の余裕をとる）
        val now = System.currentTimeMillis()
        if (!force && now - lastAttemptMillis < sendIntervalSec * 1000L - 5_000) return
        while (true) {
            val batch = dao.oldest(MAX_BATCH)
            if (batch.isEmpty()) return
            lastAttemptMillis = System.currentTimeMillis()
            val result = client.post(ApiPayload.rpcBody(batch.map { it.json }))
            val at = System.currentTimeMillis()
            val action = when (result) {
                is ApiClient.Result.Ok -> ApiRetryPolicy.Action.DONE
                is ApiClient.Result.HttpError -> ApiRetryPolicy.decide(result.httpCode)
                is ApiClient.Result.NetworkError -> ApiRetryPolicy.Action.RETRY
            }
            val message = when (result) {
                is ApiClient.Result.Ok -> "OK (HTTP ${result.httpCode}) ${batch.size}件 ${result.body}"
                is ApiClient.Result.HttpError -> "HTTP ${result.httpCode} ${result.body}".trim()
                is ApiClient.Result.NetworkError -> "通信失敗: ${result.message}"
            }
            when (action) {
                ApiRetryPolicy.Action.DONE, ApiRetryPolicy.Action.DISCARD -> dao.delete(batch.map { it.id })
                ApiRetryPolicy.Action.RETRY -> Unit
            }
            val queued = dao.count()
            _status.update {
                it.copy(
                    lastAttemptMillis = at,
                    lastSuccessMillis = if (action == ApiRetryPolicy.Action.DONE) at else it.lastSuccessMillis,
                    lastMessage = when (action) {
                        ApiRetryPolicy.Action.DONE -> message
                        ApiRetryPolicy.Action.DISCARD -> "$message（${batch.size}件を破棄）"
                        ApiRetryPolicy.Action.RETRY -> "$message（再送待ち）"
                    },
                    queued = queued,
                    sentCount = it.sentCount + if (action == ApiRetryPolicy.Action.DONE) batch.size else 0,
                    failedCount = it.failedCount + if (action == ApiRetryPolicy.Action.DONE) 0 else 1,
                )
            }
            Log.i(TAG, "post n=${batch.size} -> $action: $message")
            // 再送待ちなら次の窓まで待つ。成功・破棄なら残りを続けて送る
            if (action == ApiRetryPolicy.Action.RETRY) return
        }
    }

    companion object {
        private const val TAG = "ApiUploader"
        /** 1週間分（1分1件）。超えたら古いものから捨てる */
        const val MAX_QUEUE = 7 * 24 * 60
        const val MAX_BATCH = 100
    }
}
