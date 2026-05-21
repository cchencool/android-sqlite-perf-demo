// ============================================================
// 首页组件（MainScreen）
// ============================================================
// 职责：
// 1. 展示应用标题和所有功能入口卡片
// 2. 使用 LazyColumn 实现可滚动列表（性能优于 Column，适合长列表）
// 3. 每个功能卡片对应一个 NavKey，点击后触发页面导航
//
// Compose 关键概念：
// - LazyColumn：懒加载垂直列表，只渲染可见区域内的子项
// - item { ... }：在 LazyColumn 中添加单个条目
// - items(count) { index -> ... }：批量添加多个条目
// - clickable：Modifier 点击事件扩展
// - Icon：Material 图标组件
// - weight：Row 中的权重分配，让子组件按比例分配空间
// ============================================================

package com.example.sqliteperfresearch.ui.main

import androidx.compose.foundation.clickable              // Modifier.clickable() 扩展：添加点击事件
import androidx.compose.foundation.layout.Column          // 垂直线性布局
import androidx.compose.foundation.layout.Row             // 水平线性布局
import androidx.compose.foundation.layout.fillMaxSize     // 填满整个父容器
import androidx.compose.foundation.layout.fillMaxWidth    // 宽度填满父容器
import androidx.compose.foundation.layout.padding         // 内边距扩展
import androidx.compose.foundation.lazy.LazyColumn        // 懒加载垂直列表
import androidx.compose.material.icons.Icons              // Material Icons 集合
import androidx.compose.material.icons.automirrored.filled.ArrowForward  // 右箭头图标（自动镜像，适配 RTL）
import androidx.compose.material.icons.filled.DataObject  // 数据图标
import androidx.compose.material.icons.filled.Lock        // 锁图标
import androidx.compose.material.icons.filled.Search      // 搜索/查找图标
import androidx.compose.material.icons.filled.Speed       // 速度/性能图标
import androidx.compose.material.icons.filled.Storage     // 存储图标
import androidx.compose.material.icons.filled.Warning     // 警告图标
import androidx.compose.material3.Card                    // Material 3 卡片组件
import androidx.compose.material3.Icon                    // 图标组件
import androidx.compose.material3.MaterialTheme           // Material 3 主题
import androidx.compose.material3.Text                    // 文字组件
import androidx.compose.runtime.Composable                // @Composable 注解
import androidx.compose.ui.Alignment                      // 对齐方式枚举（CenterVertically 等）
import androidx.compose.ui.Modifier                       // 修饰符基类
import androidx.compose.ui.graphics.vector.ImageVector    // 矢量图标类型
import androidx.compose.ui.tooling.preview.Preview        // @Preview 注解：Android Studio 预览
import androidx.compose.ui.unit.dp                        // dp 单位扩展
import androidx.navigation3.runtime.NavKey                // Nav3 导航键接口
import com.example.sqliteperfresearch.ConnectionPool      // 导航键：连接池页面
import com.example.sqliteperfresearch.CursorHolding       // 导航键：Cursor 持有页面
import com.example.sqliteperfresearch.DataFill            // 导航键：数据填充页面
import com.example.sqliteperfresearch.LockMechanism       // 导航键：锁机制页面
import com.example.sqliteperfresearch.ReadLock            // 导航键：读锁页面
import com.example.sqliteperfresearch.WalConcurrency      // 导航键：WAL 并发页面

/**
 * 功能卡片数据类
 *
 * 定义首页每个功能入口的展示信息：
 * - title：卡片标题（粗体大字）
 * - desc：卡片描述（小字说明）
 * - icon：左侧 Material 图标
 * - navKey：点击后导航到的页面键
 */
data class FeatureCard(
    val title: String,       // 卡片标题
    val desc: String,        // 卡片描述文字
    val icon: ImageVector,   // Material 图标
    val navKey: NavKey       // 导航目标
)

/**
 * 功能卡片列表
 *
 * listOf 创建不可变列表，包含 6 个功能入口。
 * 新增页面时需要在此添加对应的 FeatureCard。
 */
