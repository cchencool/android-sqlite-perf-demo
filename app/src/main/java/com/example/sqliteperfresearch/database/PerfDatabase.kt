// ============================================================
// SQLite 数据库辅助类（PerfDatabase）
// ============================================================
// 职责：
// 1. 继承 SQLiteOpenHelper，管理数据库的创建、升级和连接
// 2. 提供 WAL 模式切换、日志模式查询、行数统计等实验用 API
// 3. 所有实验共享此连接池（不使用 SQLiteDatabase.openDatabase() 创建独立连接）
//
// SQLiteOpenHelper 工作原理：
// - 构造时传入 Context、数据库文件名、CursorFactory（null 用默认）、版本号
// - 首次调用 getReadableDatabase()/getWritableDatabase() 时触发 onCreate()
// - 内部维护一个连接池，readableDatabase 和 writableDatabase 复用已有连接
// - 默认最大连接数为 1（单连接模式），WAL 模式下可开启额外的 WAL reader 连接
// ============================================================

package com.example.sqliteperfresearch.database

import android.content.Context                        // Android 上下文，用于获取文件目录和访问权限
import android.database.sqlite.SQLiteDatabase         // SQLite 数据库核心 API，执行 SQL 语句
import android.database.sqlite.SQLiteOpenHelper       // 数据库辅助基类，管理连接池和版本迁移
import android.util.Log                               // Android logcat 日志 API
import com.example.sqliteperfresearch.util.LOG_TAG    // 全局 logcat 标签根

/**
 * 性能测试数据库辅助类
 *
 * 继承 SQLiteOpenHelper，封装数据库创建和实验控制逻辑。
 * 每个 PerfDatabase 实例管理一个独立的 .db 文件（默认 perf.db）。
 *
 * @param context Android 上下文，SQLiteOpenHelper 通过它访问应用私有数据目录
 * @param dbName 数据库文件名，默认 "perf.db"。不同文件名 = 不同数据库文件
 */
class PerfDatabase(
    context: Context,
    private val dbName: String = "perf.db"
) : SQLiteOpenHelper(context, dbName, null, 1) {
    // null = 使用默认 CursorFactory
    // 1 = 数据库版本号，首次创建为 1，升级时 onUpgrade 被触发

    companion object {
        /** 日志 tag：SQLitePerf.DB */
        const val TAG = "$LOG_TAG.DB"
    }

    /**
     * 数据库首次创建时调用
     *
     * 由 SQLiteOpenHelper 在第一次调用 getReadableDatabase()/getWritableDatabase() 时自动触发
     * 执行 Schema.CREATE_TABLE 创建 performance_test 表
     *
     * @param db 已打开的可写数据库实例
     */
    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(Schema.CREATE_TABLE)  // 执行 CREATE TABLE 语句
        Log.d(TAG, "Database created: $dbName")
    }

    /**
     * 数据库版本升级时调用
     *
     * 当构造函数传入的 version 号高于磁盘上的版本号时触发。
     * 当前实现采用简单策略：删除旧表 + 重新创建（适用于 Demo，生产环境应做数据迁移）。
     *
     * @param db 已打开的可写数据库实例
     * @param oldVersion 磁盘上的数据库版本号
     * @param newVersion 代码中请求的版本号
     */
    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        db.execSQL(Schema.DROP_TABLE)    // 删除旧表
        db.execSQL(Schema.CREATE_TABLE)  // 重新创建空表
        Log.d(TAG, "Database upgraded: $oldVersion -> $newVersion")
    }

    /**
     * 切换 WAL（Write-Ahead Logging）模式
     *
     * Android SQLiteOpenHelper 连接池管理下，PRAGMA journal_mode=WAL 不会持久化，
     * 必须使用 SQLiteDatabase 的 enableWriteAheadLogging() / disableWriteAheadLogging() API。
     *
     * WAL 模式特点：
     * - 写入追加到 WAL 文件，不直接修改主 .db 文件
     * - 读写不阻塞：reader 读主文件，writer 写 WAL 文件
     * - 支持并发读（多个 reader 同时读取）
     * - TRUNCATE 模式（默认）：写操作需要 EXCLUSIVE lock，阻塞所有读
     *
     * @param enabled true = 启用 WAL，false = 切换回 TRUNCATE 模式
     */
    fun setWalMode(enabled: Boolean) {
        val db = writableDatabase  // 获取可写连接（触发 onCreate 如果尚未创建）
        if (enabled) {
            db.enableWriteAheadLogging()      // 启用 WAL，Android 官方推荐 API
        } else {
            db.disableWriteAheadLogging()     // 禁用 WAL，回到默认 TRUNCATE 模式
        }
        Log.d(TAG, "WAL ${if (enabled) "enabled" else "disabled"} for $dbName")
    }

    /**
     * 查询当前日志模式（journal_mode）
     *
     * 通过 PRAGMA journal_mode 读取 SQLite 当前的日志写入模式。
     * 返回值："wal" = WAL 模式，"truncate" = TRUNCATE 模式，"delete" = DELETE 模式等。
     *
     * @return 当前 journal_mode 字符串
     */
    fun getJournalMode(): String {
        val db = readableDatabase  // 获取只读连接
        // rawQuery 返回 Cursor，需要手动 moveToFirst() 和 close()
        val cursor = db.rawQuery("PRAGMA journal_mode", null)
        var result = "unknown"
        if (cursor.moveToFirst()) {
            result = cursor.getString(0)  // 读取第一行第一列
        }
        cursor.close()  // 必须关闭 Cursor，否则泄漏 native 资源
        return result
    }

    /**
     * 查询表行数（COUNT(*)）
     *
     * 使用 Schema.COUNT_ROWS 预定义的 SELECT COUNT(*) 语句。
     *
     * @return 表中总行数，空表返回 0
     */
    fun getRowCount(): Long {
        val db = readableDatabase
        val cursor = db.rawQuery(Schema.COUNT_ROWS, null)
        var count = 0L
        if (cursor.moveToFirst()) {
            count = cursor.getLong(0)  // 读取 COUNT(*) 的结果（第一列）
        }
        cursor.close()
        return count
    }
}
