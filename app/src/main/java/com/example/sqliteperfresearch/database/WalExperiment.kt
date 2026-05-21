// ============================================================
// WAL 并发对比实验（WalExperiment）
// ============================================================
// 职责：
// 1. 创建两个独立 DB（WAL 模式 / TRUNCATE 模式），填充相同数据
// 2. 基础实验：并发读、并发写、读写混合，对比 WAL vs TRUNCATE 耗时
// 3. 完整事务模式对比：三种事务模式（独占/非独占/只读）× 两种日志模式 × 三种场景
// 4. 写阻塞读实验：验证 writer 执行批量插入时，reader 是否被阻塞
//
// 核心对比维度：
// - 日志模式：WAL（Write-Ahead Logging）vs TRUNCATE（传统日志模式）
// - WAL：写入追加到 WAL 文件，读写不阻塞，支持并发读
// - TRUNCATE：写入直接修改主文件，写操作持有 EXCLUSIVE lock，阻塞所有读
//
// 关键设计：
// - 使用两个独立的 PerfDatabase 实例（test_wal.db / test_delete.db）
// - 通过 setWalMode(true/false) 切换各自的日志模式
// - 所有实验使用相同的线程数和行数，保证对比公平
// ============================================================

package com.example.sqliteperfresearch.database

import android.content.ContentValues    // SQLite 插入/更新操作的键值对容器
import android.util.Log
import com.example.sqliteperfresearch.model.ExperimentLog
import com.example.sqliteperfresearch.model.LogType
import com.example.sqliteperfresearch.util.LOG_TAG
import kotlinx.coroutines.Dispatchers   // 协程调度器：IO 线程池用于数据库操作
import kotlinx.coroutines.async         // 启动异步协程，返回 Deferred<T>
import kotlinx.coroutines.awaitAll      // 等待所有 Deferred 完成并返回结果
import kotlinx.coroutines.supervisorScope // 监督作用域：子协程失败不影响兄弟协程
import kotlin.random.Random             // Kotlin 随机数 API
import kotlin.system.measureTimeMillis  // 测量代码块执行耗时（毫秒）

/**
 * WAL 并发对比实验类
 *
 * 管理两个独立的数据库实例，分别在 WAL 和 TRUNCATE 模式下执行相同的并发实验，
 * 对比两种日志模式在不同场景下的性能差异。
 *
 * @param context Android Context，用于创建数据库文件
 */
class WalExperiment(private val context: android.content.Context) {
    companion object {
        /** 日志 tag：SQLitePerf.WAL */
        const val TAG = "$LOG_TAG.WAL"
    }

    /** 实验日志回调接口 */
    interface Callback {
        fun onLog(log: ExperimentLog)
    }

    /** 生成时间戳字符串，格式：HH:mm:ss.SSS */
    fun now() = java.text.SimpleDateFormat("HH:mm:ss.SSS", java.util.Locale.getDefault()).format(java.util.Date())

    /** 获取数据库文件绝对路径 */
    private fun dbPath(name: String) = context.getDatabasePath(name).absolutePath

    // ============================================================
    // 数据库准备
    // ============================================================

    /**
     * 准备两个全新的数据库（WAL + TRUNCATE）
     *
     * 流程：
     * 1. 删除旧的 test_wal.db 和 test_delete.db（如果存在）
     * 2. 创建两个新的 PerfDatabase 实例
     * 3. walDb 启用 WAL 模式，deleteDb 禁用 WAL（使用 TRUNCATE）
     * 4. 验证并输出各自的 journal_mode
     *
     * @return Pair<WAL数据库, TRUNCATE数据库>
     */
    suspend fun prepareDatabases(callback: Callback): Pair<PerfDatabase, PerfDatabase> {
        // 删除旧数据库，确保从零开始
        context.deleteDatabase("test_wal.db")
        context.deleteDatabase("test_delete.db")

        // 创建两个独立的数据库实例（不同文件名 = 不同 .db 文件）
        val walDb = PerfDatabase(context, "test_wal.db")       // WAL 模式数据库
        val deleteDb = PerfDatabase(context, "test_delete.db")  // TRUNCATE 模式数据库

        // 设置各自的日志模式
        walDb.setWalMode(true)    // 启用 WAL
        deleteDb.setWalMode(false) // 禁用 WAL，回到 TRUNCATE

        // 输出验证结果
        callback.onLog(ExperimentLog(now(), "WAL对比", "WAL DB 模式: ${walDb.getJournalMode()}", LogType.INFO))
        callback.onLog(ExperimentLog(now(), "WAL对比", "TRUNCATE DB 模式: ${deleteDb.getJournalMode()}", LogType.INFO))
        return walDb to deleteDb
    }

