// ============================================================
// 数据填充页面（DataFillScreen）
// ============================================================
// 职责：
// 1. 向 performance_test 表批量填充测试数据（10w/20w/30w 行，增量模式）
// 2. 显示当前行数和填充进度
// 3. 支持清空数据（DROP TABLE + CREATE TABLE）
//
// Compose 关键概念：
// - LaunchedEffect(Unit)：页面初始化时执行一次数据库准备
// - remember { mutableStateOf<T>() }：记住可变状态，值变化触发重组
// - rememberCoroutineScope()：记住协程作用域，用于在 Composable 中启动协程
// - LocalContext.current：获取当前 Android Context
// - verticalScroll：使 Column 可滚动
// - withContext(Dispatchers.Main)：从 IO 线程切回主线程更新 UI 状态
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
import androidx.compose.material3.Card                     // Material 3 卡片
import androidx.compose.material3.CircularProgressIndicator // 圆形加载指示器
import androidx.compose.material3.MaterialTheme            // Material 3 主题
import androidx.compose.material3.Text                     // 文字组件
import androidx.compose.runtime.Composable                 // @Composable 注解
import androidx.compose.runtime.LaunchedEffect             // 副作用：初始化时执行一次
import androidx.compose.runtime.getValue                   // by 委托：自动 getValue
import androidx.compose.runtime.mutableIntStateOf          // Int 类型的可变状态工厂
import androidx.compose.runtime.mutableStateListOf         // 可变状态列表工厂（用于日志）
import androidx.compose.runtime.mutableStateOf             // 泛型可变状态工厂
import androidx.compose.runtime.remember                   // 记住值，重组后不丢失
import androidx.compose.runtime.rememberCoroutineScope     // 记住协程作用域
import androidx.compose.runtime.setValue                   // by 委托：自动 setValue
import androidx.compose.ui.Modifier                        // 修饰符基类
import androidx.compose.ui.platform.LocalContext           // 提供当前 Android Context 的 CompositionLocal
import androidx.compose.ui.unit.dp                         // dp 单位扩展
import androidx.compose.ui.tooling.preview.Preview         // @Preview 注解
import com.example.sqliteperfresearch.database.DataGenerator
import com.example.sqliteperfresearch.database.PerfDatabase
import com.example.sqliteperfresearch.database.Schema
import com.example.sqliteperfresearch.model.ExperimentLog
import com.example.sqliteperfresearch.model.LogType
import com.example.sqliteperfresearch.util.LOG_TAG
import kotlinx.coroutines.Dispatchers                     // 协程调度器
import kotlinx.coroutines.launch                          // 启动协程
import kotlinx.coroutines.withContext                     // 切换协程调度器

private const val TAG = "$LOG_TAG.DataFill"

/**
 * 数据填充页面
 *
 * 布局结构：
 * ┌──────────────────────────────────┐
 * │ 标题：数据填充                   │
 * │ 卡片：当前行数 + 进度指示器      │
 * │ [10w] [20w] [30w] 三个填充按钮   │
 * │ [清空数据]                       │
 * │ 操作日志（AutoScrollLogList）    │
 * └──────────────────────────────────┘
 *
 * Compose 状态管理模式：
 * - var x by remember { mutableStateOf<T>() }：记住可变状态，值变化触发 UI 重组
 * - val logs = remember { mutableStateListOf<T>() }：记住可变列表，增删元素触发重组
 * - LaunchedEffect(Unit)：Unit 作为 key，只在首次组合时执行一次
 */
