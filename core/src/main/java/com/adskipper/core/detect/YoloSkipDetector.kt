package com.adskipper.core.detect

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Rect
import org.tensorflow.lite.Interpreter
import org.tensorflow.lite.gpu.CompatibilityList
import org.tensorflow.lite.gpu.GpuDelegate
import timber.log.Timber
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.MappedByteBuffer
import java.nio.channels.FileChannel
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * L3 fast path: bundled YOLO11n skip-button detector (~10MB TFLite).
 * Trained on synthetic splash ads + real UI negatives (vlm-bench 2026-08);
 * scores 20/20 on the grounding benchmark vs 16/20 for the 1.5GB
 * InternVL3-2B VLM, with zero false positives on real app screens.
 *
 * Runs on the TFLite GPU delegate when the device supports it, falling back
 * to CPU/XNNPACK. All interpreter work is confined to a single thread: the
 * GPU delegate binds its EGL context to the thread that created it, so
 * creation and inference must happen on the same thread.
 */
class YoloSkipDetector(
    private val context: Context,
    /** Attempt the GPU delegate even when CompatibilityList rejects the device.
     *  Off by default: an unsupported driver may abort natively rather than
     *  throw. Useful for validating the GPU path on emulators / new hardware. */
    private val forceGpuAttempt: Boolean = false,
) {

    private val executor = Executors.newSingleThreadExecutor { r -> Thread(r, "yolo-skip") }

    private var interpreter: Interpreter? = null
    private var gpuDelegate: GpuDelegate? = null
    private var initTried = false

    @Volatile
    var backend: String = "uninitialized"
        private set

    /** Cheap constructor-time check; the interpreter is built lazily on the
     *  executor thread at first use. */
    val isReady: Boolean = try {
        context.assets.openFd(ASSET).use { true }
    } catch (t: Throwable) {
        Timber.e(t, "YOLO skip detector asset missing")
        false
    }

    /** Best detection above [CONF_THRESHOLD] mapped back to bitmap pixels. */
    fun findSkipButton(bitmap: Bitmap): Rect? {
        if (!isReady) return null
        return try {
            executor.submit(Callable { detect(bitmap) }).get(15, TimeUnit.SECONDS)
        } catch (t: Throwable) {
            Timber.e(t, "YOLO detection failed")
            null
        }
    }

    // ---- everything below runs on the executor thread ----

    private fun detect(bitmap: Bitmap): Rect? {
        val itp = interpreterOrNull() ?: return null
        val scale = IN_SIZE.toFloat() / maxOf(bitmap.width, bitmap.height)
        val w = (bitmap.width * scale).toInt().coerceAtLeast(1)
        val h = (bitmap.height * scale).toInt().coerceAtLeast(1)
        val padX = (IN_SIZE - w) / 2f
        val padY = (IN_SIZE - h) / 2f

        val input = letterbox(bitmap, w, h, padX.toInt(), padY.toInt())
        val outShape = itp.getOutputTensor(0).shape() // [1,5,N] or [1,N,5]
        val chFirst = outShape[1] < outShape[2]
        val n = if (chFirst) outShape[2] else outShape[1]
        val out = Array(1) {
            if (chFirst) Array(5) { FloatArray(n) } else Array(n) { FloatArray(5) }
        }
        itp.run(input, out)

        var best = -1f
        var bx = 0f; var by = 0f; var bw = 0f; var bh = 0f
        var maxCoord = 0f
        for (i in 0 until n) {
            val conf = if (chFirst) out[0][4][i] else out[0][i][4]
            if (conf <= best) continue
            best = conf
            bx = if (chFirst) out[0][0][i] else out[0][i][0]
            by = if (chFirst) out[0][1][i] else out[0][i][1]
            bw = if (chFirst) out[0][2][i] else out[0][i][2]
            bh = if (chFirst) out[0][3][i] else out[0][i][3]
            maxCoord = maxOf(maxCoord, bx, by)
        }
        if (best < CONF_THRESHOLD) return null
        // Some exports emit coords normalized to 0-1, others in input pixels.
        val toPx = if (maxCoord <= 2f) IN_SIZE.toFloat() else 1f
        val cx = (bx * toPx - padX) / scale
        val cy = (by * toPx - padY) / scale
        val halfW = bw * toPx / scale / 2f
        val halfH = bh * toPx / scale / 2f
        Timber.d("YOLO hit conf=%.2f at (%.0f, %.0f) [%s]", best, cx, cy, backend)
        return Rect(
            (cx - halfW).toInt().coerceIn(0, bitmap.width),
            (cy - halfH).toInt().coerceIn(0, bitmap.height),
            (cx + halfW).toInt().coerceIn(0, bitmap.width),
            (cy + halfH).toInt().coerceIn(0, bitmap.height),
        )
    }

    private fun interpreterOrNull(): Interpreter? {
        if (initTried) return interpreter
        initTried = true

        val model = mapModel() ?: run { backend = "error"; return null }
        // GPU first. CompatibilityList is a conservative allowlist and only
        // supplies tuned options; we attempt the delegate whenever it says yes
        // OR when force-enabled, then *validate* by a warmup inference — the
        // delegate can fail at creation or at first run (shader compilation),
        // and either way we fall back to CPU. Attempting on a device the list
        // rejects is opt-in (forceGpuAttempt) because a truly unsupported
        // driver can abort natively instead of throwing.
        val compat = try {
            CompatibilityList()
        } catch (t: Throwable) {
            Timber.w(t, "GPU compatibility list unavailable")
            null
        }
        val supported = compat?.isDelegateSupportedOnThisDevice == true
        if (supported || forceGpuAttempt) {
            try {
                val options = if (supported && compat != null) {
                    compat.bestOptionsForThisDevice
                } else {
                    GpuDelegate.Options()
                }
                val delegate = GpuDelegate(options)
                val itp = Interpreter(model, Interpreter.Options().addDelegate(delegate))
                warmup(itp)
                gpuDelegate = delegate
                interpreter = itp
                backend = "gpu"
                Timber.i("YOLO running on GPU delegate (supported=%b)", supported)
                return itp
            } catch (t: Throwable) {
                Timber.w(t, "GPU delegate failed, falling back to CPU")
                gpuDelegate?.close()
                gpuDelegate = null
            }
        } else {
            Timber.i("GPU delegate not supported on this device")
        }
        return try {
            val itp = Interpreter(model, Interpreter.Options().setNumThreads(THREADS))
            warmup(itp)
            interpreter = itp
            backend = "cpu"
            Timber.i("YOLO running on CPU (%d threads)", THREADS)
            itp
        } catch (t: Throwable) {
            Timber.e(t, "YOLO interpreter init failed")
            backend = "error"
            null
        }
    }

    private fun warmup(itp: Interpreter) {
        val input = ByteBuffer.allocateDirect(4 * IN_SIZE * IN_SIZE * 3)
            .order(ByteOrder.nativeOrder())
        val shape = itp.getOutputTensor(0).shape()
        val out = Array(1) { Array(shape[1]) { FloatArray(shape[2]) } }
        itp.run(input, out)
    }

    private fun mapModel(): MappedByteBuffer? = try {
        context.assets.openFd(ASSET).use { fd ->
            fd.createInputStream().channel
                .map(FileChannel.MapMode.READ_ONLY, fd.startOffset, fd.declaredLength)
        }
    } catch (t: Throwable) {
        Timber.e(t, "cannot map %s", ASSET)
        null
    }

    /** Center-letterboxed RGB float input, /255, gray padding (ultralytics). */
    private fun letterbox(src: Bitmap, w: Int, h: Int, padX: Int, padY: Int): ByteBuffer {
        val canvasBmp = Bitmap.createBitmap(IN_SIZE, IN_SIZE, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(canvasBmp)
        canvas.drawColor(Color.rgb(114, 114, 114))
        val scaled = Bitmap.createScaledBitmap(src, w, h, true)
        canvas.drawBitmap(scaled, padX.toFloat(), padY.toFloat(), null)

        val pixels = IntArray(IN_SIZE * IN_SIZE)
        canvasBmp.getPixels(pixels, 0, IN_SIZE, 0, 0, IN_SIZE, IN_SIZE)
        val buf = ByteBuffer.allocateDirect(4 * IN_SIZE * IN_SIZE * 3)
            .order(ByteOrder.nativeOrder())
        for (p in pixels) {
            buf.putFloat(((p shr 16) and 0xFF) / 255f)
            buf.putFloat(((p shr 8) and 0xFF) / 255f)
            buf.putFloat((p and 0xFF) / 255f)
        }
        buf.rewind()
        return buf
    }

    fun close() {
        executor.execute {
            interpreter?.close()
            interpreter = null
            gpuDelegate?.close()
            gpuDelegate = null
        }
        executor.shutdown()
    }

    private companion object {
        const val ASSET = "skip_detector.tflite"
        const val IN_SIZE = 640
        const val THREADS = 4
        // Real-world screens (launcher icons, dark chips) can score ~0.4-0.5;
        // true skip buttons score 0.9+. Keep well above the noise floor.
        const val CONF_THRESHOLD = 0.55f
    }
}
