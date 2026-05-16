package com.example.sqliteperfresearch.ui.main

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.DataObject
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material.icons.filled.Storage
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Card
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.navigation3.runtime.NavKey
import com.example.sqliteperfresearch.ConnectionPool
import com.example.sqliteperfresearch.CursorHolding
import com.example.sqliteperfresearch.DataFill
import com.example.sqliteperfresearch.LockMechanism
import com.example.sqliteperfresearch.ReadLock
import com.example.sqliteperfresearch.WalConcurrency

data class FeatureCard(val title: String, val desc: String, val icon: ImageVector, val navKey: NavKey)

val features = listOf(
    FeatureCard("数据填充", "选择 10w/20w/30w 批量填充数据库, 查看进度和行数", Icons.Default.DataObject, DataFill),
    FeatureCard("锁机制验证", "并发读写、锁阻塞、锁超时等场景演示", Icons.Default.Lock, LockMechanism),
    FeatureCard("Cursor 持有验证", "Cursor 遍历中并发修改, 验证快照隔离", Icons.Default.Search, CursorHolding),
    FeatureCard("WAL 并发对比", "WAL vs TRUNCATE 模式下的并发性能对比", Icons.Default.Speed, WalConcurrency),
    FeatureCard("连接池打满验证", "探测连接池容量上限, 观察打满后的行为", Icons.Default.Storage, ConnectionPool),
    FeatureCard("读DB锁阻塞写验证", "Cursor 不遍历到底持有 SHARED lock, 是否阻塞写操作", Icons.Default.Warning, ReadLock),
)

@Composable
fun MainScreen(
    onItemClick: (NavKey) -> Unit,
    modifier: Modifier = Modifier,
) {
    LazyColumn(modifier) {
        item {
            Text(
                "SQLite 性能预研",
                style = MaterialTheme.typography.headlineMedium,
                modifier = Modifier.padding(bottom = 8.dp)
            )
            Text(
                "数据库规模: 60 字段 × 10w+ 行 | 锁机制 · Cursor 快照 · WAL 对比",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(bottom = 16.dp)
            )
        }
        items(features.size) { idx ->
            FeatureCardItem(feature = features[idx], onClick = { onItemClick(features[idx].navKey) })
        }
    }
}

@Composable
fun FeatureCardItem(feature: FeatureCard, onClick: () -> Unit) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 6.dp)
            .clickable(onClick = onClick),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(feature.icon, contentDescription = null, modifier = Modifier.padding(end = 12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(feature.title, style = MaterialTheme.typography.titleMedium)
                Text(feature.desc, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Icon(Icons.AutoMirrored.Filled.ArrowForward, contentDescription = null)
        }
    }
}

@Preview(showBackground = true)
@Composable
fun MainScreenPreview() {
    Column { features.forEach { FeatureCardItem(it, {}) } }
}
