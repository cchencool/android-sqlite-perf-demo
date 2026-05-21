// ============================================================
// 锁机制验证页面（LockMechanismScreen）
// ============================================================
// 职责：
// 1. 演示 SQLite 并发读写时的锁行为
// 2. 三个实验按钮：并发读（5 线程）、读写并发（1 写 + 3 读）、锁超时测试
//
// Compose 页面通用模式：
// 1. LaunchedEffect(Unit) 初始化数据库和实验实例
// 2. 按钮 onClick 中通过 scope.launch(Dispatchers.IO) 启动后台协程
// 3. 实验通过 Callback 回调推送日志，scope.launch(Dispatchers.Main) 更新 UI 状态
// 4. isRunning 状态控制按钮的 enabled 属性，防止重复点击
// 5. Column + verticalScroll 实现页面可滚动，底部用 AutoScrollLogList 显示日志
// ============================================================

package com.example.sqliteperfresearch.ui.main

import android.util.Log
import androidx.compose.foundation.layout.Column           // 垂直线性布局
import androidx.compose.foundation.layout.Row              // 水平线性布局
import androidx.compose.foundation.layout.fillMaxSize      // 填满整个父容器
import androidx.compose.foundation.layout.fillMaxWidth     // 宽度填满父容器
import androidx.compose.foundation.layout.padding          // 内边距扩展
import androidx.compose.foundation.rememberScrollState     // 记住滚动状态
import androidx.compose.foundation.verticalScroll          // 使 Column 可垂直滚动
import androidx.compose.material3.Button                   // Material 3 按钮
import androidx.compose.material3.MaterialTheme            // Material 3 主题
import androidx.compose.material3.Text                     // 文字组件
import androidx.compose.runtime.Composable                 // @Composable 注解
import androidx.compose.runtime.LaunchedEffect             // 副作用：初始化时执行一次
import androidx.compose.runtime.getValue                   // by 委托：自动 getValue
import androidx.compose.runtime.mutableStateListOf         // 可变状态列表
import androidx.compose.runtime.mutableStateOf             // 泛型可变状态
import androidx.compose.runtime.remember                   // 记住值
import androidx.compose.runtime.rememberCoroutineScope     // 记住协程作用域
import androidx.compose.runtime.setValue                   // by 委托：自动 setValue
import androidx.compose.ui.Modifier                        // 修饰符基类
import androidx.compose.ui.platform.LocalContext           // 当前 Android Context
import androidx.compose.ui.unit.dp                         // dp 单位扩展
import androidx.compose.ui.tooling.preview.Preview         // @Preview 注解
import com.example.sqliteperfresearch.database.LockExperiment
import com.example.sqliteperfresearch.database.PerfDatabase
import com.example.sqliteperfresearch.model.ExperimentLog
import com.example.sqliteperfresearch.model.LogType
import com.example.sqliteperfresearch.util.LOG_TAG
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

private const val TAG = "$LOG_TAG.LockScreen"

/**
 * 锁机制验证页面
 *
 * 布局结构：
 * ┌──────────────────────────────────┐
 * │ 标题：锁机制验证                 │
 * │ 描述文字                         │
 * │ [并发读 ×5] [读写并发]           │
 * │ [锁超时测试]                     │
 * │ 实验日志                         │
 * └──────────────────────────────────┘
 */
