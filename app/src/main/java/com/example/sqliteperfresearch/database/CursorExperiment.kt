// ============================================================
// Cursor 持有验证实验（CursorExperiment）
// ============================================================
// 职责：
// 1. 验证 Cursor 遍历过程中，并发执行 INSERT/UPDATE/DELETE 后，Cursor 是否能看到新数据
// 2. 提供两种实验模式：
//    a. 普通遍历模式（runReadConsistencyTest）：不包裹事务，使用 CountDownLatch 精确控制时序
//    b. 事务包裹模式（runTransactionWrappedTest）：用 beginTransaction* 包裹读过程，reader/writer 完全并发
// 3. 支持 WAL 和 TRUNCATE 两种日志模式切换
//
// 核心概念：
// - CursorWindow：SQLite 返回 Cursor 时，数据被加载到 CursorWindow（native mmap 内存映射）
// - 按需填充：CursorWindow 不是一次性加载所有数据，而是按需从数据库填充
// - 快照隔离：理想情况下，Cursor 打开时应看到数据库的快照，后续写操作不影响 Cursor
// - SQLite 的隔离级别：READ COMMITTED（非 SERIALIZABLE），不保证完整的快照隔离
//
// CountDownLatch 用法：
// - cursorPausedLatch：Cursor 遍历到关键行时 countDown() 通知 writer 可以开始写了
// - cursorResumeLatch：writer 写完写入后 countDown() 通知 Cursor 继续遍历
// - 这种精确时序控制确保写操作发生在 Cursor 遍历的中间位置
// ============================================================

package com.example.sqliteperfresearch.database

import android.content.ContentValues    // SQLite 插入/更新操作的键值对容器
import android.database.sqlite.SQLiteDatabase
import android.util.Log
import com.example.sqliteperfresearch.model.ExperimentLog
import com.example.sqliteperfresearch.model.LogType
import com.example.sqliteperfresearch.util.LOG_TAG
import kotlinx.coroutines.Dispatchers   // 协程调度器：IO 线程池用于数据库操作
import kotlinx.coroutines.async         // 启动异步协程，返回 Deferred<T>
import kotlinx.coroutines.delay         // 协程延迟（非阻塞式 sleep）
import kotlinx.coroutines.supervisorScope // 监督作用域：子协程失败不影响兄弟协程
import java.util.concurrent.CountDownLatch // 倒计时门闩，用于线程间同步
import kotlin.random.Random             // Kotlin 随机数 API

/**
 * 事务包裹读操作的开始事务方式
 *
 * 三种事务模式的区别：
 * - EXCLUSIVE（独占事务）：立即获取 EXCLUSIVE lock，阻塞所有其他读写操作
 * - NON_EXCLUSIVE（非独占事务）：WAL 模式下获取 SHARED lock，允许并发读；
 *   TRUNCATE 模式下沉价为独占事务
 * - READ_ONLY（只读事务）：只允许读操作，任何写操作抛异常
 */
enum class ReadTransactionMode(val label: String, val description: String) {
    /** beginTransaction: 独占事务，会立即获取 EXCLUSIVE lock */
    EXCLUSIVE("独占事务", "beginTransaction — 立即获取 EXCLUSIVE lock, 阻塞其他读写"),
    /** beginTransactionNonExclusive: 非独占事务，WAL 下获取 SHARED lock */
    NON_EXCLUSIVE("非独占事务", "beginTransactionNonExclusive — WAL 下获取 SHARED lock, TRUNCATE 下等价于独占"),
    /** beginTransactionReadOnly: 只读事务，仅允许读操作 */
    READ_ONLY("只读事务", "beginTransactionReadOnly — 仅允许读, 任何写操作会抛异常"),
}

/**
 * Cursor 持有验证实验类
 *
 * 使用 PerfDatabase 共享连接池。
 * 实验通过在 Cursor 遍历过程中插入写操作，验证读一致性保障。
 *
 * @param dbHelper 数据库辅助类实例
 */
class CursorExperiment(private val dbHelper: PerfDatabase) {
    companion object {
        /** 日志 tag：SQLitePerf.Cursor */
        const val TAG = "$LOG_TAG.Cursor"
    }

    /** 实验日志回调接口 */
    interface Callback {
        fun onLog(log: ExperimentLog)
    }

    /** 生成时间戳字符串，格式：HH:mm:ss.SSS */
    fun now() = java.text.SimpleDateFormat("HH:mm:ss.SSS", java.util.Locale.getDefault()).format(java.util.Date())

    /** 获取数据库文件路径 */
    private val dbPath: String
        get() = dbHelper.readableDatabase.path

    // ============================================================
    // 模式 1：普通遍历（无事务包裹）
    // ============================================================
    // 使用 CountDownLatch 精确控制 Cursor 和 writer 的时序：
    // 1. Cursor 开始遍历
    // 2. Cursor 遍历到关键行（第 500 行或目标行）时暂停，通知 writer 开始写
    // 3. writer 执行 INSERT/UPDATE/DELETE 并提交
    // 4. writer 通知 Cursor 继续遍历
    // 5. 检查 Cursor 是否看到了写操作的结果
    // ============================================================

