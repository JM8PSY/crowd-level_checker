package com.crowdlevel.node.upload.api

import android.content.Context
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase

/** 未送信の観測値（1窓1行）。送信に成功するまで端末内に残す。Service を止めても、端末を再起動しても消えない */
@Entity(tableName = "pending_observations")
data class PendingObservation(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    /** 観測したときのノード ID。ノード ID を変えたあとに古い ID の点を送らないために使う */
    val nodeId: String,
    /** 窓の開始時刻（epoch ミリ秒）。古い順に送るために使う */
    val windowStartMillis: Long,
    /** submit_observations に送る1件分の JSON（devices を含まない） */
    val json: String,
)

@Dao
interface PendingDao {
    @Insert
    suspend fun insert(item: PendingObservation): Long

    @Query("SELECT * FROM pending_observations ORDER BY windowStartMillis, id LIMIT :limit")
    suspend fun oldest(limit: Int): List<PendingObservation>

    @Query("DELETE FROM pending_observations WHERE id IN (:ids)")
    suspend fun delete(ids: List<Long>)

    /** ノード ID を変えたとき、前の ID の点を捨てる（新しいトークンでは受け付けられないため） */
    @Query("DELETE FROM pending_observations WHERE nodeId != :nodeId")
    suspend fun deleteOtherNodes(nodeId: String): Int

    @Query("SELECT COUNT(*) FROM pending_observations")
    suspend fun count(): Int

    /** 上限を超えた分を古いものから捨てる */
    @Query(
        "DELETE FROM pending_observations WHERE id NOT IN " +
            "(SELECT id FROM pending_observations ORDER BY windowStartMillis DESC, id DESC LIMIT :keep)",
    )
    suspend fun trimTo(keep: Int)
}

@Database(entities = [PendingObservation::class], version = 1, exportSchema = false)
abstract class QueueDatabase : RoomDatabase() {
    abstract fun pending(): PendingDao

    companion object {
        @Volatile private var instance: QueueDatabase? = null

        fun get(context: Context): QueueDatabase = instance ?: synchronized(this) {
            instance ?: Room.databaseBuilder(context.applicationContext, QueueDatabase::class.java, "upload_queue.db")
                .build()
                .also { instance = it }
        }
    }
}
