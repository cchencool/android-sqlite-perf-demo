package com.example.sqliteperfresearch

import androidx.navigation3.runtime.NavKey
import kotlinx.serialization.Serializable

@Serializable data object Main : NavKey
@Serializable data object DataFill : NavKey
@Serializable data object LockMechanism : NavKey
@Serializable data object CursorHolding : NavKey
@Serializable data object WalConcurrency : NavKey
@Serializable data object ConnectionPool : NavKey
@Serializable data object ReadLock : NavKey
