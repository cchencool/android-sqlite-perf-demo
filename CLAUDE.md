# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## 项目概述

Android SQLite 性能预研 Demo。基于 Jetpack Compose，目标 SDK 36 (Android 16)。演示和基准测试 SQLite 在并发访问模式下的行为。

## 构建与运行

```bash
# 构建调试 APK
./gradlew assembleDebug

# 安装到设备/模拟器
adb install -r app/build/outputs/apk/debug/app-debug.apk

# 运行测试
./gradlew test

# 清理构建
./gradlew clean
```

## 技术栈

- **Target SDK**: 36 (Android 16)，**Min SDK**: 26
- **UI**: Jetpack Compose + Material 3
- **导航**: Nav3 (Navigation 3) 使用 `NavDisplay` + `entryProvider`
- **数据库**: `SQLiteOpenHelper`、`SQLiteDatabase`
- **协程**: kotlinx-coroutines 使用 `supervisorScope`、`async`、`Dispatchers.IO/Main`

## 架构

### 导航

- `NavigationKeys.kt` — `@Serializable data object` NavKey 定义（Main, DataFill, LockMechanism, CursorHolding, WalConcurrency, ConnectionPool, ReadLock）
- `Navigation.kt` — `NavDisplay` 配合 `entryProvider` 将每个 NavKey 映射到对应的页面 Composable
- 新增页面需要：(1) 在 NavigationKeys.kt 中新增 `@Serializable data object`，(2) 在 Navigation.kt 中新增 `entry<>()` 并 import，(3) 在 MainScreen.kt 中新增 FeatureCard

### 数据库层 (`database/`)

| 文件 | 职责 |
|------|------|
| `Schema.kt` | 60 列 `performance_test` 表定义（INTEGER, REAL, TEXT, BLOB, datetime 字段） |
| `PerfDatabase.kt` | `SQLiteOpenHelper` 子类。`setWalMode(Boolean)` / `getJournalMode()` / `getRowCount()` |
| `DataGenerator.kt` | 批量行生成（每批 1000 行），为 60 列生成随机数据 |
| `CursorExperiment.kt` | 读一致性测试：Cursor 遍历过程中执行 INSERT/UPDATE/DELETE，有无事务包裹对比（`beginTransaction`/`beginTransactionNonExclusive`/`beginTransactionReadOnly`） |
| `WalExperiment.kt` | WAL vs TRUNCATE 对比：并发读/写/混合读，以及完整事务模式 × 日志模式交叉对比 |
| `LockExperiment.kt` | 锁机制演示：并发读、读写阻塞、锁超时 |
| `ConnectionPoolExperiment.kt` | 连接池饱和检测，使用 PerfDatabase 共享连接池 |
| `ReadLockBlockingExperiment.kt` | Cursor 持有的 SHARED lock 阻塞 writer 的实验（TRUNCATE 模式） |

### UI 层 (`ui/main/`)

所有页面遵循相同模式：`LaunchedEffect` 初始化 DB + 实验实例，按钮在 `Dispatchers.IO` 上启动协程，结果输出到 `MutableStateList<ExperimentLog>` 和 logcat。

| 页面 | 职责 |
|------|------|
| `MainScreen.kt` | 首页：7 个功能入口卡片 |
| `DataFillScreen.kt` | 批量数据填充（10w/20w/30w 行，增量） |
| `LockMechanismScreen.kt` | 锁机制验证 |
| `CursorHoldingScreen.kt` | Cursor 持有 + 读一致性（3 种事务模式，WAL/TRUNCATE 切换开关） |
| `WalConcurrencyScreen.kt` | WAL vs TRUNCATE 对比（3 种场景 × 3 种事务模式） |
| `ConnectionPoolScreen.kt` | 连接池饱和测试 |
| `ReadLockScreen.kt` | 读锁阻塞写操作测试 |

### 关键模式

- **所有数据库访问使用 `PerfDatabase`（共享连接池）** — 除特定实验需要独立连接外，不使用 `SQLiteDatabase.openDatabase()`
- **WAL 模式通过 `enableWriteAheadLogging()` 设置** — 不使用 `PRAGMA journal_mode=WAL`（在 Android 连接池中不会持久化）
- **日志输出**：`addLog()` 同时写入 UI `LazyColumn` 和 logcat（`SQLitePerf.*` tag）
- **Preview**：每个页面都有 `@Preview`，调用实际的页面 Composable 并传入 `Modifier.fillMaxSize()`
