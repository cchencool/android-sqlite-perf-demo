# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Project Overview

Android SQLite performance research demo. Built with Jetpack Compose, targeting API 36 (Android 16). Demonstrates and benchmarks SQLite behavior under concurrent access patterns.

## Build & Run

```bash
# Build debug APK
./gradlew assembleDebug

# Install to device/emulator
adb install -r app/build/outputs/apk/debug/app-debug.apk

# Run tests (if any exist)
./gradlew test

# Clean build
./gradlew clean
```

## Tech Stack

- **Target SDK**: 36 (Android 16), **Min SDK**: 26
- **UI**: Jetpack Compose + Material 3
- **Navigation**: Nav3 (Navigation 3) with `NavDisplay` + `entryProvider`
- **Database**: `SQLiteOpenHelper`, `SQLiteDatabase`
- **Coroutines**: kotlinx-coroutines with `supervisorScope`, `async`, `Dispatchers.IO/Main`

## Architecture

### Navigation
- `NavigationKeys.kt` — `@Serializable data object` NavKey definitions (Main, DataFill, LockMechanism, CursorHolding, WalConcurrency, ConnectionPool, ReadLock)
- `Navigation.kt` — `NavDisplay` with `entryProvider` mapping each NavKey to its screen Composable
- Adding a new screen requires: (1) new `@Serializable data object` in NavigationKeys.kt, (2) new `entry<>()` in Navigation.kt with import, (3) new FeatureCard in MainScreen.kt

### Database Layer (`database/`)

| File | Purpose |
|------|---------|
| `Schema.kt` | 60-column `performance_test` table (INTEGER, REAL, TEXT, BLOB, datetime fields) |
| `PerfDatabase.kt` | `SQLiteOpenHelper` subclass. `setWalMode(Boolean)` / `getJournalMode()` / `getRowCount()` |
| `DataGenerator.kt` | Batch row generation (1000/batch) with randomized data for all 60 columns |
| `CursorExperiment.kt` | Read consistency tests: INSERT/UPDATE/DELETE during cursor traversal, with and without transaction wrapping (`beginTransaction`/`beginTransactionNonExclusive`/`beginTransactionReadOnly`) |
| `WalExperiment.kt` | WAL vs TRUNCATE comparison: concurrent read/write/mixed-read, plus full tx-mode comparison across both journal modes |
| `LockExperiment.kt` | Lock mechanism demos: concurrent reads, read-write blocking, lock timeout |
| `ConnectionPoolExperiment.kt` | Connection pool saturation detection using shared PerfDatabase pool |
| `ReadLockBlockingExperiment.kt` | Cursor-held SHARED lock blocking writer in TRUNCATE mode |

### UI Layer (`ui/main/`)

All screens follow the same pattern: `LaunchedEffect` initializes DB + experiment instance, buttons launch coroutines on `Dispatchers.IO`, results go to `MutableStateList<ExperimentLog>` + logcat.

| Screen | Purpose |
|--------|---------|
| `MainScreen.kt` | Home: 7 feature entry cards |
| `DataFillScreen.kt` | Batch data fill (10w/20w/30w rows, incremental) |
| `LockMechanismScreen.kt` | Lock mechanism verification |
| `CursorHoldingScreen.kt` | Cursor holding + read consistency (3 tx modes, WAL/TRUNCATE toggle) |
| `WalConcurrencyScreen.kt` | WAL vs TRUNCATE comparison (3 scenarios × 3 tx modes) |
| `ConnectionPoolScreen.kt` | Connection pool saturation test |
| `ReadLockScreen.kt` | Read-lock blocking writer test |

### Key Patterns

- **All database access uses `PerfDatabase` (shared connection pool)** — never `SQLiteDatabase.openDatabase()` except in specific experiments that need independent connections
- **WAL mode is set via `enableWriteAheadLogging()`** — never `PRAGMA journal_mode=WAL` (doesn't persist in Android's connection pool)
- **Log output**: `addLog()` writes to UI `LazyColumn` AND logcat (`SQLitePerf.*` tags)
- **Preview**: Each screen has `@Preview` calling the actual screen Composable with `Modifier.fillMaxSize()`
