package com.example.sqliteperfresearch.model

data class ExperimentLog(
    val timestamp: String,
    val phase: String,
    val message: String,
    val type: LogType = LogType.INFO,
)

enum class LogType { INFO, SUCCESS, WARNING, ERROR }
