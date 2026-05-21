// ============================================================
// 日志标签常量
// ============================================================
// 所有实验日志统一使用 "SQLitePerf" 作为 logcat 主 tag
// 各页面/模块在此基础上追加子 tag，如 "SQLitePerf.WalScreen"
// 可通过 `adb logcat -s "SQLitePerf.*"` 过滤所有实验日志
// ============================================================

package com.example.sqliteperfresearch.util

/** 全局 logcat 标签根前缀，各模块在此基础上追加子标签 */
const val LOG_TAG = "SQLitePerf"
