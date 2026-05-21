// ============================================================
// 锁机制验证实验（LockExperiment）
// ============================================================
// 职责：
// 1. 演示 SQLite 在 TRUNCATE 模式下的锁竞争行为
// 2. 三个实验场景：并发读（无锁竞争）、读写并发（写阻塞读）、锁超时（长事务）
// 3. 使用 PerfDatabase 共享连接池，所有数据库访问通过 dbHelper 获取
//
// SQLite 锁机制简介：
// - SQLite 使用 5 级锁：UNLOCKED → SHARED → RESERVED → PENDING → EXCLUSIVE
// - 读操作获取 SHARED lock（多个 reader 可共存）
// - 写操作需要 EXCLUSIVE lock（排他，阻塞所有其他连接）
// - TRUNCATE 模式下：写操作在事务期间持有 EXCLUSIVE lock
// - WAL 模式下：写操作写 WAL 文件，只需要 SHARED lock，不被 reader 阻塞
// ============================================================

package com.example.sqliteperfresearch.database

import android.util.Log
import com.example.sqliteperfresearch.model.ExperimentLog
import com.example.sqliteperfresearch.model.LogType
import com.example.sqliteperfresearch.util.LOG_TAG
import kotlinx.coroutines.Dispatchers        // 协程调度器：IO 用于阻塞 IO 操作，Main 用于 UI 线程
import kotlinx.coroutines.async              // 协程异步启动，返回 Deferred<T>，可 await 获取结果
import kotlinx.coroutines.awaitAll           // 等待所有 Deferred 完成并返回结果列表
import kotlinx.coroutines.supervisorScope    // 监督作用域：子协程失败不影响其他兄弟协程
import java.util.concurrent.CountDownLatch   // Java 并发工具：倒计时门闩，await() 阻塞直到 countDown() 归零
import kotlin.system.measureTimeMillis       // Kotlin 标准库：测量代码块执行耗时（毫秒）

/**
 * 锁机制实验类
 *
 * 使用 PerfDatabase 共享连接池进行实验。
 * dbHelper.readableDatabase 获取只读连接，dbHelper.writableDatabase 获取可写连接。
 *
 * @param dbHelper 数据库辅助类实例，提供共享连接池
 */
class LockExperiment(private val dbHelper: PerfDatabase) {
    companion object {
        /** 日志 tag：SQLitePerf.Lock */
        const val TAG = "$LOG_TAG.Lock"
    }

    /** 实验日志回调接口，实验类通过它向 UI 推送日志 */
    interface Callback {
        fun onLog(log: ExperimentLog)
    }

    /**
     * 生成当前时间戳字符串
     * 格式：HH:mm:ss.SSS（时:分:秒.毫秒），用于日志时间戳
     */
    fun now() = java.text.SimpleDateFormat("HH:mm:ss.SSS", java.util.Locale.getDefault()).format(java.util.Date())

    // ============================================================
    // 实验 1：并发读
    // ============================================================
    // 目的：验证 TRUNCATE 模式下多个 reader 并发查询是否互相阻塞
    // 预期：不阻塞，所有线程几乎同时完成（SQLite 允许多个 SHARED lock 共存）
    // ============================================================

