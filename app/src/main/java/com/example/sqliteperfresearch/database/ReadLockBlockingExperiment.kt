// ============================================================
// 读 DB 锁阻塞写数据验证实验（ReadLockBlockingExperiment）
// ============================================================
// 职责：
// 1. 验证 Cursor 未遍历到底时，native sqlite statement 保持 active，持有 SHARED lock
// 2. 在 TRUNCATE 模式下，writer 需要 EXCLUSIVE lock，会被 SHARED lock 阻塞
// 3. WAL 模式下，读写分离，互不阻塞
// 4. 对比 Cursor 遍历到底（statement SQLITE_DONE，锁释放）和不遍历到底的差异
//
// SQLite 锁机制回顾：
// - rawQuery 返回 Cursor 后，native statement 保持 active 状态
// - 如果 Cursor 没有遍历到底（没有达到 SQLITE_DONE），statement 持有 SHARED lock
// - SHARED lock 不阻塞其他 SHARED lock（多个 reader 可共存）
// - 但 SHARED lock 阻塞 EXCLUSIVE lock（writer 需要排他锁）
// - 当 Cursor 遍历到底或 close() 时，statement 变为 SQLITE_DONE，释放 SHARED lock
//
// 关键设计：
// - 必须使用同一个 PerfDatabase 实例（同一连接池），否则锁行为不会正确传递
// - 使用 CountDownLatch 确保 Reader 先就绪，Writer 再开始
// ============================================================

package com.example.sqliteperfresearch.database

import android.content.ContentValues    // SQLite 插入操作的键值对容器
import android.database.sqlite.SQLiteDatabase
import android.util.Log
import com.example.sqliteperfresearch.model.ExperimentLog
import com.example.sqliteperfresearch.model.LogType
import com.example.sqliteperfresearch.util.LOG_TAG
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async         // 启动异步协程
import kotlinx.coroutines.delay         // 协程非阻塞延迟
import kotlinx.coroutines.supervisorScope
import java.util.concurrent.CountDownLatch // 倒计时门闩，用于线程间同步
import kotlin.system.measureTimeMillis

/**
 * 读锁阻塞写实验类
 *
 * 验证 Cursor 持有 SHARED lock 期间是否阻塞写操作。
 * 通过控制 Cursor 是否遍历到底，对比两种场景下的锁行为。
 *
 * @param dbHelper 数据库辅助类实例（必须使用同一个实例，保证同一连接池）
 */
class ReadLockBlockingExperiment(private val dbHelper: PerfDatabase) {
    companion object {
        /** 日志 tag：SQLitePerf.ReadLock */
        const val TAG = "$LOG_TAG.ReadLock"
    }

    /** 实验日志回调接口 */
    interface Callback {
        fun onLog(log: ExperimentLog)
    }

    /** 生成时间戳字符串 */
    fun now() = java.text.SimpleDateFormat("HH:mm:ss.SSS", java.util.Locale.getDefault()).format(java.util.Date())

