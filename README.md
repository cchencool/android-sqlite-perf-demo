# Android SQLite 性能预研 Demo

用于预研 SQLite 在高数据规模下性能表现的 Android demo，基于 Jetpack Compose，目标 SDK 36 (Android 16)。

## 功能

> **本 Demo App 的核心验证入口是 `CursorExperiment`（Cursor 持有验证）和 `WalExperiment`（WAL 并发对比）。**
> 其他功能页面（数据填充、锁机制验证、连接池打满、读锁阻塞）为辅助验证和补充实验。

### 数据填充
- 60 列批量插入，覆盖 INTEGER、REAL、TEXT、BLOB 等类型
- 每次选择填充 10w / 20w / 30w 行（增量累加）
- 实时进度条和行数显示

### 锁机制验证
- **并发读 ×5** — 演示共享锁允许多个读线程同时执行
- **读写并发** — 写事务持有排他锁，阻塞并发读
- **锁超时** — busy timeout 机制下的锁等待行为

### Cursor 持有验证

Cursor 遍历过程中执行并发 INSERT / UPDATE / DELETE，验证读一致性表现。

**两种实验模式：**

1. **普通遍历（无事务包裹）** — 使用 `CountDownLatch` 精确控制 Cursor 和 Writer 的执行时序：
   - Cursor 遍历到关键行（第 500 行）时暂停，通知 Writer 开始写
   - Writer 执行 INSERT / UPDATE / DELETE 并提交后，通知 Cursor 继续
   - 验证 Cursor 是否能看到写操作的结果

2. **事务包裹读** — 使用 `beginTransaction*` 包裹整个 Cursor 遍历，Reader 和 Writer 完全并发（无 CountDownLatch）：
   - **独占事务** (`beginTransaction`) — 立即获取 EXCLUSIVE lock，阻塞其他读写
   - **非独占事务** (`beginTransactionNonExclusive`) — WAL 下获取 SHARED lock，TRUNCATE 下等价于独占
   - **只读事务** (`beginTransactionReadOnly`) — 仅允许读，任何写操作抛异常

**三个子实验（每种模式下均执行）：**

- **INSERT 读一致性** — 在 Cursor 遍历中间插入新行，验证 Cursor 是否能看到新数据
- **UPDATE 读一致性** — 在 Cursor 到达目标行时更新该行，验证 Cursor 读到旧值还是新值
- **DELETE 读一致性** — 在 Cursor 到达目标行时删除该行，观察 Cursor 行为（读到已删除行、CursorWindow 崩溃、或未遍历到该行）

- **WAL / TRUNCATE 切换开关** — 运行时切换 journal mode，对比两种模式下的差异

### WAL 并发对比

使用两个独立数据库（WAL 模式 / TRUNCATE 模式），填充相同数据后执行相同的并发实验，对比两种日志模式的性能差异。

**实验准备：**
- 支持「新建两个 DB」从零填充，或「使用现有 DB」直接加载已有数据
- 两个 DB 并发填充相同行数，保证对比公平

**基础实验（3 个，无事务包裹）：**

1. **并发读** — 10 线程同时查询不同偏移范围的数据，对比读取耗时
2. **读写混合** — 8 读线程 + 2 写线程（写线程用事务包裹批量 UPDATE），对比 TRUNCATE 写阻塞读 vs WAL 读写不阻塞的差异
3. **并发写** — 10 线程同时写入（事务包裹 INSERT），TRUNCATE 下写操作互相阻塞（串行化），WAL 下并发度更高

**写阻塞读实验（事务模式 × 日志模式）：**
- 一个 writer 线程用指定事务模式批量插入 5000 行，writer 完成后故意 sleep 3 秒再提交（延长锁持有时间）
- 同时一个 reader 线程尝试查询，观察查询耗时是否接近写入耗时（判定是否被阻塞）
- 覆盖 2 种事务模式（独占 / 非独占，跳过只读）× 2 种日志模式 = 4 种组合

