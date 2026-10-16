package com.crowdlevel.node.upload.api

import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

/**
 * Supabase の RPC submit_observations を呼ぶ最小クライアント。
 * ノードのトークンは Authorization ではなく x-node-token で送る（Supabase では Authorization が JWT 用のため）
 */
class ApiClient(baseUrl: String, private val apiKey: String, private val nodeToken: String) {

    private val url = URL(baseUrl.trimEnd('/') + "/rest/v1/rpc/submit_observations")

    sealed class Result {
        data class Ok(val httpCode: Int, val body: String) : Result()
        data class HttpError(val httpCode: Int, val body: String) : Result()
        data class NetworkError(val message: String) : Result()
    }

    fun post(jsonBody: String): Result = try {
        val conn = url.openConnection() as HttpURLConnection
        try {
            conn.requestMethod = "POST"
            conn.connectTimeout = TIMEOUT_MILLIS
            conn.readTimeout = TIMEOUT_MILLIS
            conn.doOutput = true
            conn.setRequestProperty("Content-Type", "application/json; charset=utf-8")
            conn.setRequestProperty("apikey", apiKey)
            // 旧形式の anon key（JWT）は Authorization にも付ける。新形式の publishable key は apikey だけでよい
            if (apiKey.startsWith("eyJ")) conn.setRequestProperty("Authorization", "Bearer $apiKey")
            conn.setRequestProperty("x-node-token", nodeToken)
            conn.outputStream.use { it.write(jsonBody.toByteArray(Charsets.UTF_8)) }
            val code = conn.responseCode
            if (code in 200..299) {
                Result.Ok(code, conn.inputStream.bufferedReader().use { it.readText() }.take(200))
            } else {
                val body = runCatching { conn.errorStream?.bufferedReader()?.readText() ?: "" }.getOrDefault("")
                Result.HttpError(code, body.take(300))
            }
        } finally {
            conn.disconnect()
        }
    } catch (e: IOException) {
        Result.NetworkError(e.message ?: e.javaClass.simpleName)
    }

    companion object {
        private const val TIMEOUT_MILLIS = 15_000
    }
}

/** HTTP の結果から、キューの点をどう扱うかを決める */
object ApiRetryPolicy {
    enum class Action {
        /** 受け付けられた（重複も含む）。キューから消す */
        DONE,
        /** 中身の誤りで、再送しても直らない。キューから消す */
        DISCARD,
        /** 一時的な失敗か設定の誤り（トークンなど）。キューに残して次の窓で再送する */
        RETRY,
    }

    fun decide(httpCode: Int): Action = when {
        httpCode in 200..299 -> Action.DONE
        // 401/403 はトークン・キーの誤り。設定を直せば送れるので捨てない。404 は URL の誤り、408/429 は一時的
        httpCode in setOf(401, 403, 404, 408, 429) -> Action.RETRY
        httpCode in 400..499 -> Action.DISCARD
        else -> Action.RETRY
    }
}