    /**
     * 读一致性验证实验入口（普通遍历模式）
     *
     * 依次执行三个子实验：INSERT 读一致性、UPDATE 读一致性、DELETE 读一致性。
     *
     * @param mode "WAL" 或 "TRUNCATE"，设置对应的日志模式
     * @param callback 日志回调
     */
    suspend fun runReadConsistencyTest(mode: String, callback: Callback) = supervisorScope {
        // 设置数据库日志模式
        dbHelper.setWalMode(mode == "WAL")
        val actualMode = dbHelper.getJournalMode()
        callback.onLog(ExperimentLog(now(), "Cursor持有", "当前模式: $actualMode, 开始读一致性验证", LogType.INFO))

        // 依次执行三个实验
        callback.onLog(ExperimentLog(now(), "Cursor持有", "--- 实验1: INSERT 读一致性 ---", LogType.INFO))
        runInsertTest(callback)

        callback.onLog(ExperimentLog(now(), "Cursor持有", "--- 实验2: UPDATE 读一致性 ---", LogType.INFO))
        runUpdateTest(callback)

        callback.onLog(ExperimentLog(now(), "Cursor持有", "--- 实验3: DELETE 读一致性 ---", LogType.INFO))
        runDeleteTest(callback)

        Log.d(TAG, "Read consistency test done")
    }

    // ============================================================
    // 模式 2：事务包裹读（完全并发）
    // ============================================================
    // 使用 beginTransaction* 包裹整个 Cursor 遍历过程，
    // 不使用 CountDownLatch，reader 和 writer 完全并发执行。
    // 验证事务提供的快照隔离是否能保护 Cursor 不受并发写影响。
    // ============================================================

    /**
     * 事务包裹下的 Cursor 读一致性验证入口
     *
     * 对于指定的事务模式（独占/非独占/只读），依次执行三个子实验。
     * Reader 和 Writer 完全并发，无 CountDownLatch 同步。
     *
     * @param txMode 事务模式（EXCLUSIVE / NON_EXCLUSIVE / READ_ONLY）
     * @param callback 日志回调
     */
    suspend fun runTransactionWrappedTest(txMode: ReadTransactionMode, callback: Callback) = supervisorScope {
        val actualMode = dbHelper.getJournalMode()
        callback.onLog(ExperimentLog(now(), "Cursor持有", "当前模式: $actualMode, 事务类型: ${txMode.label}", LogType.INFO))
        callback.onLog(ExperimentLog(now(), "Cursor持有", "关键: ${txMode.description}", LogType.INFO))

        callback.onLog(ExperimentLog(now(), "Cursor持有", "--- 实验1: INSERT 读一致性 (${txMode.label}) ---", LogType.INFO))
        runTransactionWrappedInsertTest(txMode, callback)

        callback.onLog(ExperimentLog(now(), "Cursor持有", "--- 实验2: UPDATE 读一致性 (${txMode.label}) ---", LogType.INFO))
        runTransactionWrappedUpdateTest(txMode, callback)

        callback.onLog(ExperimentLog(now(), "Cursor持有", "--- 实验3: DELETE 读一致性 (${txMode.label}) ---", LogType.INFO))
        runTransactionWrappedDeleteTest(txMode, callback)

        Log.d(TAG, "Transaction-wrapped read consistency test done")
    }

    // ============================================================
    // 工具方法
    // ============================================================

    /**
     * 安全读取 Cursor 当前行
     *
     * 在 DELETE 实验中，被删除行的偏移量可能仍然存在于 CursorWindow 中，
     * 但数据已无效，调用 getLong/getString 会抛出 IllegalStateException。
     * 此方法捕获异常并返回 null，避免 CursorWindow 崩溃导致整个实验中断。
     *
     * @param Cursor SQLite 游标
     * @return (id, name) 对，读取失败返回 null
     */
    private fun safeReadRow(cursor: android.database.Cursor): Pair<Long, String>? {
        return try {
            cursor.getLong(0) to cursor.getString(1)
        } catch (e: IllegalStateException) {
            Log.e(TAG, "safeReadRow exception.", e)
            null
        }
    }

    // ============================================================
    // 普通遍历模式 — INSERT 实验
    // ============================================================
    // 1. 检查目标 ID 是否存在
    // 2. Cursor 开始遍历 ORDER BY id
    // 3. Cursor 遍历到第 500 行时暂停（cursorPausedLatch.countDown()）
    // 4. writer 收到暂停信号，插入新行
    // 5. writer 插入完成后通知 Cursor 继续（cursorResumeLatch.countDown()）
    // 6. Cursor 继续遍历到底
    // 7. 检查 Cursor 是否看到了新插入的行
    // ============================================================

