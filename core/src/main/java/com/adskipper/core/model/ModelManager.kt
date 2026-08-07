package com.adskipper.core.model

import android.content.Context
import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import okhttp3.OkHttpClient
import okhttp3.Request
import timber.log.Timber
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * Manages GGUF model files under filesDir/models/<modelId>/.
 * Downloads try each source URL in order (ModelScope -> hf-mirror),
 * with HTTP Range resume on a `.part` file.
 */
class ModelManager(private val context: Context) {

    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .build()

    val modelsRoot: File get() = File(context.filesDir, "models")

    fun dirFor(model: ModelInfo): File = File(modelsRoot, model.id)

    fun isDownloaded(model: ModelInfo): Boolean =
        model.files.all { f -> File(dirFor(model), f.name).let { it.exists() && it.length() > 0 } }

    /** (modelPath, mmprojPath) if the model is fully downloaded. */
    fun modelPaths(model: ModelInfo): Pair<String, String>? {
        if (!isDownloaded(model)) return null
        val dir = dirFor(model)
        val modelPath = File(dir, model.modelFile.name).absolutePath
        val mmproj = model.mmprojFile ?: return null
        return modelPath to File(dir, mmproj.name).absolutePath
    }

    fun delete(model: ModelInfo) {
        dirFor(model).deleteRecursively()
    }

    sealed interface DownloadEvent {
        data class Progress(val file: String, val downloadedBytes: Long, val totalBytes: Long) : DownloadEvent
        object Done : DownloadEvent
        data class Error(val message: String) : DownloadEvent
    }

    fun download(model: ModelInfo): Flow<DownloadEvent> = flow {
        val dir = dirFor(model)
        dir.mkdirs()
        try {
            for (file in model.files) {
                downloadFile(file, dir) { event -> emit(event) }
            }
            emit(DownloadEvent.Done)
        } catch (e: Exception) {
            Timber.w(e, "download failed")
            emit(DownloadEvent.Error(e.message ?: e.javaClass.simpleName))
        }
    }.flowOn(Dispatchers.IO)

    private suspend fun downloadFile(
        file: ModelFile,
        dir: File,
        emit: suspend (DownloadEvent) -> Unit,
    ) {
        val target = File(dir, file.name)
        if (target.exists() && target.length() > 0) {
            emit(DownloadEvent.Progress(file.name, target.length(), target.length()))
            return
        }
        val part = File(dir, "${file.name}.part")
        var lastError: Exception? = null
        for (url in file.urls) {
            try {
                downloadFrom(url, part, emit.wrapFileName(file.name))
                part.renameTo(target)
                return
            } catch (e: Exception) {
                Timber.w("source failed: %s (%s)", url, e.message)
                lastError = e
            }
        }
        throw lastError ?: IllegalStateException("no source urls")
    }

    private fun (suspend (DownloadEvent) -> Unit).wrapFileName(name: String):
            suspend (DownloadEvent) -> Unit = { event ->
        when (event) {
            is DownloadEvent.Progress -> this@wrapFileName(
                DownloadEvent.Progress(name, event.downloadedBytes, event.totalBytes))
            else -> this@wrapFileName(event)
        }
    }

    private suspend fun downloadFrom(
        url: String,
        part: File,
        emit: suspend (DownloadEvent) -> Unit,
    ) {
        val existing = if (part.exists()) part.length() else 0L
        val request = Request.Builder().url(url).apply {
            if (existing > 0) header("Range", "bytes=$existing-")
        }.build()

        client.newCall(request).execute().use { response ->
            // 416 = range not satisfiable (file already complete); restart fresh.
            if (response.code == 416) {
                part.delete()
                return downloadFrom(url, part, emit)
            }
            if (!response.isSuccessful) error("HTTP ${response.code}")
            val body = response.body ?: error("empty body")
            // Server ignored Range and sent the whole file -> rewrite from scratch.
            val resume = existing > 0 && response.code == 206
            val total = body.contentLength().let { if (it > 0) (if (resume) it + existing else it) else -1L }
            var downloaded = if (resume) existing else 0L
            var lastEmit = 0L
            body.byteStream().use { input ->
                java.io.RandomAccessFile(part, "rw").use { raf ->
                    if (resume) raf.seek(existing) else raf.setLength(0)
                    val buf = ByteArray(256 * 1024)
                    while (true) {
                        val n = input.read(buf)
                        if (n < 0) break
                        raf.write(buf, 0, n)
                        downloaded += n
                        val now = System.nanoTime()
                        if (now - lastEmit > 300_000_000L) {
                            lastEmit = now
                            emit(DownloadEvent.Progress("", downloaded, total))
                        }
                    }
                }
            }
            emit(DownloadEvent.Progress("", downloaded, total))
        }
    }

    /** Import a user-picked file (SAF) into the custom model directory. */
    suspend fun importCustomFile(uri: Uri, destName: String): File = kotlinx.coroutines.withContext(Dispatchers.IO) {
        val dir = dirFor(ModelCatalog.custom)
        dir.mkdirs()
        val dest = File(dir, destName)
        context.contentResolver.openInputStream(uri)?.use { input ->
            dest.outputStream().use { output -> input.copyTo(output) }
        } ?: error("cannot open $uri")
        dest
    }
}
