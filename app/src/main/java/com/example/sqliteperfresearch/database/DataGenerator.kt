// ============================================================
// 测试数据生成器（DataGenerator）
// ============================================================
// 职责：
// 1. 为 performance_test 表批量插入随机测试数据
// 2. 每 1000 行为一个事务（BATCH_SIZE），平衡写入效率和内存占用
// 3. 为 60 个列生成符合类型约束的随机数据（INTEGER/REAL/TEXT/BLOB/日期）
// 4. 通过 progress 回调通知 UI 填充进度（每 10000 行回调一次，避免过度触发重组）
//
// 批量写入策略：
// - 每个 BATCH_SIZE（1000 行）包裹在一个 beginTransaction 中
// - 事务写入比逐行 insert 快 10-100 倍（减少 fsync 和日志写入次数）
// ============================================================

package com.example.sqliteperfresearch.database

import android.content.ContentValues   // SQLite 键值对容器，用于 insert 操作
import android.database.sqlite.SQLiteDatabase
import android.util.Log
import com.example.sqliteperfresearch.util.LOG_TAG
import java.security.MessageDigest     // MD5 / SHA256 哈希计算
import java.text.SimpleDateFormat      // 日期格式化
import java.util.Date
import java.util.Locale
import java.util.Random                // 伪随机数生成器，固定种子 42 保证可复现
import java.util.UUID                  // UUID v4 生成器

/**
 * 测试数据生成器
 *
 * generate() 方法向指定数据库批量插入 count 行随机数据。
 * 使用固定种子 Random(42)，保证每次生成的数据一致，便于对比实验。
 */
