// ============================================================
// 导航配置（Navigation）
// ============================================================
// 职责：
// 1. 使用 Nav3（Navigation 3）的 NavDisplay + entryProvider 模式管理页面栈
// 2. 将每个 NavKey 映射到对应的页面 Composable
// 3. 处理页面返回逻辑（onBack 从 backStack 移除最后一个元素）
//
// Nav3 核心概念：
// - rememberNavBackStack：维护页面栈，add 推入新页面，removeLastOrNull 返回上一页
// - NavDisplay：监听 backStack 变化，自动显示栈顶页面并处理过渡动画
// - entryProvider { entry<Key> { ... } }：将 NavKey 映射到具体的 Composable
// ============================================================

package com.example.sqliteperfresearch

import androidx.compose.foundation.layout.padding        // Modifier.padding() 扩展：添加内边距
import androidx.compose.foundation.layout.safeDrawingPadding // Modifier.safeDrawingPadding()：避开系统 UI（状态栏/导航栏/刘海）的安全区域
import androidx.compose.runtime.Composable               // @Composable 注解：标记函数为 Composable，可参与 UI 树构建
import androidx.compose.ui.Modifier                      // Compose 修饰符基类
import androidx.compose.ui.unit.dp                       // dp 单位扩展，Compose 布局使用密度无关像素
import androidx.navigation3.runtime.entryProvider        // Nav3 entryProvider DSL：注册 NavKey 到 Composable 的映射
import androidx.navigation3.runtime.rememberNavBackStack // Nav3 backStack 管理：记住页面栈状态，重组后不丢失
import androidx.navigation3.ui.NavDisplay                // Nav3 核心组件：根据 backStack 自动显示对应页面并处理过渡动画
// 导入所有页面 Composable，每个 entry 需要引用对应的页面组件
import com.example.sqliteperfresearch.ui.main.ConnectionPoolScreen
import com.example.sqliteperfresearch.ui.main.CursorHoldingScreen
import com.example.sqliteperfresearch.ui.main.DataFillScreen
import com.example.sqliteperfresearch.ui.main.LockMechanismScreen
import com.example.sqliteperfresearch.ui.main.MainScreen
import com.example.sqliteperfresearch.ui.main.ReadLockScreen
import com.example.sqliteperfresearch.ui.main.WalConcurrencyScreen

/**
 * 应用导航根组件
 *
 * NavDisplay 工作原理：
 * 1. backStack 维护一个 NavKey 列表，栈顶是当前可见页面
 * 2. NavDisplay 监听 backStack 变化，当栈顶 NavKey 改变时自动切换到对应页面
 * 3. entryProvider 提供 NavKey → Composable 的映射表
 * 4. onBack 回调处理返回按钮：从 backStack 移除栈顶元素，NavDisplay 自动回到上一页
 *
 * @Composable：此函数参与 Compose UI 树，可被其他 Composable 调用
 */
@Composable
fun MainNavigation() {
    // rememberNavBackStack：创建并记住初始页面栈
    // 参数 Main 是初始页面（首页），进程重建时 Nav3 会自动恢复之前的 backStack 状态
    val backStack = rememberNavBackStack(Main)

    // NavDisplay：声明式导航显示器
    // 它根据 backStack 的栈顶 NavKey，通过 entryProvider 查找并渲染对应的 Composable
    NavDisplay(
        // backStack：页面栈数据源，NavDisplay 监听其变化自动切换页面
        backStack = backStack,

        // onBack：返回按钮回调
        // 从 backStack 移除最后一个元素（栈顶），NavDisplay 自动回到上一页
        // removeLastOrNull 避免空栈时崩溃
        onBack = { backStack.removeLastOrNull() },

        // entryProvider：注册 NavKey 到 Composable 的映射
        // 每个 entry<Key> { ... } 定义了一个路由规则
        entryProvider = entryProvider {
            // entry<Main>：当 backStack 栈顶是 Main 时，显示 MainScreen
            // onItemClick 回调：将目标 NavKey 添加到 backStack，触发 NavDisplay 切换到新页面
            entry<Main> {
                MainScreen(
                    onItemClick = { navKey -> backStack.add(navKey) }, // 点击卡片，推入对应页面
                    modifier = Modifier
                        .safeDrawingPadding()  // 避开系统 UI 安全区域
                        .padding(16.dp)        // 四周 16dp 内边距
                )
            }

            // entry<DataFill>：数据填充页
            entry<DataFill> {
                DataFillScreen(
                    modifier = Modifier
                        .safeDrawingPadding()
                        .padding(16.dp)
                )
            }

            // entry<LockMechanism>：锁机制验证页
            entry<LockMechanism> {
                LockMechanismScreen(
                    modifier = Modifier
                        .safeDrawingPadding()
                        .padding(16.dp)
                )
            }

            // entry<CursorHolding>：Cursor 持有验证页
            entry<CursorHolding> {
                CursorHoldingScreen(
                    modifier = Modifier
                        .safeDrawingPadding()
                        .padding(16.dp)
                )
            }

            // entry<WalConcurrency>：WAL 并发对比页
            entry<WalConcurrency> {
                WalConcurrencyScreen(
                    modifier = Modifier
                        .safeDrawingPadding()
                        .padding(16.dp)
                )
            }

            // entry<ConnectionPool>：连接池打满验证页
            entry<ConnectionPool> {
                ConnectionPoolScreen(
                    modifier = Modifier
                        .safeDrawingPadding()
                        .padding(16.dp)
                )
            }

            // entry<ReadLock>：读锁阻塞写验证页
            entry<ReadLock> {
                ReadLockScreen(
                    modifier = Modifier
                        .safeDrawingPadding()
                        .padding(16.dp)
                )
            }
        },
    )
}
