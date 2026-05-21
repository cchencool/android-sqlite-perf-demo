// ============================================================
// 数据库表结构定义（Schema）
// ============================================================
// 职责：
// 1. 定义 performance_test 表的完整 CREATE TABLE 语句
// 2. 包含 60 个列，覆盖 SQLite 所有基本类型：INTEGER / REAL / TEXT / BLOB
// 3. 提供 COUNT_ROWS 查询语句，用于获取表行数
//
// 设计考虑：
// - 多类型覆盖：测试 SQLite 在不同数据类型存储和查询时的性能差异
// - BLOB 分三档（small/medium/large）：模拟不同尺寸的二进制数据存储
// - 日期字段同时提供 TEXT（ISO 8601）和 INTEGER（epoch）格式
// ============================================================

package com.example.sqliteperfresearch.database

/**
 * 数据库 Schema 常量
 *
 * object：Kotlin 单例对象，保证全局只有一份 Schema 定义
 * TABLE_NAME：表名，与 DataGenerator 中 insert 使用的表名一致
 */
object Schema {
    /** performance_test 表名 */
    const val TABLE_NAME = "performance_test"

    /**
     * CREATE TABLE 语句
     *
     * 三重引号 """...""" 是 Kotlin 多行字符串，保留原始格式（包括换行和缩进）
     * IF NOT EXISTS：幂等创建，重复执行不报错
     *
     * 列分类：
     * - id：自增主键
     * - 整数类（5列）：int8/int16/int32/int64（SQLite 统一用 INTEGER 存储）
     * - 标志位（9列）：flag_bool_x、status_code、priority 等，模拟业务枚举
     * - 浮点类（9列）：float/double/price/weight/temperature/经纬度/比例/分数
     * - 文本类（16列）：name/title/description/email/地址/城市/JSON/标签等
     * - BLOB 类（5列）：small(16-128B) / medium(64-512B) / large(256-2048B) / MD5 / SHA256
     * - 日期类（12列）：TEXT 格式 ISO 时间 + INTEGER 格式 epoch + date/time 专用格式
     * - 其他（2列）：uuid_val
     */
    const val CREATE_TABLE = """
        CREATE TABLE IF NOT EXISTS $TABLE_NAME (
            id INTEGER PRIMARY KEY AUTOINCREMENT,
            int8_val INTEGER, int16_val INTEGER, int32_val INTEGER, int64_val INTEGER,
            counter INTEGER, flag_bool_1 INTEGER, flag_bool_2 INTEGER, flag_bool_3 INTEGER, flag_bool_4 INTEGER,
            status_code INTEGER, priority INTEGER, category_id INTEGER, parent_id INTEGER, sort_order INTEGER,
            float_val REAL, double_val REAL, price REAL, weight REAL, temperature REAL,
            latitude REAL, longitude REAL, ratio REAL, score REAL,
            name TEXT, title TEXT, description TEXT, email TEXT, phone TEXT,
            url TEXT, address_line1 TEXT, address_line2 TEXT, city TEXT, state TEXT,
            country_code TEXT, postal_code TEXT, json_data TEXT, tags TEXT, notes TEXT,
            short_text_1 TEXT, short_text_2 TEXT, medium_text_1 TEXT, medium_text_2 TEXT,
            blob_small BLOB, blob_medium BLOB, blob_large BLOB, hash_md5 BLOB, hash_sha256 BLOB,
            created_at TEXT, updated_at TEXT, deleted_at TEXT,
            epoch_created INTEGER, epoch_updated INTEGER, epoch_deleted INTEGER,
            date_birth TEXT, date_event TEXT, time_start TEXT, time_end TEXT,
            datetime_local TEXT, datetime_utc TEXT, uuid_val TEXT
        )
    """

    /** 删除表语句（IF EXISTS 避免表不存在时报错） */
    const val DROP_TABLE = "DROP TABLE IF EXISTS $TABLE_NAME"

    /** COUNT(*) 查询，返回表总行数 */
    const val COUNT_ROWS = "SELECT COUNT(*) FROM $TABLE_NAME"
}
