package com.crowdlevel.node.log

import com.crowdlevel.node.aggregate.WindowSummary
import java.io.File
import java.io.IOException
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * デバッグ用に WindowSummary を JSON Lines で追記する。
 * 保存先は [dir]/YYYY-MM-DD.jsonl（日付は窓の開始時刻をローカル時刻で解釈）。
 * アプリ専用外部ストレージ（Android/data/<pkg>/files/logs）に置き、送信はしない。
 */
class JsonlLogger(private val dir: File, private val zone: ZoneId) {
    private val dateFormat = DateTimeFormatter.ofPattern("yyyy-MM-dd")

    /** @return 書き込んだファイル。失敗時は IOException */
    @Throws(IOException::class)
    fun append(summary: WindowSummary, windowStartMillis: Long): File {
        if (!dir.exists() && !dir.mkdirs()) throw IOException("ログディレクトリを作成できません: $dir")
        val date = dateFormat.format(Instant.ofEpochMilli(windowStartMillis).atZone(zone))
        val file = File(dir, "$date.jsonl")
        file.appendText(summary.toJson() + "\n", Charsets.UTF_8)
        return file
    }

    /** ログファイルの一覧（新しい順）と合計サイズ */
    fun status(): LogStatus {
        val files = dir.listFiles { f -> f.isFile && f.name.endsWith(".jsonl") }?.sortedByDescending { it.name }
            ?: emptyList()
        return LogStatus(files.size, files.sumOf { it.length() }, files.firstOrNull()?.absolutePath)
    }
}

data class LogStatus(val fileCount: Int, val totalBytes: Long, val latestPath: String?)
