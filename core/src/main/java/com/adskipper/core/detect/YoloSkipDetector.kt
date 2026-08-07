package com.adskipper.core.detect

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Rect
import org.tensorflow.lite.Interpreter
import timber.log.Timber
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.channels.FileChannel

/**
 * L3 fast path: bundled YOLO11n skip-button detector (~5MB TFLite, tens of ms
 * on CPU). Trained on synthetic splash ads (vlm-bench 2026-08); scores 17/20
 * on the grounding benchmark — above the 1.5GB InternVL3-2B VLM's 16/20 —
 * with zero taps on ad CTAs. The downloadable VLM remains as fallback for
 * cases the detector can't see.
 */
class YoloSkipDetector(context: Context) {

    private val interpreter: Interpreter? = try {
        val fd = context.assets.openFd(ASSET)
        val model = fd.createInputStream().channel
            .map(FileChannel.MapMode.READ_ONLY, fd.startOffset, fd.declaredLength)
        Interpreter(model, Interpreter.Options().setNumThreads(THREADS))
    } catch (t: Throwable) {
        Timber.e(t, "YOLO skip detector unavailable")
        null
    }

    val isReady: Boolean get() = interpreter != null

    /** Best detection above [CONF_THRESHOLD] mapped back to bitmap pixels. */
    fun findSkipButton(bitmap: Bitmap): Rect? {
        val itp = interpreter ?: return null
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
        Timber.d("YOLO hit conf=%.2f at (%.0f, %.0f)", best, cx, cy)
        return Rect(
            (cx - halfW).toInt().coerceIn(0, bitmap.width),
            (cy - halfH).toInt().coerceIn(0, bitmap.height),
            (cx + halfW).toInt().coerceIn(0, bitmap.width),
            (cy + halfH).toInt().coerceIn(0, bitmap.height),
        )
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
        interpreter?.close()
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
