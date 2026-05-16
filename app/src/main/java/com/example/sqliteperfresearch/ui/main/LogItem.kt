package com.example.sqliteperfresearch.ui.main

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.example.sqliteperfresearch.model.ExperimentLog
import com.example.sqliteperfresearch.model.LogType

@Composable
fun LogItem(log: ExperimentLog) {
    val borderColor = when (log.type) {
        LogType.SUCCESS -> Color(0xFF4CAF50)
        LogType.WARNING -> Color(0xFFFF9800)
        LogType.ERROR -> Color(0xFFF44336)
        LogType.INFO -> Color(0xFF2196F3)
    }
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 2.dp),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(borderColor.copy(alpha = 0.1f))
                .padding(8.dp),
        ) {
            Text(
                log.timestamp,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(end = 8.dp),
            )
            Text(
                "[${log.phase}] ",
                style = MaterialTheme.typography.labelSmall,
                color = borderColor,
                modifier = Modifier.padding(end = 4.dp),
            )
            Text(log.message, style = MaterialTheme.typography.bodySmall)
        }
    }
}