class DataGenerator {
    companion object {
        /** 日志 tag：SQLitePerf.Generator */
        const val TAG = "$LOG_TAG.Generator"
        /** 每批次插入行数。每批包裹在一个事务中，平衡效率和内存 */
        private val BATCH_SIZE = 1000
        /** 日期时间格式：ISO 8601 不含时区偏移 */
        private val FORMATTER = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.US)
        /** 日期格式：仅年月日 */
        private val DATE_FMT = SimpleDateFormat("yyyy-MM-dd", Locale.US)
        /** 时间格式：仅时分秒 */
        private val TIME_FMT = SimpleDateFormat("HH:mm:ss", Locale.US)
    }

    /**
     * 批量生成测试数据并插入数据库
     *
     * @param db 目标数据库实例
     * @param count 要生成的总行数
     * @param progress 进度回调 lambda，参数为已插入行数
     *
     * 执行流程：
     * 1. 以 BATCH_SIZE 为步长分批处理
     * 2. 每批开启一个事务（beginTransaction）
     * 3. 逐行调用 makeRow() 生成 ContentValues 并 insert
     * 4. setTransactionSuccessful() 标记事务成功
     * 5. endTransaction() 提交事务（finally 块保证即使异常也会调用）
     * 6. 每 10 批（10000 行）调用一次 progress 回调
     */
    fun generate(db: SQLiteDatabase, count: Int, progress: (Int) -> Unit) {
        Log.d(TAG, "Starting generation of $count rows")
        val random = Random(42)  // 固定种子，保证每次生成的数据相同，便于对比实验
        val now = Date()         // 用于日期字段的基准时间
        var inserted = 0         // 已插入行数计数器

        // step BATCH_SIZE：0, 1000, 2000, ... 直到 count
        for (batchStart in 0 until count step BATCH_SIZE) {
            val batchEnd = minOf(batchStart + BATCH_SIZE, count) // 本批结束位置（不超过总行数）
            db.beginTransaction()  // 开启事务
            try {
                // 逐行生成并插入数据
                for (i in batchStart until batchEnd) {
                    db.insert(Schema.TABLE_NAME, null, makeRow(random, now, i))
                }
                db.setTransactionSuccessful()  // 标记事务成功，endTransaction 时提交
                val batchSize = batchEnd - batchStart
                inserted += batchSize
                // 每 10 批（10000 行）回调一次进度
                // 避免频繁回调导致 Compose 过度重组（主线程性能）
                if (inserted / BATCH_SIZE % 10 == 0) {
                    progress(inserted)
                }
            } finally {
                // endTransaction 必须调用：
                // - 如果已调用 setTransactionSuccessful() → 提交事务
                // - 否则 → 回滚事务
                db.endTransaction()
            }
        }
        Log.d(TAG, "Completed generation: $inserted rows")
    }

    /**
     * 生成单行数据（60 个列的随机值）
     *
     * @param r 随机数生成器
     * @param now 基准时间（用于日期字段）
     * @param idx 当前行索引（用于 counter、parent_id 等有序字段）
     * @return ContentValues 键值对，列名 → 值
     *
     * 字段分组对应 Schema.CREATE_TABLE 中的列定义
     */
    private fun makeRow(r: Random, now: Date, idx: Int): ContentValues {
        val cv = ContentValues()

        // ===== 整数类字段 =====
        cv.put("int8_val", r.nextInt(256) - 128)          // -128 ~ 127（模拟有符号 8 位整数）
        cv.put("int16_val", r.nextInt(65536) - 32768)     // -32768 ~ 32767（模拟有符号 16 位整数）
        cv.put("int32_val", r.nextInt())                  // 完整 32 位随机整数
        cv.put("int64_val", r.nextLong())                 // 完整 64 位随机长整数
        cv.put("counter", idx)                            // 递增计数器（行号）

        // ===== 布尔标志字段（SQLite 用 INTEGER 存储布尔值）=====
        cv.put("flag_bool_1", if (r.nextBoolean()) 1 else 0)
        cv.put("flag_bool_2", if (r.nextBoolean()) 1 else 0)
        cv.put("flag_bool_3", if (r.nextBoolean()) 1 else 0)
        cv.put("flag_bool_4", if (r.nextBoolean()) 1 else 0)
        cv.put("status_code", r.nextInt(5))               // 0~4 状态码
        cv.put("priority", r.nextInt(10) + 1)             // 1~10 优先级
        cv.put("category_id", r.nextInt(100))             // 0~99 分类 ID
        cv.put("parent_id", if (idx > 0) r.nextInt(idx) else 0)  // 父级 ID（树形结构模拟）
        cv.put("sort_order", idx)                         // 排序序号

        // ===== 浮点类字段 =====
        cv.put("float_val", r.nextFloat() * 1000f)        // 0.0 ~ 1000.0 Float
        cv.put("double_val", r.nextDouble() * 1_000_000)  // 0.0 ~ 1000000.0 Double
        cv.put("price", (r.nextDouble() * 9999.99).toFloat())  // 价格模拟
        cv.put("weight", r.nextDouble() * 200)            // 体重模拟（0~200）
        cv.put("temperature", r.nextDouble() * 80 - 20)   // 温度模拟（-20~60°C）
        cv.put("latitude", r.nextDouble() * 180 - 90)     // 纬度（-90~90）
        cv.put("longitude", r.nextDouble() * 360 - 180)   // 经度（-180~180）
        cv.put("ratio", r.nextDouble() * 100)             // 百分比比例（0~100）
        cv.put("score", r.nextDouble() * 100)             // 评分（0~100）

        // ===== 文本类字段 =====
        cv.put("name", randomString(r, 8))                // 短名称（1~8 字符）
        cv.put("title", randomString(r, 20))              // 标题（1~20 字符）
        cv.put("description", randomString(r, 100))       // 描述（1~100 字符）
        cv.put("email", "user_${idx}@test.com")           // 模拟邮箱
        cv.put("phone", "1${r.nextInt(900000000) + 100000000}")  // 模拟手机号（10 位）
        cv.put("url", "https://test.com/item/$idx")       // 模拟 URL
        cv.put("address_line1", "${r.nextInt(9999)} ${randomString(r, 10)} St")  // 地址行 1
        cv.put("address_line2", "Apt ${r.nextInt(999)}")  // 地址行 2
        cv.put("city", randomString(r, 12).capitalize())  // 城市名（首字母大写）
        cv.put("state", randomString(r, 5).uppercase())   // 州/省缩写（大写）
        cv.put("country_code", randomString(r, 2).uppercase())  // 国家代码（大写 2 字符）
        cv.put("postal_code", String.format("%05d", r.nextInt(100000)))  // 邮政编码（5 位，前补零）
        cv.put("json_data", """{"id":$idx,"v":"${randomString(r, 6)}"}""")  // 模拟 JSON 数据
        cv.put("tags", listOf("tag${r.nextInt(50)}", "tag${r.nextInt(50)}", "tag${r.nextInt(50)}").joinToString(","))  // 逗号分隔标签
        cv.put("notes", randomString(r, 50))              // 备注文字
        cv.put("short_text_1", randomString(r, 10))       // 短文本 1
        cv.put("short_text_2", randomString(r, 10))       // 短文本 2
        cv.put("medium_text_1", randomString(r, 30))      // 中文本 1
        cv.put("medium_text_2", randomString(r, 30))      // 中文本 2

        // ===== BLOB 类字段 =====
        cv.put("blob_small", randomBytes(r, r.nextInt(128) + 16))     // 16~128 字节
        cv.put("blob_medium", randomBytes(r, r.nextInt(512) + 64))    // 64~512 字节
        cv.put("blob_large", randomBytes(r, r.nextInt(2048) + 256))   // 256~2048 字节
        cv.put("hash_md5", md5("seed_${idx}_${randomString(r, 4)}".toByteArray()))       // MD5 哈希（16 字节）
        cv.put("hash_sha256", sha256("seed_${idx}_${randomString(r, 8)}".toByteArray())) // SHA256 哈希（32 字节）

        // ===== 日期类字段 =====
        val ts = FORMATTER.format(now)  // 基准时间戳
        cv.put("created_at", ts)                             // 创建时间（TEXT）
        cv.put("updated_at", ts)                             // 更新时间（TEXT）
        cv.put("deleted_at", null as String?)                // 删除时间（NULL，模拟软删除未启用）
        cv.put("epoch_created", now.time / 1000)             // 创建时间 epoch（秒）
        cv.put("epoch_updated", now.time / 1000)             // 更新时间 epoch（秒）
        cv.put("epoch_deleted", null as Long?)               // 删除时间 epoch（NULL）
        // safeNextLong 避免 Long.MIN_VALUE 取绝对值溢出
        cv.put("date_birth", DATE_FMT.format(Date(now.time - safeNextLong(r, 1_000L))))       // 生日（过去 1000 秒内）
        cv.put("date_event", DATE_FMT.format(Date(now.time + safeNextLong(r, 9_460L))))       // 事件日期（未来 9460 秒内）
        cv.put("time_start", TIME_FMT.format(now))           // 开始时间
        cv.put("time_end", TIME_FMT.format(Date(now.time + safeNextLong(r, 7_200L))))         // 结束时间（未来 2 小时内）
        cv.put("datetime_local", ts)                         // 本地时间
        cv.put("datetime_utc", ts)                           // UTC 时间
        cv.put("uuid_val", UUID.randomUUID().toString())     // UUID v4

        return cv
    }

    /**
     * 生成随机字符串
     *
     * @param r 随机数生成器
     * @param maxLen 最大长度（实际长度为 1~maxLen 的随机值）
     * @return 由小写字母、数字、下划线组成的随机字符串
     */
    private fun randomString(r: Random, maxLen: Int): String {
        val len = r.nextInt(maxLen) + 1  // 长度 1 ~ maxLen
        val chars = "abcdefghijklmnopqrstuvwxyz0123456789_"
        return (1..len).map { chars[r.nextInt(chars.length)] }.joinToString("")
    }

    /**
     * 生成随机字节数组
     *
     * @param r 随机数生成器
     * @param size 字节数组长度
     * @return 填充随机字节的 ByteArray
     */
    private fun randomBytes(r: Random, size: Int): ByteArray {
        val bytes = ByteArray(size)
        r.nextBytes(bytes)
        return bytes
    }

    /** 计算 MD5 哈希（返回 16 字节） */
    private fun md5(input: ByteArray): ByteArray = MessageDigest.getInstance("MD5").digest(input)

    /** 计算 SHA256 哈希（返回 32 字节） */
    private fun sha256(input: ByteArray): ByteArray = MessageDigest.getInstance("SHA-256").digest(input)

    /**
     * 安全的 nextLong 实现（rejection sampling 拒绝采样）
     *
     * 问题：Math.abs(Long.MIN_VALUE) 仍为负数（Long.MIN_VALUE = -2^63，绝对值超出 Long 范围）
     * 解决：使用拒绝采样，只接受均匀分布的结果
     *
     * @param r 随机数生成器
     * @param bound 上限（必须 > 0），返回值为 0 ~ bound-1
     * @return 均匀分布的随机 Long
     */
    private fun safeNextLong(r: Random, bound: Long): Long {
        if (bound <= 0) throw IllegalArgumentException("bound must be positive")
        var bits: Long
        var result: Long
        do {
            bits = r.nextLong() ushr 1       // 无符号右移 1 位，消除符号位，保证非负
            result = bits % bound             // 取模得到 0 ~ bound-1
            // 拒绝采样：如果 bits - result + (bound - 1) < 0，说明发生了溢出，
            // 即 bits 落在最后一个不完整区间内，需要重新采样
        } while (bits - result + (bound - 1) < 0)
        return result
    }
}