@Composable
fun DataFillScreen(modifier: Modifier = Modifier) {
    // LocalContext.current：通过 CompositionLocal 获取当前 Android Context
    // CompositionLocal 是 Compose 的隐式参数传递机制，类似 React 的 Context
    val context = LocalContext.current

    // rememberCoroutineScope()：记住一个 CoroutineScope，重组后保持相同
    // 用于在 Composable 中启动协程（如按钮点击后执行异步操作）
    val scope = rememberCoroutineScope()

    // var db by remember { mutableStateOf<PerfDatabase?>() }
    // 数据库实例，null = 尚未初始化
    // by 委托语法：访问 db 时自动调用 remember 返回的 state 的 getValue/setValue
    var db by remember { mutableStateOf<PerfDatabase?>(null) }

    // mutableIntStateOf：专门用于 Int 类型的可变状态，比 mutableStateOf<Int> 更高效（避免装箱）
    var currentCount by remember { mutableIntStateOf(0) }  // 当前数据库行数
    var isFilling by remember { mutableStateOf(false) }    // 是否正在填充
    var progress by remember { mutableIntStateOf(0) }      // 当前填充进度（已填充行数）
    var totalToFill by remember { mutableIntStateOf(0) }   // 目标填充行数

    // mutableStateListOf：记住一个可变列表，列表内容变化（add/remove/clear）触发 UI 重组
    val logs = remember { mutableStateListOf<ExperimentLog>() }

    /** 生成当前时间戳字符串 */
    fun now() = java.text.SimpleDateFormat("HH:mm:ss.SSS", java.util.Locale.getDefault()).format(java.util.Date())

    /**
     * 添加日志到 UI 列表和 logcat
     *
     * 这是一个局部函数，闭包捕获了 logs、now()、TAG 等外部变量。
     * 添加日志到 mutableStateListOf 会触发 Compose 重组（LazyColumn 自动刷新）。
     */
    fun addLog(msg: String, type: LogType = LogType.INFO) {
        logs.add(ExperimentLog(now(), "数据填充", msg, type))  // 添加到 UI 列表 → 触发重组
        when (type) {
            LogType.INFO    -> Log.d(TAG, msg)
            LogType.SUCCESS -> Log.i(TAG, msg)
            LogType.WARNING -> Log.w(TAG, msg)
            LogType.ERROR   -> Log.e(TAG, msg)
        }
    }

    // LaunchedEffect(Unit)：副作用，仅在首次进入 Composition 时执行一次
    // key = Unit：key 不变就不会重新执行
    // 常用于页面初始化操作（如打开数据库、加载数据）
    LaunchedEffect(Unit) {
        db = PerfDatabase(context)                // 创建数据库实例
        currentCount = db!!.getRowCount().toInt() // 查询当前行数
        addLog("数据库已就绪, 当前行数: $currentCount", LogType.SUCCESS)
    }

    // Column + verticalScroll：可滚动的垂直布局
    // rememberScrollState() 记住滚动位置，重组后不丢失
    Column(
        modifier = modifier
            .fillMaxSize()                     // 填满整个屏幕
            .verticalScroll(rememberScrollState()),  // 使 Column 可滚动
    ) {
        // 页面标题
        Text("数据填充", style = MaterialTheme.typography.headlineMedium, modifier = Modifier.padding(bottom = 8.dp))

        // 状态卡片：显示当前行数和填充进度
        Card(modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text("当前行数: $currentCount", style = MaterialTheme.typography.titleMedium)
                // isFilling = true 时显示加载指示器和进度
                if (isFilling) {
                    Row {
                        CircularProgressIndicator(modifier = Modifier.padding(end = 8.dp))  // 旋转加载圈
                        Text("填充中: $progress / $totalToFill")
                    }
                }
            }
        }

        // 三个填充按钮：10w / 20w / 30w
        Row(modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
            listOf(100_000, 200_000, 300_000).forEach { count ->
                Button(
                    onClick = {
                        if (isFilling) return@Button  // 正在填充时不允许重复点击
                        isFilling = true
                        progress = 0
                        totalToFill = count
                        addLog("开始填充 $count 行...", LogType.INFO)
                        Log.d(TAG, "Starting fill of $count rows")
                        val generator = DataGenerator()
                        val startTime = System.currentTimeMillis()

                        // scope.launch(Dispatchers.IO)：在 IO 线程池启动协程
                        // 数据库操作是阻塞 IO，使用 IO 调度器避免占用主线程
                        scope.launch(Dispatchers.IO) {
                            try {
                                // generate 的第三个参数是 progress 回调
                                // 每次插入 10000 行调用一次，更新 progress 状态 → 触发 UI 重组
                                generator.generate(db!!.writableDatabase, count) { inserted ->
                                    progress = inserted  // 修改状态 → 触发 Compose 重组
                                }
                                val elapsed = System.currentTimeMillis() - startTime

                                // withContext(Dispatchers.Main)：切换回主线程
                                // Compose 状态修改必须在主线程（或至少能触发重组的线程）
                                withContext(Dispatchers.Main) {
                                    currentCount = db!!.getRowCount().toInt()  // 更新当前行数
                                    isFilling = false                          // 清除填充状态
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
                    // weight(1f)：三个按钮等宽分配
                    modifier = Modifier.weight(1f).padding(horizontal = 4.dp),
                    enabled = !isFilling,  // 正在填充时禁用按钮
                ) { Text("${count / 10000}w") }  // 按钮文字：10w / 20w / 30w
            }
        }

        // 清空数据按钮
        Button(
            onClick = {
                if (isFilling) return@Button
                db?.let {
                    it.writableDatabase.execSQL(Schema.DROP_TABLE)    // 删除表
                    it.writableDatabase.execSQL(Schema.CREATE_TABLE)  // 重新创建空表
                    currentCount = 0
                    addLog("数据已清空", LogType.WARNING)
                    Log.d(TAG, "Data cleared")
                }
            },
            enabled = !isFilling && currentCount > 0,  // 有数据且不在填充时才可用
            modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
        ) { Text("清空数据") }

        // 日志标题
        Text("操作日志", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 16.dp, bottom = 8.dp))

        // 自动滚动日志列表
        AutoScrollLogList(
            logs = logs.toList(),  // 转为不可变 List 传给子组件
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

/** 数据填充页面预览 */
@Preview(showBackground = true)
@Composable
fun DataFillScreenPreview() {
    DataFillScreen(Modifier.fillMaxSize())
}
