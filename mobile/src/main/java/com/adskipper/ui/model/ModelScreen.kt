package com.adskipper.ui.model

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.adskipper.AdSkipperApp
import com.adskipper.core.data.AppSettings
import com.adskipper.core.model.ModelCatalog
import com.adskipper.core.model.ModelInfo
import com.adskipper.core.model.ModelManager
import kotlinx.coroutines.launch

@Composable
fun ModelScreen() {
    val context = LocalContext.current
    val app = AdSkipperApp.get(context)
    val settings by app.settingsRepo.settings.collectAsState(initial = AppSettings())
    val scope = rememberCoroutineScope()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("模型", style = MaterialTheme.typography.headlineMedium)
        Text(
            "模型从 ModelScope 下载（国内可直接访问），失败时自动回退 hf-mirror。也可以手动导入本地 GGUF。",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        ModelCatalog.all.forEach { model ->
            ModelCard(
                model = model,
                active = settings.activeModelId == model.id ||
                    (settings.activeModelId == null && model.id == ModelCatalog.default.id),
                manager = app.modelManager,
                onActivate = { scope.launch { app.settingsRepo.setActiveModelId(model.id) } },
            )
        }
    }
}

@Composable
private fun ModelCard(
    model: ModelInfo,
    active: Boolean,
    manager: ModelManager,
    onActivate: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    // Re-check download state whenever the model changes.
    var downloaded by remember(model.id) { mutableStateOf(manager.isDownloaded(model)) }
    var progress by remember { mutableStateOf<ModelManager.DownloadEvent.Progress?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var downloading by remember { mutableStateOf(false) }

    val context = LocalContext.current
    val pickModel = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let { scope.launch { manager.importCustomFile(it, "model.gguf"); downloaded = manager.isDownloaded(model) } }
    }
    val pickMmproj = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let { scope.launch { manager.importCustomFile(it, "mmproj.gguf"); downloaded = manager.isDownloaded(model) } }
    }

    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                RadioButton(
                    selected = active,
                    onClick = onActivate,
                    enabled = downloaded,
                )
                Column {
                    Text(model.displayName, style = MaterialTheme.typography.titleMedium)
                    Text(model.description, style = MaterialTheme.typography.bodySmall)
                }
            }

            if (downloading) {
                val p = progress
                LinearProgressIndicator(
                    progress = {
                        if (p != null && p.totalBytes > 0)
                            p.downloadedBytes.toFloat() / p.totalBytes else 0f
                    },
                    modifier = Modifier.fillMaxWidth(),
                )
                Text(
                    if (p != null && p.totalBytes > 0)
                        "${p.file}  %.1f MB / %.1f MB".format(
                            p.downloadedBytes / 1048576f, p.totalBytes / 1048576f)
                    else "下载中…",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            error?.let { Text("下载失败：$it", color = MaterialTheme.colorScheme.error) }

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (model.id == ModelCatalog.custom.id) {
                    OutlinedButton(onClick = { pickModel.launch(arrayOf("*/*")) }) {
                        Text("导入模型 GGUF")
                    }
                    OutlinedButton(onClick = { pickMmproj.launch(arrayOf("*/*")) }) {
                        Text("导入 mmproj GGUF")
                    }
                } else if (!downloaded && !downloading) {
                    Button(onClick = {
                        downloading = true
                        error = null
                        scope.launch {
                            manager.download(model).collect { event ->
                                when (event) {
                                    is ModelManager.DownloadEvent.Progress -> progress = event
                                    is ModelManager.DownloadEvent.Done -> {
                                        downloading = false
                                        downloaded = true
                                    }
                                    is ModelManager.DownloadEvent.Error -> {
                                        downloading = false
                                        error = event.message
                                    }
                                }
                            }
                        }
                    }) { Text("下载") }
                }
                if (downloaded && !downloading) {
                    Text("已下载", color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.align(Alignment.CenterVertically))
                    if (model.id != ModelCatalog.custom.id) {
                        OutlinedButton(onClick = {
                            manager.delete(model)
                            downloaded = false
                        }) { Text("删除") }
                    }
                }
            }
        }
    }
}
