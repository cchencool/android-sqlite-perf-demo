// ============================================================
// SQLite 性能预研 App 入口 Activity
// ============================================================
// 职责：
// 1. 启用边到边（Edge-to-Edge）显示，让内容延伸到系统栏下方
// 2. 通过 setContent 挂载 Jetpack Compose UI 树
// 3. 应用全局主题 SQLitePerfResearchTheme
// ============================================================

package com.example.sqliteperfresearch

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent          // Compose 与 Activity 的桥接，将 Composable 设置为 Activity 内容
import androidx.activity.enableEdgeToEdge            // 启用边到边布局，状态栏/导航栏透明
import androidx.compose.foundation.layout.fillMaxSize // Modifier 扩展：让组件填满父容器全部可用空间
import androidx.compose.material3.MaterialTheme       // Material 3 主题组件，提供 colorScheme / typography / shapes
import androidx.compose.material3.Surface             // Material 表面组件，承载子组件并应用主题色
import androidx.compose.ui.Modifier                   // Compose 修饰符基类，用于链式布局配置
import com.example.sqliteperfresearch.theme.SQLitePerfResearchTheme

/**
 * 应用唯一 Activity 入口
 *
 * Compose 架构下，Activity 仅作为根容器挂载点，所有 UI 逻辑由 Composable 函数处理。
 * 继承 ComponentActivity（而非 AppCompatActivity），是 Compose 推荐的最小基类。
 */
class MainActivity : ComponentActivity() {

  /**
   * Activity 创建回调
   *
   * enableEdgeToEdge()：让应用内容延伸到系统栏（状态栏、导航栏）区域，
   *   配合 systemBarsPadding / safeDrawingPadding 可实现内容不被系统 UI 遮挡。
   * setContent { ... }：Compose 的根节点挂载，lambda 内的 Composable 成为 UI 树根。
   */
  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)

    // 启用边到边显示，状态栏和导航栏区域变为透明
    enableEdgeToEdge()

    // setContent 是 Compose 与 Android View 系统的桥接点
    // lambda 内的 Composable 构成整个 UI 树的根节点
    setContent {
      // 应用全局 Material 3 主题
      SQLitePerfResearchTheme {
        // Surface 提供主题背景色和表面层级
        // fillMaxSize() 让 Surface 填满整个屏幕
        Surface(
          modifier = Modifier.fillMaxSize(),
          color = MaterialTheme.colorScheme.background  // 使用主题的背景色
        ) {
          // 导航根组件，管理页面栈和路由
          MainNavigation()
        }
      }
    }
  }
}
