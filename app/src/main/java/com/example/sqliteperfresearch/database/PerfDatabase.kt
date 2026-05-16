package com.example.sqliteperfresearch.database

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import android.util.Log
import com.example.sqliteperfresearch.util.LOG_TAG

class PerfDatabase(context: Context, private val dbName: String = "perf.db") :
    SQLiteOpenHelper(context, dbName, null, 1) {

    companion object {
        const val TAG = "$LOG_TAG.DB"
    }

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(Schema.CREATE_TABLE)
        Log.d(TAG, "Database created: $dbName")
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        db.execSQL(Schema.DROP_TABLE)
        db.execSQL(Schema.CREATE_TABLE)
        Log.d(TAG, "Database upgraded: $oldVersion -> $newVersion")
    }

    fun setWalMode(enabled: Boolean) {
        val db = writableDatabase
        if (enabled) {
            db.enableWriteAheadLogging()
        } else {
            db.disableWriteAheadLogging()
        }
        Log.d(TAG, "WAL ${if (enabled) "enabled" else "disabled"} for $dbName")
    }

    fun getJournalMode(): String {
        val db = readableDatabase
        val cursor = db.rawQuery("PRAGMA journal_mode", null)
        var result = "unknown"
        if (cursor.moveToFirst()) {
            result = cursor.getString(0)
        }
        cursor.close()
        return result
    }

    fun getRowCount(): Long {
        val db = readableDatabase
        val cursor = db.rawQuery(Schema.COUNT_ROWS, null)
        var count = 0L
        if (cursor.moveToFirst()) {
            count = cursor.getLong(0)
        }
        cursor.close()
        return count
    }
}
