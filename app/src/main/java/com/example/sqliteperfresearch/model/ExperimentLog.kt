// ============================================================
// 实验日志数据模型
// ============================================================
// 职责：
// 1. 定义实验日志的统一数据结构，供所有 Experiment 类和 UI 页面使用
// 2. LogType 枚举区分日志级别，UI 根据类型渲染不同颜色
// 3. ExperimentLog 是纯数据类（data class），不可变，线程安全
// ============================================================

package com.example.sqliteperfresearch.model

/**
 * 实验日志条目
 *
 * data class 自动生成 equals() / hashCode() / toString() / copy()
 * 所有字段都是 val（不可变），保证在协程间传递时的线程安全
 *
 * @param timestamp 时间戳字符串（格式由 Experiment.now() 决定）
 * @param phase 实验阶段/名称（如 "WAL对比"、"并发读"、"Cursor持有"），用于 UI 标签显示
 * @param message 日志具体内容
 * @param type 日志级别，决定 UI 渲染颜色
 */
data class ExperimentLog(
    val timestamp: String,                          // 时间戳，如 "14:23:05.123"
    val phase: String,                              // 实验阶段标识
    val message: String,                            // 日志正文
    val type: LogType = LogType.INFO,               // 日志级别，默认 INFO
)

/**
 * 日志级别枚举
 *
 * 各页面在 addLog() 中根据 type 调用不同的 Log.d/i/w/e 输出到 logcat，
 * UI 中 AutoScrollLogList / LogItem 根据 type 渲染不同的文字颜色。
 *
 * INFO：普通信息（开始实验、DB 路径等）
 * SUCCESS：成功结果（耗时数据、完成提示）
 * WARNING：警告（数据不足、降级处理等）
 * ERROR：错误（异常信息、失败原因）
 */
enum class LogType {
    INFO,       // 普通信息，蓝色/默认色
    SUCCESS,    // 成功结果，绿色
    WARNING,    // 警告，橙色
    ERROR       // 错误，红色
}
