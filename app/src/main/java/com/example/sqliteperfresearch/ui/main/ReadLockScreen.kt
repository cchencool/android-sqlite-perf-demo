package com.example.sqliteperfresearch.ui.main

import android.util.Log
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Card
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
import com.example.sqliteperfresearch.database.PerfDatabase
import com.example.sqliteperfresearch.database.ReadLockBlockingExperiment
import com.example.sqliteperfresearch.model.ExperimentLog
import com.example.sqliteperfresearch.model.LogType
import com.example.sqliteperfresearch.ui.main.LogItem
import com.example.sqliteperfresearch.util.LOG_TAG
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

private const val TAG = "$LOG_TAG.ReadLockScreen"

@Composable
fun ReadLockScreen(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var db by remember { mutableStateOf<PerfDatabase?>(null) }
    var readLockExp by remember { mutableStateOf<ReadLockBlockingExperiment?>(null) }
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
        readLockExp = ReadLockBlockingExperiment(db!!)
        addLog(ExperimentLog(readLockExp!!.now(), "读锁阻塞", "DB 路径: ${db!!.readableDatabase.path}", LogType.INFO))
    }

    Column(modifier) {
        Text("读DB锁阻塞写验证", style = MaterialTheme.typography.headlineMedium, modifier = Modifier.padding(bottom = 4.dp))
        Text(
            "验证 Cursor 未遍历到底时, native sqlite statement 保持 active 持有 SHARED lock, 是否阻塞写操作。对比 TRUNCATE 和 WAL 模式。",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(bottom = 16.dp),
        )

        Card(modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
            Column(modifier = Modifier.padding(12.dp)) {
                Text("原理:", style = MaterialTheme.typography.labelMedium)
                Text("• Cursor rawQuery 后不遍历到底 → statement 保持 active → 持有 SHARED lock", style = MaterialTheme.typography.bodySmall)
                Text("• TRUNCATE 模式: writer 需要 EXCLUSIVE lock, 被 SHARED lock 阻塞 → endTransaction 卡住", style = MaterialTheme.typography.bodySmall)
                Text("• WAL 模式: 读写分离, writer 写 WAL 文件, 不被 reader 阻塞", style = MaterialTheme.typography.bodySmall)
                Text("• Cursor 遍历到底 → statement SQLITE_DONE → lock 释放 → writer 立即成功", style = MaterialTheme.typography.bodySmall)
            }
        }

        Button(
            onClick = {
                if (isRunning || readLockExp == null) return@Button
                isRunning = true
                logs.clear()
                scope.launch(Dispatchers.IO) {
                    try {
                        readLockExp!!.runReadLockTest("TRUNCATE", cursorFullyTraverse = false, object : ReadLockBlockingExperiment.Callback {
                            override fun onLog(log: ExperimentLog) {
                                scope.launch(Dispatchers.Main) { addLog(log) }
                            }
                        })
                    } catch (e: Exception) {
                        scope.launch(Dispatchers.Main) {
                            addLog(ExperimentLog(readLockExp!!.now(), "读锁阻塞", "TRUNCATE(不遍历)异常: ${e.message}", LogType.ERROR))
                        }
                    }
                    scope.launch(Dispatchers.Main) { isRunning = false }
                }
            },
            enabled = !isRunning,
            modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp),
        ) { Text("TRUNCATE: Cursor不遍历到底 → 预期阻塞写") }

        Button(
            onClick = {
                if (isRunning || readLockExp == null) return@Button
                isRunning = true
                logs.clear()
                scope.launch(Dispatchers.IO) {
                    try {
                        readLockExp!!.runReadLockTest("TRUNCATE", cursorFullyTraverse = true, object : ReadLockBlockingExperiment.Callback {
                            override fun onLog(log: ExperimentLog) {
                                scope.launch(Dispatchers.Main) { addLog(log) }
                            }
                        })
                    } catch (e: Exception) {
                        scope.launch(Dispatchers.Main) {
                            addLog(ExperimentLog(readLockExp!!.now(), "读锁阻塞", "TRUNCATE(遍历到底)异常: ${e.message}", LogType.ERROR))
                        }
                    }
                    scope.launch(Dispatchers.Main) { isRunning = false }
                }
            },
            enabled = !isRunning,
            modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp),
        ) { Text("TRUNCATE: Cursor遍历到底 → 预期不阻塞写") }

        Button(
            onClick = {
                if (isRunning || readLockExp == null) return@Button
                isRunning = true
                logs.clear()
                scope.launch(Dispatchers.IO) {
                    try {
                        readLockExp!!.runReadLockTest("WAL", cursorFullyTraverse = false, object : ReadLockBlockingExperiment.Callback {
                            override fun onLog(log: ExperimentLog) {
                                scope.launch(Dispatchers.Main) { addLog(log) }
                            }
                        })
                    } catch (e: Exception) {
                        scope.launch(Dispatchers.Main) {
                            addLog(ExperimentLog(readLockExp!!.now(), "读锁阻塞", "WAL(不遍历)异常: ${e.message}", LogType.ERROR))
                        }
                    }
                    scope.launch(Dispatchers.Main) { isRunning = false }
                }
            },
            enabled = !isRunning,
            modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp),
        ) { Text("WAL: Cursor不遍历到底 → 预期不阻塞写") }

        Button(
            onClick = {
                if (isRunning || readLockExp == null) return@Button
                isRunning = true
                logs.clear()
                scope.launch(Dispatchers.IO) {
                    try {
                        readLockExp!!.runReadLockTest("WAL", cursorFullyTraverse = true, object : ReadLockBlockingExperiment.Callback {
                            override fun onLog(log: ExperimentLog) {
                                scope.launch(Dispatchers.Main) { addLog(log) }
                            }
                        })
                    } catch (e: Exception) {
                        scope.launch(Dispatchers.Main) {
                            addLog(ExperimentLog(readLockExp!!.now(), "读锁阻塞", "WAL(遍历到底)异常: ${e.message}", LogType.ERROR))
                        }
                    }
                    scope.launch(Dispatchers.Main) { isRunning = false }
                }
            },
            enabled = !isRunning,
            modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp),
        ) { Text("WAL: Cursor遍历到底 → 预期不阻塞写") }

        Text("实验日志", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 12.dp, bottom = 8.dp))
        LazyColumn(modifier = Modifier.fillMaxSize()) {
            items(logs.toList()) { log -> LogItem(log) }
        }
    }
}
