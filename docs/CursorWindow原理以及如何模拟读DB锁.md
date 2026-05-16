在 Android 的：

SQLiteDatabase

里，“结果是否已经全部 materialize 到 CursorWindow” 本质上是：

> SQLite native statement 是否还需要继续 `sqlite3_step()` 从数据库取数据。

如果已经全部取完：

* statement 会被 close/finalize
* read lock 释放
* 即使 Java Cursor 还没 close
* writer commit 也可能成功

这是很多人容易误判的点。

---

# 一、CursorWindow 的工作机制

Android Cursor 并不是一次性全读。

通常：

```java
Cursor c = db.rawQuery(...)
```

实际过程：

1. 创建 sqlite statement
2. 创建 CursorWindow
3. 先填充一部分行
4. Java 层按需继续 fetch

即：

```txt
SQLite DB
   ↓ step()
CursorWindow
   ↓
Java Cursor
```

---

# 二、什么时候算“已经全部 materialize”

核心判断：

## native statement 是否已经 exhausted

即：

```c
sqlite3_step() == SQLITE_DONE
```

一旦 DONE：

* statement finalize
* read txn 结束
* SHARED lock 释放

---

# 三、Android 上的典型行为

## 小结果集

例如：

```sql
SELECT * FROM t LIMIT 20
```

很可能：

* 一次 fillWindow 全装下
* statement 已 DONE
* Cursor 虽未 close
* lock 已释放

---

## 大结果集

例如：

```sql
SELECT * FROM huge_table
```

CursorWindow 放不下：

* statement 保持 active
* 后续 moveToNext() 时继续 fetch
* read lock 持续存在

---

# 四、如何“判断”

Android 没公开 API 直接告诉你：

> statement 是否已经 exhausted

但可以通过行为间接判断。

---

# 方法1：观察 writer commit 是否阻塞（最靠谱）

开两个线程：

## reader

```java
Cursor c = db.rawQuery("select * from t", null);

c.moveToFirst();

// 不继续读
Thread.sleep(60000);
```

## writer

```java
db.beginTransaction();
db.execSQL("insert into t values(...)");
db.setTransactionSuccessful();
db.endTransaction();
```

如果：

* endTransaction 卡住
* 或 SQLITE_BUSY

说明：

```txt
Cursor 仍持有 active statement
```

如果立即成功：

说明：

```txt
结果已全部 materialize
statement 已 DONE
```

---

# 方法2：控制 CursorWindow 大小观察

Android CursorWindow 默认约：

```txt
2MB
```

（新版可能更大）

如果：

```sql
SELECT id FROM t
```

只有一列 int。

几万行都能一次装下。

如果：

```sql
SELECT big_blob FROM t
```

几行就放不下。

---

所以：

## 能否一次装下结果

决定：

* statement 是否继续存活
* read lock 是否持续

---

# 方法3：强制遍历到底

这是最简单模拟方式。

## 不遍历到底

通常 lock 保持：

```java
Cursor c = db.rawQuery(...);
c.moveToFirst();

sleep();
```

---

## 遍历到底

通常 lock 已释放：

```java
while (c.moveToNext()) {
}
```

因为：

最后会 fetch 到：

```txt
SQLITE_DONE
```

statement finalize。

即使 Cursor 不 close：

writer 往往已能 commit。

---

# 五、如何“模拟避免该情况”

你说的应该是：

> 如何避免 reader 长期阻塞 writer

有几个办法。

---

# 方案1（推荐）：WAL

根本解决：

```sql
PRAGMA journal_mode=WAL;
```

这是唯一真正并发方案。

---

# 方案2：主动 materialize 全结果

例如：

```java
List<Item> all = new ArrayList<>();

Cursor c = ...

while (c.moveToNext()) {
    all.add(...)
}

c.close();
```

之后只操作 Java 内存。

这样：

* statement 很快 DONE
* lock 很快释放

这是最常见方案。

---

# 方案3：使用小结果集

例如：

```sql
LIMIT 100
```

避免大 Cursor。

---

# 方案4：避免 lazy iteration

很多代码：

```java
return cursor;
```

让调用方慢慢读。

这是最容易长期占锁的。

改成：

```java
List<Data> loadAll()
```

更安全。

---

# 六、一个非常关键的 Android 坑

## CursorWindow 不等于全部结果

很多人误以为：

```txt
CursorWindow filled
= query finished
```

错误。

实际上：

CursorWindow 只是：

```txt
当前缓存页
```

只有：

```txt
sqlite3_step -> SQLITE_DONE
```

才是真正结束 reader txn。

---

# 七、如何稳定复现“commit 被阻塞”

你可以这样：

## 建大表

```sql
CREATE TABLE t(
    id INTEGER,
    data TEXT
);
```

插入几十万行。

---

## reader

```java
Cursor c = db.rawQuery("select * from t", null);

c.moveToFirst();

Thread.sleep(60000);
```

---

## writer

```java
db.beginTransaction();
db.execSQL("insert into t values(1,'x')");
db.setTransactionSuccessful();
db.endTransaction();
```

在：

```sql
PRAGMA journal_mode=TRUNCATE;
```

下：

大概率：

```txt
endTransaction 卡住
```

---

# 八、一句话本质

在 rollback journal（TRUNCATE/DELETE）模式下：

> 是否阻塞 writer，不取决于 Cursor 是否 alive，而取决于 native sqlite statement 是否已经 SQLITE_DONE。

