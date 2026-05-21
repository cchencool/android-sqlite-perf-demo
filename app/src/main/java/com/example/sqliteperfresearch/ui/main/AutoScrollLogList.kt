// ============================================================
// 自动滚动日志列表组件（AutoScrollLogList）
// ============================================================
// 职责：
// 1. 展示实验日志列表，新日志自动滚动到底部
// 2. 使用 Column + verticalScroll 实现可滚动容器（而非 LazyColumn）
// 3. 通过 LaunchedEffect 监听 logs.size 变化，触发自动滚动
// 4. 限制高度范围（min 300dp ~ max 400dp），避免占满整个屏幕
//
// Compose 关键概念：
// - rememberScrollState()：记住滚动位置，重组后不丢失
// - LaunchedEffect(key)：当 key 变化时启动协程，key 变化时取消旧协程并启动新的
// - scrollState.animateScrollTo()：平滑滚动动画（非瞬间跳转）
// - scrollState.maxValue：滚动容器的最大可滚动距离
// ============================================================

package com.example.sqliteperfresearch.ui.main

import androidx.compose.foundation.layout.Column           // 垂直线性布局容器
import androidx.compose.foundation.layout.fillMaxWidth     // Modifier 扩展：宽度填满父容器
import androidx.compose.foundation.layout.heightIn         // Modifier 扩展：限制高度范围
import androidx.compose.foundation.rememberScrollState     // 记住滚动状态的 Composable 函数
import androidx.compose.foundation.verticalScroll          // Modifier 扩展：使 Column 可垂直滚动
import androidx.compose.runtime.Composable                 // @Composable 注解：标记为 Compose UI 函数
import androidx.compose.runtime.LaunchedEffect             // 副作用 API：key 变化时启动协程，离开 Composition 时自动取消
import androidx.compose.ui.Modifier                        // Compose 修饰符基类
import androidx.compose.ui.unit.dp                         // dp 单位扩展
import com.example.sqliteperfresearch.model.ExperimentLog  // 实验日志数据模型

/**
 * 自动滚动日志列表
 *
 * 工作原理：
 * 1. Column + verticalScroll 创建可滚动的垂直容器
 * 2. 每次 logs.size 变化（新增日志），LaunchedEffect 触发
 * 3. animateScrollTo(scrollState.maxValue) 平滑滚动到最底部
 *
 * 为什么用 Column + verticalScroll 而不是 LazyColumn？
 * - 日志数量不多（通常几十到几百条），Column 性能足够
 * - Column 所有子项一次性渲染，animateScrollTo 可以精确计算 maxValue
 * - LazyColumn 按需渲染子项，maxValue 动态变化，自动滚动更复杂
 *
 * @param logs 日志列表（只读 List，由父组件管理状态）
 * @param modifier 外部传入的修饰符，支持自定义布局
 */
@Composable
fun AutoScrollLogList(
    logs: List<ExperimentLog>,  // 日志列表，类型为不可变 List
    modifier: Modifier = Modifier,  // 默认空修饰符，由调用方决定布局
) {
    // rememberScrollState()：创建并记住滚动位置
    // 重组后滚动位置不丢失（比如新增日志后保持当前位置）
    val scrollState = rememberScrollState()

    // LaunchedEffect：当 logs.size 变化时（新增或删除日志），执行滚动
    // key = logs.size：只有日志数量变化时才触发，避免日志内容变化也触发滚动
    LaunchedEffect(logs.size) {
        if (logs.isNotEmpty()) {
            // animateScrollTo：平滑滚动动画到指定位置
            // scrollState.maxValue：最大可滚动距离 = 内容高度 - 可视区域高度
            // 即滚动到最底部，让最新日志可见
            scrollState.animateScrollTo(scrollState.maxValue)
        }
    }

    // Column：垂直线性布局，子组件从上到下排列
    Column(
        modifier = modifier
            .fillMaxWidth()           // 宽度填满父容器
            .heightIn(min = 300.dp, max = 400.dp)  // 限制高度：最小 300dp（保证可见），最大 400dp（避免占满屏幕）
            .verticalScroll(scrollState),  // 使 Column 可滚动，使用记住的滚动状态
    ) {
        // forEach 遍历所有日志，为每条日志创建 LogItem
        // 注意：这里不是懒加载，所有日志一次性渲染
        logs.forEach { log -> LogItem(log) }
    }
}
