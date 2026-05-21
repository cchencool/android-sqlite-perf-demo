// ============================================================
// Material 3 主题颜色定义
// ============================================================
// 职责：
// 1. 定义亮色/暗色主题的调色板
// 2. 使用 Material 3 语义化颜色键（primary / secondary / tertiary）
// 3. 后缀 80 代表暗色主题（高亮度），40 代表亮色主题（低饱和度）
// ============================================================

package com.example.sqliteperfresearch.theme

import androidx.compose.ui.graphics.Color  // Compose 颜色类，支持 ARGB 十六进制表示

// ===== 暗色主题调色板（80 系列，高亮度值，适配深色背景）=====
// darkColorScheme 的 primary/secondary/tertiary 是 Material 3 核心三色
// primary：主要交互元素（按钮、链接、选中状态）
// secondary：次要强调元素（筛选器、标签）
// tertiary：辅助强调色（与 primary/secondary 形成对比的装饰色）
val Purple80 = Color(0xFFD0BCFF)       // 紫色（暗色主题主色）
val PurpleGrey80 = Color(0xFFCCC2DC)   // 紫灰色（暗色主题次色）
val Pink80 = Color(0xFFEFB8C8)         // 粉色（暗色主题第三色）

// ===== 亮色主题调色板（40 系列，低饱和度，适配浅色背景）=====
val Purple40 = Color(0xFF6650a4)       // 紫色（亮色主题主色）
val PurpleGrey40 = Color(0xFF625b71)   // 紫灰色（亮色主题次色）
val Pink40 = Color(0xFF7D5260)         // 粉色（亮色主题第三色）
