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

    suspend fun openExistingDatabases(callback: Callback): Pair<PerfDatabase, PerfDatabase>? {
        val walPath = context.getDatabasePath("test_wal.db")
        val deletePath = context.getDatabasePath("test_delete.db")
        if (!walPath.exists() || !deletePath.exists()) {
            callback.onLog(ExperimentLog(now(), "WAL对比", "未找到现有测试数据库, 请先「新建两个 DB」", LogType.WARNING))
            return null
        }
        val walDb = PerfDatabase(context, "test_wal.db")
        val deleteDb = PerfDatabase(context, "test_delete.db")
        val walMode = walDb.getJournalMode()
        val deleteMode = deleteDb.getJournalMode()
        val walCount = walDb.getRowCount()
        val deleteCount = deleteDb.getRowCount()
        callback.onLog(ExperimentLog(now(), "WAL对比", "已打开现有 WAL DB ($walMode): $walCount 行", LogType.SUCCESS))
        callback.onLog(ExperimentLog(now(), "WAL对比", "已打开现有 TRUNCATE DB ($deleteMode): $deleteCount 行", LogType.SUCCESS))
        return walDb to deleteDb
    }

    suspend fun fillData(walDb: PerfDatabase, deleteDb: PerfDatabase, count: Int, callback: Callback) = supervisorScope {
        callback.onLog(ExperimentLog(now(), "WAL对比", "开始并发填充数据: 每个 DB 填充 $count 行", LogType.INFO))
        val gen = DataGenerator()

        val walJob = async(Dispatchers.IO) {
            callback.onLog(ExperimentLog(now(), "WAL对比", "WAL DB 开始填充...", LogType.INFO))
            val startMs = System.currentTimeMillis()
            gen.generate(walDb.writableDatabase, count) { }
            val elapsed = System.currentTimeMillis() - startMs
            callback.onLog(ExperimentLog(now(), "WAL对比", "WAL DB 填充完成: ${walDb.getRowCount()} 行, 耗时 ${elapsed}ms", LogType.SUCCESS))
            walDb.getRowCount()
        }

        val deleteJob = async(Dispatchers.IO) {
            callback.onLog(ExperimentLog(now(), "WAL对比", "TRUNCATE DB 开始填充...", LogType.INFO))
            val startMs = System.currentTimeMillis()
            gen.generate(deleteDb.writableDatabase, count) { }
            val elapsed = System.currentTimeMillis() - startMs
            callback.onLog(ExperimentLog(now(), "WAL对比", "TRUNCATE DB 填充完成: ${deleteDb.getRowCount()} 行, 耗时 ${elapsed}ms", LogType.SUCCESS))
            deleteDb.getRowCount()
        }

        val walCount = walJob.await()
        val deleteCount = deleteJob.await()
        callback.onLog(ExperimentLog(now(), "WAL对比", "两个 DB 填充完成: WAL=$walCount 行, TRUNCATE=$deleteCount 行", LogType.SUCCESS))
    }

    suspend fun runConcurrentReads(walDb: PerfDatabase, deleteDb: PerfDatabase, threadCount: Int, rowsPerThread: Int, callback: Callback) {
        callback.onLog(ExperimentLog(now(), "并发读", "启动 $threadCount 线程并发读, 每线程 $rowsPerThread 行", LogType.INFO))
        runTimedComparison("并发读", walDb, deleteDb, callback) { db, tid ->
            measureTimeMillis {
                db.readableDatabase.rawQuery(
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

    /**
     * 三种事务模式 × 两种日志模式 完整对比
     * 对于每种事务模式，分别在 WAL 和 TRUNCATE 上跑并发读，输出对比表格
     */
    suspend fun runFullTxModeComparison(
        walDb: PerfDatabase,
        deleteDb: PerfDatabase,
        threadCount: Int,
        rowsPerThread: Int,
        callback: Callback,
    ) {
        callback.onLog(ExperimentLog(now(), "事务模式对比", "===== WAL vs TRUNCATE 三种事务模式并发读对比 =====", LogType.INFO))

        ReadTransactionMode.entries.forEach { txMode ->
            callback.onLog(ExperimentLog(now(), "事务模式对比", "--- ${txMode.label}: 启动 $threadCount 线程并发读 ---", LogType.INFO))
            val (walMs, walP50, walP95, walOk) = measureTxMode(walDb, txMode, threadCount, rowsPerThread)
            val (delMs, delP50, delP95, delOk) = measureTxMode(deleteDb, txMode, threadCount, rowsPerThread)

            val diff = if (delMs > 0) ((delMs - walMs).toFloat() / delMs * 100).toInt() else 0
            callback.onLog(ExperimentLog(now(), "事务模式对比", "【${txMode.label}】WAL: 总耗时 ${walMs}ms | P50=${walP50}ms | P95=${walP95}ms | 成功 ${walOk}/$threadCount", LogType.SUCCESS))
            callback.onLog(ExperimentLog(now(), "事务模式对比", "【${txMode.label}】TRUNCATE: 总耗时 ${delMs}ms | P50=${delP50}ms | P95=${delP95}ms | 成功 ${delOk}/$threadCount", LogType.SUCCESS))
            callback.onLog(ExperimentLog(now(), "事务模式对比", "【${txMode.label}】WAL ${if (diff > 0) "快" else "慢"} ${diff.abs()}%", LogType.INFO))
        }

        callback.onLog(ExperimentLog(now(), "事务模式对比", "===== 对比完成 =====", LogType.INFO))
    }

    private fun measureTxMode(
        db: PerfDatabase,
        txMode: ReadTransactionMode,
        threadCount: Int,
        rowsPerThread: Int,
    ): TxModeResult {
        val results = mutableListOf<Long>()
        val latch = java.util.concurrent.CountDownLatch(threadCount)

        for (i in 0 until threadCount) {
            val tid = i
            Thread {
                val conn = db.readableDatabase
                when (txMode) {
                    ReadTransactionMode.EXCLUSIVE -> conn.beginTransaction()
                    ReadTransactionMode.NON_EXCLUSIVE -> conn.beginTransactionNonExclusive()
                    ReadTransactionMode.READ_ONLY -> conn.beginTransactionReadOnly()
                }
                try {
                    val ms = measureTimeMillis {
                        conn.rawQuery(
                            "SELECT * FROM ${Schema.TABLE_NAME} LIMIT $rowsPerThread OFFSET ${tid * rowsPerThread}",
                            null
                        ).use { cursor ->
                            var c = 0
                            while (cursor.moveToNext()) c++
                        }
                    }
                    conn.setTransactionSuccessful()
                    synchronized(results) { results.add(ms) }
                } catch (e: Exception) {
                    synchronized(results) { results.add(-1L) }
                } finally {
                    conn.endTransaction()
                    latch.countDown()
                }
            }.start()
        }

        latch.await()
        val validResults = results.filter { it >= 0 }
        val totalTime = validResults.sum()
        val p50 = validResults.sorted().let { if (it.isNotEmpty()) it[it.size / 2] else 0L }
        val p95 = validResults.sorted().let { if (it.isNotEmpty()) it[it.size * 95 / 100] else 0L }
        return TxModeResult(totalTime, p50, p95, validResults.size)
    }

    private data class TxModeResult(val total: Long, val p50: Long, val p95: Long, val okCount: Int)

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