**完整事务模式对比（3 场景 × 3 事务模式 × 2 日志模式 = 18 种组合）：**
- 三种场景：并发读、并发写、读写混合（80% 读 + 20% 写）
- 三种事务模式：独占 (`beginTransaction`)、非独占 (`beginTransactionNonExclusive`)、只读 (`beginTransactionReadOnly`)
- 两种日志模式：WAL / TRUNCATE
- 使用原生 Thread + CountDownLatch 精确控制每个线程的事务生命周期
- 输出每种组合的总耗时、P50、P95、成功线程数、WAL 相对 TRUNCATE 的快/慢百分比

### 连接池打满验证
- 通过 rawQuery + moveToFirst 保持 active statement 占住连接池 slot
- 逐步打开连接探测饱和点
- 饱和后测试写操作是否被阻塞

### 读锁阻塞写验证

验证 Cursor 未遍历到底时，native SQLite statement 保持 active 持有 SHARED lock，是否阻塞写操作。

**核心原理：**
- `rawQuery` 返回 Cursor 后，native statement 保持 active 状态
- 如果 Cursor 没有遍历到底（未达到 `SQLITE_DONE`），statement 持有 SHARED lock
- SHARED lock 不阻塞其他 SHARED lock（多个 reader 可共存），但**阻塞 EXCLUSIVE lock**（writer 需要排他锁）
- 当 Cursor 遍历到底或 `close()` 时，statement 变为 `SQLITE_DONE`，释放 SHARED lock

**4 个测试场景（2×2 组合）：**

| 日志模式 | Cursor 行为 | 预期结果 |
|---------|------------|---------|
| TRUNCATE | 不遍历到底（只 moveToFirst） | 阻塞 — SHARED lock 阻塞 EXCLUSIVE lock |
| TRUNCATE | 遍历到底（完整遍历） | 不阻塞 — statement 已 SQLITE_DONE，锁已释放 |
| WAL | 不遍历到底 | 不阻塞 — 读写使用不同连接，互不阻塞 |
| WAL | 遍历到底 | 不阻塞 |

**实现细节：**
- 使用 `CountDownLatch` 确保 Reader 先就绪（SHARED lock 已持有），Writer 再开始
- 写入耗时超过 1 秒判定为被阻塞（正常 INSERT 应在几毫秒内完成）
- 必须使用同一个 `PerfDatabase` 实例（同一连接池），否则锁行为不会正确传递

## 技术栈

- **Min SDK**: 26 (Android 8.0)
- **Target SDK**: 36 (Android 16)
- **UI**: Jetpack Compose + Material 3
- **导航**: Nav3 (Navigation 3) 使用 `NavDisplay` + `entryProvider`
- **数据库**: SQLiteOpenHelper
- **协程**: kotlinx-coroutines
- **JVM**: Java 17 工具链

## 构建与运行

```bash
# 构建调试 APK
./gradlew assembleDebug

# 安装到设备/模拟器
adb install -r app/build/outputs/apk/debug/app-debug.apk

# 查看实验日志（logcat 过滤）
adb logcat -s SQLitePerf.*

# 清理构建
./gradlew clean
```

## 关键发现

- `SQLiteDatabase.openDatabase()` 每次创建独立的连接池（Max connections: 1），互不竞争。真正的连接池由 `SQLiteOpenHelper` 管理，`readableDatabase` / `writableDatabase` 共享同一个池。
- 不使用事务包裹的 `rawQuery` 不保证快照隔离 — CursorWindow 分批次填充，后续批次能看到已提交的 INSERT，DELETE 会导致 CursorWindow 崩溃。
- 使用 `BEGIN / COMMIT` 包裹整个 Cursor 遍历可以锁定快照，INSERT 不可见，DELETE 不会导致崩溃。
- WAL 模式下读写使用不同连接，reader 不会阻塞 writer。

## License

MIT
