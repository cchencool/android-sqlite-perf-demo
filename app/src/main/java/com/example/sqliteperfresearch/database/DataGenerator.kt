package com.example.sqliteperfresearch.database

import android.content.ContentValues
import android.database.sqlite.SQLiteDatabase
import android.util.Log
import com.example.sqliteperfresearch.util.LOG_TAG
import java.security.MessageDigest
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.Random
import java.util.UUID

class DataGenerator {
    companion object {
        const val TAG = "$LOG_TAG.Generator"
        private val BATCH_SIZE = 1000
        private val FORMATTER = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.US)
        private val DATE_FMT = SimpleDateFormat("yyyy-MM-dd", Locale.US)
        private val TIME_FMT = SimpleDateFormat("HH:mm:ss", Locale.US)
    }

    fun generate(db: SQLiteDatabase, count: Int, progress: (Int) -> Unit) {
        Log.d(TAG, "Starting generation of $count rows")
        val random = Random(42)
        val now = Date()
        var inserted = 0

        for (batchStart in 0 until count step BATCH_SIZE) {
            val batchEnd = minOf(batchStart + BATCH_SIZE, count)
            db.beginTransaction()
            try {
                for (i in batchStart until batchEnd) {
                    db.insert(Schema.TABLE_NAME, null, makeRow(random, now, i))
                }
                db.setTransactionSuccessful()
                val batchSize = batchEnd - batchStart
                inserted += batchSize
                // Update progress every 10 batches to avoid overwhelming the main thread
                if (inserted / BATCH_SIZE % 10 == 0) {
                    progress(inserted)
                }
            } finally {
                db.endTransaction()
            }
        }
        Log.d(TAG, "Completed generation: $inserted rows")
    }

    private fun makeRow(r: Random, now: Date, idx: Int): ContentValues {
        val cv = ContentValues()
        cv.put("int8_val", r.nextInt(256) - 128)
        cv.put("int16_val", r.nextInt(65536) - 32768)
        cv.put("int32_val", r.nextInt())
        cv.put("int64_val", r.nextLong())
        cv.put("counter", idx)
        cv.put("flag_bool_1", if (r.nextBoolean()) 1 else 0)
        cv.put("flag_bool_2", if (r.nextBoolean()) 1 else 0)
        cv.put("flag_bool_3", if (r.nextBoolean()) 1 else 0)
        cv.put("flag_bool_4", if (r.nextBoolean()) 1 else 0)
        cv.put("status_code", r.nextInt(5))
        cv.put("priority", r.nextInt(10) + 1)
        cv.put("category_id", r.nextInt(100))
        cv.put("parent_id", if (idx > 0) r.nextInt(idx) else 0)
        cv.put("sort_order", idx)

        cv.put("float_val", r.nextFloat() * 1000f)
        cv.put("double_val", r.nextDouble() * 1_000_000)
        cv.put("price", (r.nextDouble() * 9999.99).toFloat())
        cv.put("weight", r.nextDouble() * 200)
        cv.put("temperature", r.nextDouble() * 80 - 20)
        cv.put("latitude", (r.nextDouble() * 180 - 90))
        cv.put("longitude", (r.nextDouble() * 360 - 180))
        cv.put("ratio", r.nextDouble() * 100)
        cv.put("score", r.nextDouble() * 100)

        cv.put("name", randomString(r, 8))
        cv.put("title", randomString(r, 20))
        cv.put("description", randomString(r, 100))
        cv.put("email", "user_${idx}@test.com")
        cv.put("phone", "1${r.nextInt(900000000) + 100000000}")
        cv.put("url", "https://test.com/item/$idx")
        cv.put("address_line1", "${r.nextInt(9999)} ${randomString(r, 10)} St")
        cv.put("address_line2", "Apt ${r.nextInt(999)}")
        cv.put("city", randomString(r, 12).capitalize())
        cv.put("state", randomString(r, 5).uppercase())
        cv.put("country_code", randomString(r, 2).uppercase())
        cv.put("postal_code", String.format("%05d", r.nextInt(100000)))
        cv.put("json_data", """{"id":$idx,"v":"${randomString(r, 6)}"}""")
        cv.put("tags", listOf("tag${r.nextInt(50)}", "tag${r.nextInt(50)}", "tag${r.nextInt(50)}").joinToString(","))
        cv.put("notes", randomString(r, 50))
        cv.put("short_text_1", randomString(r, 10))
        cv.put("short_text_2", randomString(r, 10))
        cv.put("medium_text_1", randomString(r, 30))
        cv.put("medium_text_2", randomString(r, 30))

        cv.put("blob_small", randomBytes(r, r.nextInt(128) + 16))
        cv.put("blob_medium", randomBytes(r, r.nextInt(512) + 64))
        cv.put("blob_large", randomBytes(r, r.nextInt(2048) + 256))
        cv.put("hash_md5", md5("seed_${idx}_${randomString(r, 4)}".toByteArray()))
        cv.put("hash_sha256", sha256("seed_${idx}_${randomString(r, 8)}".toByteArray()))

        val ts = FORMATTER.format(now)
        cv.put("created_at", ts)
        cv.put("updated_at", ts)
        cv.put("deleted_at", null as String?)
        cv.put("epoch_created", now.time / 1000)
        cv.put("epoch_updated", now.time / 1000)
        cv.put("epoch_deleted", null as Long?)
        cv.put("date_birth", DATE_FMT.format(Date(now.time - r.nextLong(1_000_000_000_000))))
        cv.put("date_event", DATE_FMT.format(Date(now.time + r.nextInt(86_400_000 * 365))))
        cv.put("time_start", TIME_FMT.format(now))
        cv.put("time_end", TIME_FMT.format(Date(now.time + r.nextInt(7200000))))
        cv.put("datetime_local", ts)
        cv.put("datetime_utc", ts)
        cv.put("uuid_val", UUID.randomUUID().toString())
        return cv
    }

    private fun randomString(r: Random, maxLen: Int): String {
        val len = r.nextInt(maxLen) + 1
        val chars = "abcdefghijklmnopqrstuvwxyz0123456789_"
        return (1..len).map { chars[r.nextInt(chars.length)] }.joinToString("")
    }

    private fun randomBytes(r: Random, size: Int): ByteArray {
        val bytes = ByteArray(size)
        r.nextBytes(bytes)
        return bytes
    }

    private fun md5(input: ByteArray): ByteArray = MessageDigest.getInstance("MD5").digest(input)
    private fun sha256(input: ByteArray): ByteArray = MessageDigest.getInstance("SHA-256").digest(input)
}
