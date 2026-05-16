package com.example.sqliteperfresearch.database

import android.database.sqlite.SQLiteDatabase
import android.util.Log
import com.example.sqliteperfresearch.model.ExperimentLog
import com.example.sqliteperfresearch.model.LogType
import com.example.sqliteperfresearch.util.LOG_TAG
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.supervisorScope
import kotlin.system.measureTimeMillis

/**
 * 连接池打满验证
 *
 * 关键发现:
 * - Android 中每个 SQLiteDatabase.openDatabase() 创建独立的连接池 (Max connections: 1)
 * - 真正的连接池是 SQLiteOpenHelper (PerfDatabase) 管理的: readableDatabase/writableDatabase 共享一个池
 * - 默认池大小 = 1 (单连接), 可通过 setMaxOpenConnections() 调整
 *
 * 实验:
 * 1. 使用同一个 PerfDatabase 实例, 多并发 rawQuery 保持 active, 探测连接池饱和
 * 2. 连接池打满后, 新调用是否阻塞
 * 3. WAL vs TRUNCATE 对比
 */
class ConnectionPoolExperiment(private val dbHelper: PerfDatabase) {
    companion object {
        const val TAG = "$LOG_TAG.Pool"
    }

    interface Callback {
        fun onLog(log: ExperimentLog)
    }

    fun now() = java.text.SimpleDateFormat("HH:mm:ss.SSS", java.util.Locale.getDefault()).format(java.util.Date())

    suspend fun probeConnectionPoolLimit(mode: String, callback: Callback) {
        dbHelper.setWalMode(mode == "WAL")
        val journalMode = dbHelper.getJournalMode()

        callback.onLog(ExperimentLog(now(), "连接池探测", "$mode 模式 - journal_mode: $journalMode", LogType.INFO))
        callback.onLog(ExperimentLog(now(), "连接池探测", "关键: 使用同一个 PerfDatabase 实例, 共享连接池 (默认 max=1)", LogType.INFO))

        // ====== 实验 1: 连接池饱和测试 ======
        callback.onLog(ExperimentLog(now(), "连接池探测", "--- 实验1: 连接池饱和测试 ---", LogType.INFO))
        runPoolSaturationTest(dbHelper, journalMode, callback)

        // ====== 实验 2: 写操作是否被阻塞 ======
        callback.onLog(ExperimentLog(now(), "连接池探测", "--- 实验2: 池饱和后写操作阻塞测试 ---", LogType.INFO))
        runWriterBlockingWithPool(dbHelper, journalMode, callback)
    }

    /**
     * 使用同一个 PerfDatabase 实例, 多并发 rawQuery 保持 cursor 不关闭,
     * 探测连接池何时饱和
     */
    private suspend fun runPoolSaturationTest(db: PerfDatabase, journalMode: String, callback: Callback) = supervisorScope {
        val maxConcurrent = 10
        val activeCursors = mutableListOf<android.database.Cursor>()
        val results = mutableListOf<Pair<Int, Long>>()

        // 先获取主连接
        val baseDb = db.readableDatabase
        val rowCount = baseDb.rawQuery("SELECT COUNT(*) FROM ${Schema.TABLE_NAME}", null).use {
            if (it.moveToFirst()) it.getLong(0) else 0L
        }
        callback.onLog(ExperimentLog(now(), "连接池探测", "数据库总行数: $rowCount, 使用 db.readableDatabase (共享连接池)", LogType.INFO))

        // 逐步打开 rawQuery, 保持 cursor 不关闭
        for (i in 1..maxConcurrent) {
            var cnt = 0;
            val openMs = measureTimeMillis {
                val cursor = db.readableDatabase.rawQuery("SELECT * FROM ${Schema.TABLE_NAME} where id > $i ORDER BY id", null)
                cursor.moveToFirst()
                cnt = cursor.count
                // 不关闭 cursor, 占住连接
                synchronized(activeCursors) { activeCursors.add(cursor) }
            }
            val isBlocked = openMs > 100
            results.add(i to openMs)
            callback.onLog(ExperimentLog(now(), "连接池探测", "Cursor #$i: rawQuery + moveToFirst cnt: $cnt 耗时 ${openMs}ms ${if (isBlocked) "(阻塞)" else "(立即)"}",
                if (isBlocked) LogType.WARNING else LogType.SUCCESS))
            if (isBlocked) {
                callback.onLog(ExperimentLog(now(), "连接池探测", "从 Cursor #$i 开始出现阻塞 — 连接池已饱和", LogType.INFO))
            }
        }

        // 释放所有 cursor
        callback.onLog(ExperimentLog(now(), "连接池探测", "释放所有 Cursor...", LogType.INFO))
        synchronized(activeCursors) {
            activeCursors.forEach { it.close() }
        }

        val blockedCount = results.count { it.second > 100 }
        val maxMs = results.maxOf { it.second }
        callback.onLog(ExperimentLog(now(), "连接池探测", "===== 结果 =====", LogType.INFO))
        callback.onLog(ExperimentLog(now(), "连接池探测", "$maxConcurrent 个 Cursor 全部打开, $blockedCount 个被阻塞, 最大 ${maxMs}ms", LogType.INFO))
        callback.onLog(ExperimentLog(now(), "连接池探测", "说明: PerfDatabase 默认连接池 max=1, 第 2 个 cursor 就会开始阻塞", LogType.INFO))
    }

    /**
     * 在连接池饱和状态下, 测试写操作是否被阻塞
     */
    private suspend fun runWriterBlockingWithPool(db: PerfDatabase, journalMode: String, callback: Callback) = supervisorScope {
        // 占住连接池
        val cursors = mutableListOf<android.database.Cursor>()
        for (i in 1..3) {
            val cursor = db.readableDatabase.rawQuery("SELECT * FROM ${Schema.TABLE_NAME} where id > $i ORDER BY id", null)
            cursor.moveToFirst()
            cursors.add(cursor)
        }
        callback.onLog(ExperimentLog(now(), "连接池探测", "已打开 3 个 Cursor (rawQuery + moveToFirst, 不关闭), 占住连接池", LogType.INFO))

        // 测试写操作
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

        val wasBlocked = writeMs > 100
        callback.onLog(ExperimentLog(now(), "连接池探测", "写操作耗时: ${writeMs}ms ${if (wasBlocked) "(被阻塞)" else "(未阻塞)"}",
            if (wasBlocked) LogType.ERROR else LogType.SUCCESS))

        if (wasBlocked) {
            callback.onLog(ExperimentLog(now(), "连接池探测", "结论: 连接池被 read Cursor 占住, 写操作需要等待可用连接", LogType.WARNING))
        } else {
            callback.onLog(ExperimentLog(now(), "连接池探测", "结论: 写操作未被阻塞 — 连接池支持同时读写 (WAL 模式)", LogType.SUCCESS))
        }

        // 清理
        cursors.forEach { it.close() }
        callback.onLog(ExperimentLog(now(), "连接池探测", "所有 Cursor 已关闭", LogType.INFO))
    }
}
