package com.example.sqliteperfresearch

import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.navigation3.runtime.entryProvider
import androidx.navigation3.runtime.rememberNavBackStack
import androidx.navigation3.ui.NavDisplay
import com.example.sqliteperfresearch.ui.main.ConnectionPoolScreen
import com.example.sqliteperfresearch.ui.main.CursorHoldingScreen
import com.example.sqliteperfresearch.ui.main.DataFillScreen
import com.example.sqliteperfresearch.ui.main.LockMechanismScreen
import com.example.sqliteperfresearch.ui.main.MainScreen
import com.example.sqliteperfresearch.ui.main.ReadLockScreen
import com.example.sqliteperfresearch.ui.main.WalConcurrencyScreen

@Composable
fun MainNavigation() {
    val backStack = rememberNavBackStack(Main)

    NavDisplay(
        backStack = backStack,
        onBack = { backStack.removeLastOrNull() },
        entryProvider = entryProvider {
            entry<Main> {
                MainScreen(onItemClick = { navKey -> backStack.add(navKey) }, modifier = Modifier.safeDrawingPadding().padding(16.dp))
            }
            entry<DataFill> {
                DataFillScreen(modifier = Modifier.safeDrawingPadding().padding(16.dp))
            }
            entry<LockMechanism> {
                LockMechanismScreen(modifier = Modifier.safeDrawingPadding().padding(16.dp))
            }
            entry<CursorHolding> {
                CursorHoldingScreen(modifier = Modifier.safeDrawingPadding().padding(16.dp))
            }
            entry<WalConcurrency> {
                WalConcurrencyScreen(modifier = Modifier.safeDrawingPadding().padding(16.dp))
            }
            entry<ConnectionPool> {
                ConnectionPoolScreen(modifier = Modifier.safeDrawingPadding().padding(16.dp))
            }
            entry<ReadLock> {
                ReadLockScreen(modifier = Modifier.safeDrawingPadding().padding(16.dp))
            }
        },
    )
}
