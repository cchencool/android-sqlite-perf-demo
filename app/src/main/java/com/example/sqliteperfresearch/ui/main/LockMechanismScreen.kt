package com.example.sqliteperfresearch.ui.main

import android.util.Log
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
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
import com.example.sqliteperfresearch.database.LockExperiment
import com.example.sqliteperfresearch.database.PerfDatabase
import com.example.sqliteperfresearch.model.ExperimentLog
import com.example.sqliteperfresearch.model.LogType
import com.example.sqliteperfresearch.ui.main.LogItem
import com.example.sqliteperfresearch.util.LOG_TAG
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

private const val TAG = "$LOG_TAG.LockScreen"

@Composable
fun LockMechanismScreen(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var db by remember { mutableStateOf<PerfDatabase?>(null) }
    var lockExp by remember { mutableStateOf<LockExperiment?>(null) }
    var isRunning by remember { mutableStateOf(false) }
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

    LaunchedEffect(Unit) {
        db = PerfDatabase(context)
        lockExp = LockExperiment(db!!)
    }

    Column(modifier) {
        Text("锁机制验证", style = MaterialTheme.typography.headlineMedium, modifier = Modifier.padding(bottom = 4.dp))
        Text(
            "演示 SQLite 并发读写时的锁行为: 共享锁允许多读、排他锁阻塞读、busy timeout 机制",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(bottom = 16.dp),
        )

        Row(modifier = Modifier.fillMaxWidth()) {
            Button(
                onClick = {
                    if (isRunning || lockExp == null) return@Button
                    isRunning = true
                    logs.clear()
                    scope.launch(Dispatchers.IO) {
                        try {
                            lockExp!!.runConcurrentReads(threadCount = 5, queryRows = 10000, object : LockExperiment.Callback {
                                override fun onLog(log: ExperimentLog) {
                                    scope.launch(Dispatchers.Main) { addLog(log) }
                                }
                            })
                        } catch (e: Exception) {
                            scope.launch(Dispatchers.Main) {
                                addLog(ExperimentLog(lockExp!!.now(), "并发读", "异常: ${e.message}", LogType.ERROR))
                            }
                        }
                        scope.launch(Dispatchers.Main) { isRunning = false }
                    }
                },
                enabled = !isRunning,
                modifier = Modifier.weight(1f).padding(end = 4.dp),
            ) { Text("并发读 ×5") }
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

        Text("实验日志", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 12.dp, bottom = 8.dp))
        LazyColumn(modifier = Modifier.fillMaxSize()) {
            items(logs.toList()) { log -> LogItem(log) }
        }
    }
}
