package com.example.sqliteperfresearch.database

import android.content.ContentValues
import android.database.sqlite.SQLiteDatabase
import android.util.Log
import com.example.sqliteperfresearch.model.ExperimentLog
import com.example.sqliteperfresearch.model.LogType
import com.example.sqliteperfresearch.util.LOG_TAG
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.supervisorScope
import java.util.concurrent.CountDownLatch
import kotlin.system.measureTimeMillis

/**
 * 读 DB 锁阻塞写数据验证
 *
 * 原理:
 * - Cursor rawQuery 后不遍历到底, native sqlite statement 保持 active, 持有 SHARED lock
 * - 在 TRUNCATE 模式下, writer 需要 EXCLUSIVE lock, 会被 SHARED lock 阻塞
 * - WAL 模式下, reader 和 writer 使用不同的连接, 互不阻塞
 *
 * 关键: Cursor 是否遍历到底决定了 statement 是否 SQLITE_DONE, 进而决定是否释放 read lock
 *
 * 注意: 必须使用同一个 SQLiteDatabase 实例(同一连接池), 否则锁行为不会正确传递
 */
class ReadLockBlockingExperiment(private val dbHelper: PerfDatabase) {
    companion object {
        const val TAG = "$LOG_TAG.ReadLock"
    }

    interface Callback {
        fun onLog(log: ExperimentLog)
    }

    fun now() = java.text.SimpleDateFormat("HH:mm:ss.SSS", java.util.Locale.getDefault()).format(java.util.Date())

    /**
     * 验证: Cursor 不遍历到底时, 是否阻塞写操作
     *
     * @param mode "TRUNCATE" 或 "WAL"
     * @param cursorFullyTraverse true=遍历到底(预期不阻塞), false=不遍历到底(预期阻塞)
     */
    suspend fun runReadLockTest(mode: String, cursorFullyTraverse: Boolean, callback: Callback) = supervisorScope {
        dbHelper.setWalMode(mode == "WAL")
        val journalMode = dbHelper.getJournalMode()
        callback.onLog(ExperimentLog(now(), "读锁阻塞", "模式: $mode ($journalMode), 场景: ${scenarioLabel(cursorFullyTraverse)}", LogType.INFO))

        val readerReadyLatch = CountDownLatch(1)
        val writeDoneLatch = CountDownLatch(1)

        // Reader: 使用 dbHelper.readableDatabase (同一连接池)
        val readerJob = async(Dispatchers.IO) {
            val cursor = dbHelper.readableDatabase.rawQuery("SELECT id, name FROM ${Schema.TABLE_NAME} ORDER BY id", null)
            val totalRows = cursor.count
            callback.onLog(ExperimentLog(now(), "读锁阻塞", "Cursor 已打开, 总行数: $totalRows", LogType.INFO))

            if (!cursorFullyTraverse) {
                // 不遍历到底: 只 moveToFirst 就暂停, 保持 statement active
                if (cursor.moveToFirst()) {
                    val firstId = cursor.getLong(0)
                    val firstName = cursor.getString(1)
                    callback.onLog(ExperimentLog(now(), "读锁阻塞", "Cursor moveToFirst: id=$firstId, name='$firstName', 暂停遍历(保持statement active)...", LogType.INFO))
                }
                readerReadyLatch.countDown()
                // 等待 10 秒, 保持 statement active, 让 writer 在此期间尝试写入
                delay(10000)
                callback.onLog(ExperimentLog(now(), "读锁阻塞", "Cursor 释放 (10s 等待结束)", LogType.INFO))
            } else {
                // 遍历到底: 完整遍历, statement 达到 SQLITE_DONE
                var count = 0
                while (cursor.moveToNext()) {
                    count++
                }
                callback.onLog(ExperimentLog(now(), "读锁阻塞", "Cursor 遍历完成: 读取 $count 行, statement 已 SQLITE_DONE", LogType.INFO))
                readerReadyLatch.countDown()
                // 遍历完后也等 10 秒, 对比阻塞情况
                delay(10000)
            }
            cursor.close()
            callback.onLog(ExperimentLog(now(), "读锁阻塞", "Cursor 已关闭", LogType.INFO))
        }

        // 等待 Reader 就绪
        readerReadyLatch.await()
        delay(200)

        // Writer: 使用 dbHelper.writableDatabase (同一连接池)
        callback.onLog(ExperimentLog(now(), "读锁阻塞", "Writer 开始尝试写入...", LogType.INFO))
        val writeMs = measureTimeMillis {
            val db = dbHelper.writableDatabase
            db.beginTransaction()
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

        val wasBlocked = writeMs > 1000
        callback.onLog(ExperimentLog(now(), "读锁阻塞", "Writer 写入耗时: ${writeMs}ms ${if (wasBlocked) "(被阻塞)" else "(未阻塞)"}",
            if (wasBlocked) LogType.ERROR else LogType.SUCCESS))

        // 等待 reader 结束
        readerJob.await()
        writeDoneLatch.await()

        // 总结
        callback.onLog(ExperimentLog(now(), "读锁阻塞", "===== 结果总结 =====", LogType.INFO))
        when {
            !cursorFullyTraverse && wasBlocked -> {
                callback.onLog(ExperimentLog(now(), "读锁阻塞", "结论: Cursor 不遍历到底 → statement 保持 active → SHARED lock 阻塞了 writer (符合预期)", LogType.SUCCESS))
            }
            !cursorFullyTraverse && !wasBlocked -> {
                callback.onLog(ExperimentLog(now(), "读锁阻塞", "结论: Cursor 不遍历到底但 writer 未被阻塞 — 可能已处于 WAL 模式或 cursor 被自动释放", LogType.WARNING))
            }
            cursorFullyTraverse && !wasBlocked -> {
                callback.onLog(ExperimentLog(now(), "读锁阻塞", "结论: Cursor 遍历到底 → statement SQLITE_DONE → lock 已释放 → writer 立即成功 (符合预期)", LogType.SUCCESS))
            }
            cursorFullyTraverse && wasBlocked -> {
                callback.onLog(ExperimentLog(now(), "读锁阻塞", "结论: Cursor 遍历到底后 writer 仍被阻塞 — 异常, 请检查", LogType.ERROR))
            }
        }

        Log.d(TAG, "Read lock test done: $mode, traverse=$cursorFullyTraverse, writeMs=$writeMs")
    }

    private fun scenarioLabel(fully: Boolean) = if (fully) "Cursor遍历到底" else "Cursor不遍历到底(保持active)"
}
