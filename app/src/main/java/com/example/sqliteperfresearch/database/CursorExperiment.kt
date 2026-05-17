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
import kotlin.random.Random

/** 事务包裹读操作的开始事务方式 */
enum class ReadTransactionMode(val label: String, val description: String) {
    /** beginTransaction: 独占事务, 会立即获取 EXCLUSIVE lock */
    EXCLUSIVE("独占事务", "beginTransaction — 立即获取 EXCLUSIVE lock, 阻塞其他读写"),
    /** beginTransactionNonExclusive: 非独占事务, WAL 下获取 SHARED lock */
    NON_EXCLUSIVE("非独占事务", "beginTransactionNonExclusive — WAL 下获取 SHARED lock, TRUNCATE 下等价于独占"),
    /** beginTransactionReadOnly: 只读事务, 仅允许读操作 */
    READ_ONLY("只读事务", "beginTransactionReadOnly — 仅允许读, 任何写操作会抛异常"),
}

/**
 * 读一致性验证实验
 */
class CursorExperiment(private val dbHelper: PerfDatabase) {
    companion object {
        const val TAG = "$LOG_TAG.Cursor"
    }

    interface Callback {
        fun onLog(log: ExperimentLog)
    }

    fun now() = java.text.SimpleDateFormat("HH:mm:ss.SSS", java.util.Locale.getDefault()).format(java.util.Date())

    private val dbPath: String
        get() = dbHelper.readableDatabase.path

    suspend fun runReadConsistencyTest(mode: String, callback: Callback) = supervisorScope {
        dbHelper.setWalMode(mode == "WAL")
        val actualMode = dbHelper.getJournalMode()
        callback.onLog(ExperimentLog(now(), "Cursor持有", "当前模式: $actualMode, 开始读一致性验证", LogType.INFO))

        callback.onLog(ExperimentLog(now(), "Cursor持有", "--- 实验1: INSERT 读一致性 ---", LogType.INFO))
        runInsertTest(callback)

        callback.onLog(ExperimentLog(now(), "Cursor持有", "--- 实验2: UPDATE 读一致性 ---", LogType.INFO))
        runUpdateTest(callback)

        callback.onLog(ExperimentLog(now(), "Cursor持有", "--- 实验3: DELETE 读一致性 ---", LogType.INFO))
        runDeleteTest(callback)

        Log.d(TAG, "Read consistency test done")
    }

