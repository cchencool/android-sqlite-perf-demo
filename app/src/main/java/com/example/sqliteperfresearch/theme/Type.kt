// ============================================================
// Material 3 排版样式（Typography）
// ============================================================
// 职责：
// 1. 定义全局文字排版样式集
// 2. 覆盖 Material 3 默认 Typography 的 bodyLarge 样式
// 3. 其他样式（titleLarge / labelSmall 等）使用 Material 默认值
//
// Compose 排版：
// - TextStyle 定义字体、粗细、字号、行高、字间距等
// - Typography 是一组命名样式（headlineMedium / bodyLarge / labelSmall 等）
// - 页面中的 Text 组件通过 style = MaterialTheme.typography.xxx 引用
// ============================================================

package com.example.sqliteperfresearch.theme

import androidx.compose.material3.Typography    // Material 3 排版样式集，包含所有命名文字样式
import androidx.compose.ui.text.TextStyle       // 文字样式数据类：字体/粗细/字号/行高/字间距
import androidx.compose.ui.text.font.FontFamily // 字体系列（Default / Monospace / SansSerif 等）
import androidx.compose.ui.text.font.FontWeight // 字体粗细枚举（Normal / Medium / Bold 等）
import androidx.compose.ui.unit.sp              // sp 单位扩展：缩放无关像素，随系统字体大小设置缩放

/**
 * 全局 Typography 排版样式集
 *
 * Material 3 定义了多种命名样式：
 * - displayLarge/Medium/Small：最大标题（欢迎页、引导页）
 * - headlineLarge/Medium/Small：页面标题（本 app 用 headlineMedium 作为页面标题）
 * - titleLarge/Medium/Small：组件标题（Card 标题、按钮文字）
 * - bodyLarge/Medium/Small：正文内容
 * - labelLarge/Medium/Small：标签、辅助说明文字
 *
 * 此处仅覆盖 bodyLarge（默认正文样式），其他样式使用 Material 3 内置默认值。
 */
val Typography = Typography(
    // bodyLarge：默认正文样式，用于大多数正文和说明文字
    bodyLarge = TextStyle(
        fontFamily = FontFamily.Default,   // 使用系统默认字体族
        fontWeight = FontWeight.Normal,    // 正常字重（400）
        fontSize = 16.sp,                  // 16sp 字号（随系统字体设置缩放）
        lineHeight = 24.sp,                // 24sp 行高（1.5 倍行距，提升可读性）
        letterSpacing = 0.5.sp,            // 0.5sp 字间距（略微增加，提升密集文字可读性）
    )
    // 其他样式使用 Material 3 默认值，如需自定义可取消注释：
    // titleLarge = TextStyle(
    //     fontFamily = FontFamily.Default,
    //     fontWeight = FontWeight.Normal,
    //     fontSize = 22.sp,
    //     lineHeight = 28.sp,
    //     letterSpacing = 0.sp
    // ),
    // labelSmall = TextStyle(
    //     fontFamily = FontFamily.Default,
    //     fontWeight = FontWeight.Medium,
    //     fontSize = 11.sp,
    //     lineHeight = 16.sp,
    //     letterSpacing = 0.5.sp
    // )
)