    /**
     * 并发读实验
     *
     * 启动 threadCount 个协程，每个协程从 PerfDatabase 获取 readableDatabase 执行 SELECT。
     * TRUNCATE 模式下，多个读操作各自获取 SHARED lock，互相不阻塞。
     *
     * @param threadCount 并发线程数
     * @param queryRows 每个线程查询的行数
     * @param callback 日志回调
     */
    suspend fun runConcurrentReads(threadCount: Int, queryRows: Int, callback: Callback) = supervisorScope {
        callback.onLog(ExperimentLog(now(), "并发读", "启动 $threadCount 个并发读线程, 每线程读 $queryRows 行", LogType.INFO))

        // CountDownLatch：等待所有线程完成的同步工具
        // 构造参数为计数值，每次 countDown() 减 1，await() 阻塞直到计数归零
        val latch = CountDownLatch(threadCount)

        // 创建 threadCount 个异步任务，每个任务是一个独立的读操作
        val results = List(threadCount) { tid ->
            // async(Dispatchers.IO)：在 IO 线程池启动协程，适合阻塞 IO 操作
            async(Dispatchers.IO) {
                // 从共享连接池获取只读连接
                val reader = dbHelper.readableDatabase
                try {
                    // measureTimeMillis 测量代码块执行耗时
                    val elapsed = measureTimeMillis {
                        // rawQuery 执行 SELECT，返回 Cursor
                        val cursor = reader.rawQuery("SELECT * FROM ${Schema.TABLE_NAME} LIMIT $queryRows", null)
                        var count = 0
                        // moveToNext() 逐行遍历 Cursor
                        while (cursor.moveToNext()) count++
                        cursor.close()  // 关闭 Cursor，释放 native 资源
                    }
                    latch.countDown()  // 当前线程完成，门闩计数减 1
                    Pair(tid, elapsed)  // 返回线程 ID 和耗时
                } finally {
                    // 即使异常也要 countDown，避免 latch.await() 永久阻塞
                }
            }
        }

        // awaitAll()：等待所有 async 任务完成并返回结果列表
        val timed = results.awaitAll()

        // 输出每个线程的耗时
        timed.forEach { (tid, ms) ->
            callback.onLog(ExperimentLog(now(), "并发读", "Thread-$tid 读取完成: ${ms}ms", LogType.SUCCESS))
        }

        // 总耗时 = 最慢的那个线程的耗时（因为并发执行，实际总时间 = max(各线程耗时)）
        val totalTime = timed.maxOf { it.second }
        callback.onLog(ExperimentLog(now(), "并发读", "全部完成, 总耗时: ${totalTime}ms (无阻塞并发)", LogType.SUCCESS))
        Log.d(TAG, "Concurrent reads completed: $totalTime ms")
    }

    // ============================================================
    // 实验 2：读写并发（写阻塞读）
    // ============================================================
    // 目的：验证 TRUNCATE 模式下写操作是否阻塞读操作
    // 预期：读操作被写操作阻塞，等写事务完成后才能执行
    // 原理：writer 在事务期间持有 EXCLUSIVE lock，reader 需要 SHARED lock，
    //       EXCLUSIVE lock 排斥 SHARED lock，reader 等待 writer 释放锁
    // ============================================================

    /**
     * 读写并发实验（演示写阻塞读）
     *
     * 使用 CountDownLatch 确保所有读写线程同时启动，
     * 观察读操作是否被写操作的 EXCLUSIVE lock 阻塞。
     *
     * @param writeThreadCount 写入线程数
     * @param readThreadCount 读取线程数
     * @param writeRows 每个写线程更新的行数
     * @param callback 日志回调
     */
    suspend fun runReadWriteBlocking(writeThreadCount: Int, readThreadCount: Int, writeRows: Int, callback: Callback) = supervisorScope {
        callback.onLog(ExperimentLog(now(), "读写并发", "启动 $writeThreadCount 写入 + $readThreadCount 读取, 写入阻塞读", LogType.INFO))

        // startLatch：确保所有线程同时开始（起跑线同步）
        val startLatch = CountDownLatch(writeThreadCount + readThreadCount)
        // writeDoneLatch：等待所有写线程完成
        val writeDoneLatch = CountDownLatch(writeThreadCount)

        // 创建读取任务
        val readJobs = List(readThreadCount) { tid ->
            async(Dispatchers.IO) {
                val reader = dbHelper.readableDatabase
                try {
                    startLatch.countDown()  // 当前线程就绪
                    startLatch.await()      // 等待所有线程就绪后同时开始
                    val elapsed = measureTimeMillis {
                        val cursor = reader.rawQuery("SELECT * FROM ${Schema.TABLE_NAME} LIMIT 1000", null)
                        var count = 0
                        while (cursor.moveToNext()) count++
                        cursor.close()
                    }
                    // 如果耗时超过 2000ms，说明被写操作阻塞了
                    val waited = elapsed > 2000
                    callback.onLog(
                        ExperimentLog(
                            now(), "读写并发",
                            "Read-Thread-$tid 完成: ${elapsed}ms${if (waited) " (被写入阻塞)" else " (未阻塞)"}",
                            if (waited) LogType.WARNING else LogType.SUCCESS  // 被阻塞标为 WARNING
                        )
                    )
                } finally {
                }
            }
        }

        // 创建写入任务
        val writeJobs = List(writeThreadCount) { tid ->
            async(Dispatchers.IO) {
                val writer = dbHelper.writableDatabase
                try {
                    writer.beginTransaction()  // 开启事务，获取 EXCLUSIVE lock
                    startLatch.countDown()
                    startLatch.await()         // 同时开始
                    val elapsed = measureTimeMillis {
                        // 批量 UPDATE 操作
                        for (i in 0 until writeRows) {
                            writer.execSQL("UPDATE ${Schema.TABLE_NAME} SET score = $tid WHERE id = ${i + 1}")
                        }
                        writer.setTransactionSuccessful()  // 标记事务成功
                    }
                    writer.endTransaction()  // 提交事务，释放 EXCLUSIVE lock
                    writeDoneLatch.countDown()
                    callback.onLog(
                        ExperimentLog(now(), "读写并发", "Write-Thread-$tid 完成: ${elapsed}ms, 释放读锁", LogType.WARNING)
                    )
                } finally {
                }
            }
        }

        // 等待所有读写任务完成
        (readJobs + writeJobs).awaitAll()
        callback.onLog(ExperimentLog(now(), "读写并发", "全部完成, 写事务持有排他锁期间读操作被阻塞", LogType.SUCCESS))
        Log.d(TAG, "Read-write blocking completed")
    }