    private suspend fun runInsertTest(callback: Callback) = supervisorScope {
        // 生成一个很大的 ID，避免与现有数据冲突
        val insertId = 999999000L + Random.nextLong(10000)
        val insertName = "consistency_insert_test_${Random.nextLong()}"

        // 先检查该 ID 是否已存在（应该不存在）
        val beforeExists = dbHelper.readableDatabase.rawQuery("SELECT 1 FROM ${Schema.TABLE_NAME} WHERE id = ?", arrayOf("$insertId")).use { it.moveToFirst() }
        callback.onLog(ExperimentLog(now(), "Cursor持有", "INSERT前查询: id=$insertId 存在=$beforeExists", LogType.INFO))

        // cursorPausedLatch：Cursor 通知 writer 可以开始写入了
        val cursorPausedLatch = CountDownLatch(1)
        // cursorResumeLatch：writer 通知 Cursor 可以继续遍历了
        val cursorResumeLatch = CountDownLatch(1)

        // ====== Cursor 线程 ======
        val cursorResult = async(Dispatchers.IO) {
            // rawQuery 执行 SELECT，返回 Cursor
            val cursor = dbHelper.readableDatabase.rawQuery("SELECT id, name FROM ${Schema.TABLE_NAME} ORDER BY id", null)
            callback.onLog(ExperimentLog(now(), "Cursor持有", "Cursor SQL 执行完成, 开始遍历", LogType.INFO))
            val rows = mutableListOf<Pair<Long, String>>()
            var sawNewRow = false    // 是否看到了新插入的行
            var pausedAt = -1        // 暂停时的行号
            try {
                while (cursor.moveToNext()) {
                    val row = safeReadRow(cursor)
                    if (row == null) {
                        callback.onLog(ExperimentLog(now(), "Cursor持有", "CursorWindow 崩溃! 无法读取第 ${rows.size + 1} 行", LogType.ERROR))
                        break
                    }
                    rows.add(row)
                    if (row.first == insertId) sawNewRow = true
                    // 遍历到第 500 行时暂停，通知 writer 开始写
                    if (rows.size == 500 && pausedAt == -1) {
                        pausedAt = rows.size
                        callback.onLog(ExperimentLog(now(), "Cursor持有", "Cursor 已遍历到 $pausedAt 行, 等待插入操作...", LogType.INFO))
                        cursorPausedLatch.countDown()  // 通知 writer
                        cursorResumeLatch.await()       // 等待 writer 完成
                        callback.onLog(ExperimentLog(now(), "Cursor持有", "Cursor 继续遍历", LogType.INFO))
                    }
                }
            } finally {
                try { cursor.close() } catch (_: Exception) {}
            }
            Triple(rows.size, sawNewRow, pausedAt)
        }

        // ====== Writer 线程 ======
        val writeResult = async(Dispatchers.IO) {
            cursorPausedLatch.await()  // 等待 Cursor 到达暂停点
            val writer = dbHelper.writableDatabase
            // 写操作不包裹事务（已注释掉事务控制）
            // writer.beginTransaction()
            callback.onLog(ExperimentLog(now(), "Cursor持有", "INSERT 事务已启动, 开始写入", LogType.WARNING))
            try {
                val cv = ContentValues().apply {
                    put("id", insertId)
                    put("name", insertName)
                    put("counter", 0)
                }
                writer.insert(Schema.TABLE_NAME, null, cv)
                // writer.setTransactionSuccessful()
                callback.onLog(ExperimentLog(now(), "Cursor持有", "INSERT 写入完成, 事务提交完成", LogType.WARNING))
            } finally {
                // writer.endTransaction()
            }

            // 独立查询验证数据确实写入了
            val afterExists = dbHelper.readableDatabase.rawQuery("SELECT 1 FROM ${Schema.TABLE_NAME} WHERE id = ?", arrayOf("$insertId")).use { it.moveToFirst() }
            val afterName = dbHelper.readableDatabase.rawQuery("SELECT name FROM ${Schema.TABLE_NAME} WHERE id = ?", arrayOf("$insertId")).use {
                if (it.moveToFirst()) it.getString(0) else null
            }
            callback.onLog(ExperimentLog(now(), "Cursor持有", "INSERT后独立查询: id=$insertId 存在=$afterExists, name='$afterName'", LogType.INFO))

            cursorResumeLatch.countDown()  // 通知 Cursor 继续
            Pair(afterExists, afterName)
        }

        // 等待两个线程都完成
        val (totalRows, sawNewRow, pausedAt) = cursorResult.await()
        val (afterExists, afterName) = writeResult.await()

        // 输出结果
        callback.onLog(ExperimentLog(now(), "Cursor持有", "===== INSERT 读一致性结果 =====", LogType.INFO))
        callback.onLog(ExperimentLog(now(), "Cursor持有", "Cursor 遍历总行数: $totalRows", LogType.INFO))
        callback.onLog(ExperimentLog(now(), "Cursor持有", "Cursor 是否看到新插入的行(id=$insertId): $sawNewRow", LogType.INFO))
        if (sawNewRow) {
            callback.onLog(ExperimentLog(now(), "Cursor持有", "结论: Cursor 读到了 INSERT 提交后的新数据 (无快照隔离)", LogType.ERROR))
        } else {
            callback.onLog(ExperimentLog(now(), "Cursor持有", "结论: Cursor 未看到新插入的行 (快照隔离生效)", LogType.SUCCESS))
        }
    }

    // ============================================================
    // 普通遍历模式 — UPDATE 实验
    // ============================================================
    // 1. 找到第 300 行作为目标行（OFFSET 299）
    // 2. Cursor 开始遍历，到达目标行时暂停
    // 3. writer 更新目标行的 name 字段
    // 4. Cursor 继续遍历
    // 5. 比较 Cursor 读到的值与更新前后的值
    // ============================================================

