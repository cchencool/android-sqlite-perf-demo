// ============================================================
// 日志条目组件（LogItem）
// ============================================================
// 职责：
// 1. 渲染单条实验日志，根据日志类型（LogType）显示不同颜色的边框和标签
// 2. 使用 Card 包裹，提供 Material 表面效果
// 3. 横向排列：时间戳 + [阶段标签] + 日志内容
//
// Compose 关键概念：
// - Card：Material 3 卡片组件，提供圆角、阴影、表面色
// - Row：水平线性布局
// - background：Modifier 背景色扩展，copy(alpha) 控制透明度
// - when 表达式：根据枚举值选择对应分支
// ============================================================

package com.example.sqliteperfresearch.ui.main

import androidx.compose.foundation.background         // Modifier.background() 扩展：设置背景色
import androidx.compose.foundation.layout.Column       // 垂直线性布局
import androidx.compose.foundation.layout.Row          // 水平线性布局
import androidx.compose.foundation.layout.fillMaxWidth // 宽度填满父容器
import androidx.compose.foundation.layout.padding      // 内边距扩展
import androidx.compose.material3.Card                 // Material 3 卡片组件
import androidx.compose.material3.MaterialTheme        // Material 3 主题
import androidx.compose.material3.Text                 // 文字组件
import androidx.compose.runtime.Composable             // @Composable 注解
import androidx.compose.ui.Modifier                    // 修饰符基类
import androidx.compose.ui.graphics.Color              // Compose 颜色类
import androidx.compose.ui.unit.dp                     // dp 单位扩展
import com.example.sqliteperfresearch.model.ExperimentLog
import com.example.sqliteperfresearch.model.LogType

/**
 * 单条日志条目组件
 *
 * 布局结构：
 * ┌─────────────────────────────────────────┐
 * │ [时间戳]  [阶段标签]  日志内容          │  ← 背景色根据类型变化
 * └─────────────────────────────────────────┘
 *
 * 颜色映射：
 * - SUCCESS（绿色 #4CAF50）：成功结果
 * - WARNING（橙色 #FF9800）：警告
 * - ERROR（红色 #F44336）：错误
 * - INFO（蓝色 #2196F3）：普通信息
 *
 * @param log 日志数据对象
 */
@Composable
fun LogItem(log: ExperimentLog) {
    // 根据日志类型选择边框/标签颜色
    val borderColor = when (log.type) {
        LogType.SUCCESS -> Color(0xFF4CAF50)  // 绿色
        LogType.WARNING -> Color(0xFFFF9800)  // 橙色
        LogType.ERROR   -> Color(0xFFF44336)  // 红色
        LogType.INFO    -> Color(0xFF2196F3)  // 蓝色
    }

    // Card：Material 3 卡片容器，提供圆角和表面色
    Card(
        modifier = Modifier
            .fillMaxWidth()        // 宽度填满父容器
            .padding(vertical = 2.dp),  // 上下 2dp 间距
    ) {
        // Row：水平排列三个 Text 组件
        Row(
            modifier = Modifier
                .fillMaxWidth()
                // background 设置半透明背景色
                // borderColor.copy(alpha = 0.1f) = 边框颜色的 10% 透明度版本
                .background(borderColor.copy(alpha = 0.1f))
                .padding(8.dp),  // 内边距 8dp
        ) {
            // 时间戳：使用 labelSmall 样式，灰色
            Text(
                log.timestamp,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(end = 8.dp),
            )
            // 阶段标签：[phase]，使用对应类型的颜色
            Text(
                "[${log.phase}] ",
                style = MaterialTheme.typography.labelSmall,
                color = borderColor,
                modifier = Modifier.padding(end = 4.dp),
            )
            // 日志正文：使用 bodySmall 样式
            Text(log.message, style = MaterialTheme.typography.bodySmall)
        }
    }
}
