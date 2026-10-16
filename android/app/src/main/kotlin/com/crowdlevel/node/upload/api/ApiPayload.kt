package com.crowdlevel.node.upload.api

import com.crowdlevel.node.aggregate.WindowSummary
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject

/** Supabase の submit_observations に送る JSON を作る */
object ApiPayload {
    /** 端末内ログにだけ載せるキー。devices は端末ごとの要約（ハッシュを含む）なので絶対に送らない */
    private val LOCAL_ONLY_KEYS = setOf("devices", "adv_count", "estimated_people")

    /** 1窓分の観測値（送信キューに保存する形） */
    fun observationJson(summary: WindowSummary): String {
        val obj = Json.parseToJsonElement(summary.toJson()).jsonObject
        return JsonObject(obj.filterKeys { it !in LOCAL_ONLY_KEYS }).toString()
    }

    /** キューに溜まった観測値をまとめた RPC の本文 {"payload": [...]} */
    fun rpcBody(observations: List<String>): String =
        JsonObject(mapOf("payload" to JsonArray(observations.map(Json::parseToJsonElement)))).toString()
}