    private suspend fun runUpdateTest(callback: Callback) = supervisorScope {
        // 找到第 300 行的 ID 作为更新目标
        val targetRowId = dbHelper.readableDatabase.rawQuery("SELECT id FROM ${Schema.TABLE_NAME} ORDER BY id LIMIT 1 OFFSET 299", null).use {
            if (it.moveToFirst()) it.getLong(0) else null
        } ?: run {
            callback.onLog(ExperimentLog(now(), "Cursor持有", "UPDATE测试: 数据不足, 跳过", LogType.WARNING))
            return@supervisorScope
        }

        // 查询更新前的值
        val beforeName = dbHelper.readableDatabase.rawQuery("SELECT name FROM ${Schema.TABLE_NAME} WHERE id = ?", arrayOf("$targetRowId")).use {
            if (it.moveToFirst()) it.getString(0) else null
        }
        val updateName = "consistency_update_${Random.nextLong()}"
        callback.onLog(ExperimentLog(now(), "Cursor持有", "UPDATE前查询: id=$targetRowId, name='$beforeName'", LogType.INFO))

        val cursorPausedLatch = CountDownLatch(1)
        val cursorResumeLatch = CountDownLatch(1)

        // ====== Cursor 线程 ======
        val cursorResult = async(Dispatchers.IO) {
            val cursor = dbHelper.readableDatabase.rawQuery("SELECT id, name FROM ${Schema.TABLE_NAME} ORDER BY id", null)
            callback.onLog(ExperimentLog(now(), "Cursor持有", "Cursor SQL 执行完成, 开始遍历", LogType.INFO))
            var cursorValueAtTarget: String? = null  // Cursor 在目标行读到的值
            var rowsBeforeTarget = 0
            try {
                while (cursor.moveToNext()) {
                    val row = safeReadRow(cursor)
                    if (row == null) {
                        callback.onLog(ExperimentLog(now(), "Cursor持有", "CursorWindow 崩溃! 无法读取第 ${rowsBeforeTarget + 1} 行", LogType.ERROR))
                        break
                    }
                    val (id, name) = row
                    rowsBeforeTarget++
                    // 到达目标行，记录值并暂停
                    if (id == targetRowId) {
                        cursorValueAtTarget = name
                        callback.onLog(ExperimentLog(now(), "Cursor持有", "Cursor 到达关键行 id=$targetRowId, name='$name', 等待更新操作...", LogType.INFO))
                        cursorPausedLatch.countDown()
                        cursorResumeLatch.await()
                        callback.onLog(ExperimentLog(now(), "Cursor持有", "Cursor 继续遍历", LogType.INFO))
                    }
                }
            } finally {
                try { cursor.close() } catch (_: Exception) {}
            }
            Triple(cursorValueAtTarget, rowsBeforeTarget, cursorValueAtTarget != null)
        }

        // ====== Writer 线程 ======
        val writeResult = async(Dispatchers.IO) {
            cursorPausedLatch.await()
            val writer = dbHelper.writableDatabase
            // writer.beginTransaction()
            callback.onLog(ExperimentLog(now(), "Cursor持有", "UPDATE 事务已启动, 开始写入", LogType.WARNING))
            try {
                val cv = ContentValues().apply { put("name", updateName) }
                writer.update(Schema.TABLE_NAME, cv, "id = ?", arrayOf("$targetRowId"))
                // writer.setTransactionSuccessful()
                callback.onLog(ExperimentLog(now(), "Cursor持有", "UPDATE 写入完成, 事务提交完成", LogType.WARNING))
            } finally {
                // writer.endTransaction()
            }

            // 独立查询验证
            val afterName = dbHelper.readableDatabase.rawQuery("SELECT name FROM ${Schema.TABLE_NAME} WHERE id = ?", arrayOf("$targetRowId")).use {
                if (it.moveToFirst()) it.getString(0) else null
            }
            callback.onLog(ExperimentLog(now(), "Cursor持有", "UPDATE后独立查询: id=$targetRowId, name='$afterName'", LogType.INFO))

            cursorResumeLatch.countDown()
            afterName
        }

        val (cursorValue, rowsBefore, reached) = cursorResult.await()
        val afterName = writeResult.await()

        callback.onLog(ExperimentLog(now(), "Cursor持有", "===== UPDATE 读一致性结果 =====", LogType.INFO))
        callback.onLog(ExperimentLog(now(), "Cursor持有", "Cursor 到达关键行前已读: $rowsBefore 行", LogType.INFO))
        callback.onLog(ExperimentLog(now(), "Cursor持有", "Cursor 在关键行读到的值: '$cursorValue'", LogType.INFO))
        callback.onLog(ExperimentLog(now(), "Cursor持有", "更新后的新值: '$updateName'", LogType.INFO))
        callback.onLog(ExperimentLog(now(), "Cursor持有", "UPDATE后独立查询值: '$afterName'", LogType.INFO))
        when {
            cursorValue == beforeName -> {
                callback.onLog(ExperimentLog(now(), "Cursor持有", "结论: Cursor 读到旧值 (快照隔离生效)", LogType.SUCCESS))
            }
            cursorValue == updateName -> {
                callback.onLog(ExperimentLog(now(), "Cursor持有", "结论: Cursor 读到了新值 (无快照隔离)", LogType.ERROR))
            }
            else -> {
                callback.onLog(ExperimentLog(now(), "Cursor持有", "结论: Cursor 读到未知值 '$cursorValue'", LogType.ERROR))
            }
        }
    }

    // ============================================================
    // 普通遍历模式 — DELETE 实验
    // ============================================================
    // 1. 找到第 200 行作为目标行（OFFSET 199）
    // 2. Cursor 开始遍历，到达目标行时暂停
    // 3. writer 删除目标行
    // 4. Cursor 继续遍历
    // 5. 观察 Cursor 行为：可能读到被删除的行、崩溃（CursorWindow 偏移量错乱）、或未遍历到该行
    // ============================================================

