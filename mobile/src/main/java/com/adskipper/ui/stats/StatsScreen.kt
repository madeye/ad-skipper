package com.adskipper.ui.stats

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.adskipper.AdSkipperApp
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun StatsScreen() {
    val context = LocalContext.current
    val app = AdSkipperApp.get(context)

    val total by app.statsRepo.totalCount.collectAsState(initial = 0)
    val byLayer by app.statsRepo.countByLayer.collectAsState(initial = emptyList())
    val byPackage by app.statsRepo.countByPackage.collectAsState(initial = emptyList())
    val recent by app.statsRepo.recent.collectAsState(initial = emptyList())

    val timeFormat = remember { SimpleDateFormat("MM-dd HH:mm:ss", Locale.getDefault()) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("统计", style = MaterialTheme.typography.headlineMedium)

        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp)) {
                Text("累计跳过：$total 次", style = MaterialTheme.typography.titleMedium)
            }
        }

        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text("按层级", style = MaterialTheme.typography.titleMedium)
                if (byLayer.isEmpty()) Text("暂无数据", style = MaterialTheme.typography.bodySmall)
                byLayer.forEach { (layer, cnt) ->
                    Text("$layer：$cnt 次", style = MaterialTheme.typography.bodyMedium)
                }
            }
        }

        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text("按应用", style = MaterialTheme.typography.titleMedium)
                if (byPackage.isEmpty()) Text("暂无数据", style = MaterialTheme.typography.bodySmall)
                byPackage.take(10).forEach { (pkg, cnt) ->
                    Text("$pkg：$cnt 次", style = MaterialTheme.typography.bodySmall)
                }
            }
        }

        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text("最近记录", style = MaterialTheme.typography.titleMedium)
                if (recent.isEmpty()) Text("暂无数据", style = MaterialTheme.typography.bodySmall)
                recent.forEach { r ->
                    Row {
                        Text(
                            timeFormat.format(Date(r.timestamp)),
                            style = MaterialTheme.typography.bodySmall,
                        )
                        Spacer(Modifier.width(8.dp))
                        Text(
                            "${r.packageName}  ${r.layer}  ${r.elapsedMs}ms",
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                }
            }
        }
    }
}
