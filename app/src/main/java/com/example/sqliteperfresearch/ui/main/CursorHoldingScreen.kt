package com.example.sqliteperfresearch.ui.main

import android.util.Log
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.example.sqliteperfresearch.database.CursorExperiment
import com.example.sqliteperfresearch.database.PerfDatabase
import com.example.sqliteperfresearch.database.ReadTransactionMode
import com.example.sqliteperfresearch.model.ExperimentLog
import com.example.sqliteperfresearch.model.LogType
import com.example.sqliteperfresearch.ui.main.LogItem
import com.example.sqliteperfresearch.util.LOG_TAG
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

private const val TAG = "$LOG_TAG.CursorScreen"

@Composable
fun CursorHoldingScreen(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var db by remember { mutableStateOf<PerfDatabase?>(null) }
    var cursorExp by remember { mutableStateOf<CursorExperiment?>(null) }
    var isRunning by remember { mutableStateOf(false) }
    val logs = remember { mutableStateListOf<ExperimentLog>() }

    // Journal mode toggle: true = WAL, false = TRUNCATE
    var useWalMode by remember { mutableStateOf(true) }

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
        cursorExp = CursorExperiment(db!!)
        addLog(ExperimentLog(cursorExp!!.now(), "Cursor持有", "DB 路径: ${db!!.readableDatabase.path}", LogType.INFO))
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp),
    ) {
        Text("Cursor 持有验证", style = MaterialTheme.typography.headlineMedium, modifier = Modifier.padding(bottom = 4.dp))
        Text(
            "在 Cursor 遍历过程中通过 CountDownLatch 精确控制暂停, 在关键行位置执行 INSERT/UPDATE/DELETE 并提交, 验证已开启的 Cursor 是否能感知到并发写操作 (读一致性)。",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(bottom = 16.dp),
        )

        // Journal mode toggle
        Card(modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("WAL 模式", style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
                Switch(checked = useWalMode, onCheckedChange = {
                    useWalMode = it
                    db?.let { d ->
                        d.setWalMode(it)
                        addLog(ExperimentLog(cursorExp?.now() ?: "?", "Cursor持有", "已切换为: ${if (it) "WAL" else "TRUNCATE"} (${d.getJournalMode()})", LogType.INFO))
                    }
                })
                Text(if (useWalMode) "ON" else "OFF", style = MaterialTheme.typography.bodyLarge, modifier = Modifier.padding(start = 8.dp))
            }
        }

        // Standard tests (no transaction wrapping)
        Button(
            onClick = {
                if (isRunning || cursorExp == null) return@Button
                isRunning = true
                logs.clear()
                scope.launch(Dispatchers.IO) {
                    try {
                        cursorExp!!.runReadConsistencyTest(if (useWalMode) "WAL" else "TRUNCATE", object : CursorExperiment.Callback {
                            override fun onLog(log: ExperimentLog) {
                                scope.launch(Dispatchers.Main) { addLog(log) }
                            }
                        })
                    } catch (e: Exception) {
                        scope.launch(Dispatchers.Main) {
                            addLog(ExperimentLog(cursorExp!!.now(), "Cursor持有", "异常: ${e.message}", LogType.ERROR))
                        }
                    }
                    scope.launch(Dispatchers.Main) { isRunning = false }
                }
            },
            enabled = !isRunning,
            modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp),
        ) { Text("普通遍历模式 (无事务)") }

        // Transaction wrapped tests with 3 modes
        Text("事务包裹读 (选择事务类型):", style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(top = 12.dp, bottom = 8.dp))

        ReadTransactionMode.entries.forEach { txMode ->
            Button(
                onClick = {
                    if (isRunning || cursorExp == null) return@Button
                    isRunning = true
                    logs.clear()
                    scope.launch(Dispatchers.IO) {
                        try {
                            cursorExp!!.runTransactionWrappedTest(txMode, object : CursorExperiment.Callback {
                                override fun onLog(log: ExperimentLog) {
                                    scope.launch(Dispatchers.Main) { addLog(log) }
                                }
                            })
                        } catch (e: Exception) {
                            scope.launch(Dispatchers.Main) {
                                addLog(ExperimentLog(cursorExp!!.now(), "Cursor持有", "${txMode.label}异常: ${e.message}", LogType.ERROR))
                            }
                        }
                        scope.launch(Dispatchers.Main) { isRunning = false }
                    }
                },
                enabled = !isRunning,
                modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp),
            ) { Text("事务包裹: ${txMode.label} — ${txMode.description}") }
        }

        Card(modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
            Column(modifier = Modifier.padding(12.dp)) {
                Text("实验说明:", style = MaterialTheme.typography.labelMedium)
                Text("• 普通遍历: rawQuery 后不包裹事务, CursorWindow 按需填充, 不保证快照隔离", style = MaterialTheme.typography.bodySmall)
                Text("• 独占事务 (beginTransaction): 立即获取 EXCLUSIVE lock, 阻塞其他读写", style = MaterialTheme.typography.bodySmall)
                Text("• 非独占事务 (beginTransactionNonExclusive): WAL 下获取 SHARED lock, TRUNCATE 下等价于独占", style = MaterialTheme.typography.bodySmall)
                Text("• 只读事务 (beginTransactionReadOnly): 仅允许读, 任何写操作会抛异常", style = MaterialTheme.typography.bodySmall)
                Text("• 切换 WAL/TRUNCATE 可对比两种 journal mode 下的差异", style = MaterialTheme.typography.bodySmall)
            }
        }

        Text("实验日志", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 12.dp, bottom = 8.dp))
        LazyColumn(
            modifier = Modifier
                .fillMaxWidth()
                .height(600.dp)
        ) {
            items(logs.toList()) { log -> LogItem(log) }
        }
    }
}
