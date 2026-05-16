你这个问题已经触到 SQLite rollback journal/TRUNCATE 模式最容易被误解的地方了。

你理解中的逻辑：

```txt
reader 持有 SHARED lock
→ writer 无法 COMMIT
→ 因此 reader 一定读一致
```

这个逻辑 **只对“一直持有 active read transaction”成立**。

而你现在实验里真正发生的事情，很可能是：

> Cursor 虽然还活着，但 SQLite 的 read transaction / SHARED lock 已经提前释放了。

这就是关键。

---

# 一、先给结论（最核心）

在 Android 的：

CursorWindow

机制下：

> Cursor 生命周期 ≠ SQLite read transaction 生命周期
> Cursor 活着 ≠ 一定还持有 SHARED lock

所以：

* DELETE 能 commit
* reader 后续 refill window 时
* 数据已经变了
* 最终 CursorWindow 崩

这是完全可能的。

---

# 二、你原来的理解只适用于“active statement 未结束”

SQLite rollback journal（TRUNCATE）确实：

## reader 持有 SHARED lock 时：

writer：

* 可以 RESERVED
* 可以写 journal
* 但 COMMIT 需要 EXCLUSIVE
* 会被 reader 阻塞

这没错。

---

# 三、但 Android Cursor 有一个“非常坑”的优化

Android query 流程不是：

```txt
Cursor 活着
= statement 一直活着
```

而是：

```txt
query
  ↓
fill CursorWindow
  ↓
statement 可能 finalize
  ↓
Cursor 继续用 window 数据
```

也就是说：

## 可能发生：

```txt
Cursor 还没 close
但 read transaction 已结束
```

---

# 四、于是你看到的现象就成立了

你实验里很可能：

---

## Step1

reader：

```kotlin
SELECT * FROM huge_table
```

SQLite fill 了一批 rows 到 CursorWindow。

---

## Step2

statement 已 SQLITE_DONE 或 connection 被释放。

于是：

```txt
SHARED lock 释放
```

---

## Step3

writer：

```sql
DELETE FROM huge_table
COMMIT
```

成功。

因为：

```txt
已经没有 active reader lock
```

---

## Step4

reader 继续 moveToNext()

发现：

```txt
需要 refill CursorWindow
```

于是：

* 尝试重新 step
* 但 row source 已变化
* cursor/window position mismatch

于是：

```txt
Couldn't read row ...
```

---

# 五、为什么“看起来像读一致性失效”

因为 Android Cursor 不是严格 snapshot cursor。

它实际上是：

```txt
CursorWindow cache + lazy refill
```

而不是：

```txt
完整 materialized snapshot
```

---

# 六、SQLite 本身并没有承诺“Cursor 永久一致”

SQLite rollback journal 模式保证的是：

## active read transaction 一致性

不是：

```txt
Java Cursor 对象生命周期一致性
```

这俩不是一回事。

---

# 七、真正决定一致性的，是 statement 是否还 active

核心判断：

```c
sqlite3_step() 是否还能继续
```

只要：

```txt
statement finalized
```

那么：

```txt
SHARED lock 就没了
```

即使：

```txt
Cursor Java对象还活着
```

---

# 八、为什么 WAL 不会这样

WAL 下：

reader 打开时：

```txt
固定 snapshot version
```

后续 refill window：

仍从：

```txt
旧 WAL snapshot
```

读取。

所以：

✔ 不会 cursor/window mismatch
✔ 不会读一致性破坏

---

# 九、你实验里真正暴露的问题

你其实已经测出了 Android SQLite 一个非常重要的现实：

> Android Cursor 的“可继续遍历能力”，并不等价于 SQLite rollback journal 的事务级 snapshot。

这是 Android CursorWindow 机制导致的。

---

# 十、如何验证“SHARED lock 已释放”

你可以做这个实验。

---

## reader

```kotlin
val c = db.rawQuery("SELECT * FROM t", null)

repeat(1000) {
    c.moveToNext()
}
```

然后：

```kotlin
Thread.sleep(60_000)
```

---

## writer

```kotlin
DELETE FROM t
```

如果：

* DELETE commit 成功
* reader 后续 moveToNext 崩

说明：

✔ reader lock 已释放
✔ Cursor 只是 window cache

---

# 十一、为什么“小结果集”不容易复现

因为：

```txt
一次性 materialize 完
```

后续：

```txt
不需要 refill window
```

于是：

即使 DELETE commit：

reader 仍读的是 window cache。

看起来：

```txt
一致性正常
```

---

# 十二、一句话总结（核心中的核心）

你看到的现象并不违反 SQLite TRUNCATE 模式规则。

真正发生的是：

> Android Cursor 在部分数据 materialize 到 CursorWindow 后，底层 sqlite statement/read transaction 已提前结束，SHARED lock 已释放；随后 writer 成功 commit，reader 后续 refill CursorWindow 时发现底层 row source 已变化，于是出现 CursorWindow 崩溃。