val features = listOf(
    FeatureCard("数据填充", "选择 10w/20w/30w 批量填充数据库, 查看进度和行数", Icons.Default.DataObject, DataFill),
    FeatureCard("锁机制验证", "并发读写、锁阻塞、锁超时等场景演示", Icons.Default.Lock, LockMechanism),
    FeatureCard("Cursor 持有验证", "Cursor 遍历中并发修改, 验证快照隔离", Icons.Default.Search, CursorHolding),
    FeatureCard("WAL 并发对比", "WAL vs TRUNCATE 模式下的并发性能对比", Icons.Default.Speed, WalConcurrency),
    FeatureCard("连接池打满验证", "探测连接池容量上限, 观察打满后的行为", Icons.Default.Storage, ConnectionPool),
    FeatureCard("读DB锁阻塞写验证", "Cursor 不遍历到底持有 SHARED lock, 是否阻塞写操作", Icons.Default.Warning, ReadLock),
)

/**
 * 首页主组件
 *
 * LazyColumn 实现可滚动的功能卡片列表。
 * 相比 Column + verticalScroll，LazyColumn 只渲染可见区域的子项，性能更好。
 *
 * @param onItemClick 点击卡片回调，参数为目标页面的 NavKey
 * @param modifier 外部修饰符
 */
@Composable
fun MainScreen(
    onItemClick: (NavKey) -> Unit,  // 点击回调：(navKey) -> 将 navKey 添加到 backStack
    modifier: Modifier = Modifier,
) {
    // LazyColumn：懒加载垂直列表
    // item { ... } 和 items(count) { ... } 是 LazyListScope 的 DSL
    LazyColumn(modifier) {
        // item：添加单个条目（标题和副标题）
        item {
            Text(
                "SQLite 性能预研",
                style = MaterialTheme.typography.headlineMedium,  // Material 3 中等标题样式
                modifier = Modifier.padding(bottom = 8.dp)
            )
            Text(
                "数据库规模: 60 字段 × 10w+ 行 | 锁机制 · Cursor 快照 · WAL 对比",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,  // 使用主题的 onSurfaceVariant 色（次要文字色）
                modifier = Modifier.padding(bottom = 16.dp)
            )
        }

        // items(count)：批量添加 count 个条目（功能卡片）
        // idx 是索引，从 0 到 features.size - 1
        items(features.size) { idx ->
            FeatureCardItem(
                feature = features[idx],
                onClick = { onItemClick(features[idx].navKey) }  // 点击卡片时调用导航回调
            )
        }
    }
}

/**
 * 单个功能卡片组件
 *
 * 布局：[图标] [标题 + 描述] [箭头]
 * 使用 weight(1f) 让中间的文字区域占据剩余空间，箭头靠右对齐。
 *
 * @param feature 功能卡片数据
 * @param onClick 点击回调
 */
@Composable
fun FeatureCardItem(feature: FeatureCard, onClick: () -> Unit) {
    // Card：可点击的卡片容器
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 6.dp)     // 上下 6dp 间距
            .clickable(onClick = onClick),  // 点击事件：点击整个卡片触发导航
    ) {
        // Row：水平排列图标、文字、箭头
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),  // 卡片内边距 16dp
            verticalAlignment = Alignment.CenterVertically,  // 垂直居中对齐
        ) {
            // 左侧图标
            Icon(
                feature.icon,
                contentDescription = null,  // null = 装饰性图标，无障碍阅读器跳过
                modifier = Modifier.padding(end = 12.dp),
            )
            // 中间文字（标题 + 描述），weight(1f) 占据剩余空间
            Column(modifier = Modifier.weight(1f)) {
                Text(feature.title, style = MaterialTheme.typography.titleMedium)
                Text(
                    feature.desc,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            // 右侧箭头
            Icon(
                Icons.AutoMirrored.Filled.ArrowForward,  // 右箭头，AutoMirrored 适配 RTL 布局
                contentDescription = null,
            )
        }
    }
}

/**
 * 首页预览
 *
 * @Preview(showBackground = true)：在 Android Studio 中显示预览，带白色背景
 * 调用实际的 MainScreen Composable，传入空回调和 fillMaxSize 修饰符
 */
@Preview(showBackground = true)
@Composable
fun MainScreenPreview() {
    MainScreen({}, Modifier.fillMaxSize())
}
