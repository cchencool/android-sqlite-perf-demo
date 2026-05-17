package com.example.sqliteperfresearch.database

import android.util.Log
import com.example.sqliteperfresearch.model.ExperimentLog
import com.example.sqliteperfresearch.model.LogType
import com.example.sqliteperfresearch.util.LOG_TAG
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.supervisorScope
import java.util.concurrent.CountDownLatch
import kotlin.system.measureTimeMillis

class LockExperiment(private val dbHelper: PerfDatabase) {
    companion object {
        const val TAG = "$LOG_TAG.Lock"
    }

    interface Callback {
        fun onLog(log: ExperimentLog)
    }

    fun now() = java.text.SimpleDateFormat("HH:mm:ss.SSS", java.util.Locale.getDefault()).format(java.util.Date())

    suspend fun runConcurrentReads(threadCount: Int, queryRows: Int, callback: Callback) = supervisorScope {
        callback.onLog(ExperimentLog(now(), "并发读", "启动 $threadCount 个并发读线程, 每线程读 $queryRows 行", LogType.INFO))
        val latch = CountDownLatch(threadCount)
        val results = List(threadCount) { tid ->
            async(Dispatchers.IO) {
                val reader = dbHelper.readableDatabase
                try {
                    val elapsed = measureTimeMillis {
                        val cursor = reader.rawQuery("SELECT * FROM ${Schema.TABLE_NAME} LIMIT $queryRows", null)
                        var count = 0
                        while (cursor.moveToNext()) count++
                        cursor.close()
                    }
                    latch.countDown()
                    Pair(tid, elapsed)
                } finally {
                }
            }
        }
        val timed = results.awaitAll()
        timed.forEach { (tid, ms) ->
            callback.onLog(ExperimentLog(now(), "并发读", "Thread-$tid 读取完成: ${ms}ms", LogType.SUCCESS))
        }
        val totalTime = timed.maxOf { it.second }
        callback.onLog(ExperimentLog(now(), "并发读", "全部完成, 总耗时: ${totalTime}ms (无阻塞并发)", LogType.SUCCESS))
        Log.d(TAG, "Concurrent reads completed: $totalTime ms")
    }

    suspend fun runReadWriteBlocking(writeThreadCount: Int, readThreadCount: Int, writeRows: Int, callback: Callback) = supervisorScope {
        callback.onLog(ExperimentLog(now(), "读写并发", "启动 $writeThreadCount 写入 + $readThreadCount 读取, 写入阻塞读", LogType.INFO))

        val startLatch = CountDownLatch(writeThreadCount + readThreadCount)
        val writeDoneLatch = CountDownLatch(writeThreadCount)

        val readJobs = List(readThreadCount) { tid ->
            async(Dispatchers.IO) {
                val reader = dbHelper.readableDatabase
                try {
                    startLatch.countDown()
                    startLatch.await()
                    val elapsed = measureTimeMillis {
                        val cursor = reader.rawQuery("SELECT * FROM ${Schema.TABLE_NAME} LIMIT 1000", null)
                        var count = 0
                        while (cursor.moveToNext()) count++
                        cursor.close()
                    }
                    val waited = elapsed > 2000
                    callback.onLog(
                        ExperimentLog(
                            now(), "读写并发",
                            "Read-Thread-$tid 完成: ${elapsed}ms${if (waited) " (被写入阻塞)" else " (未阻塞)"}",
                            if (waited) LogType.WARNING else LogType.SUCCESS
                        )
                    )
                } finally {
                }
            }
        }

        val writeJobs = List(writeThreadCount) { tid ->
            async(Dispatchers.IO) {
                val writer = dbHelper.writableDatabase
                try {
                    writer.beginTransaction()
                    startLatch.countDown()
                    startLatch.await()
                    val elapsed = measureTimeMillis {
                        for (i in 0 until writeRows) {
                            writer.execSQL("UPDATE ${Schema.TABLE_NAME} SET score = $tid WHERE id = ${i + 1}")
                        }
                        writer.setTransactionSuccessful()
                    }
                    writer.endTransaction()
                    writeDoneLatch.countDown()
                    callback.onLog(
                        ExperimentLog(now(), "读写并发", "Write-Thread-$tid 完成: ${elapsed}ms, 释放读锁", LogType.WARNING)
                    )
                } finally {
                }
            }
        }

        (readJobs + writeJobs).awaitAll()
        callback.onLog(ExperimentLog(now(), "读写并发", "全部完成, 写事务持有排他锁期间读操作被阻塞", LogType.SUCCESS))
        Log.d(TAG, "Read-write blocking completed")
    }

    suspend fun runLockTimeout(callback: Callback) = supervisorScope {
        callback.onLog(ExperimentLog(now(), "锁超时", "启动长事务并尝试读取, 演示 busy timeout", LogType.INFO))

        val db1 = dbHelper.writableDatabase
        db1.beginTransaction()

        val elapsed = measureTimeMillis {
            val doneLatch = CountDownLatch(1)
            async(Dispatchers.IO) {
                try {
                    val db2 = dbHelper.readableDatabase
                    db2.rawQuery("SELECT COUNT(*) FROM ${Schema.TABLE_NAME}", null).close()
                    callback.onLog(ExperimentLog(now(), "锁超时", "读操作成功 (未超时)", LogType.SUCCESS))
                } catch (e: Exception) {
                    callback.onLog(ExperimentLog(now(), "锁超时", "读操作异常: ${e.message}", LogType.ERROR))
                }
                doneLatch.countDown()
            }
            Thread.sleep(3000)
            doneLatch.await()
        }

        db1.endTransaction()
        callback.onLog(ExperimentLog(now(), "锁超时", "测试完成, 总耗时: ${elapsed}ms", LogType.SUCCESS))
        Log.d(TAG, "Lock timeout test completed")
    }
}
