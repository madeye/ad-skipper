package com.adskipper.ui.home

import android.content.Context
import android.content.Intent
import android.provider.Settings
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.adskipper.AdSkipperApp
import com.adskipper.core.data.AppSettings
import com.adskipper.ui.TestAdActivity
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

private fun isAccessibilityEnabled(context: Context): Boolean {
    val enabled = Settings.Secure.getString(
        context.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES) ?: return false
    return enabled.contains(context.packageName, ignoreCase = true)
}

@Composable
fun HomeScreen() {
    val context = LocalContext.current
    val app = AdSkipperApp.get(context)
    val settings by app.settingsRepo.settings
        .collectAsState(initial = AppSettings())
    val scope = rememberCoroutineScope()

    // Poll accessibility status while this screen is visible.
    val serviceEnabled by produceState(initialValue = isAccessibilityEnabled(context)) {
        while (true) {
            value = isAccessibilityEnabled(context)
            delay(1000)
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("广告跳过", style = MaterialTheme.typography.headlineMedium)

        Card(modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text(
                    if (serviceEnabled) "无障碍服务：已开启" else "无障碍服务：未开启",
                    style = MaterialTheme.typography.titleMedium,
                    color = if (serviceEnabled) MaterialTheme.colorScheme.primary
                    else MaterialTheme.colorScheme.error,
                )
                Spacer(Modifier.height(8.dp))
                if (!serviceEnabled) {
                    Button(onClick = {
                        context.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
                    }) { Text("去开启") }
                }
            }
        }

        Card(modifier = Modifier.fillMaxWidth()) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("自动跳过广告", style = MaterialTheme.typography.titleMedium)
                Switch(
                    checked = settings.masterEnabled,
                    onCheckedChange = { v -> scope.launch { app.settingsRepo.setMasterEnabled(v) } },
                )
            }
        }

        OutlinedButton(
            onClick = { context.startActivity(Intent(context, TestAdActivity::class.java)) },
            modifier = Modifier.fillMaxWidth(),
        ) { Text("模拟广告测试") }

        Text(
            "提示：模拟测试需要在设置页打开「自测模式」，并把 L1 节点匹配保持开启。",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
