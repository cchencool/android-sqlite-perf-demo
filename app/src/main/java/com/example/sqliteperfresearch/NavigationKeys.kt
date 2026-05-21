// ============================================================
// 导航键定义（Navigation Keys）
// ============================================================
// 职责：
// 1. 为每个页面定义唯一的导航标识（NavKey）
// 2. 使用 @Serializable data object 模式，Nav3 通过序列化标识路由到对应页面
// 3. 所有 NavKey 实现 NavKey 接口，被 Nav3 的 backStack 管理
//
// 新增页面流程：
//   1. 在此文件新增 @Serializable data object Xxx : NavKey
//   2. 在 Navigation.kt 新增 entry<Xxx> { XxxScreen(...) }
//   3. 在 MainScreen.kt 新增 FeatureCard 入口
// ============================================================

package com.example.sqliteperfresearch

import androidx.navigation3.runtime.NavKey  // Nav3 导航键接口，标记一个对象可作为路由目标
import kotlinx.serialization.Serializable   // Kotlinx 序列化注解，Nav3 用它持久化/恢复 backStack 状态

/**
 * 首页 — 所有功能入口的导航键
 *
 * @Serializable：让 data object 可被序列化，Nav3 在进程被杀后重建时能恢复 backStack。
 * data object：Kotlin 单例对象，无状态，适合作为路由标识。
 * : NavKey：实现 Nav3 的导航键接口，标记此对象可用于路由。
 */
@Serializable
data object Main : NavKey

/**
 * 数据填充页 — 批量向 performance_test 表插入 10w/20w/30w 行测试数据
 */
@Serializable
data object DataFill : NavKey

/**
 * 锁机制验证页 — 演示 SQLite 并发读、读写阻塞、锁超时行为
 */
@Serializable
data object LockMechanism : NavKey

/**
 * Cursor 持有验证页 — 遍历过程中执行 INSERT/UPDATE/DELETE，验证读一致性
 */
@Serializable
data object CursorHolding : NavKey

/**
 * WAL 并发对比页 — 对比 WAL 与 TRUNCATE 日志模式下的并发性能
 */
@Serializable
data object WalConcurrency : NavKey

/**
 * 连接池打满验证页 — 探测 SQLite 连接池最大连接数及饱和行为
 */
@Serializable
data object ConnectionPool : NavKey

/**
 * 读锁阻塞写验证页 — 验证 Cursor 持有 SHARED lock 是否阻塞 writer
 */
@Serializable
data object ReadLock : NavKey