    /**
     * 读锁阻塞写实验
     *
     * 场景组合：
     * - TRUNCATE + Cursor 不遍历到底 → 预期阻塞（SHARED lock 阻塞 EXCLUSIVE lock）
     * - TRUNCATE + Cursor 遍历到底 → 预期不阻塞（statement SQLITE_DONE，锁已释放）
     * - WAL + Cursor 不遍历到底 → 预期不阻塞（读写分离）
     * - WAL + Cursor 遍历到底 → 预期不阻塞
     *
     * @param mode "TRUNCATE" 或 "WAL"
     * @param cursorFullyTraverse true = 遍历到底（预期不阻塞），false = 不遍历到底（预期阻塞，仅 TRUNCATE）
     * @param callback 日志回调
     */
    suspend fun runReadLockTest(mode: String, cursorFullyTraverse: Boolean, callback: Callback) = supervisorScope {
        // 设置数据库日志模式
        dbHelper.setWalMode(mode == "WAL")
        val journalMode = dbHelper.getJournalMode()
        callback.onLog(ExperimentLog(now(), "读锁阻塞", "模式: $mode ($journalMode), 场景: ${scenarioLabel(cursorFullyTraverse)}", LogType.INFO))

        // readerReadyLatch：Reader 就绪后通知 Writer 可以开始写了
        val readerReadyLatch = CountDownLatch(1)
        // writeDoneLatch：Writer 完成后通知结束
        val writeDoneLatch = CountDownLatch(1)

        // ====== Reader 协程 ======
        // 使用 dbHelper.readableDatabase（同一连接池）
        val readerJob = async(Dispatchers.IO) {
            // rawQuery 返回 Cursor，此时 native statement 变为 active
            val cursor = dbHelper.readableDatabase.rawQuery("SELECT id, name FROM ${Schema.TABLE_NAME} ORDER BY id", null)
            val totalRows = cursor.count  // count 属性触发元数据查询，不遍历数据
            callback.onLog(ExperimentLog(now(), "读锁阻塞", "Cursor 已打开, 总行数: $totalRows", LogType.INFO))

            if (!cursorFullyTraverse) {
                // ====== 不遍历到底：只 moveToFirst 就暂停 ======
                // moveToFirst 移动到第一行，但 statement 仍然 active，持有 SHARED lock
                if (cursor.moveToFirst()) {
                    val firstId = cursor.getLong(0)
                    val firstName = cursor.getString(1)
                    callback.onLog(ExperimentLog(now(), "读锁阻塞", "Cursor moveToFirst: id=$firstId, name='$firstName', 暂停遍历(保持statement active)...", LogType.INFO))
                }
                readerReadyLatch.countDown()  // 通知 Writer：Reader 就绪，SHARED lock 已持有
                // 等待 10 秒，保持 statement active
                // 在这 10 秒内，Writer 尝试获取 EXCLUSIVE lock 会被阻塞
                delay(10000)
                callback.onLog(ExperimentLog(now(), "读锁阻塞", "Cursor 释放 (10s 等待结束)", LogType.INFO))
            } else {
                // ====== 遍历到底：完整遍历 Cursor ======
                var count = 0
                while (cursor.moveToNext()) {
                    count++
                }
                callback.onLog(ExperimentLog(now(), "读锁阻塞", "Cursor 遍历完成: 读取 $count 行, statement 已 SQLITE_DONE", LogType.INFO))
                readerReadyLatch.countDown()  // 通知 Writer
                // 遍历完后也等 10 秒，对比阻塞情况
                // 此时 statement 已 SQLITE_DONE，SHARED lock 已释放
                delay(10000)
            }
            cursor.close()  // 关闭 Cursor，释放 native 资源
            callback.onLog(ExperimentLog(now(), "读锁阻塞", "Cursor 已关闭", LogType.INFO))
        }

        // 等待 Reader 就绪（确保 SHARED lock 已持有或已释放）
        readerReadyLatch.await()
        delay(200)  // 额外等待 200ms，确保状态稳定

        // ====== Writer 操作 ======
        // 使用 dbHelper.writableDatabase（同一连接池）
        callback.onLog(ExperimentLog(now(), "读锁阻塞", "Writer 开始尝试写入...", LogType.INFO))
        val writeMs = measureTimeMillis {
            val db = dbHelper.writableDatabase
            db.beginTransaction()  // 开启事务，尝试获取 EXCLUSIVE lock
            try {
                val cv = ContentValues().apply {
                    put("name", "read_lock_test_${System.currentTimeMillis()}")
                    put("counter", 0)
                }
                db.insert(Schema.TABLE_NAME, null, cv)
                db.setTransactionSuccessful()
            } finally {
                db.endTransaction()
            }
            writeDoneLatch.countDown()
        }

        // 判断是否被阻塞：超过 1 秒认为被阻塞（正常 INSERT 应在几毫秒内完成）
        val wasBlocked = writeMs > 1000
        callback.onLog(ExperimentLog(now(), "读锁阻塞", "Writer 写入耗时: ${writeMs}ms ${if (wasBlocked) "(被阻塞)" else "(未阻塞)"}",
            if (wasBlocked) LogType.ERROR else LogType.SUCCESS))

        // 等待 Reader 和 Writer 都结束
        readerJob.await()
        writeDoneLatch.await()

        // ====== 结果总结 ======
        callback.onLog(ExperimentLog(now(), "读锁阻塞", "===== 结果总结 =====", LogType.INFO))
        when {
            // 不遍历到底 + 被阻塞 = 符合预期（SHARED lock 阻塞 EXCLUSIVE lock）
            !cursorFullyTraverse && wasBlocked -> {
                callback.onLog(ExperimentLog(now(), "读锁阻塞", "结论: Cursor 不遍历到底 → statement 保持 active → SHARED lock 阻塞了 writer (符合预期)", LogType.SUCCESS))
            }
            // 不遍历到底 + 未阻塞 = 异常（可能在 WAL 模式下，读写不互相阻塞）
            !cursorFullyTraverse && !wasBlocked -> {
                callback.onLog(ExperimentLog(now(), "读锁阻塞", "结论: Cursor 不遍历到底但 writer 未被阻塞 — 可能已处于 WAL 模式或 cursor 被自动释放", LogType.WARNING))
            }
            // 遍历到底 + 未阻塞 = 符合预期（statement SQLITE_DONE，锁已释放）
            cursorFullyTraverse && !wasBlocked -> {
                callback.onLog(ExperimentLog(now(), "读锁阻塞", "结论: Cursor 遍历到底 → statement SQLITE_DONE → lock 已释放 → writer 立即成功 (符合预期)", LogType.SUCCESS))
            }
            // 遍历到底 + 被阻塞 = 异常（锁已释放但仍被阻塞）
            cursorFullyTraverse && wasBlocked -> {
                callback.onLog(ExperimentLog(now(), "读锁阻塞", "结论: Cursor 遍历到底后 writer 仍被阻塞 — 异常, 请检查", LogType.ERROR))
            }
        }

        Log.d(TAG, "Read lock test done: $mode, traverse=$cursorFullyTraverse, writeMs=$writeMs")
    }

    /** 场景标签：遍历到底 / 不遍历到底 */
    private fun scenarioLabel(fully: Boolean) = if (fully) "Cursor遍历到底" else "Cursor不遍历到底(保持active)"
}
