# Android SQLite 性能预研 Demo

用于预研 SQLite 在高数据规模下性能表现的 Android demo，基于 Jetpack Compose，目标 SDK 36 (Android 16)。

## 功能

### 数据填充
- 60 列批量插入，覆盖 INTEGER、REAL、TEXT、BLOB 等类型
- 每次选择填充 10w / 20w / 30w 行（增量累加）
- 实时进度条和行数显示

### 锁机制验证
- **并发读 ×5** — 演示共享锁允许多个读线程同时执行
- **读写并发** — 写事务持有排他锁，阻塞并发读
- **锁超时** — busy timeout 机制下的锁等待行为

### Cursor 持有验证
- Cursor 遍历过程中执行并发 INSERT / UPDATE / DELETE
- **WAL / TRUNCATE 切换开关** — 运行时切换 journal mode
- **普通遍历** — rawQuery 不包裹事务，CursorWindow 按需填充
- **事务包裹** — BEGIN/COMMIT 包裹整个遍历，锁定快照
- 验证并发写操作下的读一致性表现

### WAL 并发对比
- 两个独立数据库：WAL 模式 vs TRUNCATE 模式
- 并发读（10 线程）、读写混合（8 读 + 2 写）、并发写（10 线程）
- 对比总耗时、P50、P95 延迟

### 连接池打满验证
- 通过 rawQuery + moveToFirst 保持 active statement 占住连接池 slot
- 逐步打开连接探测饱和点
- 饱和后测试写操作是否被阻塞

### 读 DB 锁阻塞写验证
- Cursor 不遍历到底 → statement 保持 active → 持有 SHARED lock
- TRUNCATE 模式：writer 需要 EXCLUSIVE lock → 被阻塞
- WAL 模式：读写使用不同连接 → 互不阻塞

## 技术栈

- **Min SDK**: 26 (Android 8.0)
- **Target SDK**: 36 (Android 16)
- **UI**: Jetpack Compose + Material 3
- **导航**: Nav3 (Navigation 3)
- **数据库**: SQLiteOpenHelper
- **协程**: kotlinx-coroutines

## 构建与运行

```bash
./gradlew assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

## 关键发现

- `SQLiteDatabase.openDatabase()` 每次创建独立的连接池（Max connections: 1），互不竞争。真正的连接池由 `SQLiteOpenHelper` 管理，`readableDatabase` / `writableDatabase` 共享同一个池。
- 不使用事务包裹的 `rawQuery` 不保证快照隔离 — CursorWindow 分批次填充，后续批次能看到已提交的 INSERT，DELETE 会导致 CursorWindow 崩溃。
- 使用 `BEGIN / COMMIT` 包裹整个 Cursor 遍历可以锁定快照，INSERT 不可见，DELETE 不会导致崩溃。
- WAL 模式下读写使用不同连接，reader 不会阻塞 writer。

## License

MIT
