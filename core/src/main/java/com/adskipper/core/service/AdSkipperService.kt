package com.adskipper.core.service

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Path
import android.view.accessibility.AccessibilityEvent
import com.adskipper.core.data.AppSettings
import com.adskipper.core.data.SettingsRepository
import com.adskipper.core.data.StatsRepository
import com.adskipper.core.detect.DetectionPipeline
import com.adskipper.core.detect.SkipTarget
import com.adskipper.core.detect.YoloSkipDetector
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
    private var yolo: YoloSkipDetector? = null

    /** Latest settings snapshot, collected continuously. */
    private val settings = MutableStateFlow(AppSettings())

    /** Per-package cooldown to avoid click loops. */
    private val lastAttemptAt = ConcurrentHashMap<String, Long>()

    /** Foreground-session tracking for the L3 splash-window gate. */
    private val lastEventAt = ConcurrentHashMap<String, Long>()
    private val sessionStartAt = ConcurrentHashMap<String, Long>()
    private val processing = AtomicBoolean(false)

    /** Home/launcher apps resolved from the device, always skipped: splash ads
     *  never appear there, and this app's own icon sits on the home screen —
     *  an image L3 false positive would tap it. The static DEFAULT_WHITELIST
     *  can't cover every OEM launcher, and users with an old persisted
     *  whitelist never receive new defaults. */
    private var homePackages: Set<String> = emptySet()

    /** This app's launcher label ("广告跳过") — it contains the keyword "跳过",
     *  so L1/L2 must never treat it as a skip button. */
    private var selfLabels: Set<String> = emptySet()

    /** True while the mock-ad test page is the app's foreground window. */
    @Volatile
    private var selfTestAdVisible = false

    override fun onServiceConnected() {
        Timber.i("AdSkipperService connected")
        startKeepaliveForeground()
        settingsRepo = SettingsRepository(this)
        statsRepo = StatsRepository(this)
        modelManager = ModelManager(this)
        engine = VlmEngine()
        overlay = DebugOverlay(this)
        yolo = YoloSkipDetector(this).takeIf { it.isReady }
        homePackages = resolveHomePackages() + HOME_SURFACE_PACKAGES
        selfLabels = setOf(applicationInfo.loadLabel(packageManager).toString())
        scope.launch { settingsRepo.seedDefaultLauncher() }

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

    /** Promote to a foreground service so aggressive OEM task killers don't
     *  reap the process. Best-effort: the service still works without it. */
    private fun startKeepaliveForeground() {
        try {
            val channel = android.app.NotificationChannel(
                KEEPALIVE_CHANNEL_ID,
                "运行状态",
                android.app.NotificationManager.IMPORTANCE_MIN,
            )
            getSystemService(android.app.NotificationManager::class.java)
                .createNotificationChannel(channel)
            val notification = android.app.Notification.Builder(this, KEEPALIVE_CHANNEL_ID)
                .setSmallIcon(android.R.drawable.ic_menu_view)
                .setContentTitle("广告跳过运行中")
                .setOngoing(true)
                .build()
            startForeground(
                KEEPALIVE_NOTIFICATION_ID,
                notification,
                android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE,
            )
        } catch (t: Throwable) {
            Timber.e(t, "startForeground failed")
        }
    }

    private fun resolveHomePackages(): Set<String> = try {
        val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
        packageManager.queryIntentActivities(
            intent,
            PackageManager.ResolveInfoFlags.of(PackageManager.MATCH_ALL.toLong()),
        ).mapTo(mutableSetOf()) { it.activityInfo.packageName }
            .also { Timber.i("home packages: %s", it) }
    } catch (t: Throwable) {
        Timber.e(t, "resolving home packages failed")
        emptySet()
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
        if (pkg == packageName) {
            // Self-test only exempts the mock-ad page, not the whole app:
            // our own UI is full of keyword-bearing text（自动跳过广告、
            // 白名单（不跳过的应用）…）that L1/L2 would happily tap.
            if (event.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
                selfTestAdVisible =
                    event.className?.toString()?.endsWith("TestAdActivity") == true
            }
            if (!s.selfTest || !selfTestAdVisible) return
        }
        if (pkg in s.whitelist || pkg in homePackages) return

        val now = android.os.SystemClock.elapsedRealtime()

        // Splash ads only exist in the first seconds after an app (re)starts.
        // Outside that window, image-based L3 detectors must not run: unlike
        // L1/L2 keyword matching they will occasionally see "skip buttons" in
        // ordinary UI and tap them. Self-test is exempt so the in-app mock ad
        // can exercise L3 at any time.
        val prevEvent = lastEventAt.put(pkg, now)
        if (prevEvent == null || now - prevEvent > SESSION_GAP_MS) {
            sessionStartAt[pkg] = now
        }
        val inSplashWindow = now - (sessionStartAt[pkg] ?: 0L) <= SPLASH_WINDOW_MS
        val allowL3 = inSplashWindow || (s.selfTest && pkg == packageName)
        val effective = if (allowL3) s else s.copy(layer3Enabled = false)
        if (!effective.layer1Enabled && !effective.layer2Enabled && !effective.layer3Enabled) return

        val last = lastAttemptAt[pkg] ?: 0L
        if (now - last < ATTEMPT_COOLDOWN_MS) return
        if (processing.get()) return
        lastAttemptAt[pkg] = now

        val model = ModelCatalog.byId(effective.activeModelId) ?: ModelCatalog.default
        val pipeline = DetectionPipeline.create(engine, effective, model, yolo, selfLabels)
        scope.launch { runDetection(pkg, pipeline, effective) }
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

        /** L3 image detectors only run this long after an app session starts. */
        private const val SPLASH_WINDOW_MS = 8000L

        /** Gap without events after which the next event starts a new session. */
        private const val SESSION_GAP_MS = 20_000L
        private const val TAP_DURATION_MS = 50L

        private const val KEEPALIVE_CHANNEL_ID = "keepalive"
        private const val KEEPALIVE_NOTIFICATION_ID = 1

        /** Home-screen companion surfaces that are separate packages from the
         *  launcher (search overlay, minus-one screen). They list installed
         *  apps, never show splash ads, and must not be tapped. */
        private val HOME_SURFACE_PACKAGES = setOf(
            "com.android.quicksearchbox",             // MIUI/AOSP home search
            "com.google.android.googlequicksearchbox", // Google app / Pixel search
            "com.miui.personalassistant",             // MIUI minus-one screen
        )
    }
}
