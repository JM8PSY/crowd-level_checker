package com.crowdlevel.node.upload.api

import com.crowdlevel.node.aggregate.WindowSummary
import com.crowdlevel.node.estimate.Estimator
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject

/** Supabase の submit_observations に送る JSON を作る */
object ApiPayload {
    /** 端末内ログにだけ載せるキー。devices は端末ごとの要約（ハッシュを含む）なので絶対に送らない */
    private val LOCAL_ONLY_KEYS = setOf("devices", "adv_count", "estimated_people", "excluded")

    /**
     * 1窓分の観測値（送信キューに保存する形）。
     * 推定人数はサーバが計算するので、その式（この端末の設定）を estimator として添える。
     * 画面に出す推定人数と、サーバの推定人数が同じ式になる
     */
    fun observationJson(summary: WindowSummary, estimator: Estimator): String {
        val obj = Json.parseToJsonElement(summary.toJson()).jsonObject
        return JsonObject(obj.filterKeys { it !in LOCAL_ONLY_KEYS } + ("estimator" to estimatorJson(estimator))).toString()
    }

    fun estimatorJson(estimator: Estimator): JsonObject = JsonObject(
        mapOf(
            "feature" to JsonPrimitive(estimator.featureKey),
            "coef" to JsonPrimitive(estimator.slope),
            "intercept" to JsonPrimitive(estimator.intercept),
        ),
    )

    /** キューに溜まった観測値をまとめた RPC の本文 {"payload": [...]} */
    fun rpcBody(observations: List<String>): String =
        JsonObject(mapOf("payload" to JsonArray(observations.map(Json::parseToJsonElement)))).toString()
}
