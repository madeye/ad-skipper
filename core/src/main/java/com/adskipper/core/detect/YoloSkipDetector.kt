package com.adskipper.core.detect

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Rect
import timber.log.Timber
import java.io.File
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * L3 fast path: bundled YOLO11n skip-button detector running on ncnn
 * (Vulkan compute, CPU/NEON fallback).
 *
 * The model is the official Ultralytics NCNN export of the trained skip_v2
 * weights (assets/yolo.ncnn.param + yolo.ncnn.bin, fp16). Validated against
 * the original weights: mAP50 0.992 / P 0.98 / R 0.972 on the 240-image val
 * set. ncnn is used instead of ggml-vulkan or the TFLite GPU delegate because
 * its Vulkan backend carries the mobile (Adreno/Mali) driver workarounds and
 * ships its own loader, so it degrades cleanly to NEON when Vulkan is broken.
 *
 * All native work is confined to a single thread so the Vulkan device/queue
 * is used consistently.
 */
class YoloSkipDetector(private val context: Context) {

    private val executor = Executors.newSingleThreadExecutor { r -> Thread(r, "yolo-skip") }

    @Volatile
    var backend: String = "uninitialized"
        private set

    private var initTried = false
    private var initOk = false

    /** Asset presence is the readiness signal; the native engine is built
     *  lazily on the executor thread at first use. */
    val isReady: Boolean = try {
        context.assets.openFd(BIN_ASSET).use { true }
    } catch (t: Throwable) {
        // openFd throws for compressed assets; fall back to a stream probe.
        try { context.assets.open(BIN_ASSET).use { true } } catch (e: Throwable) {
            Timber.e(e, "YOLO assets missing"); false
        }
    }

    fun findSkipButton(bitmap: Bitmap): Rect? {
        if (!isReady) return null
        return try {
            executor.submit(Callable { detect(bitmap) }).get(15, TimeUnit.SECONDS)
        } catch (t: Throwable) {
            Timber.e(t, "YOLO detection failed")
            null
        }
    }

    /** Build the native engine ahead of the first splash: cold init (Vulkan
     *  device + model load) takes ~3s on a flagship, which would otherwise be
     *  paid inside the first splash window after service (re)start. */
    fun warmUp() {
        if (!isReady) return
        executor.execute { ensureInit() }
    }

    // ---- executor thread only ----

    private fun detect(bitmap: Bitmap): Rect? {
        if (!ensureInit()) return null
        val scale = IN_SIZE.toFloat() / maxOf(bitmap.width, bitmap.height)
        val w = (bitmap.width * scale).toInt().coerceAtLeast(1)
        val h = (bitmap.height * scale).toInt().coerceAtLeast(1)
        val padX = (IN_SIZE - w) / 2f
        val padY = (IN_SIZE - h) / 2f

        val canvasBmp = Bitmap.createBitmap(IN_SIZE, IN_SIZE, Bitmap.Config.ARGB_8888)
        Canvas(canvasBmp).apply {
            drawColor(Color.rgb(114, 114, 114))
            drawBitmap(Bitmap.createScaledBitmap(bitmap, w, h, true), padX, padY, null)
        }

        val out = YoloNative.nativeInfer(canvasBmp) ?: return null
        // out layout: index = channel*ANCHORS + anchor; channels 0..3 = xywh
        // (640px letterbox coords), channel 4 = class score (already sigmoid).
        var best = -1f
        var bi = -1
        val scoreBase = 4 * ANCHORS
        for (a in 0 until ANCHORS) {
            val s = out[scoreBase + a]
            if (s > best) { best = s; bi = a }
        }
        if (best < CONF_THRESHOLD || bi < 0) return null

        val cx = (out[bi] - padX) / scale
        val cy = (out[ANCHORS + bi] - padY) / scale
        val bw = out[2 * ANCHORS + bi] / scale
        val bh = out[3 * ANCHORS + bi] / scale
        Timber.d("YOLO hit conf=%.2f at (%.0f, %.0f) [%s]", best, cx, cy, backend)
        return Rect(
            (cx - bw / 2f).toInt().coerceIn(0, bitmap.width),
            (cy - bh / 2f).toInt().coerceIn(0, bitmap.height),
            (cx + bw / 2f).toInt().coerceIn(0, bitmap.width),
            (cy + bh / 2f).toInt().coerceIn(0, bitmap.height),
        )
    }

    private fun ensureInit(): Boolean {
        if (initTried) return initOk
        initTried = true
        val param = extractAsset(PARAM_ASSET)
        val bin = extractAsset(BIN_ASSET)
        if (param == null || bin == null) { backend = "error"; return false }
        backend = try {
            YoloNative.nativeInit(param.absolutePath, bin.absolutePath)
        } catch (t: Throwable) {
            Timber.e(t, "nativeInit crashed"); "error"
        }
        initOk = backend != "error"
        Timber.i("YOLO ncnn engine: %s", backend)
        return initOk
    }

    /** Copy an asset into filesDir (skip if already present with same size). */
    private fun extractAsset(name: String): File? = try {
        val dir = File(context.filesDir, "yolo").apply { mkdirs() }
        val dest = File(dir, name)
        val size = context.assets.openFd(name).use { it.length }
        if (!dest.exists() || dest.length() != size) {
            context.assets.open(name).use { input ->
                dest.outputStream().use { input.copyTo(it) }
            }
        }
        dest
    } catch (t: Throwable) {
        // Compressed asset: openFd fails; copy without the size check.
        try {
            val dir = File(context.filesDir, "yolo").apply { mkdirs() }
            val dest = File(dir, name)
            if (!dest.exists()) {
                context.assets.open(name).use { input ->
                    dest.outputStream().use { input.copyTo(it) }
                }
            }
            dest
        } catch (e: Throwable) {
            Timber.e(e, "extract %s failed", name); null
        }
    }

    fun close() {
        executor.execute { runCatching { YoloNative.nativeRelease() } }
        executor.shutdown()
    }

    private companion object {
        const val PARAM_ASSET = "yolo.ncnn.param"
        const val BIN_ASSET = "yolo.ncnn.bin"
        const val IN_SIZE = 640
        const val ANCHORS = 8400
        const val CONF_THRESHOLD = 0.55f
    }
}
