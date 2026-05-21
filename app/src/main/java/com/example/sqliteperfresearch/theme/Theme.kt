// ============================================================
// Material 3 主题组合（Theme）
// ============================================================
// 职责：
// 1. 根据系统暗色模式设置、Android 版本动态选择颜色方案
// 2. Android 12+（API 31+）支持动态颜色（从壁纸提取配色）
// 3. 将颜色方案 + 排版样式组合为 MaterialTheme，供子组件使用
//
// Compose 主题机制：
// - @Composable 函数通过 LocalContext.current 获取当前 Context
// - MaterialTheme 是一个 CompositionLocal 提供者，子组件通过 MaterialTheme.colorScheme 访问颜色
// ============================================================

package com.example.sqliteperfresearch.theme

import android.os.Build                                     // Android 系统版本检测
import androidx.compose.foundation.isSystemInDarkTheme      // Compose 系统暗色模式查询 Composable
import androidx.compose.material3.MaterialTheme             // Material 3 主题组件，提供 colorScheme / typography / shapes 给子树
import androidx.compose.material3.darkColorScheme           // 暗色主题颜色方案工厂函数
import androidx.compose.material3.dynamicDarkColorScheme    // Android 12+ 动态暗色主题（从壁纸提取颜色）
import androidx.compose.material3.dynamicLightColorScheme   // Android 12+ 动态亮色主题（从壁纸提取颜色）
import androidx.compose.material3.lightColorScheme          // 亮色主题颜色方案工厂函数
import androidx.compose.runtime.Composable                  // @Composable 注解：标记函数可参与 Compose UI 树
import androidx.compose.ui.platform.LocalContext            // CompositionLocal：提供当前 Android Context 的 Compose 访问方式

// ===== 暗色主题颜色方案 =====
// 使用自定义的紫色/紫灰色/粉色组合
// darkColorScheme 返回 ColorScheme 对象，包含 primary / background / surface 等语义化颜色键
private val DarkColorScheme = darkColorScheme(
    primary = Purple80,        // 主要交互色
    secondary = PurpleGrey80,  // 次要强调色
    tertiary = Pink80          // 辅助强调色
)

// ===== 亮色主题颜色方案 =====
// 使用较低饱和度的紫色/紫灰色/粉色组合
private val LightColorScheme = lightColorScheme(
    primary = Purple40,        // 主要交互色
    secondary = PurpleGrey40,  // 次要强调色
    tertiary = Pink40,         // 辅助强调色
    // 其他颜色键使用 Material 默认值，如需自定义可取消注释：
    // background = Color(0xFFFFFBFE),   // 页面背景色
    // surface = Color(0xFFFFFBFE),      // 表面色（Card、Surface 等）
    // onPrimary = Color.White,          // 绘制在 primary 色上的颜色
    // onSecondary = Color.White,        // 绘制在 secondary 色上的颜色
    // onTertiary = Color.White,         // 绘制在 tertiary 色上的颜色
    // onBackground = Color(0xFF1C1B1F), // 绘制在 background 上的文字/图标颜色
    // onSurface = Color(0xFF1C1B1F),    // 绘制在 surface 上的文字/图标颜色
)

/**
 * 应用全局 Material 3 主题
 *
 * @param darkTheme 是否使用暗色主题，默认跟随系统设置（isSystemInDarkTheme）
 * @param dynamicColor 是否启用动态颜色（Android 12+ 从壁纸提取配色），默认开启
 * @param content 主题包裹的子组件内容
 *
 * 颜色方案选择优先级：
 * 1. Android 12+ 且 dynamicColor=true → 使用系统动态颜色（dynamicDarkColorScheme / dynamicLightColorScheme）
 * 2. darkTheme=true → 使用自定义暗色方案（DarkColorScheme）
 * 3. 其他情况 → 使用自定义亮色方案（LightColorScheme）
 */
@Composable
fun SQLitePerfResearchTheme(
    darkTheme: Boolean = isSystemInDarkTheme(), // 默认值调用系统 API 检测当前是否为暗色模式
    // Android 12（API 31）及以上支持从用户壁纸提取动态配色
    dynamicColor: Boolean = true,
    content: @Composable () -> Unit,            // @Composable lambda：主题包裹的子组件树
) {
    // when 表达式根据条件选择颜色方案
    // Compose 的状态读取（LocalContext.current、darkTheme 等）会自动追踪重组
    val colorScheme = when {
        // 优先级最高：Android 12+ 且用户启用了动态颜色
        dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
            val context = LocalContext.current  // 通过 CompositionLocal 获取当前 Android Context
            // 根据 darkTheme 选择动态暗色或亮色方案
            if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        }
        // 第二优先级：自定义暗色方案
        darkTheme -> DarkColorScheme
        // 默认：自定义亮色方案
        else -> LightColorScheme
    }

    // MaterialTheme 是主题提供者，将 colorScheme 和 typography 注入 CompositionLocal
    // 子组件通过 MaterialTheme.colorScheme.xxx 和 MaterialTheme.typography.xxx 访问
    MaterialTheme(
        colorScheme = colorScheme,   // 当前颜色方案
        typography = Typography,     // 排版样式集（定义在 Type.kt）
        content = content            // 子组件树，可访问上述主题设置
    )
}
