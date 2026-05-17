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
import androidx.compose.ui.tooling.preview.Preview
import com.example.sqliteperfresearch.database.ConnectionPoolExperiment
import com.example.sqliteperfresearch.database.PerfDatabase
import com.example.sqliteperfresearch.model.ExperimentLog
import com.example.sqliteperfresearch.model.LogType
import com.example.sqliteperfresearch.ui.main.LogItem
import com.example.sqliteperfresearch.util.LOG_TAG
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

private const val TAG = "$LOG_TAG.PoolScreen"

@Composable
fun ConnectionPoolScreen(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var db by remember { mutableStateOf<PerfDatabase?>(null) }
    var poolExp by remember { mutableStateOf<ConnectionPoolExperiment?>(null) }
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
        poolExp = ConnectionPoolExperiment(db!!)
        addLog(ExperimentLog(poolExp!!.now(), "连接池探测", "DB 路径: ${db!!.readableDatabase.path}", LogType.INFO))
    }

    Column(modifier) {
        Text("连接池打满验证", style = MaterialTheme.typography.headlineMedium, modifier = Modifier.padding(bottom = 4.dp))
        Text(
            "逐步打开多个 SQLite 连接, 探测连接池容量上限, 观察打满后的行为。分别在 WAL 和 TRUNCATE 模式下测试。",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(bottom = 16.dp),
        )

        Row(modifier = Modifier.fillMaxWidth()) {
            Button(
                onClick = {
                    if (isRunning || poolExp == null) return@Button
                    isRunning = true
                    logs.clear()
                    scope.launch(Dispatchers.IO) {
                        try {
                            poolExp!!.probeConnectionPoolLimit("WAL", object : ConnectionPoolExperiment.Callback {
                                override fun onLog(log: ExperimentLog) {
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

        Text("实验日志", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 12.dp, bottom = 8.dp))
        LazyColumn(modifier = Modifier.fillMaxSize()) {
            items(logs.toList()) { log -> LogItem(log) }
        }
    }
}

@Preview(showBackground = true)
@Composable
fun ConnectionPoolScreenPreview() {
    Column(modifier = Modifier.padding(16.dp)) {
        Text("ConnectionPoolScreen", style = MaterialTheme.typography.headlineMedium)
        Text("Preview", style = MaterialTheme.typography.bodyMedium)
    }
}