    /**
     * 打开已有的两个数据库文件
     *
     * 用于用户之前已经创建过数据库，不想重新填充数据的场景。
     * 打开后仍然设置各自的 WAL/TRUNCATE 模式。
     *
     * @return Pair<WAL数据库, TRUNCATE数据库>，如果文件不存在返回 null
     */
    suspend fun openExistingDatabases(callback: Callback): Pair<PerfDatabase, PerfDatabase>? {
        val walPath = context.getDatabasePath("test_wal.db")
        val deletePath = context.getDatabasePath("test_delete.db")
        // 检查两个数据库文件是否都存在
        if (!walPath.exists() || !deletePath.exists()) {
            callback.onLog(ExperimentLog(now(), "WAL对比", "未找到现有测试数据库, 请先「新建两个 DB」", LogType.WARNING))
            return null
        }
        val walDb = PerfDatabase(context, "test_wal.db")
        val deleteDb = PerfDatabase(context, "test_delete.db")

        // 打开后仍然设置日志模式（确保模式正确）
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

    /**
     * 并发填充两个数据库
     *
     * 使用 supervisorScope + async 在两个 IO 协程中同时填充两个数据库。
     * WAL 和 TRUNCATE 数据库并发填充，利用 WAL 的并发写入优势。
     *
     * @param walDb WAL 模式数据库
     * @param deleteDb TRUNCATE 模式数据库
     * @param count 每个数据库填充的行数
     * @param callback 日志回调
     */
    suspend fun fillData(walDb: PerfDatabase, deleteDb: PerfDatabase, count: Int, callback: Callback) = supervisorScope {
        callback.onLog(ExperimentLog(now(), "WAL对比", "开始并发填充数据: 每个 DB 填充 $count 行", LogType.INFO))
        val gen = DataGenerator()

        // WAL 数据库填充任务
        val walJob = async(Dispatchers.IO) {
            callback.onLog(ExperimentLog(now(), "WAL对比", "WAL DB 开始填充...", LogType.INFO))
            val startMs = System.currentTimeMillis()
            gen.generate(walDb.writableDatabase, count) { }  // 不传 progress 回调，避免频繁重组
            val elapsed = System.currentTimeMillis() - startMs
            callback.onLog(ExperimentLog(now(), "WAL对比", "WAL DB 填充完成: ${walDb.getRowCount()} 行, 耗时 ${elapsed}ms", LogType.SUCCESS))
            walDb.getRowCount()
        }

        // TRUNCATE 数据库填充任务
        val deleteJob = async(Dispatchers.IO) {
            callback.onLog(ExperimentLog(now(), "WAL对比", "TRUNCATE DB 开始填充...", LogType.INFO))
            val startMs = System.currentTimeMillis()
            gen.generate(deleteDb.writableDatabase, count) { }
            val elapsed = System.currentTimeMillis() - startMs
            callback.onLog(ExperimentLog(now(), "WAL对比", "TRUNCATE DB 填充完成: ${deleteDb.getRowCount()} 行, 耗时 ${elapsed}ms", LogType.SUCCESS))
            deleteDb.getRowCount()
        }

        // 等待两个任务都完成
        val walCount = walJob.await()
        val deleteCount = deleteJob.await()
        callback.onLog(ExperimentLog(now(), "WAL对比", "两个 DB 填充完成: WAL=$walCount 行, TRUNCATE=$deleteCount 行", LogType.SUCCESS))
    }

    // ============================================================
    // 基础实验（无事务包裹）
    // ============================================================
    // 使用 runTimedComparison 统一框架，在 WAL 和 TRUNCATE 上执行相同的操作，
    // 对比总耗时、P50（中位数）、P95（95 分位）耗时。
    // ============================================================

    /**
     * 基础实验 1：并发读
     *
     * 启动 10 个线程，每个线程查询不同偏移范围的 data，
     * 对比 WAL 和 TRUNCATE 模式下并发读取的耗时。
     *
     * @param threadCount 并发线程数
     * @param rowsPerThread 每个线程读取的行数
     */
    suspend fun runConcurrentReads(walDb: PerfDatabase, deleteDb: PerfDatabase, threadCount: Int, rowsPerThread: Int, callback: Callback) {
        callback.onLog(ExperimentLog(now(), "并发读", "启动 $threadCount 线程并发读, 每线程 $rowsPerThread 行", LogType.INFO))
        // runTimedComparison 自动在两个 DB 上执行相同操作并输出对比结果
        runTimedComparison("并发读", walDb, deleteDb, callback) { db, tid ->
            measureTimeMillis {
                // 每个线程读取不同偏移范围的数据（避免重复）
                db.readableDatabase.rawQuery(
                    "SELECT * FROM ${Schema.TABLE_NAME} LIMIT $rowsPerThread OFFSET ${tid * rowsPerThread}",
                    null
                ).use {  // .use 是 Kotlin 的 AutoCloseable 扩展，自动关闭 Cursor
                    var c = 0
                    while (it.moveToNext()) c++
                }
            }
        }
    }

    /**
     * 基础实验 2：读写混合
     *
     * 8 个读线程 + 2 个写线程并发执行。
     * 读线程查询 50000 行数据，写线程更新 2000 行数据。
     * 对比 WAL（读写不阻塞）vs TRUNCATE（写阻塞读）的差异。
     */
    suspend fun runMixedReadWrite(walDb: PerfDatabase, deleteDb: PerfDatabase, callback: Callback) {
        callback.onLog(ExperimentLog(now(), "读写混合", "8 读 + 2 写并发执行", LogType.INFO))
        runTimedComparison("读写混合", walDb, deleteDb, callback) { db, tid ->
            measureTimeMillis {
                if (tid < 8) {
                    // 线程 0-7：读操作
                    db.readableDatabase.rawQuery(
                        "SELECT * FROM ${Schema.TABLE_NAME} LIMIT 50000 OFFSET ${tid * 5000}",
                        null
                    ).close()
                } else {
                    // 线程 8-9：写操作（事务包裹批量 UPDATE）
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

    /**
     * 基础实验 3：并发写
     *
     * 启动 10 个线程同时写入数据，每个线程插入 rowsPerThread 行。
     * TRUNCATE 模式下写操作互相阻塞（串行化），WAL 模式下并发度更高。
     *
     * @param threadCount 并发写线程数
     * @param rowsPerThread 每个线程插入的行数
     */
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

    // ============================================================
    // 事务模式 × 日志模式 完整对比
    // ============================================================
    // 三种场景（并发读 / 并发写 / 读写混合）× 三种事务模式（独占 / 非独占 / 只读）
    // × 两种日志模式（WAL / TRUNCATE），共 3 × 3 × 2 = 18 种组合
    // （并发写场景跳过 READ_ONLY 模式，因为只读事务不能写）
    // ============================================================

    /**
     * 完整事务模式对比实验
     *
     * 对于每种场景，依次对三种事务模式分别在 WAL 和 TRUNCATE 上执行，
     * 输出总耗时、P50、P95、成功线程数和差异百分比。
     *
     * @param walDb WAL 模式数据库
     * @param deleteDb TRUNCATE 模式数据库
     * @param threadCount 并发线程数
     * @param rowsPerThread 每线程操作的行数
     * @param callback 日志回调
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

        // ====== 场景 1：并发读 ======
        callback.onLog(ExperimentLog(now(), "事务模式对比", "===== 场景1: 并发读 =====", LogType.INFO))
        ReadTransactionMode.entries.forEach { txMode ->
            callback.onLog(ExperimentLog(now(), "事务模式对比", "--- ${txMode.label} 并发读 ---", LogType.INFO))
            // 分别在 WAL 和 TRUNCATE 上测量
            val (walMs, walP50, walP95, walOk) = measureTxModeRead(walDb, txMode, threadCount, rowsPerThread)
            val (delMs, delP50, delP95, delOk) = measureTxModeRead(deleteDb, txMode, threadCount, rowsPerThread)
            logComparison(callback, txMode, walMs, walP50, walP95, walOk, delMs, delP50, delP95, delOk, threadCount)
        }

        // ====== 场景 2：并发写 ======
        callback.onLog(ExperimentLog(now(), "事务模式对比", "===== 场景2: 并发写 =====", LogType.INFO))
        ReadTransactionMode.entries.forEach { txMode ->
            // READ_ONLY 事务不能写，跳过
            if (txMode == ReadTransactionMode.READ_ONLY) {
                callback.onLog(ExperimentLog(now(), "事务模式对比", "并发写跳过READ_ONLY模式", LogType.WARNING))
                return@forEach  // Kotlin forEach 中的 continue 等价物
            }
            callback.onLog(ExperimentLog(now(), "事务模式对比", "--- ${txMode.label} 并发写 ---", LogType.INFO))
            val (walMs, walP50, walP95, walOk) = measureTxModeWrite(walDb, txMode, threadCount, rowsPerThread)
            val (delMs, delP50, delP95, delOk) = measureTxModeWrite(deleteDb, txMode, threadCount, rowsPerThread)
            logComparison(callback, txMode, walMs, walP50, walP95, walOk, delMs, delP50, delP95, delOk, threadCount)
        }

        // ====== 场景 3：读写混合 ======
        callback.onLog(ExperimentLog(now(), "事务模式对比", "===== 场景3: 读写混合 (8读+2写) =====", LogType.INFO))
        ReadTransactionMode.entries.forEach { txMode ->
            callback.onLog(ExperimentLog(now(), "事务模式对比", "--- ${txMode.label} 读写混合 ---", LogType.INFO))
            val (walMs, walP50, walP95, walOk) = measureTxModeMixed(walDb, txMode, threadCount, rowsPerThread)
            val (delMs, delP50, delP95, delOk) = measureTxModeMixed(deleteDb, txMode, threadCount, rowsPerThread)
            logComparison(callback, txMode, walMs, walP50, walP95, walOk, delMs, delP50, delP95, delOk, threadCount)
        }

        callback.onLog(ExperimentLog(now(), "事务模式对比", "===== 对比完成 =====", LogType.INFO))
    }

    // ============================================================
    // 结果输出工具
    // ============================================================

    /**
     * 输出 WAL vs TRUNCATE 对比结果
     *
     * 计算差异百分比：(TRUNCATE - WAL) / TRUNCATE * 100
     * 正数表示 WAL 更快，负数表示 WAL 更慢。
     *
     * @param txMode 当前事务模式
     * @param walMs / delMs WAL / TRUNCATE 总耗时
     * @param walP50 / delP50 WAL / TRUNCATE P50 耗时
     * @param walP95 / delP95 WAL / TRUNCATE P95 耗时
     * @param walOk / delOk WAL / TRUNCATE 成功线程数
     */
    private fun logComparison(
        callback: Callback,
        txMode: ReadTransactionMode,
        walMs: Long, walP50: Long, walP95: Long, walOk: Int,
        delMs: Long, delP50: Long, delP95: Long, delOk: Int,
        threadCount: Int,
    ) {
        // 计算差异百分比：WAL 相对于 TRUNCATE 快/慢多少
        val diff = if (delMs > 0) ((delMs - walMs).toFloat() / delMs * 100).toInt() else 0
        // 如果所有线程都成功，标为 SUCCESS；否则标为 ERROR
        val type = if (walOk == threadCount) LogType.SUCCESS else LogType.ERROR
        callback.onLog(ExperimentLog(now(), "事务模式对比", "【${txMode.label}】WAL: 总耗时 ${walMs}ms | P50=${walP50}ms | P95=${walP95}ms | 成功 ${walOk}/$threadCount", LogType.SUCCESS))
        callback.onLog(ExperimentLog(now(), "事务模式对比", "【${txMode.label}】TRUNCATE: 总耗时 ${delMs}ms | P50=${delP50}ms | P95=${delP95}ms | 成功 ${delOk}/$threadCount", LogType.SUCCESS))
        callback.onLog(ExperimentLog(now(), "事务模式对比", "【${txMode.label}】WAL ${if (diff > 0) "快" else "慢"} ${diff.abs()}%", LogType.INFO))
    }

    // ============================================================
    // 事务模式测量方法
    // ============================================================
    // 使用原生 Thread + CountDownLatch（而非协程），因为：
    // 1. 需要精确控制每个线程的事务生命周期
    // 2. 需要 synchronized 保护共享 results 列表
    // 3. 每个线程使用独立的数据库连接（db.readableDatabase/writableDatabase）
    // ============================================================

    /**
     * 测量事务模式下的并发读耗时
     *
     * 每个线程：
     * 1. 获取数据库连接
     * 2. 开启指定类型的事务
     * 3. 执行 SELECT 查询指定偏移范围的数据
     * 4. 提交事务，记录耗时
     *
     * @return TxModeResult（总耗时、P50、P95、成功线程数）
     */
    private fun measureTxModeRead(
        db: PerfDatabase,
        txMode: ReadTransactionMode,
        threadCount: Int,
        rowsPerThread: Int,
    ): TxModeResult {
        val results = mutableListOf<Long>()  // 存储每个线程的耗时
        val latch = java.util.concurrent.CountDownLatch(threadCount)

        // 创建 threadCount 个原生线程
        for (i in 0 until threadCount) {
            val tid = i
            Thread {
                val conn = db.readableDatabase
                // 根据事务模式开启不同类型的事务
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
                    // synchronized 保护共享 results 列表（多线程并发写入）
                    synchronized(results) { results.add(ms) }
                } catch (e: Exception) {
                    // 失败记录 -1
                    synchronized(results) { results.add(-1L) }
                } finally {
                    conn.endTransaction()
                    latch.countDown()
                }
            }.start()
        }

        // 等待所有线程完成
        latch.await()
        // 过滤有效结果（>= 0 表示成功）
        val validResults = results.filter { it >= 0 }
        val totalTime = validResults.sum()
        // P50（中位数）：排序后取中间值
        val p50 = validResults.sorted().let { if (it.isNotEmpty()) it[it.size / 2] else 0L }
        // P95（95 分位）：排序后取 95% 位置的值
        val p95 = validResults.sorted().let { if (it.isNotEmpty()) it[it.size * 95 / 100] else 0L }
        return TxModeResult(totalTime, p50, p95, validResults.size)
    }

    /** 测量事务模式下的并发写耗时（逻辑同 measureTxModeRead，操作改为 INSERT） */
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

    /**
     * 测量事务模式下的读写混合耗时
     *
     * 线程分配：前 80% 为读线程，后 20% 为写线程。
     * 只有读线程开启事务，写线程直接执行（不受事务模式影响）。
     */
    private fun measureTxModeMixed(
        db: PerfDatabase,
        txMode: ReadTransactionMode,
        threadCount: Int,
        rowsPerThread: Int,
    ): TxModeResult {
        val results = mutableListOf<Long>()
        val readCount = (threadCount * 0.8).toInt()  // 80% 读线程
        val writeCount = threadCount - readCount      // 20% 写线程
        val latch = java.util.concurrent.CountDownLatch(threadCount)

        for (i in 0 until threadCount) {
            val tid = i
            val isWriter = i >= readCount  // 后 20% 是写线程
            Thread {
                // 读线程用 readableDatabase，写线程用 writableDatabase
                val conn = if (isWriter) db.writableDatabase else db.readableDatabase
                // 只有读线程开启事务
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
                            // 读操作：查询指定偏移范围
                            conn.rawQuery(
                                "SELECT * FROM ${Schema.TABLE_NAME} LIMIT $rowsPerThread OFFSET ${tid * rowsPerThread}",
                                null
                            ).use { cursor ->
                                var c = 0
                                while (cursor.moveToNext()) c++
                            }
                        } else {
                            // 写操作：更新 200 行数据
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

    /** 事务模式测量结果 */
    private data class TxModeResult(val total: Long, val p50: Long, val p95: Long, val okCount: Int)

    // ============================================================
    // 写阻塞读实验
    // ============================================================
    // 场景：一个 writer 线程批量插入数据，同时一个 reader 线程查询。
    // 验证在 TRUNCATE 模式下 reader 是否被 writer 的 EXCLUSIVE lock 阻塞，
    // 以及 WAL 模式下读写分离是否避免了阻塞。
    // ============================================================

    /**
     * 写阻塞读测试入口
     *
     * 对每种事务模式（跳过 READ_ONLY），分别在 WAL 和 TRUNCATE 上执行写阻塞读实验，
     * 输出写入耗时、查询耗时、是否被阻塞。
     */
    suspend fun runWriteBlocksReadTest(
        walDb: PerfDatabase,
        deleteDb: PerfDatabase,
        insertRows: Int = 5000,
        callback: Callback,
    ) {
        val phase = "写阻塞读"
        callback.onLog(ExperimentLog(now(), phase, "===== 写阻塞读测试 =====", LogType.INFO))
        callback.onLog(ExperimentLog(now(), phase, "场景: 一个线程用指定事务模式批量插入 $insertRows 行, 同时另一个线程查询, 观察查询是否被阻塞及耗时", LogType.INFO))

        // 跳过 READ_ONLY（只读事务不能写）
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

    /**
     * 测量写阻塞读场景
     *
     * 工作流程：
     * 1. 启动 writer 线程，开启事务并批量插入数据
     * 2. writer 完成后 sleep 3 秒再提交（延长锁持有时间）
     * 3. reader 线程在 writer 开始后立即尝试查询
     * 4. 观察 reader 的查询耗时：如果耗时接近 writer 的总耗时，说明被阻塞
     *
     * @return WriteBlocksReadResult（写入总耗时、查询耗时、是否被阻塞）
     */
    private fun measureWriteBlocksRead(
        db: PerfDatabase,
        txMode: ReadTransactionMode,
        insertRows: Int,
        callback: Callback,
    ): WriteBlocksReadResult {
        val dbLabel = db.getJournalMode()  // 当前数据库的日志模式
        var writeTotalMs = 0L
        var readMs = 0L
        var writerStarted = false

        // ====== Writer 线程 ======
        val writerThread = Thread {
            val conn = db.writableDatabase
            when (txMode) {
                ReadTransactionMode.EXCLUSIVE -> conn.beginTransaction()
                ReadTransactionMode.NON_EXCLUSIVE -> conn.beginTransactionNonExclusive()
                ReadTransactionMode.READ_ONLY -> return@Thread  // READ_ONLY 不执行写操作
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
                // 故意 sleep 3 秒再提交，延长 EXCLUSIVE lock 持有时间
                // 这样 reader 有更长的时间窗口遇到阻塞
                Thread.sleep(3 * 1000)
                conn.setTransactionSuccessful()
                callback.onLog(ExperimentLog(now(), "写阻塞读", "[$dbLabel/${txMode.label}] 写入完成, 耗时 ${ms}ms"))
            } catch (e: Throwable) {
                callback.onLog(ExperimentLog(now(), "写阻塞读", "[$dbLabel/${txMode.label}] 写入异常: ${e.message}", LogType.ERROR))
            } finally {
                conn.endTransaction()
            }
        }

        // ====== Reader 线程 ======
        val readerThread = Thread {
            // 等待 writer 开始后再执行（确保竞争条件）
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

        // 启动两个线程并等待完成
        writerThread.start()
        readerThread.start()
        writerThread.join()
        readerThread.join()

        // 判断是否被阻塞：如果查询耗时超过写入耗时的 75%，认为被阻塞了
        val readBlocked = readMs > (writeTotalMs * 3 / 4)
        return WriteBlocksReadResult(writeTotalMs, readMs, readBlocked)
    }

    /** 写阻塞读测量结果 */
    private data class WriteBlocksReadResult(val writeTotalMs: Long, val readMs: Long, val blocked: Boolean)

    // ============================================================
    // 通用对比框架
    // ============================================================

    /**
     * 通用耗时对比框架
     *
     * 在 WAL 和 TRUNCATE 两个数据库上各启动 10 个并发任务执行相同的操作，
     * 计算总耗时、P50、P95 并输出对比结果。
     *
     * @param phase 实验阶段名称（用于日志输出）
     * @param walDb WAL 数据库
     * @param deleteDb TRUNCATE 数据库
     * @param callback 日志回调
     * @param block 被测量的操作 lambda，接收 (数据库, 线程ID) 参数，返回耗时毫秒
     */
    private suspend fun runTimedComparison(
        phase: String,
        walDb: PerfDatabase,
        deleteDb: PerfDatabase,
        callback: Callback,
        block: suspend (PerfDatabase, Int) -> Long,
    ) = supervisorScope {
        // 在 WAL 数据库上启动 10 个并发任务
        val walTimes = List(10) { tid ->
            async(Dispatchers.IO) { block(walDb, tid) }
        }.awaitAll()

        // 在 TRUNCATE 数据库上启动 10 个并发任务
        val deleteTimes = List(10) { tid ->
            async(Dispatchers.IO) { block(deleteDb, tid) }
        }.awaitAll()

        // 计算 WAL 统计值
        val walTotal = walTimes.sum()
        val walP50 = walTimes.sorted()[walTimes.size / 2]
        val walP95 = walTimes.sorted()[walTimes.size * 95 / 100]

        // 计算 TRUNCATE 统计值
        val deleteTotal = deleteTimes.sum()
        val deleteP50 = deleteTimes.sorted()[deleteTimes.size / 2]
        val deleteP95 = deleteTimes.sorted()[deleteTimes.size * 95 / 100]

        // 输出对比结果
        callback.onLog(ExperimentLog(now(), phase, "WAL: 总耗时 ${walTotal}ms | P50=${walP50}ms | P95=${walP95}ms", LogType.SUCCESS))
        callback.onLog(ExperimentLog(now(), phase, "TRUNCATE: 总耗时 ${deleteTotal}ms | P50=${deleteP50}ms | P95=${deleteP95}ms", LogType.SUCCESS))
        val diff = if (deleteTotal > 0) ((deleteTotal - walTotal).toFloat() / deleteTotal * 100).toInt() else 0
        callback.onLog(ExperimentLog(now(), phase, "WAL ${if (diff > 0) "快" else "慢"} ${diff.abs()}%", LogType.INFO))
        Log.d(TAG, "$phase: WAL=$walTotal ms, TRUNCATE=$deleteTotal ms")
    }

    /** Int 绝对值扩展函数（避免引入 kotlin.math.abs） */
    private fun Int.abs() = if (this < 0) -this else this
}
