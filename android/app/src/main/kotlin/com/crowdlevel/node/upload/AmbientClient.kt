package com.crowdlevel.node.upload

import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

/** Ambient の HTTP API（POST /api/v2/channels/{id}/dataarray、複数点の一括書き込み）を呼ぶ最小クライアント */
class AmbientClient(private val channelId: String) {

    sealed class Result {
        data class Ok(val httpCode: Int) : Result()
        data class HttpError(val httpCode: Int, val body: String) : Result()
        data class NetworkError(val message: String) : Result()
    }

    fun post(jsonBody: String): Result {
        val url = URL("$BASE_URL/$channelId/dataarray")
        return try {
            val conn = url.openConnection() as HttpURLConnection
            try {
                conn.requestMethod = "POST"
                conn.connectTimeout = TIMEOUT_MILLIS
                conn.readTimeout = TIMEOUT_MILLIS
                conn.doOutput = true
                conn.setRequestProperty("Content-Type", "application/json; charset=utf-8")
                conn.outputStream.use { it.write(jsonBody.toByteArray(Charsets.UTF_8)) }
                val code = conn.responseCode
                if (code in 200..299) {
                    Result.Ok(code)
                } else {
                    val body = runCatching { conn.errorStream?.bufferedReader()?.readText() ?: "" }.getOrDefault("")
                    Result.HttpError(code, body.take(200))
                }
            } finally {
                conn.disconnect()
            }
        } catch (e: IOException) {
            Result.NetworkError(e.message ?: e.javaClass.simpleName)
        }
    }

    companion object {
        const val BASE_URL = "https://ambidata.io/api/v2/channels"
        private const val TIMEOUT_MILLIS = 15_000
    }
}
