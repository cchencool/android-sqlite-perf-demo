package com.example.sqliteperfresearch.ui.main

import android.util.Log
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.example.sqliteperfresearch.database.PerfDatabase
import com.example.sqliteperfresearch.database.WalExperiment
import com.example.sqliteperfresearch.model.ExperimentLog
import com.example.sqliteperfresearch.model.LogType
import com.example.sqliteperfresearch.ui.main.LogItem
import com.example.sqliteperfresearch.util.LOG_TAG
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

private const val TAG = "$LOG_TAG.WalScreen"

@Composable
fun WalConcurrencyScreen(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var walExp by remember { mutableStateOf<WalExperiment?>(null) }
    var walDb by remember { mutableStateOf<PerfDatabase?>(null) }
    var deleteDb by remember { mutableStateOf<PerfDatabase?>(null) }
    var isRunning by remember { mutableStateOf(false) }
    var isPrepared by remember { mutableStateOf(false) }
    val logs = remember { mutableStateListOf<ExperimentLog>() }

    fun addLog(log: ExperimentLog) {
        logs.add(log)
        when (log.type) {
            LogType.INFO -> Log.d(TAG, "[${log.phase}] ${log.message}")
            LogType.SUCCESS -> Log.i(TAG, "[${log.phase}] ${log.message}")
            LogType.WARNING -> Log.w(TAG, "[${log.phase}] ${log.message}")
            LogType.ERROR -> Log.e(TAG, "[${log.phase}] ${log.message}")
        }
    }

    Column(modifier) {
        Text("WAL 并发对比", style = MaterialTheme.typography.headlineMedium, modifier = Modifier.padding(bottom = 4.dp))
        Text(
            "对比 WAL 与 TRUNCATE 日志模式下的并发性能: 并发读、读写混合、并发写。两个独立 DB 使用相同数据。",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(bottom = 16.dp),
        )

        Button(
            onClick = {
                if (isRunning) return@Button
                isRunning = true
                logs.clear()
                isPrepared = false
                scope.launch(Dispatchers.IO) {
                    walExp = WalExperiment(context)
                    try {
                        val (wDb, dDb) = walExp!!.prepareDatabases(object : WalExperiment.Callback {
                            override fun onLog(log: ExperimentLog) {
                                scope.launch(Dispatchers.Main) { addLog(log) }
                            }
                        })
                        walDb = wDb
                        deleteDb = dDb
                        scope.launch(Dispatchers.Main) {
                            isPrepared = true
                            isRunning = false
                            addLog(ExperimentLog(walExp!!.now(), "WAL对比", "两个 DB 已就绪, 可开始实验", LogType.SUCCESS))
                        }
                    } catch (e: Exception) {
                        scope.launch(Dispatchers.Main) {
                            isRunning = false
                            addLog(ExperimentLog(walExp?.now() ?: "?", "WAL对比", "准备失败: ${e.message}", LogType.ERROR))
                        }
                    }
                }
            },
            enabled = !isRunning,
            modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
        ) { Text(if (isPrepared) "重新准备 DB" else "准备两个 DB (WAL + TRUNCATE)") }

        if (!isPrepared) {
            Text(
                "请先点击「准备两个 DB」创建测试数据库 (需先在数据填充页填入数据)",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(vertical = 8.dp),
            )
        }

        Button(
            onClick = {
                if (isRunning || !isPrepared) return@Button
                isRunning = true
                addLog(ExperimentLog(walExp!!.now(), "并发读", "--- 开始并发读实验 ---", LogType.INFO))
                scope.launch(Dispatchers.IO) {
                    try {
                        walExp!!.runConcurrentReads(walDb!!, deleteDb!!, threadCount = 10, rowsPerThread = 5000,
                            callback = object : WalExperiment.Callback {
                                override fun onLog(log: ExperimentLog) {
                                    scope.launch(Dispatchers.Main) { addLog(log) }
                                }
                            })
                    } catch (e: Exception) {
                        scope.launch(Dispatchers.Main) {
                            addLog(ExperimentLog(walExp!!.now(), "并发读", "异常: ${e.message}", LogType.ERROR))
                        }
                    }
                    scope.launch(Dispatchers.Main) { isRunning = false }
                }
            },
            enabled = !isRunning && isPrepared,
            modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp),
        ) { Text("实验1: 并发读 (10 线程)") }

        Button(
            onClick = {
                if (isRunning || !isPrepared) return@Button
                isRunning = true
                addLog(ExperimentLog(walExp!!.now(), "读写混合", "--- 开始读写混合实验 ---", LogType.INFO))
                scope.launch(Dispatchers.IO) {
                    try {
                        walExp!!.runMixedReadWrite(walDb!!, deleteDb!!,
                            callback = object : WalExperiment.Callback {
                                override fun onLog(log: ExperimentLog) {
                                    scope.launch(Dispatchers.Main) { addLog(log) }
                                }
                            })
                    } catch (e: Exception) {
                        scope.launch(Dispatchers.Main) {
                            addLog(ExperimentLog(walExp!!.now(), "读写混合", "异常: ${e.message}", LogType.ERROR))
                        }
                    }
                    scope.launch(Dispatchers.Main) { isRunning = false }
                }
            },
            enabled = !isRunning && isPrepared,
            modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp),
        ) { Text("实验2: 读写混合 (8读+2写)") }

        Button(
            onClick = {
                if (isRunning || !isPrepared) return@Button
                isRunning = true
                addLog(ExperimentLog(walExp!!.now(), "并发写", "--- 开始并发写实验 ---", LogType.INFO))
                scope.launch(Dispatchers.IO) {
                    try {
                        walExp!!.runConcurrentWrites(walDb!!, deleteDb!!, threadCount = 10, rowsPerThread = 200,
                            callback = object : WalExperiment.Callback {
                                override fun onLog(log: ExperimentLog) {
                                    scope.launch(Dispatchers.Main) { addLog(log) }
                                }
                            })
                    } catch (e: Exception) {
                        scope.launch(Dispatchers.Main) {
                            addLog(ExperimentLog(walExp!!.now(), "并发写", "异常: ${e.message}", LogType.ERROR))
                        }
                    }
                    scope.launch(Dispatchers.Main) { isRunning = false }
                }
            },
            enabled = !isRunning && isPrepared,
            modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp),
        ) { Text("实验3: 并发写 (10 线程 × 200行)") }

        Text("实验日志", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 12.dp, bottom = 8.dp))
        LazyColumn(modifier = Modifier.fillMaxSize()) {
            items(logs.toList()) { log -> LogItem(log) }
        }
    }
}