    private suspend fun runDeleteTest(callback: Callback) = supervisorScope {
        // 找到第 200 行的 ID 作为删除目标
        val targetRowId = dbHelper.readableDatabase.rawQuery("SELECT id FROM ${Schema.TABLE_NAME} ORDER BY id LIMIT 1 OFFSET 199", null).use {
            if (it.moveToFirst()) it.getLong(0) else null
        } ?: run {
            callback.onLog(ExperimentLog(now(), "Cursor持有", "DELETE测试: 数据不足, 跳过", LogType.WARNING))
            return@supervisorScope
        }

        val beforeName = dbHelper.readableDatabase.rawQuery("SELECT name FROM ${Schema.TABLE_NAME} WHERE id = ?", arrayOf("$targetRowId")).use {
            if (it.moveToFirst()) it.getString(0) else null
        }
        callback.onLog(ExperimentLog(now(), "Cursor持有", "DELETE前查询: id=$targetRowId, name='$beforeName'", LogType.INFO))

        val cursorPausedLatch = CountDownLatch(1)
        val cursorResumeLatch = CountDownLatch(1)

        // 使用 data class 封装 DELETE 实验的结果，包含崩溃状态
        data class DeleteCursorResult(
            val cursorValue: String?,    // Cursor 在目标行读到的值
            val rowsBefore: Int,         // 到达目标行前已读行数
            val foundTarget: Boolean,    // 是否找到了目标行
            val cursorWindowCrash: Boolean,  // CursorWindow 是否崩溃
            val crashRow: Int            // 崩溃发生在第几行
        )

        // ====== Cursor 线程 ======
        val cursorResult = async(Dispatchers.IO) {
            val cursor = dbHelper.readableDatabase.rawQuery("SELECT id, name FROM ${Schema.TABLE_NAME} ORDER BY id", null)
            callback.onLog(ExperimentLog(now(), "Cursor持有", "Cursor SQL 执行完成, 开始遍历", LogType.INFO))
            var cursorValueAtTarget: String? = null
            var rowsBeforeTarget = 0
            var foundTarget = false
            var cursorWindowCrash = false
            var crashRow = -1
            try {
                while (cursor.moveToNext()) {
                    val row = safeReadRow(cursor)
                    if (row == null) {
                        // DELETE 后 CursorWindow 偏移量错乱，safeReadRow 捕获到异常
                        cursorWindowCrash = true
                        crashRow = rowsBeforeTarget + 1
                        callback.onLog(ExperimentLog(now(), "Cursor持有", "CursorWindow 崩溃! 无法读取第 $crashRow 行 (行被删除导致 CursorWindow 偏移量错乱)", LogType.ERROR))
                        break
                    }
                    val (id, name) = row
                    rowsBeforeTarget++
                    if (id == targetRowId) {
                        cursorValueAtTarget = name
                        foundTarget = true
                        callback.onLog(ExperimentLog(now(), "Cursor持有", "Cursor 到达关键行 id=$targetRowId, name='$name', 等待删除操作...", LogType.INFO))
                        cursorPausedLatch.countDown()
                        cursorResumeLatch.await()
                        callback.onLog(ExperimentLog(now(), "Cursor持有", "Cursor 继续遍历", LogType.INFO))
                    }
                }
            } finally {
                try { cursor.close() } catch (_: Exception) {}
            }
            DeleteCursorResult(cursorValueAtTarget, rowsBeforeTarget, foundTarget, cursorWindowCrash, crashRow)
        }

        // ====== Writer 线程 ======
        val writeResult = async(Dispatchers.IO) {
            cursorPausedLatch.await()
            val writer = dbHelper.writableDatabase
            // writer.beginTransaction()
            callback.onLog(ExperimentLog(now(), "Cursor持有", "DELETE 事务已启动, 开始写入", LogType.WARNING))
            try {
                val deleted = writer.delete(Schema.TABLE_NAME, "id = ?", arrayOf("$targetRowId"))
                // writer.setTransactionSuccessful()
                callback.onLog(ExperimentLog(now(), "Cursor持有", "DELETE 写入完成 (影响行数=$deleted), 事务提交完成", LogType.WARNING))
            } finally {
                // writer.endTransaction()
            }

            // 独立查询验证行确实被删除了
            val afterExists = dbHelper.readableDatabase.rawQuery("SELECT 1 FROM ${Schema.TABLE_NAME} WHERE id = ?", arrayOf("$targetRowId")).use { it.moveToFirst() }
            callback.onLog(ExperimentLog(now(), "Cursor持有", "DELETE后独立查询: id=$targetRowId 存在=$afterExists", LogType.INFO))

            cursorResumeLatch.countDown()
            afterExists
        }

        val result = cursorResult.await()
        val afterExists = writeResult.await()

        callback.onLog(ExperimentLog(now(), "Cursor持有", "===== DELETE 读一致性结果 =====", LogType.INFO))
        callback.onLog(ExperimentLog(now(), "Cursor持有", "Cursor 到达关键行前已读: ${result.rowsBefore} 行", LogType.INFO))
        when {
            result.cursorWindowCrash -> {
                // 最常见情况：DELETE 导致 CursorWindow 内部行号与物理偏移量不一致
                callback.onLog(ExperimentLog(now(), "Cursor持有", "CursorWindow 崩溃! 在第 ${result.crashRow} 行读取失败", LogType.ERROR))
                callback.onLog(ExperimentLog(now(), "Cursor持有", "结论: DELETE 操作导致 Cursor 崩溃 — 删除行后 CursorWindow 内部行号与物理偏移量不一致", LogType.ERROR))
                callback.onLog(ExperimentLog(now(), "Cursor持有", "说明: CursorWindow 不是完全的快照隔离, 底层数据删除会导致已打开的 Cursor 读取失败", LogType.INFO))
            }
            result.foundTarget -> {
                callback.onLog(ExperimentLog(now(), "Cursor持有", "Cursor 读到被删除的行(id=$targetRowId): name='${result.cursorValue}'", LogType.WARNING))
                callback.onLog(ExperimentLog(now(), "Cursor持有", "结论: Cursor 读到了已删除的行 (快照隔离保护)", LogType.SUCCESS))
            }
            else -> {
                callback.onLog(ExperimentLog(now(), "Cursor持有", "Cursor 未读到被删除的行 (未遍历到该行)", LogType.INFO))
                callback.onLog(ExperimentLog(now(), "Cursor持有", "结论: Cursor 未读到被删除的行", LogType.SUCCESS))
            }
        }
        callback.onLog(ExperimentLog(now(), "Cursor持有", "DELETE后独立查询: 行存在=$afterExists", LogType.INFO))
    }