    /**
     * 事务包裹下的 Cursor 读一致性验证
     *
     * 使用 beginTransaction* 包裹整个 Cursor 遍历, 锁定快照。
     * Reader 和 Writer 完全并发执行, 不使用 CountDownLatch 同步。
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

    private fun safeReadRow(cursor: android.database.Cursor): Pair<Long, String>? {
        return try {
            cursor.getLong(0) to cursor.getString(1)
        } catch (e: IllegalStateException) {
            null
        }
    }

    private suspend fun runInsertTest(callback: Callback) = supervisorScope {
        val insertId = 999999000L + Random.nextLong(10000)
        val insertName = "consistency_insert_test_${Random.nextLong()}"

        val beforeExists = dbHelper.readableDatabase.rawQuery("SELECT 1 FROM ${Schema.TABLE_NAME} WHERE id = ?", arrayOf("$insertId")).use { it.moveToFirst() }
        callback.onLog(ExperimentLog(now(), "Cursor持有", "INSERT前查询: id=$insertId 存在=$beforeExists", LogType.INFO))

        val cursorPausedLatch = CountDownLatch(1)
        val cursorResumeLatch = CountDownLatch(1)

        val cursorResult = async(Dispatchers.IO) {
            val cursor = dbHelper.readableDatabase.rawQuery("SELECT id, name FROM ${Schema.TABLE_NAME} ORDER BY id", null)
            callback.onLog(ExperimentLog(now(), "Cursor持有", "Cursor SQL 执行完成, 开始遍历", LogType.INFO))
            val rows = mutableListOf<Pair<Long, String>>()
            var sawNewRow = false
            var pausedAt = -1
            try {
                while (cursor.moveToNext()) {
                    val row = safeReadRow(cursor)
                    if (row == null) {
                        callback.onLog(ExperimentLog(now(), "Cursor持有", "CursorWindow 崩溃! 无法读取第 ${rows.size + 1} 行", LogType.ERROR))
                        break
                    }
                    rows.add(row)
                    if (row.first == insertId) sawNewRow = true
                    if (rows.size == 500 && pausedAt == -1) {
                        pausedAt = rows.size
                        callback.onLog(ExperimentLog(now(), "Cursor持有", "Cursor 已遍历到 $pausedAt 行, 等待插入操作...", LogType.INFO))
                        cursorPausedLatch.countDown()
                        cursorResumeLatch.await()
                        callback.onLog(ExperimentLog(now(), "Cursor持有", "Cursor 继续遍历", LogType.INFO))
                    }
                }
            } finally {
                try { cursor.close() } catch (_: Exception) {}
            }
            Triple(rows.size, sawNewRow, pausedAt)
        }

        val writeResult = async(Dispatchers.IO) {
            cursorPausedLatch.await()
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

            val afterExists = dbHelper.readableDatabase.rawQuery("SELECT 1 FROM ${Schema.TABLE_NAME} WHERE id = ?", arrayOf("$insertId")).use { it.moveToFirst() }
            val afterName = dbHelper.readableDatabase.rawQuery("SELECT name FROM ${Schema.TABLE_NAME} WHERE id = ?", arrayOf("$insertId")).use {
                if (it.moveToFirst()) it.getString(0) else null
            }
            callback.onLog(ExperimentLog(now(), "Cursor持有", "INSERT后独立查询: id=$insertId 存在=$afterExists, name='$afterName'", LogType.INFO))

            cursorResumeLatch.countDown()
            Pair(afterExists, afterName)
        }

        val (totalRows, sawNewRow, pausedAt) = cursorResult.await()
        val (afterExists, afterName) = writeResult.await()

        callback.onLog(ExperimentLog(now(), "Cursor持有", "===== INSERT 读一致性结果 =====", LogType.INFO))
        callback.onLog(ExperimentLog(now(), "Cursor持有", "Cursor 遍历总行数: $totalRows", LogType.INFO))
        callback.onLog(ExperimentLog(now(), "Cursor持有", "Cursor 是否看到新插入的行(id=$insertId): $sawNewRow", LogType.INFO))
        if (sawNewRow) {
            callback.onLog(ExperimentLog(now(), "Cursor持有", "结论: Cursor 读到了 INSERT 提交后的新数据 (无快照隔离)", LogType.ERROR))
        } else {
            callback.onLog(ExperimentLog(now(), "Cursor持有", "结论: Cursor 未看到新插入的行 (快照隔离生效)", LogType.SUCCESS))
        }
    }

    private suspend fun runUpdateTest(callback: Callback) = supervisorScope {
        val targetRowId = dbHelper.readableDatabase.rawQuery("SELECT id FROM ${Schema.TABLE_NAME} ORDER BY id LIMIT 1 OFFSET 299", null).use {
            if (it.moveToFirst()) it.getLong(0) else null
        } ?: run {
            callback.onLog(ExperimentLog(now(), "Cursor持有", "UPDATE测试: 数据不足, 跳过", LogType.WARNING))
            return@supervisorScope
        }

        val beforeName = dbHelper.readableDatabase.rawQuery("SELECT name FROM ${Schema.TABLE_NAME} WHERE id = ?", arrayOf("$targetRowId")).use {
            if (it.moveToFirst()) it.getString(0) else null
        }
        val updateName = "consistency_update_${Random.nextLong()}"
        callback.onLog(ExperimentLog(now(), "Cursor持有", "UPDATE前查询: id=$targetRowId, name='$beforeName'", LogType.INFO))

        val cursorPausedLatch = CountDownLatch(1)
        val cursorResumeLatch = CountDownLatch(1)

        val cursorResult = async(Dispatchers.IO) {
            val cursor = dbHelper.readableDatabase.rawQuery("SELECT id, name FROM ${Schema.TABLE_NAME} ORDER BY id", null)
            callback.onLog(ExperimentLog(now(), "Cursor持有", "Cursor SQL 执行完成, 开始遍历", LogType.INFO))
            var cursorValueAtTarget: String? = null
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

    private suspend fun runDeleteTest(callback: Callback) = supervisorScope {
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

        data class DeleteCursorResult(val cursorValue: String?, val rowsBefore: Int, val foundTarget: Boolean, val cursorWindowCrash: Boolean, val crashRow: Int)

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

    // ====== 事务包裹下的读一致性验证 (无 CountDownLatch, 完全并发) ======

    private suspend fun runTransactionWrappedInsertTest(txMode: ReadTransactionMode, callback: Callback) = supervisorScope {
        val insertId = 999999000L + Random.nextLong(10000)
        val insertName = "txn_insert_test_${Random.nextLong()}"

        val beforeExists = dbHelper.readableDatabase.rawQuery("SELECT 1 FROM ${Schema.TABLE_NAME} WHERE id = ?", arrayOf("$insertId")).use { it.moveToFirst() }
        callback.onLog(ExperimentLog(now(), "Cursor持有", "INSERT前查询: id=$insertId 存在=$beforeExists", LogType.INFO))

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
                reader.endTransaction()
            }
            Pair(totalRows, sawNewRow)
        }

        delay(100)
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
