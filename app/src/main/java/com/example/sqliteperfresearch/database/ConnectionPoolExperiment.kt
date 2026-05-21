// ============================================================
// 连接池打满验证实验（ConnectionPoolExperiment）
// ============================================================
// 职责：
// 1. 探测 PerfDatabase 共享连接池的容量上限（默认 max=1）
// 2. 连接池饱和后，新操作是否阻塞（busy timeout 等待）
// 3. WAL vs TRUNCATE 模式下连接池行为的对比
//
// 关键发现：
// - SQLiteDatabase.openDatabase() 创建独立连接池（Max connections: 1）
// - PerfDatabase（SQLiteOpenHelper）管理共享连接池，readableDatabase/writableDatabase 共享
// - 默认池大小 = 1（单连接），第 2 个并发请求会阻塞等待
// - 可通过 setMaxOpenConnections() 调整池大小
//
// 实验设计：
// 1. 逐步打开 rawQuery 并保持 Cursor 不关闭，占住连接
// 2. 观察从第几个 Cursor 开始出现阻塞
// 3. 连接池饱和后测试写操作是否被阻塞
// ============================================================

package com.example.sqliteperfresearch.database

import android.database.sqlite.SQLiteDatabase  // SQLite 数据库核心 API
import android.util.Log
import com.example.sqliteperfresearch.model.ExperimentLog
import com.example.sqliteperfresearch.model.LogType
import com.example.sqliteperfresearch.util.LOG_TAG
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.delay          // 协程非阻塞延迟
import kotlinx.coroutines.supervisorScope
import kotlin.system.measureTimeMillis   // 测量代码块执行耗时

/**
 * 连接池打满实验类
 *
 * 使用 PerfDatabase 共享连接池进行实验。
 * 通过保持 Cursor 不关闭来占住连接，逐步探测池容量。
 *
 * @param dbHelper 数据库辅助类实例，提供共享连接池
 */
class ConnectionPoolExperiment(private val dbHelper: PerfDatabase) {
    companion object {
        /** 日志 tag：SQLitePerf.Pool */
        const val TAG = "$LOG_TAG.Pool"
    }

    /** 实验日志回调接口 */
    interface Callback {
        fun onLog(log: ExperimentLog)
    }

    /** 生成时间戳字符串 */
    fun now() = java.text.SimpleDateFormat("HH:mm:ss.SSS", java.util.Locale.getDefault()).format(java.util.Date())

    /**
     * 连接池探测实验入口
     *
     * 分别在 WAL 和 TRUNCATE 模式下执行两个子实验：
     * 1. 连接池饱和测试：逐步打开 Cursor 占住连接，观察何时开始阻塞
     * 2. 写操作阻塞测试：连接池饱和后，测试写操作是否被阻塞
     *
     * @param mode "WAL" 或 "TRUNCATE"
     * @param callback 日志回调
     */
    suspend fun probeConnectionPoolLimit(mode: String, callback: Callback) {
        // 设置数据库日志模式
        dbHelper.setWalMode(mode == "WAL")
        val journalMode = dbHelper.getJournalMode()

        callback.onLog(ExperimentLog(now(), "连接池探测", "$mode 模式 - journal_mode: $journalMode", LogType.INFO))
        callback.onLog(ExperimentLog(now(), "连接池探测", "关键: 使用同一个 PerfDatabase 实例, 共享连接池 (默认 max=1)", LogType.INFO))

        // 实验 1：连接池饱和测试
        callback.onLog(ExperimentLog(now(), "连接池探测", "--- 实验1: 连接池饱和测试 ---", LogType.INFO))
        runPoolSaturationTest(dbHelper, journalMode, callback)

        // 实验 2：写操作是否被阻塞
        callback.onLog(ExperimentLog(now(), "连接池探测", "--- 实验2: 池饱和后写操作阻塞测试 ---", LogType.INFO))
        runWriterBlockingWithPool(dbHelper, journalMode, callback)
    }