    // ============================================================
    // 事务包裹模式 — 完全并发，无 CountDownLatch
    // ============================================================
    // 使用 beginTransaction* 包裹 Cursor 遍历，writer 在 delay(100) 后直接写入。
    // Reader 和 Writer 完全并发，不等待对方。
    // 验证事务提供的快照隔离是否能保护 Cursor 不受并发写影响。
    // ============================================================

    /** 事务包裹 INSERT 实验 */
    private suspend fun runTransactionWrappedInsertTest(txMode: ReadTransactionMode, callback: Callback) = supervisorScope {
        val insertId = 999999000L + Random.nextLong(10000)
        val insertName = "txn_insert_test_${Random.nextLong()}"

        val beforeExists = dbHelper.readableDatabase.rawQuery("SELECT 1 FROM ${Schema.TABLE_NAME} WHERE id = ?", arrayOf("$insertId")).use { it.moveToFirst() }
        callback.onLog(ExperimentLog(now(), "Cursor持有", "INSERT前查询: id=$insertId 存在=$beforeExists", LogType.INFO))

        // ====== Cursor 线程（事务包裹） ======
        val cursorResult = async(Dispatchers.IO) {
            val reader = dbHelper.readableDatabase
            // 根据事务模式选择不同的 beginTransaction
            when (txMode) {
                ReadTransactionMode.EXCLUSIVE -> {
                    reader.beginTransaction()
                    callback.onLog(ExperimentLog(now(), "Cursor持有", "Reader beginTransaction (独占) 已启动", LogType.INFO))
                }
                ReadTransactionMode.NON_EXCLUSIVE -> {
                    reader.beginTransactionNonExclusive()
                    callback.onLog(ExperimentLog(now(), "Cursor持有", "Reader beginTransactionNonExclusive (非独占) 已启动", LogType.INFO))
                }
                ReadTransactionMode.READ_ONLY -> {
                    reader.beginTransactionReadOnly()
                    callback.onLog(ExperimentLog(now(), "Cursor持有", "Reader beginTransactionReadOnly (只读) 已启动", LogType.INFO))
                }
            }
            val cursor = reader.rawQuery("SELECT id, name FROM ${Schema.TABLE_NAME} ORDER BY id", null)
            callback.onLog(ExperimentLog(now(), "Cursor持有", "Cursor SQL 执行完成, 开始遍历", LogType.INFO))
            var totalRows = 0
            var sawNewRow = false
            try {
                callback.onLog(ExperimentLog(now(), "Cursor持有", "Cursor 遍历 start", LogType.WARNING))
                while (cursor.moveToNext()) {
                    val row = safeReadRow(cursor) ?: break
                    totalRows++
                    if (row.first == insertId) sawNewRow = true
                }
            } finally {
                callback.onLog(ExperimentLog(now(), "Cursor持有", "Cursor 遍历 end", LogType.WARNING))
                try { cursor.close() } catch (_: Exception) {}
                reader.endTransaction()  // 结束事务
            }
            Pair(totalRows, sawNewRow)
        }

        // ====== Writer 线程（完全并发，延迟 100ms 确保 cursor 先开始） ======
        delay(100)  // 确保 reader 已经开始遍历
        val writer = dbHelper.writableDatabase
        // writer.beginTransaction()
        callback.onLog(ExperimentLog(now(), "Cursor持有", "INSERT 事务已启动, 开始写入", LogType.WARNING))
        try {
            val cv = ContentValues().apply {
                put("id", insertId)
                put("name", insertName)
                put("counter", 0)
            }
            writer.insert(Schema.TABLE_NAME, null, cv)
            // writer.setTransactionSuccessful()
            callback.onLog(ExperimentLog(now(), "Cursor持有", "INSERT 写入完成, 事务提交完成", LogType.WARNING))
        } finally {
            // writer.endTransaction()
        }

        // 独立查询验证
        val afterExists = dbHelper.readableDatabase.rawQuery("SELECT 1 FROM ${Schema.TABLE_NAME} WHERE id = ?", arrayOf("$insertId")).use { it.moveToFirst() }
        callback.onLog(ExperimentLog(now(), "Cursor持有", "INSERT后独立查询: id=$insertId 存在=$afterExists", LogType.INFO))

        val (totalRows, sawNewRow) = cursorResult.await()

        callback.onLog(ExperimentLog(now(), "Cursor持有", "===== INSERT 读一致性结果 (事务包裹) =====", LogType.INFO))
        callback.onLog(ExperimentLog(now(), "Cursor持有", "Cursor 遍历总行数: $totalRows", LogType.INFO))
        callback.onLog(ExperimentLog(now(), "Cursor持有", "Cursor 是否看到新插入的行(id=$insertId): $sawNewRow", LogType.INFO))
        if (sawNewRow) {
            callback.onLog(ExperimentLog(now(), "Cursor持有", "结论: Cursor 读到了 INSERT 提交后的新数据", LogType.ERROR))
        } else {
            callback.onLog(ExperimentLog(now(), "Cursor持有", "结论: Cursor 未看到新插入的行 — 事务 BEGIN 锁定的快照隔离了并发 INSERT", LogType.SUCCESS))
        }
    }

