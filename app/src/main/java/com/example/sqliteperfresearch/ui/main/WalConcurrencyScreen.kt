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
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.example.sqliteperfresearch.database.PerfDatabase
import com.example.sqliteperfresearch.database.WalExperiment
import com.example.sqliteperfresearch.model.ExperimentLog
import com.example.sqliteperfresearch.model.LogType
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
    var walRowCount by remember { mutableStateOf(0L) }
    var deleteRowCount by remember { mutableStateOf(0L) }
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

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState()),
    ) {
        Text("WAL 并发对比", style = MaterialTheme.typography.headlineMedium, modifier = Modifier.padding(bottom = 4.dp))
            Text(
                "对比 WAL 与 TRUNCATE 日志模式下的并发性能: 并发读、读写混合、并发写。两个独立 DB 使用相同数据。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(bottom = 16.dp),
            )

            Row(modifier = Modifier.fillMaxWidth()) {
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
                                walRowCount = wDb.getRowCount()
                                deleteRowCount = dDb.getRowCount()
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
                    modifier = Modifier.weight(1f).padding(end = 4.dp),
                ) { Text(if (isPrepared) "重新准备 DB" else "新建两个 DB") }
                Button(
                    onClick = {
                        if (isRunning) return@Button
                        isRunning = true
                        logs.clear()
                        isPrepared = false
                        scope.launch(Dispatchers.IO) {
                            walExp = WalExperiment(context)
                            try {
                                val result = walExp!!.openExistingDatabases(object : WalExperiment.Callback {
                                    override fun onLog(log: ExperimentLog) {
                                        scope.launch(Dispatchers.Main) { addLog(log) }
                                    }
                                })
                                if (result != null) {
                                    val (wDb, dDb) = result
                                    walDb = wDb
                                    deleteDb = dDb
                                    walRowCount = wDb.getRowCount()
                                    deleteRowCount = dDb.getRowCount()
                                    scope.launch(Dispatchers.Main) {
                                        isPrepared = true
                                        isRunning = false
                                        addLog(ExperimentLog(walExp!!.now(), "WAL对比", "使用现有 DB 成功, 可开始实验", LogType.SUCCESS))
                                    }
                                } else {
                                    scope.launch(Dispatchers.Main) { isRunning = false }
                                }
                            } catch (e: Exception) {
                                scope.launch(Dispatchers.Main) {
                                    isRunning = false
                                    addLog(ExperimentLog(walExp?.now() ?: "?", "WAL对比", "打开失败: ${e.message}", LogType.ERROR))
                                }
                            }
                        }
                    },
                    enabled = !isRunning,
                    modifier = Modifier.weight(1f).padding(start = 4.dp),
                ) { Text("使用现有 DB") }
            }

            if (!isPrepared) {
                Text(
                    "请先点击「新建两个 DB」或「使用现有 DB」",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(vertical = 8.dp),
                )
            } else {
                Text(
                    "当前数据量: WAL DB = $walRowCount 行, TRUNCATE DB = $deleteRowCount 行",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(vertical = 8.dp),
                )
                Button(
                    onClick = {
                        if (isRunning) return@Button
                        isRunning = true
                        addLog(ExperimentLog(walExp!!.now(), "WAL对比", "--- 开始填充 10w 行 ---", LogType.INFO))
                        scope.launch(Dispatchers.IO) {
                            try {
                                walExp!!.fillData(walDb!!, deleteDb!!, 100_000, object : WalExperiment.Callback {
                                    override fun onLog(log: ExperimentLog) {
                                        scope.launch(Dispatchers.Main) { addLog(log) }
                                    }
                                })
                                walRowCount = walDb!!.getRowCount()
                                deleteRowCount = deleteDb!!.getRowCount()
                                addLog(ExperimentLog(walExp!!.now(), "WAL对比", "WAL DB: $walRowCount 行, TRUNCATE DB: $deleteRowCount 行", LogType.SUCCESS))
                            } catch (e: Exception) {
                                scope.launch(Dispatchers.Main) {
                                    addLog(ExperimentLog(walExp!!.now(), "WAL对比", "填充失败: ${e.message}", LogType.ERROR))
                                    Log.e(TAG, "填充失败", e)
                                }
                            }
                            scope.launch(Dispatchers.Main) { isRunning = false }
                        }
                    },
                    enabled = !isRunning,
                    modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp),
                ) { Text("填充 10w 行数据 (两个 DB 并发)") }
            }

            Text("基础实验:", style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(top = 12.dp, bottom = 8.dp))

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

            Card(modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
                Column(modifier = Modifier.padding(12.dp)) {
                    Text("事务模式对比", style = MaterialTheme.typography.titleSmall)
                    Text(
                        "对每个事务模式 (独占 / 非独占 / 只读), 分别在 WAL 和 TRUNCATE DB 上执行并发读、并发写、读写混合 (8读+2写), 输出耗时对比和差异百分比。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            Button(
                onClick = {
                    if (isRunning || !isPrepared) return@Button
                    isRunning = true
                    addLog(ExperimentLog(walExp!!.now(), "事务模式对比", "--- 开始 WAL vs TRUNCATE 完整事务模式对比 ---", LogType.INFO))
                    scope.launch(Dispatchers.IO) {
                        try {
                            walExp!!.runFullTxModeComparison(walDb!!, deleteDb!!, 10, 5000,
                                callback = object : WalExperiment.Callback {
                                    override fun onLog(log: ExperimentLog) {
                                        scope.launch(Dispatchers.Main) { addLog(log) }
                                    }
                                })
                        } catch (e: Exception) {
                            scope.launch(Dispatchers.Main) {
                                addLog(ExperimentLog(walExp!!.now(), "事务模式对比", "异常: ${e.message}", LogType.ERROR))
                            }
                        }
                        scope.launch(Dispatchers.Main) { isRunning = false }
                    }
                },
                enabled = !isRunning && isPrepared,
                modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
            ) { Text("WAL vs TRUNCATE: 三种事务模式完整对比") }

        Text("实验日志", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 12.dp, bottom = 8.dp))

        AutoScrollLogList(
            logs = logs.toList(),
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

@Preview(showBackground = true)
@Composable
fun WalConcurrencyScreenPreview() {
    WalConcurrencyScreen(Modifier.fillMaxSize())
}