    // ============================================================
    // 实验 3：锁超时
    // ============================================================
    // 目的：演示长事务持有锁期间，其他连接的读操作等待行为
    // 场景：一个线程开启事务但不提交（持有 EXCLUSIVE lock），
    //       另一个线程尝试读取，观察是否等待或超时
    // ============================================================

    /**
     * 锁超时实验
     *
     * 模拟场景：
     * 1. 主线程开启一个长事务（不提交），持有 EXCLUSIVE lock
     * 2. 子协程尝试执行读操作
     * 3. 等待 3 秒后结束主事务
     * 4. 观察读操作是等待锁还是成功执行
     *
     * 注意：由于使用 PerfDatabase 共享连接池（默认 max=1），
     * db1 和 db2 实际上是同一个连接，所以读操作不会真正被阻塞。
     * 这个实验主要用于演示锁的行为概念。
     *
     * @param callback 日志回调
     */
    suspend fun runLockTimeout(callback: Callback) = supervisorScope {
        callback.onLog(ExperimentLog(now(), "锁超时", "启动长事务并尝试读取, 演示 busy timeout", LogType.INFO))

        // 获取可写连接并开启事务（不提交）
        val db1 = dbHelper.writableDatabase
        db1.beginTransaction()

        // 测量从开启事务到读操作完成的总耗时
        val elapsed = measureTimeMillis {
            val doneLatch = CountDownLatch(1)
            // 在协程中尝试读操作
            async(Dispatchers.IO) {
                try {
                    val db2 = dbHelper.readableDatabase
                    // 尝试执行 COUNT 查询
                    db2.rawQuery("SELECT COUNT(*) FROM ${Schema.TABLE_NAME}", null).close()
                    callback.onLog(ExperimentLog(now(), "锁超时", "读操作成功 (未超时)", LogType.SUCCESS))
                } catch (e: Exception) {
                    // 如果锁等待超时，会抛 SQLiteException
                    callback.onLog(ExperimentLog(now(), "锁超时", "读操作异常: ${e.message}", LogType.ERROR))
                }
                doneLatch.countDown()
            }
            // 等待 3 秒，给读操作足够的时间等待锁
            Thread.sleep(3000)
            doneLatch.await()  // 等待读操作完成
        }

        // 结束事务（回滚，因为没有调用 setTransactionSuccessful）
        db1.endTransaction()
        callback.onLog(ExperimentLog(now(), "锁超时", "测试完成, 总耗时: ${elapsed}ms", LogType.SUCCESS))
        Log.d(TAG, "Lock timeout test completed")
    }
}