@Composable
fun LockMechanismScreen(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var db by remember { mutableStateOf<PerfDatabase?>(null) }
    var lockExp by remember { mutableStateOf<LockExperiment?>(null) }
    var isRunning by remember { mutableStateOf(false) }  // 控制按钮禁用状态
    val logs = remember { mutableStateListOf<ExperimentLog>() }

    /** 添加日志到 UI 列表和 logcat */
    fun addLog(log: ExperimentLog) {
        logs.add(log)
        when (log.type) {
            LogType.INFO    -> Log.d(TAG, "[${log.phase}] ${log.message}")
            LogType.SUCCESS -> Log.i(TAG, "[${log.phase}] ${log.message}")
            LogType.WARNING -> Log.w(TAG, "[${log.phase}] ${log.message}")
            LogType.ERROR   -> Log.e(TAG, "[${log.phase}] ${log.message}")
        }
    }

    // 初始化：创建数据库和锁实验实例
    LaunchedEffect(Unit) {
        db = PerfDatabase(context)
        lockExp = LockExperiment(db!!)
    }

    // 可滚动的垂直布局
    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState()),
    ) {
        Text("锁机制验证", style = MaterialTheme.typography.headlineMedium, modifier = Modifier.padding(bottom = 4.dp))
        Text(
            "演示 SQLite 并发读写时的锁行为: 共享锁允许多读、排他锁阻塞读、busy timeout 机制",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(bottom = 16.dp),
        )

        // 第一行两个按钮
        Row(modifier = Modifier.fillMaxWidth()) {
            // ====== 并发读 ×5 ======
            Button(
                onClick = {
                    if (isRunning || lockExp == null) return@Button
                    isRunning = true
                    logs.clear()  // 清空旧日志
                    // 在 IO 线程启动实验协程
                    scope.launch(Dispatchers.IO) {
                        try {
                            lockExp!!.runConcurrentReads(threadCount = 5, queryRows = 10000, object : LockExperiment.Callback {
                                // 实验回调：在主线程更新 UI 状态
                                override fun onLog(log: ExperimentLog) {
                                    scope.launch(Dispatchers.Main) { addLog(log) }
                                }
                            })
                        } catch (e: Exception) {
                            scope.launch(Dispatchers.Main) {
                                addLog(ExperimentLog(lockExp!!.now(), "并发读", "异常: ${e.message}", LogType.ERROR))
                            }
                        }
                        // 实验结束，恢复按钮可用状态
                        scope.launch(Dispatchers.Main) { isRunning = false }
                    }
                },
                enabled = !isRunning,  // 实验运行时禁用按钮
                modifier = Modifier.weight(1f).padding(end = 4.dp),  // weight(1f) = 等宽分配
            ) { Text("并发读 ×5") }

            // ====== 读写并发 ======
            Button(
                onClick = {
                    if (isRunning || lockExp == null) return@Button
                    isRunning = true
                    logs.clear()
                    scope.launch(Dispatchers.IO) {
                        try {
                            lockExp!!.runReadWriteBlocking(
                                writeThreadCount = 1, readThreadCount = 3, writeRows = 5000,
                                callback = object : LockExperiment.Callback {
                                    override fun onLog(log: ExperimentLog) {
                                        scope.launch(Dispatchers.Main) { addLog(log) }
                                    }
                                }
                            )
                        } catch (e: Exception) {
                            scope.launch(Dispatchers.Main) {
                                addLog(ExperimentLog(lockExp!!.now(), "读写并发", "异常: ${e.message}", LogType.ERROR))
                            }
                        }
                        scope.launch(Dispatchers.Main) { isRunning = false }
                    }
                },
                enabled = !isRunning,
                modifier = Modifier.weight(1f).padding(start = 4.dp),
            ) { Text("读写并发") }
        }

        // 锁超时测试按钮（全宽）
        Button(
            onClick = {
                if (isRunning || lockExp == null) return@Button
                isRunning = true
                logs.clear()
                scope.launch(Dispatchers.IO) {
                    try {
                        lockExp!!.runLockTimeout(
                            callback = object : LockExperiment.Callback {
                                override fun onLog(log: ExperimentLog) {
                                    scope.launch(Dispatchers.Main) { addLog(log) }
                                }
                            }
                        )
                    } catch (e: Exception) {
                        scope.launch(Dispatchers.Main) {
                            addLog(ExperimentLog(lockExp!!.now(), "锁超时", "异常: ${e.message}", LogType.ERROR))
                        }
                    }
                    scope.launch(Dispatchers.Main) { isRunning = false }
                }
            },
            enabled = !isRunning,
            modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
        ) { Text("锁超时测试") }

        // 日志区域
        Text("实验日志", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 12.dp, bottom = 8.dp))

        AutoScrollLogList(
            logs = logs.toList(),
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

/** 锁机制验证页面预览 */
@Preview(showBackground = true)
@Composable
fun LockMechanismScreenPreview() {
    LockMechanismScreen(Modifier.fillMaxSize())
}