    /** 事务包裹 UPDATE 实验 */
    private suspend fun runTransactionWrappedUpdateTest(txMode: ReadTransactionMode, callback: Callback) = supervisorScope {
        val targetRowId = dbHelper.readableDatabase.rawQuery("SELECT id FROM ${Schema.TABLE_NAME} ORDER BY id LIMIT 1 OFFSET 299", null).use {
            if (it.moveToFirst()) it.getLong(0) else null
        } ?: run {
            callback.onLog(ExperimentLog(now(), "Cursor持有", "UPDATE测试: 数据不足, 跳过", LogType.WARNING))
            return@supervisorScope
        }

        val beforeName = dbHelper.readableDatabase.rawQuery("SELECT name FROM ${Schema.TABLE_NAME} WHERE id = ?", arrayOf("$targetRowId")).use {
            if (it.moveToFirst()) it.getString(0) else null
        }
        val updateName = "txn_update_${Random.nextLong()}"
        callback.onLog(ExperimentLog(now(), "Cursor持有", "UPDATE前查询: id=$targetRowId, name='$beforeName'", LogType.INFO))

        val cursorResult = async(Dispatchers.IO) {
            val reader = dbHelper.readableDatabase
            when (txMode) {
                ReadTransactionMode.EXCLUSIVE -> {
                    reader.beginTransaction()
                    callback.onLog(ExperimentLog(now(), "Cursor持有", "Reader beginTransaction (独占) 已启动", LogType.INFO))
                }
                ReadTransactionMode.NON_EXCLUSIVE -> {
                    reader.beginTransactionNonExclusive()
                    callback.onLog(ExperimentLog(now(), "Cursor持有", "Reader beginTransactionNonExclusive (非独占) 已启动", LogType.INFO))
                }
                ReadTransactionMode.READ_ONLY -> {
                    reader.beginTransactionReadOnly()
                    callback.onLog(ExperimentLog(now(), "Cursor持有", "Reader beginTransactionReadOnly (只读) 已启动", LogType.INFO))
                }
            }
            val cursor = reader.rawQuery("SELECT id, name FROM ${Schema.TABLE_NAME} ORDER BY id", null)
            callback.onLog(ExperimentLog(now(), "Cursor持有", "Cursor SQL 执行完成, 开始遍历", LogType.INFO))
            var cursorValueAtTarget: String? = null
            var rowsCount = 0
            try {
                callback.onLog(ExperimentLog(now(), "Cursor持有", "Cursor 遍历 start", LogType.WARNING))
                while (cursor.moveToNext()) {
                    val row = safeReadRow(cursor) ?: break
                    val (id, name) = row
                    rowsCount++
                    if (id == targetRowId) {
                        cursorValueAtTarget = name
                    }
                }
            } finally {
                callback.onLog(ExperimentLog(now(), "Cursor持有", "Cursor 遍历 end", LogType.WARNING))
                try { cursor.close() } catch (_: Exception) {}
                reader.endTransaction()
            }
            Triple(cursorValueAtTarget, rowsCount, cursorValueAtTarget != null)
        }

        delay(100)
        val writer = dbHelper.writableDatabase
        // writer.beginTransaction()
        callback.onLog(ExperimentLog(now(), "Cursor持有", "UPDATE 事务已启动, 开始写入", LogType.WARNING))
        try {
            val cv = ContentValues().apply { put("name", updateName) }
            writer.update(Schema.TABLE_NAME, cv, "id = ?", arrayOf("$targetRowId"))
            // writer.setTransactionSuccessful()
            callback.onLog(ExperimentLog(now(), "Cursor持有", "UPDATE 写入完成, 事务提交完成", LogType.WARNING))
        } finally {
            // writer.endTransaction()
        }

        val afterName = dbHelper.readableDatabase.rawQuery("SELECT name FROM ${Schema.TABLE_NAME} WHERE id = ?", arrayOf("$targetRowId")).use {
            if (it.moveToFirst()) it.getString(0) else null
        }
        callback.onLog(ExperimentLog(now(), "Cursor持有", "UPDATE后独立查询: id=$targetRowId, name='$afterName'", LogType.INFO))

        val (cursorValue, rowsCount, reached) = cursorResult.await()

        callback.onLog(ExperimentLog(now(), "Cursor持有", "===== UPDATE 读一致性结果 (事务包裹) =====", LogType.INFO))
        callback.onLog(ExperimentLog(now(), "Cursor持有", "Cursor 遍历总行数: $rowsCount 行", LogType.INFO))
        callback.onLog(ExperimentLog(now(), "Cursor持有", "Cursor 在关键行读到的值: '$cursorValue'", LogType.INFO))
        callback.onLog(ExperimentLog(now(), "Cursor持有", "更新后的新值: '$updateName'", LogType.INFO))
        callback.onLog(ExperimentLog(now(), "Cursor持有", "UPDATE后独立查询值: '$afterName'", LogType.INFO))
        when {
            cursorValue == beforeName -> {
                callback.onLog(ExperimentLog(now(), "Cursor持有", "结论: Cursor 读到旧值 — 事务 BEGIN 锁定的快照隔离了并发 UPDATE", LogType.SUCCESS))
            }
            cursorValue == updateName -> {
                callback.onLog(ExperimentLog(now(), "Cursor持有", "结论: Cursor 读到了新值", LogType.ERROR))
            }
            else -> {
                callback.onLog(ExperimentLog(now(), "Cursor持有", "结论: Cursor 读到未知值 '$cursorValue'", LogType.ERROR))
            }
        }
    }

