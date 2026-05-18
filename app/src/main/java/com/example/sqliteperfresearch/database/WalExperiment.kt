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

        walDb.setWalMode(true)
        deleteDb.setWalMode(false)

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

    // ====== 基础实验 (无事务包裹) ======

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
                        "SELECT * FROM ${Schema.TABLE_NAME} LIMIT 50000 OFFSET ${tid * 5000}",
                        null
                    ).close()
                } else {
                    val writer = db.writableDatabase
                    writer.beginTransaction()
                    try {
                        for (i in 0 until 2000) {
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

    // ====== 事务模式 × 日志模式 完整对比 ======

    /**
     * 三种事务模式 × 三种场景 × 两种日志模式 完整对比
     *
     * 场景: 并发读、并发写、读写混合
     * 对于每种场景，对每种事务模式分别在 WAL 和 TRUNCATE 上跑，输出对比结果
     */
    suspend fun runFullTxModeComparison(
        walDb: PerfDatabase,
        deleteDb: PerfDatabase,
        threadCount: Int,
        rowsPerThread: Int,
        callback: Callback,
    ) {
        callback.onLog(ExperimentLog(now(), "事务模式对比", "===== WAL vs TRUNCATE 完整对比 =====", LogType.INFO))
        callback.onLog(ExperimentLog(now(), "事务模式对比", "线程数: $threadCount, 每线程行数: $rowsPerThread", LogType.INFO))

        // 场景1: 并发读
        callback.onLog(ExperimentLog(now(), "事务模式对比", "===== 场景1: 并发读 =====", LogType.INFO))
        ReadTransactionMode.entries.forEach { txMode ->
            callback.onLog(ExperimentLog(now(), "事务模式对比", "--- ${txMode.label} 并发读 ---", LogType.INFO))
            val (walMs, walP50, walP95, walOk) = measureTxModeRead(walDb, txMode, threadCount, rowsPerThread)
            val (delMs, delP50, delP95, delOk) = measureTxModeRead(deleteDb, txMode, threadCount, rowsPerThread)
            logComparison(callback, txMode, walMs, walP50, walP95, walOk, delMs, delP50, delP95, delOk, threadCount)
        }

        // 场景2: 并发写
        callback.onLog(ExperimentLog(now(), "事务模式对比", "===== 场景2: 并发写 =====", LogType.INFO))
        ReadTransactionMode.entries.forEach { txMode ->
            if (txMode == ReadTransactionMode.READ_ONLY) {
                callback.onLog(ExperimentLog(now(), "事务模式对比", "并发写跳过READ_ONLY模式", LogType.WARNING))
                return@forEach
            }
            callback.onLog(ExperimentLog(now(), "事务模式对比", "--- ${txMode.label} 并发写 ---", LogType.INFO))
            val (walMs, walP50, walP95, walOk) = measureTxModeWrite(walDb, txMode, threadCount, rowsPerThread)
            val (delMs, delP50, delP95, delOk) = measureTxModeWrite(deleteDb, txMode, threadCount, rowsPerThread)
            logComparison(callback, txMode, walMs, walP50, walP95, walOk, delMs, delP50, delP95, delOk, threadCount)
        }

        // 场景3: 读写混合
        callback.onLog(ExperimentLog(now(), "事务模式对比", "===== 场景3: 读写混合 (8读+2写) =====", LogType.INFO))
        ReadTransactionMode.entries.forEach { txMode ->
            callback.onLog(ExperimentLog(now(), "事务模式对比", "--- ${txMode.label} 读写混合 ---", LogType.INFO))
            val (walMs, walP50, walP95, walOk) = measureTxModeMixed(walDb, txMode, threadCount, rowsPerThread)
            val (delMs, delP50, delP95, delOk) = measureTxModeMixed(deleteDb, txMode, threadCount, rowsPerThread)
            logComparison(callback, txMode, walMs, walP50, walP95, walOk, delMs, delP50, delP95, delOk, threadCount)
        }

        callback.onLog(ExperimentLog(now(), "事务模式对比", "===== 对比完成 =====", LogType.INFO))
    }

    private fun logComparison(
        callback: Callback,
        txMode: ReadTransactionMode,
        walMs: Long, walP50: Long, walP95: Long, walOk: Int,
        delMs: Long, delP50: Long, delP95: Long, delOk: Int,
        threadCount: Int,
    ) {
        val diff = if (delMs > 0) ((delMs - walMs).toFloat() / delMs * 100).toInt() else 0
        val type = if (walOk == threadCount) LogType.SUCCESS else LogType.ERROR
        callback.onLog(ExperimentLog(now(), "事务模式对比", "【${txMode.label}】WAL: 总耗时 ${walMs}ms | P50=${walP50}ms | P95=${walP95}ms | 成功 ${walOk}/$threadCount", LogType.SUCCESS))
        callback.onLog(ExperimentLog(now(), "事务模式对比", "【${txMode.label}】TRUNCATE: 总耗时 ${delMs}ms | P50=${delP50}ms | P95=${delP95}ms | 成功 ${delOk}/$threadCount", LogType.SUCCESS))
        callback.onLog(ExperimentLog(now(), "事务模式对比", "【${txMode.label}】WAL ${if (diff > 0) "快" else "慢"} ${diff.abs()}%", LogType.INFO))
    }

    private fun measureTxModeRead(
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

    private fun measureTxModeWrite(
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
                val conn = db.writableDatabase
                when (txMode) {
                    ReadTransactionMode.EXCLUSIVE -> conn.beginTransaction()
                    ReadTransactionMode.NON_EXCLUSIVE -> conn.beginTransactionNonExclusive()
                    ReadTransactionMode.READ_ONLY -> conn.beginTransactionReadOnly()
                }
                try {
                    val ms = measureTimeMillis {
                        for (j in 0 until rowsPerThread) {
                            val cv = ContentValues()
                            cv.put("name", "write_${tid}_$j")
                            cv.put("counter", tid * 1000 + j)
                            conn.insertOrThrow(Schema.TABLE_NAME, null, cv)
                        }
                    }
                    conn.setTransactionSuccessful()
                    synchronized(results) { results.add(ms) }
                } catch (e: Throwable) {
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

    private fun measureTxModeMixed(
        db: PerfDatabase,
        txMode: ReadTransactionMode,
        threadCount: Int,
        rowsPerThread: Int,
    ): TxModeResult {
        val results = mutableListOf<Long>()
        val readCount = (threadCount * 0.8).toInt()
        val writeCount = threadCount - readCount
        val latch = java.util.concurrent.CountDownLatch(threadCount)

        for (i in 0 until threadCount) {
            val tid = i
            val isWriter = i >= readCount
            Thread {
                val conn = if (isWriter) db.writableDatabase else db.readableDatabase
                if (!isWriter) {
                    when (txMode) {
                        ReadTransactionMode.EXCLUSIVE -> conn.beginTransaction()
                        ReadTransactionMode.NON_EXCLUSIVE -> conn.beginTransactionNonExclusive()
                        ReadTransactionMode.READ_ONLY -> conn.beginTransactionReadOnly()
                    }
                }
                try {
                    val ms = measureTimeMillis {
                        if (!isWriter) {
                            conn.rawQuery(
                                "SELECT * FROM ${Schema.TABLE_NAME} LIMIT $rowsPerThread OFFSET ${tid * rowsPerThread}",
                                null
                            ).use { cursor ->
                                var c = 0
                                while (cursor.moveToNext()) c++
                            }
                        } else {
                            for (j in 0 until 200) {
                                val cv = ContentValues()
                                cv.put("score", Random.nextDouble(0.0, 100.0))
                                conn.update(Schema.TABLE_NAME, cv, "id = ?", arrayOf("${tid * 200 + j + 1}"))
                            }
                        }
                    }
                    if (!isWriter) {
                        conn.setTransactionSuccessful()
                    }
                    synchronized(results) { results.add(ms) }
                } catch (e: Exception) {
                    synchronized(results) { results.add(-1L) }
                } finally {
                    if (!isWriter) {
                        conn.endTransaction()
                    }
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

    suspend fun runWriteBlocksReadTest(
        walDb: PerfDatabase,
        deleteDb: PerfDatabase,
        insertRows: Int = 5000,
        callback: Callback,
    ) {
        val phase = "写阻塞读"
        callback.onLog(ExperimentLog(now(), phase, "===== 写阻塞读测试 =====", LogType.INFO))
        callback.onLog(ExperimentLog(now(), phase, "场景: 一个线程用指定事务模式批量插入 $insertRows 行, 同时另一个线程查询, 观察查询是否被阻塞及耗时", LogType.INFO))

        ReadTransactionMode.entries.filter { it != ReadTransactionMode.READ_ONLY }.forEach { txMode ->
            callback.onLog(ExperimentLog(now(), phase, "--- ${txMode.label} ---", LogType.INFO))
            val (walTotal, walReadMs, walBlocked) = measureWriteBlocksRead(walDb, txMode, insertRows, callback)
            val (delTotal, delReadMs, delBlocked) = measureWriteBlocksRead(deleteDb, txMode, insertRows, callback)
            val walStatus = if (walBlocked) "被阻塞" else "未阻塞"
            val delStatus = if (delBlocked) "被阻塞" else "未阻塞"
            callback.onLog(ExperimentLog(now(), phase, "【${txMode.label}】WAL: 写入总耗时 ${walTotal}ms | 查询耗时 ${walReadMs}ms | $walStatus", LogType.SUCCESS))
            callback.onLog(ExperimentLog(now(), phase, "【${txMode.label}】TRUNCATE: 写入总耗时 ${delTotal}ms | 查询耗时 ${delReadMs}ms | $delStatus", LogType.SUCCESS))
            if (walReadMs > 0 && delReadMs > 0) {
                val diff = ((delReadMs - walReadMs).toFloat() / delReadMs * 100).toInt()
                callback.onLog(ExperimentLog(now(), phase, "【${txMode.label}】WAL 查询 ${if (diff > 0) "快" else "慢"} ${diff.abs()}%", LogType.INFO))
            }
        }

        callback.onLog(ExperimentLog(now(), phase, "===== 测试完成 =====", LogType.INFO))
    }

    private fun measureWriteBlocksRead(
        db: PerfDatabase,
        txMode: ReadTransactionMode,
        insertRows: Int,
        callback: Callback,
    ): WriteBlocksReadResult {
        val dbLabel = db.getJournalMode()
        var writeTotalMs = 0L
        var readMs = 0L
        var writerStarted = false

        val writerThread = Thread {
            val conn = db.writableDatabase
            when (txMode) {
                ReadTransactionMode.EXCLUSIVE -> conn.beginTransaction()
                ReadTransactionMode.NON_EXCLUSIVE -> conn.beginTransactionNonExclusive()
                ReadTransactionMode.READ_ONLY -> return@Thread
            }
            try {
                callback.onLog(ExperimentLog(now(), "写阻塞读", "[$dbLabel/${txMode.label}] 写入开始"))
                writerStarted = true
                val ms = measureTimeMillis {
                    for (i in 0 until insertRows) {
                        val cv = ContentValues()
                        cv.put("name", "block_read_$i")
                        cv.put("counter", i)
                        conn.insertOrThrow(Schema.TABLE_NAME, null, cv)
                    }
                }
                writeTotalMs = ms
                Thread.sleep(3*1000)
                conn.setTransactionSuccessful()
                callback.onLog(ExperimentLog(now(), "写阻塞读", "[$dbLabel/${txMode.label}] 写入完成, 耗时 ${ms}ms"))
            } catch (e: Throwable) {
                callback.onLog(ExperimentLog(now(), "写阻塞读", "[$dbLabel/${txMode.label}] 写入异常: ${e.message}", LogType.ERROR))
            } finally {
                conn.endTransaction()
            }
        }

        val readerThread = Thread {
            while (!writerStarted) Thread.sleep(1)
            try {
                callback.onLog(ExperimentLog(now(), "写阻塞读", "[$dbLabel/${txMode.label}] 读取开始"))
                val ms = measureTimeMillis {
                    db.readableDatabase.rawQuery(
                        "SELECT * FROM ${Schema.TABLE_NAME} LIMIT 1000",
                        null
                    ).use { cursor ->
                        var c = 0
                        while (cursor.moveToNext()) c++
                    }
                }
                readMs = ms
                callback.onLog(ExperimentLog(now(), "写阻塞读", "[$dbLabel/${txMode.label}] 读取完成, 耗时 ${ms}ms"))
            } catch (e: Throwable) {
                callback.onLog(ExperimentLog(now(), "写阻塞读", "[$dbLabel/${txMode.label}] 读取异常: ${e.message}", LogType.ERROR))
            }
        }

        writerThread.start()
        readerThread.start()
        writerThread.join()
        readerThread.join()

        val readBlocked = readMs > (writeTotalMs * 3 / 4)
        return WriteBlocksReadResult(writeTotalMs, readMs, readBlocked)
    }

    private data class WriteBlocksReadResult(val writeTotalMs: Long, val readMs: Long, val blocked: Boolean)

    private suspend fun runTimedComparison(
        phase: String,
        walDb: PerfDatabase,
        deleteDb: PerfDatabase,
        callback: Callback,
        block: suspend (PerfDatabase, Int) -> Long,
    ) = supervisorScope {
        val walTimes = List(10) { tid ->
            async(Dispatchers.IO) { block(walDb, tid) }
        }.awaitAll()

        val deleteTimes = List(10) { tid ->
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
