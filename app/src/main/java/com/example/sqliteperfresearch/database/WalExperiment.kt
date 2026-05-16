package com.example.sqliteperfresearch.database

import android.content.ContentValues
import android.util.Log
import com.example.sqliteperfresearch.model.ExperimentLog
import com.example.sqliteperfresearch.model.LogType
import com.example.sqliteperfresearch.util.LOG_TAG
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.supervisorScope
import kotlin.random.Random
import kotlin.system.measureTimeMillis

class WalExperiment(private val context: android.content.Context) {
    companion object {
        const val TAG = "$LOG_TAG.WAL"
    }

    interface Callback {
        fun onLog(log: ExperimentLog)
    }

    fun now() = java.text.SimpleDateFormat("HH:mm:ss.SSS", java.util.Locale.getDefault()).format(java.util.Date())

    private fun dbPath(name: String) = context.getDatabasePath(name).absolutePath

    suspend fun prepareDatabases(callback: Callback): Pair<PerfDatabase, PerfDatabase> {
        // Delete any existing DB files to ensure clean state
        context.deleteDatabase("test_wal.db")
        context.deleteDatabase("test_delete.db")

        val walDb = PerfDatabase(context, "test_wal.db")
        val deleteDb = PerfDatabase(context, "test_delete.db")

        walDb.setWalMode(true)
        deleteDb.setWalMode(false)

        callback.onLog(ExperimentLog(now(), "WAL对比", "WAL DB 模式: ${walDb.getJournalMode()}", LogType.INFO))
        callback.onLog(ExperimentLog(now(), "WAL对比", "TRUNCATE DB 模式: ${deleteDb.getJournalMode()}", LogType.INFO))
        return walDb to deleteDb
    }

    suspend fun runConcurrentReads(walDb: PerfDatabase, deleteDb: PerfDatabase, threadCount: Int, rowsPerThread: Int, callback: Callback) {
        callback.onLog(ExperimentLog(now(), "并发读", "启动 $threadCount 线程并发读, 每线程 $rowsPerThread 行", LogType.INFO))
        runTimedComparison("并发读", walDb, deleteDb, callback) { db, tid ->
            measureTimeMillis {
                // Use readableDatabase directly - SQLiteOpenHelper manages the connection pool
                val reader = db.readableDatabase
                reader.rawQuery(
                    "SELECT * FROM ${Schema.TABLE_NAME} LIMIT $rowsPerThread OFFSET ${tid * rowsPerThread}",
                    null
                ).use {
                    var c = 0
                    while (it.moveToNext()) c++
                }
            }
        }
    }

    suspend fun runMixedReadWrite(walDb: PerfDatabase, deleteDb: PerfDatabase, callback: Callback) {
        callback.onLog(ExperimentLog(now(), "读写混合", "8 读 + 2 写并发执行", LogType.INFO))
        runTimedComparison("读写混合", walDb, deleteDb, callback) { db, tid ->
            measureTimeMillis {
                if (tid < 8) {
                    db.readableDatabase.rawQuery(
                        "SELECT * FROM ${Schema.TABLE_NAME} LIMIT 5000 OFFSET ${tid * 5000}",
                        null
                    ).close()
                } else {
                    val writer = db.writableDatabase
                    writer.beginTransaction()
                    try {
                        for (i in 0 until 200) {
                            val cv = ContentValues()
                            cv.put("score", Random.nextDouble(0.0, 100.0))
                            writer.update(Schema.TABLE_NAME, cv, "id = ?", arrayOf("${tid * 200 + i + 1}"))
                        }
                        writer.setTransactionSuccessful()
                    } catch (e: Exception) {
                        Log.d(TAG, "exception on runMixedReadWrite: ${e}")
                    } finally {
                        writer.endTransaction()
                    }
                }
            }
        }
    }

    suspend fun runConcurrentWrites(walDb: PerfDatabase, deleteDb: PerfDatabase, threadCount: Int, rowsPerThread: Int, callback: Callback) {
        callback.onLog(ExperimentLog(now(), "并发写", "$threadCount 线程并发写, 每线程 $rowsPerThread 行", LogType.INFO))
        runTimedComparison("并发写", walDb, deleteDb, callback) { db, tid ->
            measureTimeMillis {
                val writer = db.writableDatabase
                writer.beginTransaction()
                try {
                    for (i in 0 until rowsPerThread) {
                        val cv = ContentValues()
                        cv.put("name", "wal_write_${tid}_$i")
                        cv.put("counter", tid * 1000 + i)
                        writer.insert(Schema.TABLE_NAME, null, cv)
                    }
                    writer.setTransactionSuccessful()
                } finally {
                    writer.endTransaction()
                }
            }
        }
    }

    private suspend fun runTimedComparison(
        phase: String,
        walDb: PerfDatabase,
        deleteDb: PerfDatabase,
        callback: Callback,
        block: suspend (PerfDatabase, Int) -> Long,
    ) = supervisorScope {
        val threadCount = 10
        val walTimes = List(threadCount) { tid ->
            async(Dispatchers.IO) { block(walDb, tid) }
        }.awaitAll()

        val deleteTimes = List(threadCount) { tid ->
            async(Dispatchers.IO) { block(deleteDb, tid) }
        }.awaitAll()

        val walTotal = walTimes.sum()
        val walP50 = walTimes.sorted()[walTimes.size / 2]
        val walP95 = walTimes.sorted()[walTimes.size * 95 / 100]
        val deleteTotal = deleteTimes.sum()
        val deleteP50 = deleteTimes.sorted()[deleteTimes.size / 2]
        val deleteP95 = deleteTimes.sorted()[deleteTimes.size * 95 / 100]

        callback.onLog(ExperimentLog(now(), phase, "WAL: 总耗时 ${walTotal}ms | P50=${walP50}ms | P95=${walP95}ms", LogType.SUCCESS))
        callback.onLog(ExperimentLog(now(), phase, "TRUNCATE: 总耗时 ${deleteTotal}ms | P50=${deleteP50}ms | P95=${deleteP95}ms", LogType.SUCCESS))
        val diff = if (deleteTotal > 0) ((deleteTotal - walTotal).toFloat() / deleteTotal * 100).toInt() else 0
        callback.onLog(ExperimentLog(now(), phase, "WAL ${if (diff > 0) "快" else "慢"} ${diff.abs()}%", LogType.INFO))
        Log.d(TAG, "$phase: WAL=$walTotal ms, TRUNCATE=$deleteTotal ms")
    }

    private fun Int.abs() = if (this < 0) -this else this
}
