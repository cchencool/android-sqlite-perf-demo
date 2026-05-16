package com.example.sqliteperfresearch.util

import android.os.SystemClock

data class TimedResult<T>(val value: T, val elapsedMs: Long)

fun <T> measureTimed(block: () -> T): TimedResult<T> {
    val start = SystemClock.elapsedRealtimeNanos()
    val result = block()
    val elapsedMs = (SystemClock.elapsedRealtimeNanos() - start) / 1_000_000
    return TimedResult(result, elapsedMs)
}

suspend fun <T> measureTimedSuspend(block: suspend () -> T): TimedResult<T> {
    val start = SystemClock.elapsedRealtimeNanos()
    val result = block()
    val elapsedMs = (SystemClock.elapsedRealtimeNanos() - start) / 1_000_000
    return TimedResult(result, elapsedMs)
}