    /** 事务包裹 DELETE 实验 */
    private suspend fun runTransactionWrappedDeleteTest(txMode: ReadTransactionMode, callback: Callback) = supervisorScope {
        val targetRowId = dbHelper.readableDatabase.rawQuery("SELECT id FROM ${Schema.TABLE_NAME} ORDER BY id LIMIT 1 OFFSET 199", null).use {
            if (it.moveToFirst()) it.getLong(0) else null
        } ?: run {
            callback.onLog(ExperimentLog(now(), "Cursor持有", "DELETE测试: 数据不足, 跳过", LogType.WARNING))
            return@supervisorScope
        }

        val beforeName = dbHelper.readableDatabase.rawQuery("SELECT name FROM ${Schema.TABLE_NAME} WHERE id = ?", arrayOf("$targetRowId")).use {
            if (it.moveToFirst()) it.getString(0) else null
        }
        callback.onLog(ExperimentLog(now(), "Cursor持有", "DELETE前查询: id=$targetRowId, name='$beforeName'", LogType.INFO))

        data class DeleteCursorResult(val cursorValue: String?, val rowsBefore: Int, val foundTarget: Boolean, val cursorWindowCrash: Boolean, val crashRow: Int)

        val cursorResult = async(Dispatchers.IO) {
            val reader = dbHelper.readableDatabase
            when (txMode) {
                ReadTransactionMode.EXCLUSIVE -> {
                    reader.beginTransaction()
                    callback.onLog(ExperimentLog(now(), "Cursor持有", "Reader beginTransaction (独占) 已启动", LogType.INFO))
                }
                ReadTransactionMode.NON_EXCLUSIVE -> {
                    reader.beginTransactionNonExclusive()
                    callback.onLog(ExperimentLog(now(), "Cursor持有", "Reader beginTransactionNonExclusive (非独占) 已启动", LogType.INFO))
                }
                ReadTransactionMode.READ_ONLY -> {
                    reader.beginTransactionReadOnly()
                    callback.onLog(ExperimentLog(now(), "Cursor持有", "Reader beginTransactionReadOnly (只读) 已启动", LogType.INFO))
                }
            }
            val cursor = reader.rawQuery("SELECT id, name FROM ${Schema.TABLE_NAME} ORDER BY id", null)
            callback.onLog(ExperimentLog(now(), "Cursor持有", "Cursor SQL 执行完成, 开始遍历", LogType.INFO))
            var cursorValueAtTarget: String? = null
            var rowsBeforeTarget = 0
            var foundTarget = false
            var cursorWindowCrash = false
            var crashRow = -1
            try {
                callback.onLog(ExperimentLog(now(), "Cursor持有", "Cursor 遍历 start", LogType.WARNING))
                while (cursor.moveToNext()) {
                    val row = safeReadRow(cursor)
                    if (row == null) {
                        cursorWindowCrash = true
                        crashRow = rowsBeforeTarget + 1
                        callback.onLog(ExperimentLog(now(), "Cursor持有", "CursorWindow 崩溃! 无法读取第 $crashRow 行", LogType.ERROR))
                        break
                    }
                    val (id, name) = row
                    rowsBeforeTarget++
                    if (id == targetRowId) {
                        cursorValueAtTarget = name
                        foundTarget = true
                    }
                }
            } finally {
                callback.onLog(ExperimentLog(now(), "Cursor持有", "Cursor 遍历 end", LogType.WARNING))
                try { cursor.close() } catch (_: Exception) {}
                reader.endTransaction()
            }
            DeleteCursorResult(cursorValueAtTarget, rowsBeforeTarget, foundTarget, cursorWindowCrash, crashRow)
        }

        delay(100)
        val writer = dbHelper.writableDatabase
        // writer.beginTransaction()
        callback.onLog(ExperimentLog(now(), "Cursor持有", "DELETE 事务已启动, 开始写入", LogType.WARNING))
        try {
            val deleted = writer.delete(Schema.TABLE_NAME, "id = ?", arrayOf("$targetRowId"))
            // writer.setTransactionSuccessful()
            callback.onLog(ExperimentLog(now(), "Cursor持有", "DELETE 写入完成 (影响行数=$deleted), 事务提交完成", LogType.WARNING))
        } finally {
            // writer.endTransaction()
        }

        val afterExists = dbHelper.readableDatabase.rawQuery("SELECT 1 FROM ${Schema.TABLE_NAME} WHERE id = ?", arrayOf("$targetRowId")).use { it.moveToFirst() }
        callback.onLog(ExperimentLog(now(), "Cursor持有", "DELETE后独立查询: id=$targetRowId 存在=$afterExists", LogType.INFO))

        val result = cursorResult.await()

        callback.onLog(ExperimentLog(now(), "Cursor持有", "===== DELETE 读一致性结果 (事务包裹) =====", LogType.INFO))
        callback.onLog(ExperimentLog(now(), "Cursor持有", "Cursor 遍历总行数: ${result.rowsBefore} 行", LogType.INFO))
        when {
            result.cursorWindowCrash -> {
                callback.onLog(ExperimentLog(now(), "Cursor持有", "CursorWindow 崩溃! 在第 ${result.crashRow} 行读取失败", LogType.ERROR))
                callback.onLog(ExperimentLog(now(), "Cursor持有", "结论: 即使事务包裹, CursorWindow 仍然崩溃", LogType.ERROR))
            }
            result.foundTarget -> {
                callback.onLog(ExperimentLog(now(), "Cursor持有", "Cursor 读到被删除的行(id=$targetRowId): name='${result.cursorValue}'", LogType.SUCCESS))
                callback.onLog(ExperimentLog(now(), "Cursor持有", "结论: 事务 BEGIN 锁定的快照保护了已删除的行 — Cursor 仍然看到了它", LogType.SUCCESS))
            }
            else -> {
                callback.onLog(ExperimentLog(now(), "Cursor持有", "Cursor 未读到被删除的行 (未遍历到该行)", LogType.INFO))
            }
        }
        callback.onLog(ExperimentLog(now(), "Cursor持有", "DELETE后独立查询: 行存在=$afterExists", LogType.INFO))
    }
}
