# Android SQLite Performance Research Demo

Android app for researching SQLite performance and behavior at scale. Built with Jetpack Compose, targeting API 36 (Android 16).

## Features

### Data Fill
- Batch insert with 60 columns covering INTEGER, REAL, TEXT, BLOB types
- Choose to insert 10w / 20w / 30w rows per operation (cumulative)
- Real-time progress display and row count

### Lock Mechanism
- **Concurrent Read ×5** — demonstrates shared lock allows multiple readers
- **Read-Write Blocking** — write transaction blocks concurrent readers
- **Lock Timeout** — busy timeout mechanism under lock contention

### Cursor Holding
- Cursor iteration with concurrent INSERT / UPDATE / DELETE
- **WAL/TRUNCATE toggle** — switch journal mode at runtime
- **Plain iteration** — rawQuery without transaction, CursorWindow fills on-demand
- **Transaction-wrapped** — BEGIN/COMMIT wraps entire iteration, locking a snapshot
- Validates read consistency behavior under concurrent writes

### WAL Concurrency
- Two independent databases: WAL vs TRUNCATE journal mode
- Concurrent read (10 threads), mixed read-write (8R+2W), concurrent write (10 threads)
- Measures total time, P50, P95 latency comparison

### Connection Pool
- Detects SQLite connection pool saturation by holding active statements
- Tests write blocking under pool saturation
- Compares WAL vs TRUNCATE behavior

### Read-Lock Blocking
- Cursor not iterated to end → statement stays active → holds SHARED lock
- TRUNCATE mode: writer needs EXCLUSIVE lock → blocked
- WAL mode: reader/writer use separate connections → not blocked

## Tech Stack

- **Min SDK**: 26 (Android 8.0)
- **Target SDK**: 36 (Android 16)
- **UI**: Jetpack Compose + Material 3
- **Navigation**: Nav3 (Navigation 3)
- **Database**: SQLiteOpenHelper
- **Coroutines**: kotlinx-coroutines

## Build & Run

```bash
./gradlew assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

## License

MIT
