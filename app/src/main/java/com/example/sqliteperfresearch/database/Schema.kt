package com.example.sqliteperfresearch.database

object Schema {
    const val TABLE_NAME = "performance_test"

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

    const val DROP_TABLE = "DROP TABLE IF EXISTS $TABLE_NAME"

    const val COUNT_ROWS = "SELECT COUNT(*) FROM $TABLE_NAME"
}
