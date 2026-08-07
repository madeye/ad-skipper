package com.adskipper.core.service

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Path
import android.view.accessibility.AccessibilityEvent
import com.adskipper.core.data.AppSettings
import com.adskipper.core.data.SettingsRepository
import com.adskipper.core.data.StatsRepository
import com.adskipper.core.detect.DetectionPipeline
import com.adskipper.core.detect.SkipTarget
import com.adskipper.core.model.ModelCatalog
import com.adskipper.core.model.ModelManager
import com.adskipper.core.vlm.VlmEngine
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import timber.log.Timber
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Watches window changes, runs the three-tier detection pipeline and taps
 * the skip button via a dispatched gesture.
 */
class AdSkipperService : AccessibilityService() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private lateinit var settingsRepo: SettingsRepository
    private lateinit var statsRepo: StatsRepository
    private lateinit var modelManager: ModelManager
    private lateinit var engine: VlmEngine
    private lateinit var overlay: DebugOverlay

    /** Latest settings snapshot, collected continuously. */
    private val settings = MutableStateFlow(AppSettings())

    /** Per-package cooldown to avoid click loops. */
    private val lastAttemptAt = ConcurrentHashMap<String, Long>()
    private val processing = AtomicBoolean(false)

    override fun onServiceConnected() {
        Timber.i("AdSkipperService connected")
        settingsRepo = SettingsRepository(this)
        statsRepo = StatsRepository(this)
        modelManager = ModelManager(this)
        engine = VlmEngine()
        overlay = DebugOverlay(this)

        scope.launch {
            settingsRepo.settings.collect { new ->
                val prev = settings.value
                settings.value = new
                if (new.layer3Enabled &&
                    (new.activeModelId != prev.activeModelId ||
                        new.vlmThreads != prev.vlmThreads ||
                        !engine.isReady)
                ) {
                    loadActiveModel(new)
                }
            }
        }
    }

    private suspend fun loadActiveModel(s: AppSettings) {
        val model = ModelCatalog.byId(s.activeModelId) ?: ModelCatalog.default
        val paths = modelManager.modelPaths(model)
        if (paths == null) {
            Timber.w("L3 enabled but model %s not downloaded", model.id)
            return
        }
        Timber.i("loading VLM model %s", model.id)
        engine.load(paths.first, paths.second, s.vlmThreads)
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null) return
        if (event.eventType != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED &&
            event.eventType != AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED
        ) return

        val pkg = event.packageName?.toString() ?: return
        val s = settings.value
        if (!s.masterEnabled) return
        if (!s.layer1Enabled && !s.layer2Enabled && !s.layer3Enabled) return
        if (pkg == packageName && !s.selfTest) return
        if (pkg in s.whitelist) return

        val now = android.os.SystemClock.elapsedRealtime()
        val last = lastAttemptAt[pkg] ?: 0L
        if (now - last < ATTEMPT_COOLDOWN_MS) return
        if (processing.get()) return
        lastAttemptAt[pkg] = now

        val pipeline = DetectionPipeline.create(engine, s)
        scope.launch { runDetection(pkg, pipeline, s) }
    }

    private suspend fun runDetection(
        pkg: String,
        pipeline: DetectionPipeline<android.graphics.Bitmap>,
        s: AppSettings,
    ) {
        if (!processing.compareAndSet(false, true)) {
            Timber.d("detection already running, skip %s", pkg)
            return
        }
        Timber.d("detecting in %s", pkg)
        val t0 = android.os.SystemClock.elapsedRealtime()
        try {
            val result = pipeline.detect(
                root = rootInActiveWindow,
                screenshot = { ScreenshotCapturer.capture(this) },
                settings = s,
            )
            val target = result.target ?: return
            Timber.i(
                "skip hit in %s via %s at (%.0f, %.0f), timings=%s",
                pkg, target.layer, target.x, target.y, result.timings,
            )
            if (performClick(target.x, target.y)) {
                val elapsed = android.os.SystemClock.elapsedRealtime() - t0
                statsRepo.record(pkg, target.layer.name, elapsed)
                if (s.debugOverlay) {
                    overlay.update("${target.layer}  (${target.x.toInt()}, ${target.y.toInt()})  ${elapsed}ms")
                }
            }
        } catch (t: Throwable) {
            Timber.w(t, "detection failed for %s", pkg)
        } finally {
            processing.set(false)
        }
    }

    private fun performClick(x: Float, y: Float): Boolean {
        val path = Path().apply { moveTo(x, y) }
        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0, TAP_DURATION_MS))
            .build()
        return dispatchGesture(gesture, null, null)
    }

    override fun onInterrupt() {
        Timber.i("AdSkipperService interrupted")
    }

    override fun onDestroy() {
        overlay.dismiss()
        scope.launch { engine.release() }
        scope.cancel()
        super.onDestroy()
    }

    companion object {
        private const val ATTEMPT_COOLDOWN_MS = 2000L
        private const val TAP_DURATION_MS = 50L
    }
}
