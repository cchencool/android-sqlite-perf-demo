// ============================================================
// 计时工具函数
// ============================================================
// 职责：
// 1. 使用 SystemClock.elapsedRealtimeNanos() 进行高精度计时（纳秒级）
// 2. 将纳秒差转换为毫秒（/ 1_000_000）
// 3. 提供同步和异步两个版本，分别用于阻塞代码和 suspend 函数
//
// 为什么用 SystemClock.elapsedRealtimeNanos() 而不是 System.currentTimeMillis()？
// - elapsedRealtimeNanos() 基于设备启动时间，不受系统时钟调整（NTP 同步、时区切换）影响
// - 纳秒级精度适合测量毫秒级的数据库操作耗时
// - currentTimeMillis() 会受系统时钟回拨/跳变影响，不适合性能测量
// ============================================================

package com.example.sqliteperfresearch.util

import android.os.SystemClock  // Android 系统时钟 API，提供 monotonic 时间源

/**
 * 带计时的函数执行结果
 *
 * @param T 被测量函数的返回值类型
 * @param value 函数的实际返回值
 * @param elapsedMs 函数执行耗时（毫秒）
 */
data class TimedResult<T>(
    val value: T,         // 被测量代码块的返回值
    val elapsedMs: Long   // 执行耗时，单位毫秒
)

/**
 * 测量同步代码块的执行耗时
 *
 * @param block 被测量的同步 lambda
 * @return TimedResult 包含原始返回值和耗时
 *
 * 泛型用法：measureTimed { someFunction() } 自动推断 T 为 someFunction 的返回类型
 * 下划线分隔符 1_000_000 是 Kotlin 数字字面量语法，等价于 1000000，提升可读性
 */
fun <T> measureTimed(block: () -> T): TimedResult<T> {
    // 记录开始时间（纳秒），elapsedRealtimeNanos 返回自设备启动以来的纳秒数
    val start = SystemClock.elapsedRealtimeNanos()

    // 执行被测量的代码块，捕获其返回值
    val result = block()

    // 计算耗时：结束时间 - 开始时间，纳秒转毫秒
    val elapsedMs = (SystemClock.elapsedRealtimeNanos() - start) / 1_000_000

    return TimedResult(result, elapsedMs)
}

/**
 * 测量 suspend 函数（协程）的执行耗时
 *
 * @param block 被测量的 suspend lambda
 * @return TimedResult 包含原始返回值和耗时
 *
 * 与 measureTimed 的区别：block 是 suspend 函数，需要在协程作用域内调用
 * 计时逻辑完全相同，只是函数签名支持 suspend
 */
suspend fun <T> measureTimedSuspend(block: suspend () -> T): TimedResult<T> {
    val start = SystemClock.elapsedRealtimeNanos()
    val result = block()
    val elapsedMs = (SystemClock.elapsedRealtimeNanos() - start) / 1_000_000
    return TimedResult(result, elapsedMs)
}
