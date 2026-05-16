package com.example.sqliteperfresearch.database

import android.content.ContentValues
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

/**
 * 读一致性验证实验
 *
 * 关键: 不再使用 SQLiteDatabase.openDatabase()（会创建新连接池并触发 TRUNCATE），
 * 改用 PerfDatabase 的共享连接池 (readableDatabase/writableDatabase)。
 * WAL 模式下共享池的 reader 和 writer 互不阻塞。
 */
class CursorExperiment(private val dbHelper: PerfDatabase) {
    companion object {
        const val TAG = "$LOG_TAG.Cursor"
    }

    interface Callback {
        fun onLog(log: ExperimentLog)
    }

    fun now() = java.text.SimpleDateFormat("HH:mm:ss.SSS", java.util.Locale.getDefault()).format(java.util.Date())

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
     * 关键区别: 使用 BEGIN/COMMIT 包裹整个 Cursor 遍历, 锁定一个快照。
     * 在事务内, Cursor 看到的是事务开始时的数据库状态, 不感知并发写操作。
     */
    suspend fun runTransactionWrappedTest(callback: Callback) = supervisorScope {
        val actualMode = dbHelper.getJournalMode()
        callback.onLog(ExperimentLog(now(), "Cursor持有", "当前模式: $actualMode, 开始事务包裹读一致性验证", LogType.INFO))
        callback.onLog(ExperimentLog(now(), "Cursor持有", "关键: 使用 BEGIN/COMMIT 包裹整个 Cursor 遍历, 锁定快照", LogType.INFO))

        // ====== INSERT 测试 ======
        callback.onLog(ExperimentLog(now(), "Cursor持有", "--- 实验1: INSERT 读一致性 (事务包裹) ---", LogType.INFO))
        runTransactionWrappedInsertTest(callback)

        // ====== UPDATE 测试 ======
        callback.onLog(ExperimentLog(now(), "Cursor持有", "--- 实验2: UPDATE 读一致性 (事务包裹) ---", LogType.INFO))
        runTransactionWrappedUpdateTest(callback)

        // ====== DELETE 测试 ======
        callback.onLog(ExperimentLog(now(), "Cursor持有", "--- 实验3: DELETE 读一致性 (事务包裹) ---", LogType.INFO))
        runTransactionWrappedDeleteTest(callback)

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
            callback.onLog(ExperimentLog(now(), "Cursor持有", "Cursor 已暂停, 开始 INSERT", LogType.WARNING))

            val writer = dbHelper.writableDatabase
            writer.beginTransaction()
            try {
                val cv = ContentValues().apply {
                    put("id", insertId)
                    put("name", insertName)
                    put("counter", 0)
                }
                writer.insert(Schema.TABLE_NAME, null, cv)
                writer.setTransactionSuccessful()
                callback.onLog(ExperimentLog(now(), "Cursor持有", "INSERT 已执行, 事务提交完成", LogType.WARNING))
            } finally {
                writer.endTransaction()
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
            callback.onLog(ExperimentLog(now(), "Cursor持有", "Cursor 已暂停在关键行, 开始 UPDATE", LogType.WARNING))

            val writer = dbHelper.writableDatabase
            writer.beginTransaction()
            try {
                val cv = ContentValues().apply { put("name", updateName) }
                writer.update(Schema.TABLE_NAME, cv, "id = ?", arrayOf("$targetRowId"))
                writer.setTransactionSuccessful()
                callback.onLog(ExperimentLog(now(), "Cursor持有", "UPDATE 已执行, 事务提交完成", LogType.WARNING))
            } finally {
                writer.endTransaction()
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
            callback.onLog(ExperimentLog(now(), "Cursor持有", "Cursor 已暂停在关键行, 开始 DELETE", LogType.WARNING))

            val writer = dbHelper.writableDatabase
            writer.beginTransaction()
            try {
                val deleted = writer.delete(Schema.TABLE_NAME, "id = ?", arrayOf("$targetRowId"))
                writer.setTransactionSuccessful()
                callback.onLog(ExperimentLog(now(), "Cursor持有", "DELETE 已执行 (影响行数=$deleted), 事务提交完成", LogType.WARNING))
            } finally {
                writer.endTransaction()
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

    // ====== 事务包裹下的读一致性验证 ======

    private suspend fun runTransactionWrappedInsertTest(callback: Callback) = supervisorScope {
        val insertId = 999999000L + Random.nextLong(10000)
        val insertName = "txn_insert_test_${Random.nextLong()}"

        val beforeExists = dbHelper.readableDatabase.rawQuery("SELECT 1 FROM ${Schema.TABLE_NAME} WHERE id = ?", arrayOf("$insertId")).use { it.moveToFirst() }
        callback.onLog(ExperimentLog(now(), "Cursor持有", "INSERT前查询: id=$insertId 存在=$beforeExists", LogType.INFO))

        val cursorPausedLatch = CountDownLatch(1)
        val cursorResumeLatch = CountDownLatch(1)

        val cursorResult = async(Dispatchers.IO) {
            val db = dbHelper.readableDatabase
            db.execSQL("BEGIN;")
            val cursor = db.rawQuery("SELECT id, name FROM ${Schema.TABLE_NAME} ORDER BY id", null)
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
                db.execSQL("COMMIT;")
            }
            Triple(rows.size, sawNewRow, pausedAt)
        }

        val writeResult = async(Dispatchers.IO) {
            cursorPausedLatch.await()
            callback.onLog(ExperimentLog(now(), "Cursor持有", "Cursor 已暂停 (事务中), 开始 INSERT", LogType.WARNING))

            val writer = dbHelper.writableDatabase
            writer.beginTransaction()
            try {
                val cv = ContentValues().apply {
                    put("id", insertId)
                    put("name", insertName)
                    put("counter", 0)
                }
                writer.insert(Schema.TABLE_NAME, null, cv)
                writer.setTransactionSuccessful()
                callback.onLog(ExperimentLog(now(), "Cursor持有", "INSERT 已执行, 事务提交完成", LogType.WARNING))
            } finally {
                writer.endTransaction()
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

        callback.onLog(ExperimentLog(now(), "Cursor持有", "===== INSERT 读一致性结果 (事务包裹) =====", LogType.INFO))
        callback.onLog(ExperimentLog(now(), "Cursor持有", "Cursor 遍历总行数: $totalRows", LogType.INFO))
        callback.onLog(ExperimentLog(now(), "Cursor持有", "Cursor 是否看到新插入的行(id=$insertId): $sawNewRow", LogType.INFO))
        if (sawNewRow) {
            callback.onLog(ExperimentLog(now(), "Cursor持有", "结论: Cursor 读到了 INSERT 提交后的新数据", LogType.ERROR))
        } else {
            callback.onLog(ExperimentLog(now(), "Cursor持有", "结论: Cursor 未看到新插入的行 — 事务 BEGIN 锁定的快照隔离了并发 INSERT", LogType.SUCCESS))
        }
    }

    private suspend fun runTransactionWrappedUpdateTest(callback: Callback) = supervisorScope {
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

        val cursorPausedLatch = CountDownLatch(1)
        val cursorResumeLatch = CountDownLatch(1)

        val cursorResult = async(Dispatchers.IO) {
            val db = dbHelper.readableDatabase
            db.execSQL("BEGIN;")
            val cursor = db.rawQuery("SELECT id, name FROM ${Schema.TABLE_NAME} ORDER BY id", null)
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
                db.execSQL("COMMIT;")
            }
            Triple(cursorValueAtTarget, rowsBeforeTarget, cursorValueAtTarget != null)
        }

        val writeResult = async(Dispatchers.IO) {
            cursorPausedLatch.await()
            callback.onLog(ExperimentLog(now(), "Cursor持有", "Cursor 已暂停在关键行 (事务中), 开始 UPDATE", LogType.WARNING))

            val writer = dbHelper.writableDatabase
            writer.beginTransaction()
            try {
                val cv = ContentValues().apply { put("name", updateName) }
                writer.update(Schema.TABLE_NAME, cv, "id = ?", arrayOf("$targetRowId"))
                writer.setTransactionSuccessful()
                callback.onLog(ExperimentLog(now(), "Cursor持有", "UPDATE 已执行, 事务提交完成", LogType.WARNING))
            } finally {
                writer.endTransaction()
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

        callback.onLog(ExperimentLog(now(), "Cursor持有", "===== UPDATE 读一致性结果 (事务包裹) =====", LogType.INFO))
        callback.onLog(ExperimentLog(now(), "Cursor持有", "Cursor 到达关键行前已读: $rowsBefore 行", LogType.INFO))
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

    private suspend fun runTransactionWrappedDeleteTest(callback: Callback) = supervisorScope {
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
            val db = dbHelper.readableDatabase
            db.execSQL("BEGIN;")
            val cursor = db.rawQuery("SELECT id, name FROM ${Schema.TABLE_NAME} ORDER BY id", null)
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
                        callback.onLog(ExperimentLog(now(), "Cursor持有", "CursorWindow 崩溃! 无法读取第 $crashRow 行", LogType.ERROR))
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
                db.execSQL("COMMIT;")
            }
            DeleteCursorResult(cursorValueAtTarget, rowsBeforeTarget, foundTarget, cursorWindowCrash, crashRow)
        }

        val writeResult = async(Dispatchers.IO) {
            cursorPausedLatch.await()
            callback.onLog(ExperimentLog(now(), "Cursor持有", "Cursor 已暂停在关键行 (事务中), 开始 DELETE", LogType.WARNING))

            val writer = dbHelper.writableDatabase
            writer.beginTransaction()
            try {
                val deleted = writer.delete(Schema.TABLE_NAME, "id = ?", arrayOf("$targetRowId"))
                writer.setTransactionSuccessful()
                callback.onLog(ExperimentLog(now(), "Cursor持有", "DELETE 已执行 (影响行数=$deleted), 事务提交完成", LogType.WARNING))
            } finally {
                writer.endTransaction()
            }

            val afterExists = dbHelper.readableDatabase.rawQuery("SELECT 1 FROM ${Schema.TABLE_NAME} WHERE id = ?", arrayOf("$targetRowId")).use { it.moveToFirst() }
            callback.onLog(ExperimentLog(now(), "Cursor持有", "DELETE后独立查询: id=$targetRowId 存在=$afterExists", LogType.INFO))

            cursorResumeLatch.countDown()
            afterExists
        }

        val result = cursorResult.await()
        val afterExists = writeResult.await()

        callback.onLog(ExperimentLog(now(), "Cursor持有", "===== DELETE 读一致性结果 (事务包裹) =====", LogType.INFO))
        callback.onLog(ExperimentLog(now(), "Cursor持有", "Cursor 到达关键行前已读: ${result.rowsBefore} 行", LogType.INFO))
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
                callback.onLog(ExperimentLog(now(), "Cursor持有", "Cursor 未读到被删除的行", LogType.INFO))
            }
        }
        callback.onLog(ExperimentLog(now(), "Cursor持有", "DELETE后独立查询: 行存在=$afterExists", LogType.INFO))
    }
}
