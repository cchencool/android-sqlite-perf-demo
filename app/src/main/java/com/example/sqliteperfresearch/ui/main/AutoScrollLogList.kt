package com.example.sqliteperfresearch.ui.main

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.example.sqliteperfresearch.model.ExperimentLog

@Composable
fun AutoScrollLogList(
    logs: List<ExperimentLog>,
    modifier: Modifier = Modifier,
) {
    val scrollState = rememberScrollState()

    LaunchedEffect(logs.size) {
        if (logs.isNotEmpty()) {
            scrollState.animateScrollTo(scrollState.maxValue)
        }
    }

    Column(
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = 300.dp, max = 400.dp)
            .verticalScroll(scrollState),
    ) {
        logs.forEach { log -> LogItem(log) }
    }
}