    /**
     * 连接池饱和测试
     *
     * 工作原理：
     * 1. 逐步调用 db.readableDatabase.rawQuery() 并保持 Cursor 不关闭
     * 2. 每个 Cursor 占住一个连接（PerfDatabase 默认 max=1）
     * 3. 记录每次 rawQuery + moveToFirst 的耗时
     * 4. 耗时超过 100ms 认为被阻塞（正常应 < 10ms）
     *
     * 预期结果：
     * - PerfDatabase 默认连接池 max=1
     * - 第 1 个 Cursor 立即打开
     * - 第 2 个及以后的 Cursor 开始阻塞（等待可用连接）
     *
     * @param db PerfDatabase 实例
     * @param journalMode 当前日志模式
     * @param callback 日志回调
     */
    private suspend fun runPoolSaturationTest(db: PerfDatabase, journalMode: String, callback: Callback) = supervisorScope {
        val maxConcurrent = 10  // 最多尝试 10 个并发 Cursor
        val activeCursors = mutableListOf<android.database.Cursor>()  // 保持引用的 Cursor 列表（不关闭）
        val results = mutableListOf<Pair<Int, Long>>()  // 记录每个 Cursor 的打开耗时

        // 先获取主连接并查询总行数
        val baseDb = db.readableDatabase
        val rowCount = baseDb.rawQuery("SELECT COUNT(*) FROM ${Schema.TABLE_NAME}", null).use {
            if (it.moveToFirst()) it.getLong(0) else 0L
        }
        callback.onLog(ExperimentLog(now(), "连接池探测", "数据库总行数: $rowCount, 使用 db.readableDatabase (共享连接池)", LogType.INFO))

        // 逐步打开 rawQuery，保持 Cursor 不关闭
        for (i in 1..maxConcurrent) {
            var cnt = 0
            val openMs = measureTimeMillis {
                // rawQuery 返回 Cursor，moveToFirst 触发实际的数据加载
                val cursor = db.readableDatabase.rawQuery("SELECT * FROM ${Schema.TABLE_NAME} where id > $i ORDER BY id", null)
                cursor.moveToFirst()
                cnt = cursor.count
                // 关键：不关闭 cursor，让它占住连接
                synchronized(activeCursors) { activeCursors.add(cursor) }
            }
            // 超过 100ms 认为被阻塞（正常 rawQuery + moveToFirst 应在 10ms 内完成）
            val isBlocked = openMs > 100
            results.add(i to openMs)
            callback.onLog(ExperimentLog(now(), "连接池探测", "Cursor #$i: rawQuery + moveToFirst cnt: $cnt 耗时 ${openMs}ms ${if (isBlocked) "(阻塞)" else "(立即)"}",
                if (isBlocked) LogType.WARNING else LogType.SUCCESS))
            if (isBlocked) {
                callback.onLog(ExperimentLog(now(), "连接池探测", "从 Cursor #$i 开始出现阻塞 — 连接池已饱和", LogType.INFO))
            }
        }

        // 释放所有 Cursor（关闭连接）
        callback.onLog(ExperimentLog(now(), "连接池探测", "释放所有 Cursor...", LogType.INFO))
        synchronized(activeCursors) {
            activeCursors.forEach { it.close() }
        }

        // 统计结果
        val blockedCount = results.count { it.second > 100 }  // 被阻塞的 Cursor 数量
        val maxMs = results.maxOf { it.second }               // 最大耗时
        callback.onLog(ExperimentLog(now(), "连接池探测", "===== 结果 =====", LogType.INFO))
        callback.onLog(ExperimentLog(now(), "连接池探测", "$maxConcurrent 个 Cursor 全部打开, $blockedCount 个被阻塞, 最大 ${maxMs}ms", LogType.INFO))
        callback.onLog(ExperimentLog(now(), "连接池探测", "说明: PerfDatabase 默认连接池 max=1, 第 2 个 cursor 就会开始阻塞", LogType.INFO))
    }

    /**
     * 连接池饱和后的写操作阻塞测试
     *
     * 步骤：
     * 1. 打开 3 个 Cursor 并保持不关闭，占住连接池
     * 2. 尝试执行写操作（INSERT）
     * 3. 观察写操作是否被阻塞（等待可用连接）
     *
     * 预期：
     * - 写操作需要 writableDatabase，如果连接池已被 read Cursor 占满，需要等待
     * - WAL 模式下可能有不同的行为（读写分离）
     */
    private suspend fun runWriterBlockingWithPool(db: PerfDatabase, journalMode: String, callback: Callback) = supervisorScope {
        // 打开 3 个 Cursor 占住连接池
        val cursors = mutableListOf<android.database.Cursor>()
        for (i in 1..3) {
            val cursor = db.readableDatabase.rawQuery("SELECT * FROM ${Schema.TABLE_NAME} where id > $i ORDER BY id", null)
            cursor.moveToFirst()
            cursors.add(cursor)
        }
        callback.onLog(ExperimentLog(now(), "连接池探测", "已打开 3 个 Cursor (rawQuery + moveToFirst, 不关闭), 占住连接池", LogType.INFO))

        // 测试写操作耗时
        callback.onLog(ExperimentLog(now(), "连接池探测", "--- 测试写操作 ---", LogType.INFO))
        val writeMs = measureTimeMillis {
            val writer = db.writableDatabase
            writer.beginTransaction()
            try {
                writer.execSQL("INSERT INTO ${Schema.TABLE_NAME} (name, counter) VALUES ('pool_test', 0)")
                writer.setTransactionSuccessful()
            } finally {
                writer.endTransaction()
            }
        }

        val wasBlocked = writeMs > 100  // 超过 100ms 认为被阻塞
        callback.onLog(ExperimentLog(now(), "连接池探测", "写操作耗时: ${writeMs}ms ${if (wasBlocked) "(被阻塞)" else "(未阻塞)"}",
            if (wasBlocked) LogType.ERROR else LogType.SUCCESS))

        if (wasBlocked) {
            callback.onLog(ExperimentLog(now(), "连接池探测", "结论: 连接池被 read Cursor 占住, 写操作需要等待可用连接", LogType.WARNING))
        } else {
            callback.onLog(ExperimentLog(now(), "连接池探测", "结论: 写操作未被阻塞 — 连接池支持同时读写 (WAL 模式)", LogType.SUCCESS))
        }

        // 清理：关闭所有 Cursor
        cursors.forEach { it.close() }
        callback.onLog(ExperimentLog(now(), "连接池探测", "所有 Cursor 已关闭", LogType.INFO))
    }
}
