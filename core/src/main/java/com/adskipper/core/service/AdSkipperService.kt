package com.adskipper.core.service

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.app.AlarmManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.graphics.Path
import android.graphics.PixelFormat
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import com.adskipper.core.data.AppProfileRepository
import com.adskipper.core.data.AppSettings
import com.adskipper.core.data.SettingsRepository
import com.adskipper.core.data.StatsRepository
import com.adskipper.core.detect.AdEvidenceTracker
import com.adskipper.core.detect.AdSdkSignatures
import com.adskipper.core.detect.DetectionPipeline
import com.adskipper.core.detect.NodeMatcher
import com.adskipper.core.detect.SkipTarget
import com.adskipper.core.detect.YoloSkipDetector
import com.adskipper.core.model.ModelCatalog
import com.adskipper.core.model.ModelManager
import com.adskipper.core.vlm.VlmEngine
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
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
    private lateinit var profileRepo: AppProfileRepository
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

    /** Active splash-window pollers, one per package. */
    private val pollJobs = ConcurrentHashMap<String, Job>()

    /** Positive "this screen is an ad" evidence, per session; image L3 taps
     *  require it. See [AdEvidenceTracker]. */
    private val adEvidence = AdEvidenceTracker { android.os.SystemClock.elapsedRealtime() }

    /** Session-start stamps whose outcome has been persisted, so a session
     *  whose poller runs twice is counted once. */
    private val recordedSessions = ConcurrentHashMap<String, Long>()

    /** Held for the duration of a splash window; see holdSplashWakeLock. */
    @Volatile
    private var splashWakeLock: android.os.PowerManager.WakeLock? = null

    /** Self-thaw machinery for OEM process freezers; see startThawPulse. */
    private val pulseHandler = Handler(Looper.getMainLooper())
    private var pulseView: View? = null
    @Volatile
    private var pulseActive = false

    private val pulseRunnable = object : Runnable {
        override fun run() {
            if (!pulseActive) return
            if (pulseView == null) ensurePulseView() // retry after transient add failures
            try {
                pulseView?.sendAccessibilityEvent(
                    AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED)
            } catch (t: Throwable) {
                Timber.w(t, "thaw pulse failed")
            }
            pulseHandler.postDelayed(this, THAW_PULSE_INTERVAL_MS)
        }
    }

    /** Pulse only needs to run while the screen is on (no splash ads on a dark
     *  screen); SCREEN_OFF stops it so the process can be frozen while idle.
     *  The matching SCREEN_ON restart is best-effort — HyperOS does not
     *  deliver broadcasts into a frozen process — so the watchdog alarm below
     *  is the guaranteed restart path. */
    private val screenReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                Intent.ACTION_SCREEN_ON -> startThawPulse()
                Intent.ACTION_SCREEN_OFF -> stopThawPulse()
            }
        }
    }

    /** Alarm delivery is the one mechanism HyperOS GreezeManager reliably
     *  thaws a frozen process for (logcat: `THAW uid=... reason : alarm`).
     *  Accessibility-event delivery does NOT thaw (verified 2026-08-08: a
     *  Douban cold start delivered zero events into the frozen service), so
     *  once frozen — e.g. after a screen-off period — the process would stay
     *  frozen forever without this. Each firing restarts the pulse chain if
     *  the screen is on, then re-arms. */
    private val watchdogListener = AlarmManager.OnAlarmListener {
        if (getSystemService(PowerManager::class.java).isInteractive) startThawPulse()
        scheduleWatchdog()
    }

    private fun scheduleWatchdog() {
        try {
            // Non-wakeup on purpose: while the screen is on the pulse chain
            // prevents freezing anyway, and an alarm that expired during
            // sleep is delivered the moment the device wakes — which is
            // exactly when a thaw is needed. Costs zero wakeups.
            getSystemService(AlarmManager::class.java).setExact(
                AlarmManager.ELAPSED_REALTIME,
                android.os.SystemClock.elapsedRealtime() + WATCHDOG_INTERVAL_MS,
                "adskipper:thaw-watchdog",
                watchdogListener,
                pulseHandler,
            )
        } catch (t: Throwable) {
            Timber.w(t, "watchdog schedule failed")
        }
    }

    /** Home/launcher apps resolved from the device, always skipped: splash ads
     *  never appear there, and this app's own icon sits on the home screen —
     *  an image L3 false positive would tap it. The static DEFAULT_WHITELIST
     *  can't cover every OEM launcher, and users with an old persisted
     *  whitelist never receive new defaults. */
    private var homePackages: Set<String> = emptySet()

    /** Browser apps resolved from the device, always skipped: web pages are
     *  full of "跳过/Skip" labels and ad-like imagery that L1–L3 would tap,
     *  and a stray tap inside a page can navigate, submit or dismiss things
     *  the user is reading. Splash-ad skipping is for native apps only. */
    private var browserPackages: Set<String> = emptySet()

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
        profileRepo = AppProfileRepository(this)
        modelManager = ModelManager(this)
        engine = VlmEngine()
        overlay = DebugOverlay(this)
        yolo = YoloSkipDetector(this).takeIf { it.isReady }
        // Cold-init of the YOLO native engine (Vulkan device + pipeline
        // creation + model load) takes ~3s; paid inside the first splash
        // window it would eat most of it. Warm it up shortly after connect
        // instead — but NOT immediately: ncnn's Vulkan load_model SIGSEGVd
        // when called ~1-3s after process start on HyperOS (tombstone:
        // null deref in Net::load_model, uptime 3s), whereas the same init
        // succeeds once the process has settled.
        scope.launch {
            delay(YOLO_WARMUP_DELAY_MS)
            yolo?.warmUp()
        }
        homePackages = resolveHomePackages() + HOME_SURFACE_PACKAGES
        browserPackages = resolveBrowserPackages() + BROWSER_PACKAGES - SPLASH_AD_BROWSERS
        selfLabels = setOf(applicationInfo.loadLabel(packageManager).toString())
        scope.launch { settingsRepo.seedDefaultLauncher() }

        // Freeze prevention must be continuous, not splash-scoped: HyperOS
        // freezes this process ~1s after the last accessibility event and
        // does NOT thaw it to deliver new events — a frozen service misses
        // the app-launch event entirely, so no poller ever starts and the
        // whole pipeline is dead (verified 2026-08-08 on Douban cold starts).
        registerReceiver(screenReceiver, IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_ON)
            addAction(Intent.ACTION_SCREEN_OFF)
        })
        if (getSystemService(PowerManager::class.java).isInteractive) startThawPulse()
        scheduleWatchdog()

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

    /** Apps that handle a plain http URL with no host filter are browsers;
     *  deep-link handlers for specific hosts don't match a bare host like
     *  this one. The static BROWSER_PACKAGES list backstops OEM browsers
     *  that hide from the query (e.g. behind role-based resolution). */
    private fun resolveBrowserPackages(): Set<String> = try {
        val intent = Intent(Intent.ACTION_VIEW, android.net.Uri.parse("http://example.invalid/"))
            .addCategory(Intent.CATEGORY_BROWSABLE)
        packageManager.queryIntentActivities(
            intent,
            PackageManager.ResolveInfoFlags.of(PackageManager.MATCH_ALL.toLong()),
        ).mapTo(mutableSetOf()) { it.activityInfo.packageName }
            .also { Timber.i("browser packages: %s", it) }
    } catch (t: Throwable) {
        Timber.e(t, "resolving browser packages failed")
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
        if (pkg in s.whitelist || pkg in homePackages || pkg in browserPackages) return

        val now = android.os.SystemClock.elapsedRealtime()

        // NOTE: a foreign package's window-state change must NOT cancel this
        // package's splash poller. HyperOS fires transient permissioncontroller
        // window events during cold starts; cancelling here killed Douban's
        // poller mid-window and — since a video splash ad emits no further
        // accessibility events — it never restarted, so the ad played out.
        // Tapping through a genuinely different foreground window is instead
        // prevented by the active-window guard in runDetection().

        val prevEvent = lastEventAt.put(pkg, now)
        if (prevEvent == null || now - prevEvent > SESSION_GAP_MS) {
            sessionStartAt[pkg] = now
            adEvidence.onSessionStart(pkg)
        }
        val inSplashWindow = now - (sessionStartAt[pkg] ?: 0L) <= SPLASH_WINDOW_MS

        // Splash-ad SDK activities announce themselves in window-state
        // events (穿山甲 TTAppOpenAdActivity, 优量汇 ADActivity, …) — free,
        // OCR-independent ad evidence.
        if (event.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED &&
            AdSdkSignatures.matchesClassName(event.className?.toString())
        ) {
            Timber.d("ad SDK window in %s: %s", pkg, event.className)
            adEvidence.noteSdkSignal(pkg)
        }

        // Splash ads only exist in the first seconds after an app (re)starts,
        // and that window is covered by a polling loop rather than by events:
        // a launch emits a single early event burst — while a stale snapshot
        // starting window still covers the not-yet-interactive UI — and the ad
        // countdown that follows often emits no events at all. The self-test
        // mock ad goes through the same poller so it exercises the real path.
        if (inSplashWindow || (s.selfTest && pkg == packageName)) {
            startSplashPoller(pkg)
            return
        }

        // Steady state, event-driven: keyword layers only — unlike L1/L2
        // matching, image-based L3 detectors would occasionally see "skip
        // buttons" in ordinary UI and tap them.
        val effective = s.copy(layer3Enabled = false)
        if (!effective.layer1Enabled && !effective.layer2Enabled) return
        val last = lastAttemptAt[pkg] ?: 0L
        if (now - last < ATTEMPT_COOLDOWN_MS) return
        if (processing.get()) return
        scope.launch { runDetection(pkg, effective) }
    }

    /** Runs the pipeline on a fixed cadence for the duration of [pkg]'s splash
     *  window. Polling sidesteps the two ways event-driven detection loses the
     *  race against a splash ad: the launch burst arrives too early (stale
     *  snapshot on screen, targets not yet tappable) and the ad itself emits no
     *  events. Re-detection on the next tick doubles as tap verification — a
     *  target that survived a tap gets tapped again, up to [SPLASH_MAX_TAPS]. */
    private fun startSplashPoller(pkg: String) {
        if (pollJobs.containsKey(pkg)) return
        val job = scope.launch(start = CoroutineStart.LAZY) {
            delay(SPLASH_POLL_INITIAL_DELAY_MS)
            var taps = 0
            var ticks = 0
            // Apps with a long ad-free history run in a degraded mode: L1
            // only, no screenshots/OCR — near-zero cost and zero image-layer
            // misclick surface. Every PROBE_EVERY-th session probes at full
            // strength in case the app started showing ads; an SDK signal
            // (event-driven, works regardless of mode) lifts the downgrade
            // instantly.
            val barren = if (pkg == packageName) 0 else try {
                profileRepo.barrenSessions(pkg)
            } catch (t: Throwable) {
                Timber.w(t, "profile read failed"); 0
            }
            val downgraded = barren >= BARREN_SESSIONS_TO_DOWNGRADE &&
                barren % PROBE_EVERY_SESSIONS != 0
            if (downgraded) Timber.d("%s: %d ad-free sessions — L1-only mode", pkg, barren)
            try {
                while (isActive && taps < SPLASH_MAX_TAPS) {
                    val s = settings.value
                    if (!s.masterEnabled) break
                    if (!s.layer1Enabled && !s.layer2Enabled && !s.layer3Enabled) break
                    val selfTesting = s.selfTest && pkg == packageName
                    if (selfTesting && !selfTestAdVisible) break
                    val start = sessionStartAt[pkg] ?: break
                    val elapsed = android.os.SystemClock.elapsedRealtime() - start
                    // Hupu's splash ad appeared ~8s after launch (slow app
                    // init on a white splash) and then played for ~40s in
                    // total event silence — a poller stopping at
                    // SPLASH_WINDOW_MS provides zero coverage for it. Poll to
                    // the extended window, stretched further while a confirmed
                    // countdown says the ad is still running.
                    val deadline = maxOf(
                        SPLASH_WINDOW_EXTENDED_MS,
                        minOf(adEvidence.pollUntil(pkg) - start, SPLASH_WINDOW_HARD_CAP_MS),
                    )
                    if (!selfTesting && elapsed > deadline) break
                    ticks++
                    val effective =
                        if (downgraded && !adEvidence.isAdConfirmed(pkg)) {
                            s.copy(layer2Enabled = false, layer3Enabled = false)
                        } else {
                            s
                        }
                    if (runDetection(pkg, effective, inCoreWindow = elapsed <= SPLASH_WINDOW_MS)) taps++
                    delay(SPLASH_POLL_INTERVAL_MS)
                }
            } finally {
                // Persist the session outcome once (poller may restart within
                // the same session; count it a single time).
                val startStamp = sessionStartAt[pkg]
                if (pkg != packageName && ticks > 0 && startStamp != null &&
                    recordedSessions.put(pkg, startStamp) != startStamp
                ) {
                    val adSeen = taps > 0 || adEvidence.isAdConfirmed(pkg)
                    scope.launch {
                        try {
                            profileRepo.recordSession(pkg, adSeen, appVersionCode(pkg))
                        } catch (t: Throwable) {
                            Timber.w(t, "profile write failed")
                        }
                    }
                }
            }
        }
        if (pollJobs.putIfAbsent(pkg, job) == null) {
            Timber.d("splash poller start for %s", pkg)
            holdSplashWakeLock()
            // The pulse chain normally runs whenever the screen is on, but the
            // watchdog may not have caught up yet after a thaw — an event just
            // arrived, so the process is provably running: re-arm here.
            startThawPulse()
            job.invokeOnCompletion { cause ->
                Timber.d("splash poller exit for %s (%s)", pkg, cause?.javaClass?.simpleName ?: "done")
                pollJobs.remove(pkg, job)
                if (pollJobs.isEmpty()) releaseSplashWakeLock()
            }
            job.start()
        } else {
            job.cancel()
        }
    }

    /** HyperOS GreezeManager cgroup-freezes this process ~1s after the last
     *  accessibility event (observed: cgroup.freeze=1; held wake locks are
     *  force-disabled by the freezer) and never thaws it for event delivery.
     *  The thaw pulse keeps a minimal self-sustaining event stream alive:
     *  each pulse makes an invisible 1px overlay view emit a content-changed
     *  event, whose round-trip through system_server resets the freezer's
     *  inactivity timer, during which the next pulse is posted. The pulse
     *  interval must stay well below the freezer's ~1s grace period. Runs
     *  whenever the screen is interactive; pending postDelayed messages
     *  survive a freeze, so the chain self-resumes after any thaw. */
    private fun startThawPulse() {
        if (pulseActive) return
        Timber.d("thaw pulse start")
        pulseActive = true
        ensurePulseView()
        pulseHandler.post(pulseRunnable)
    }

    private fun ensurePulseView() {
        if (pulseView != null) return
        try {
            val v = View(this)
            v.importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_YES
            val lp = WindowManager.LayoutParams(
                1, 1,
                WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE,
                PixelFormat.TRANSLUCENT,
            )
            lp.gravity = Gravity.TOP or Gravity.START
            lp.alpha = 0f
            getSystemService(WindowManager::class.java).addView(v, lp)
            pulseView = v
        } catch (t: Throwable) {
            // A stale binding (post-crash rebind on HyperOS) rejects the
            // overlay token with BadTokenException; retried by pulseRunnable.
            Timber.w(t, "thaw pulse view failed")
        }
    }

    /** Stops the pulse chain but keeps the overlay view attached — it is
     *  invisible and free while idle, and re-adding a window on every screen
     *  cycle would be churn. Removed only in [onDestroy]. */
    private fun stopThawPulse() {
        if (pulseActive) Timber.d("thaw pulse stop")
        pulseActive = false
        pulseHandler.removeCallbacks(pulseRunnable)
    }

    private fun removePulseView() {
        pulseView?.let {
            try {
                getSystemService(WindowManager::class.java).removeView(it)
            } catch (t: Throwable) {
                Timber.w(t, "thaw pulse view removal failed")
            }
        }
        pulseView = null
    }

    /** HyperOS GreezeManager cgroup-freezes this process ~0.5s after the
     *  last accessibility event; a silent video splash ad (Douban) emits
     *  none, so without a wake lock the poll loop stops getting CPU right
     *  when the ad appears (observed: cgroup.freeze=1 for the whole window).
     *  A partial wake lock is held for the splash window only — it is
     *  acquired here, i.e. during an event delivery, while the process is
     *  guaranteed to be running. */
    private fun holdSplashWakeLock() {
        try {
            if (splashWakeLock?.isHeld == true) return
            val pm = getSystemService(PowerManager::class.java)
            splashWakeLock = pm.newWakeLock(
                PowerManager.PARTIAL_WAKE_LOCK,
                "adskipper:splash",
            ).apply { acquire(SPLASH_WINDOW_EXTENDED_MS + 10_000L) } // auto-release backstop
        } catch (t: Throwable) {
            Timber.w(t, "wake lock acquire failed")
        }
    }

    private fun releaseSplashWakeLock() {
        try {
            splashWakeLock?.takeIf { it.isHeld }?.release()
        } catch (t: Throwable) {
            Timber.w(t, "wake lock release failed")
        }
        splashWakeLock = null
    }

    /** One pipeline pass; returns true when a target was found and tapped.
     *  Image L3 always requires positive ad evidence ([AdEvidenceTracker]) —
     *  apps without splash ads never produce it, so their launches never see
     *  an image-layer tap. Outside the core splash window the screen must
     *  additionally still look like a splash (tiny node tree). The tree check
     *  must NOT apply inside the core window: Douban preloads its feed
     *  beneath the splash ad, so the ad screen itself is 30+ nodes — a
     *  universal tree gate kept L3 off for the entire ad (regression found
     *  on-device 2026-08-09). */
    private suspend fun runDetection(
        pkg: String,
        s: AppSettings,
        inCoreWindow: Boolean = false,
    ): Boolean {
        // Never detect or tap unless [pkg] owns the active window: transient
        // overlay windows (HyperOS permissioncontroller pops up during cold
        // starts) must neither be tapped themselves nor cause their
        // full-screen screenshot to be tapped at the ad's coordinates.
        val root = rootInActiveWindow
        if (root?.packageName?.toString() != pkg) {
            Timber.d(
                "active window is %s, not %s, skip tick",
                root?.packageName ?: "<null root>", pkg,
            )
            return false
        }
        // Ad-SDK fingerprints in the tree are evidence too (cheap: splash
        // trees are tiny and the walk is capped).
        if (pkg != packageName && !adEvidence.isAdConfirmed(pkg) &&
            NodeMatcher.hasAdSdkMarker(root, SDK_SCAN_NODE_CAP)
        ) {
            Timber.d("ad SDK marker in %s tree", pkg)
            adEvidence.noteSdkSignal(pkg)
        }
        var effective = s
        if (s.layer3Enabled && !inCoreWindow) {
            val treeSize = NodeMatcher.treeSize(root, SPLASH_TREE_MAX_NODES + 1)
            if (treeSize > SPLASH_TREE_MAX_NODES) {
                effective = s.copy(layer3Enabled = false)
                Timber.d("tree size %d+ — L3 off for this tick", treeSize)
            }
        }
        val selfTesting = s.selfTest && pkg == packageName
        if (!processing.compareAndSet(false, true)) {
            Timber.d("detection already running, skip %s", pkg)
            return false
        }
        lastAttemptAt[pkg] = android.os.SystemClock.elapsedRealtime()
        Timber.d("detecting in %s", pkg)
        val t0 = android.os.SystemClock.elapsedRealtime()
        try {
            val model = ModelCatalog.byId(effective.activeModelId) ?: ModelCatalog.default
            val pipeline = DetectionPipeline.create(engine, effective, model, yolo, selfLabels)
            val result = pipeline.detect(
                root = root,
                screenshot = {
                    ScreenshotCapturer.capture(this)?.also { bmp ->
                        if (effective.debugOverlay) dumpDebugFrame(bmp)
                    }
                },
                settings = effective,
                imageLayerGate = { lines ->
                    // Feed this tick's OCR into the evidence tracker first, so
                    // a badge/countdown visible right now unlocks L3 in the
                    // same tick.
                    adEvidence.observeOcr(pkg, lines)
                    val open = selfTesting || adEvidence.isAdConfirmed(pkg)
                    if (!open) Timber.d("no ad evidence in %s — image L3 gated", pkg)
                    open
                },
            )
            val target = result.target ?: run {
                Timber.d("no target in %s, timings=%s", pkg, result.timings)
                return false
            }
            Timber.i(
                "skip hit in %s via %s at (%.0f, %.0f), timings=%s",
                pkg, target.layer, target.x, target.y, result.timings,
            )
            if (!performClick(target.x, target.y)) return false
            val elapsed = android.os.SystemClock.elapsedRealtime() - t0
            statsRepo.record(pkg, target.layer.name, elapsed)
            if (s.debugOverlay) {
                overlay.update("${target.layer}  (${target.x.toInt()}, ${target.y.toInt()})  ${elapsed}ms")
            }
            return true
        } catch (t: Throwable) {
            Timber.w(t, "detection failed for %s", pkg)
            return false
        } finally {
            processing.set(false)
        }
    }

    /** Debug aid (debugOverlay on): persist the frames the pipeline actually
     *  sees during splash windows, so detector misses can be distinguished
     *  from stale takeScreenshot frames. Pull via run-as from filesDir/shots. */
    private fun dumpDebugFrame(bmp: android.graphics.Bitmap) {
        scope.launch(Dispatchers.IO) {
            try {
                val dir = java.io.File(filesDir, "shots").apply { mkdirs() }
                val old = dir.listFiles()?.sortedBy { it.name }.orEmpty()
                old.dropLast(KEEP_DEBUG_FRAMES - 1).forEach { it.delete() }
                java.io.File(dir, "${System.currentTimeMillis()}.png")
                    .outputStream().use { bmp.compress(android.graphics.Bitmap.CompressFormat.PNG, 90, it) }
            } catch (t: Throwable) {
                Timber.w(t, "debug frame dump failed")
            }
        }
    }

    private fun appVersionCode(pkg: String): Long = try {
        packageManager.getPackageInfo(pkg, 0).longVersionCode
    } catch (t: Throwable) {
        -1L
    }

    private fun performClick(x: Float, y: Float): Boolean {
        val path = Path().apply { moveTo(x, y) }
        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0, TAP_DURATION_MS))
            .build()
        // The system can cancel a dispatched gesture (touch collisions,
        // window transitions) and the ad plays on as if never tapped — log
        // the outcome so those runs are diagnosable from logcat.
        val callback = object : AccessibilityService.GestureResultCallback() {
            override fun onCompleted(g: GestureDescription?) {
                Timber.d("tap completed at (%.0f, %.0f)", x, y)
            }

            override fun onCancelled(g: GestureDescription?) {
                Timber.w("tap CANCELLED at (%.0f, %.0f)", x, y)
            }
        }
        return dispatchGesture(gesture, callback, pulseHandler)
    }

    override fun onInterrupt() {
        Timber.i("AdSkipperService interrupted")
    }

    override fun onDestroy() {
        overlay.dismiss()
        releaseSplashWakeLock()
        stopThawPulse()
        removePulseView()
        try {
            unregisterReceiver(screenReceiver)
        } catch (t: Throwable) {
            Timber.w(t, "screen receiver unregister failed")
        }
        try {
            getSystemService(AlarmManager::class.java).cancel(watchdogListener)
        } catch (t: Throwable) {
            Timber.w(t, "watchdog cancel failed")
        }
        scope.launch { engine.release() }
        scope.cancel()
        super.onDestroy()
    }

    companion object {
        private const val ATTEMPT_COOLDOWN_MS = 2000L

        /** L3 image detectors run unconditionally this long after an app
         *  session starts. */
        private const val SPLASH_WINDOW_MS = 8000L

        /** Default cap on splash polling. Sized for slow-launching apps whose
         *  ad appears late and plays long (hupu: white splash ~8s, then a
         *  ~40s silent ad). A confirmed countdown may stretch polling past
         *  this, up to [SPLASH_WINDOW_HARD_CAP_MS]. */
        private const val SPLASH_WINDOW_EXTENDED_MS = 45_000L

        /** Absolute ceiling on splash polling, countdown or not. */
        private const val SPLASH_WINDOW_HARD_CAP_MS = 90_000L

        /** Node budget for the per-tick ad-SDK fingerprint walk. */
        private const val SDK_SCAN_NODE_CAP = 60

        /** After this many consecutive ad-free splash sessions an app runs
         *  in L1-only mode (no screenshots/OCR/image L3). */
        private const val BARREN_SESSIONS_TO_DOWNGRADE = 15

        /** Every Nth ad-free session of a downgraded app probes at full
         *  strength, so an app that starts showing ads is re-detected. */
        private const val PROBE_EVERY_SESSIONS = 5

        /** A screen whose node tree exceeds this is real app UI, not a
         *  splash ad (splash screens are a handful of ad-SDK views; feeds
         *  are hundreds of nodes). */
        private const val SPLASH_TREE_MAX_NODES = 30

        /** Gap without events after which the next event starts a new session. */
        private const val SESSION_GAP_MS = 20_000L
        private const val TAP_DURATION_MS = 50L

        /** Self-thaw pulse cadence; must stay below the ~1s freeze grace
         *  period observed for HyperOS GreezeManager. */
        private const val THAW_PULSE_INTERVAL_MS = 300L

        /** Watchdog alarm cadence. Bounds how long the service can stay
         *  frozen (and thus miss app launches) after waking from a
         *  screen-off period. Non-wakeup alarm, so no sleep-battery cost. */
        private const val WATCHDOG_INTERVAL_MS = 60_000L

        /** Delay before warming up the YOLO native engine after connect;
         *  see onServiceConnected. */
        private const val YOLO_WARMUP_DELAY_MS = 8000L

        /** How many pipeline frames to keep for debugOverlay frame dumps. */
        private const val KEEP_DEBUG_FRAMES = 12

        /** Skipped before the first splash poll: launch transitions briefly
         *  show a snapshot of the app's previous session, and both OCR and the
         *  node tree see that stale frame. */
        private const val SPLASH_POLL_INITIAL_DELAY_MS = 500L
        private const val SPLASH_POLL_INTERVAL_MS = 750L

        /** Taps per splash session are capped so a phantom target (matched but
         *  not dismissible) is never tapped indefinitely. */
        private const val SPLASH_MAX_TAPS = 3

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

        /** Common browsers, backstopping [resolveBrowserPackages] for OEM
         *  builds where the intent query misses (role-gated resolution,
         *  package-visibility filtering). Never detect or tap in these. */
        private val BROWSER_PACKAGES = setOf(
            "com.android.chrome",              // Chrome
            "com.chrome.beta",
            "com.chrome.dev",
            "org.mozilla.firefox",             // Firefox
            "com.microsoft.emmx",              // Edge
            "com.opera.browser",
            "com.brave.browser",
            "com.android.browser",             // AOSP / MIUI CN browser
            "com.mi.globalbrowser",            // Mi Browser (global)
            "com.heytap.browser",              // OPPO
            "com.coloros.browser",
            "com.vivo.browser",
            "com.huawei.browser",
            "com.sec.android.app.sbrowser",    // Samsung Internet
            "com.tencent.mtt",                 // QQ 浏览器
            "com.baidu.browser.apps",          // 百度浏览器
        )

        /** CN browsers that show their own splash ads on launch — the user
         *  wants those skipped, so they stay in the detection pipeline even
         *  though the browser intent query resolves them. */
        private val SPLASH_AD_BROWSERS = setOf(
            "com.UCMobile",                    // UC 浏览器
            "com.ucmobile.lite",
            "com.quark.browser",               // 夸克
        )
    }
}
