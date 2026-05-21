// ============================================================
// 连接池打满验证页面（ConnectionPoolScreen）
// ============================================================
// 职责：
// 1. 提供 WAL 和 TRUNCATE 两种模式的连接池探测按钮
// 2. 调用 ConnectionPoolExperiment 执行实验
// 3. 实时显示实验日志
//
// Compose 模式：与 LockMechanismScreen 相同的页面模板
// - LaunchedEffect 初始化 → 按钮启动 IO 协程 → Callback 回调更新 Main 线程日志
// ============================================================

package com.example.sqliteperfresearch.ui.main

import android.util.Log
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.tooling.preview.Preview
import com.example.sqliteperfresearch.database.ConnectionPoolExperiment
import com.example.sqliteperfresearch.database.PerfDatabase
import com.example.sqliteperfresearch.model.ExperimentLog
import com.example.sqliteperfresearch.model.LogType
import com.example.sqliteperfresearch.util.LOG_TAG
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

private const val TAG = "$LOG_TAG.PoolScreen"

/**
 * 连接池打满验证页面
 *
 * 布局结构：
 * ┌──────────────────────────────────┐
 * │ 标题：连接池打满验证             │
 * │ 描述文字                         │
 * │ [WAL 模式探测] [TRUNCATE 模式探测]│
 * │ 实验日志                         │
 * └──────────────────────────────────┘
 */
@Composable
fun ConnectionPoolScreen(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var db by remember { mutableStateOf<PerfDatabase?>(null) }
    var poolExp by remember { mutableStateOf<ConnectionPoolExperiment?>(null) }
    var isRunning by remember { mutableStateOf(false) }
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

    // 初始化：创建数据库和连接池实验实例
    LaunchedEffect(Unit) {
        db = PerfDatabase(context)
        poolExp = ConnectionPoolExperiment(db!!)
        addLog(ExperimentLog(poolExp!!.now(), "连接池探测", "DB 路径: ${db!!.readableDatabase.path}", LogType.INFO))
    }

    // 可滚动布局
    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState()),
    ) {
        Text("连接池打满验证", style = MaterialTheme.typography.headlineMedium, modifier = Modifier.padding(bottom = 4.dp))
        Text(
            "逐步打开多个 SQLite 连接, 探测连接池容量上限, 观察打满后的行为。分别在 WAL 和 TRUNCATE 模式下测试。",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(bottom = 16.dp),
        )

        // 两个探测按钮并排
        Row(modifier = Modifier.fillMaxWidth()) {
            // ====== WAL 模式探测 ======
            Button(
                onClick = {
                    if (isRunning || poolExp == null) return@Button
                    isRunning = true
                    logs.clear()
                    scope.launch(Dispatchers.IO) {
                        try {
                            poolExp!!.probeConnectionPoolLimit("WAL", object : ConnectionPoolExperiment.Callback {
                                override fun onLog(log: ExperimentLog) {
                                    // 实验在 IO 线程回调，切换到 Main 线程更新 UI 状态
                                    scope.launch(Dispatchers.Main) { addLog(log) }
                                }
                            })
                        } catch (e: Exception) {
                            scope.launch(Dispatchers.Main) {
                                addLog(ExperimentLog(poolExp!!.now(), "连接池探测", "WAL模式异常: ${e.message}", LogType.ERROR))
                            }
                        }
                        scope.launch(Dispatchers.Main) { isRunning = false }
                    }
                },
                enabled = !isRunning,
                modifier = Modifier.weight(1f).padding(end = 4.dp),
            ) { Text("WAL 模式探测") }

            // ====== TRUNCATE 模式探测 ======
            Button(
                onClick = {
                    if (isRunning || poolExp == null) return@Button
                    isRunning = true
                    logs.clear()
                    scope.launch(Dispatchers.IO) {
                        try {
                            poolExp!!.probeConnectionPoolLimit("TRUNCATE", object : ConnectionPoolExperiment.Callback {
                                override fun onLog(log: ExperimentLog) {
                                    scope.launch(Dispatchers.Main) { addLog(log) }
                                }
                            })
                        } catch (e: Exception) {
                            scope.launch(Dispatchers.Main) {
                                addLog(ExperimentLog(poolExp!!.now(), "连接池探测", "TRUNCATE模式异常: ${e.message}", LogType.ERROR))
                            }
                        }
                        scope.launch(Dispatchers.Main) { isRunning = false }
                    }
                },
                enabled = !isRunning,
                modifier = Modifier.weight(1f).padding(start = 4.dp),
            ) { Text("TRUNCATE 模式探测") }
        }

        // 日志区域
        Text("实验日志", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 12.dp, bottom = 8.dp))

        AutoScrollLogList(
            logs = logs.toList(),
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

/** 连接池页面预览 */
@Preview(showBackground = true)
@Composable
fun ConnectionPoolScreenPreview() {
    ConnectionPoolScreen(Modifier.fillMaxSize())
}
