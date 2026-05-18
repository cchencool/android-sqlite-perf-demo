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
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.tooling.preview.Preview
import com.example.sqliteperfresearch.database.DataGenerator
import com.example.sqliteperfresearch.database.PerfDatabase
import com.example.sqliteperfresearch.database.Schema
import com.example.sqliteperfresearch.model.ExperimentLog
import com.example.sqliteperfresearch.model.LogType
import com.example.sqliteperfresearch.util.LOG_TAG
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private const val TAG = "$LOG_TAG.DataFill"

@Composable
fun DataFillScreen(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var db by remember { mutableStateOf<PerfDatabase?>(null) }
    var currentCount by remember { mutableIntStateOf(0) }
    var isFilling by remember { mutableStateOf(false) }
    var progress by remember { mutableIntStateOf(0) }
    var totalToFill by remember { mutableIntStateOf(0) }
    val logs = remember { mutableStateListOf<ExperimentLog>() }

    fun now() = java.text.SimpleDateFormat("HH:mm:ss.SSS", java.util.Locale.getDefault()).format(java.util.Date())
    fun addLog(msg: String, type: LogType = LogType.INFO) {
        logs.add(ExperimentLog(now(), "数据填充", msg, type))
        when (type) {
            LogType.INFO -> Log.d(TAG, msg)
            LogType.SUCCESS -> Log.i(TAG, msg)
            LogType.WARNING -> Log.w(TAG, msg)
            LogType.ERROR -> Log.e(TAG, msg)
        }
    }

    LaunchedEffect(Unit) {
        db = PerfDatabase(context)
        currentCount = db!!.getRowCount().toInt()
        addLog("数据库已就绪, 当前行数: $currentCount", LogType.SUCCESS)
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState()),
    ) {
        Text("数据填充", style = MaterialTheme.typography.headlineMedium, modifier = Modifier.padding(bottom = 8.dp))
            Card(modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text("当前行数: $currentCount", style = MaterialTheme.typography.titleMedium)
                    if (isFilling) {
                        Row {
                            CircularProgressIndicator(modifier = Modifier.padding(end = 8.dp))
                            Text("填充中: $progress / $totalToFill")
                        }
                    }
                }
            }

            Row(modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
                listOf(100_000, 200_000, 300_000).forEach { count ->
                    Button(
                        onClick = {
                            if (isFilling) return@Button
                            isFilling = true
                            progress = 0
                            totalToFill = count
                            addLog("开始填充 $count 行...", LogType.INFO)
                            Log.d(TAG, "Starting fill of $count rows")
                            val generator = DataGenerator()
                            val startTime = System.currentTimeMillis()
                            scope.launch(Dispatchers.IO) {
                                try {
                                    generator.generate(db!!.writableDatabase, count) { inserted ->
                                        progress = inserted
                                    }
                                    val elapsed = System.currentTimeMillis() - startTime
                                    withContext(Dispatchers.Main) {
                                        currentCount = db!!.getRowCount().toInt()
                                        isFilling = false
                                        addLog("完成: $count 行, 耗时 ${elapsed}ms, 当前总行数: $currentCount", LogType.SUCCESS)
                                        Log.d(TAG, "Fill completed: $count rows in ${elapsed}ms")
                                    }
                                } catch (e: Exception) {
                                    withContext(Dispatchers.Main) {
                                        isFilling = false
                                        addLog("填充失败: ${e.message}", LogType.ERROR)
                                        Log.e(TAG, "Fill failed", e)
                                    }
                                }
                            }
                        },
                        modifier = Modifier.weight(1f).padding(horizontal = 4.dp),
                        enabled = !isFilling,
                    ) { Text("${count / 10000}w") }
                }
            }

            Button(
                onClick = {
                    if (isFilling) return@Button
                    db?.let {
                        it.writableDatabase.execSQL(Schema.DROP_TABLE)
                        it.writableDatabase.execSQL(Schema.CREATE_TABLE)
                        currentCount = 0
                        addLog("数据已清空", LogType.WARNING)
                        Log.d(TAG, "Data cleared")
                    }
                },
                enabled = !isFilling && currentCount > 0,
                modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
            ) { Text("清空数据") }

        Text("操作日志", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 16.dp, bottom = 8.dp))

        AutoScrollLogList(
            logs = logs.toList(),
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

@Preview(showBackground = true)
@Composable
fun DataFillScreenPreview() {
    DataFillScreen(Modifier.fillMaxSize())
}
