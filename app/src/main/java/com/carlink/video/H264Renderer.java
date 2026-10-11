package com.carlink.video;

import android.media.MediaCodec;
import android.media.MediaFormat;
import android.os.Process;
import android.os.SystemClock;
import android.view.Surface;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.LockSupport;

import com.carlink.util.AppExecutors;
import com.carlink.util.LogCallback;


/**
 * Hardware-accelerated H.264 decoder using MediaCodec SYNC mode.
 *
 * Decodes video from the CPC200-CCPA adapter and renders to a {@link Surface}.
 * CarPlay/Android Auto streams are live UI — prioritize immediacy over fidelity.
 *
 * Sync codec approach matches the proven Autokit AvcDecoder pattern:
 * - Bare MediaFormat (no KEY_LOW_LATENCY, KEY_PRIORITY, KEY_MAX_INPUT_SIZE, csd-0/csd-1)
 * - All frames fed with flags=0 (no BUFFER_FLAG_CODEC_CONFIG)
 * - PTS from elapsed time: (uptimeMillis - startDecodeTime) * 1000 microseconds
 * - Separate render thread started on first queued frame
 * - No frame dropping — render all decoded frames immediately
 */
public class H264Renderer {

    /** Callback to request keyframe (IDR) from adapter after codec reset. */
    public interface KeyframeRequestCallback {
        void onKeyframeNeeded();
    }

    /** Callback when SPS/PPS are extracted from stream for persistent caching. */
    public interface CsdExtractedCallback {
        void onCsdExtracted(byte[] sps, int spsLength, byte[] pps, int ppsLength);
    }

    /** Pre-allocated frame buffer for staging between USB and feeder threads.
     *  Ownership transfer via SPSC ring buffer with volatile indices provides happens-before guarantee. */
    private static final class StagedFrame {
        final byte[] data;
        int length;
        StagedFrame(int capacity) {
            this.data = new byte[capacity];
        }
    }

    private volatile MediaCodec mCodec;
    private int width;
    private int height;
    private Surface surface;
    private volatile boolean running = false;
    private volatile boolean stopped = false;     // Shutdown flag — prevents timer resurrection; cleared on resume()
    private volatile java.util.Timer retryTimer;  // Stored for cancellation in stop()
    private final LogCallback logCallback;
    private KeyframeRequestCallback keyframeCallback;
    private CsdExtractedCallback csdCallback;

    // Cached CSD for codec reconfigure (persists across stop/start cycles).
    // In sync mode these are cached but NOT pre-warmed into MediaFormat.
    private byte[] cachedSps;
    private int cachedSpsLength;
    private byte[] cachedPps;
    private int cachedPpsLength;

    // Android Auto mode — enables AA-specific decoder behaviors:
    //   - Skip first N rendered frames (boot-screen IDR avoidance)
    //   - VIDEO_SCALING_MODE_SCALE_TO_FIT_WITH_CROPPING
    private volatile boolean androidAutoMode = false;

    // AA first-frame skip: decoded frames are released with render=false until this count
    // reaches AA_RENDER_SKIP_COUNT. Prevents the deterministic 2,735B boot-screen IDR
    // from ever displaying. AutoKit uses 4; we use the same threshold.
    private static final int AA_RENDER_SKIP_COUNT = 4;
    private final AtomicLong aaRenderedFrameCount = new AtomicLong(0);

    private final AppExecutors executors;
    private final String preferredDecoderName;

    // Essential monitoring only
    private final AtomicLong totalFramesDecoded = new AtomicLong(0);
    private final AtomicLong codecResetCount = new AtomicLong(0);
    private static final long PERF_LOG_INTERVAL_MS = 30000;
    private long lastPerfLogTime = 0;

    // Pipeline diagnostic counters — always updated; only the periodic logStats()
    // emission is gated (via the VIDEO_PERF tag in logPerf). They identify
    // WHERE the pipeline breaks when video freezes.
    private final AtomicLong framesReceived = new AtomicLong(0);
    private final AtomicLong feedSuccesses = new AtomicLong(0);
    private volatile long lastFeedTime = 0;
    private volatile long lastRenderTime = 0;

    // Silent failure tracking - helps diagnose zombie codec state
    private final AtomicLong feedExceptionCount = new AtomicLong(0);
    private volatile String lastFeedException = null;

    // Drop tracking with NAL type awareness
    // IDR drops are catastrophic (all subsequent P-frames corrupt until next IDR)
    private final AtomicLong idrDropCount = new AtomicLong(0);
    // Accumulative IDR drop counter (not reset by logStats — for total session tracking)
    private final AtomicLong sessionIdrDrops = new AtomicLong(0);

    // Watchdog: non-resettable session counters (avoid logStats reset interference)
    private final AtomicLong sessionFramesReceived = new AtomicLong(0);
    private final AtomicLong sessionFramesDecoded = new AtomicLong(0);

    // Watchdog executor and state
    private ScheduledExecutorService watchdogExecutor;
    private long watchdogLastReceivedSnapshot;
    private long watchdogLastDecodedSnapshot;
    private int watchdogConsecutiveFailures;
    private int watchdogConsecutiveResets;
    private long watchdogLastResetTimeMs;
    private volatile long lastLifecycleEventTime;

    // Watchdog constants
    private static final long WATCHDOG_INTERVAL_MS = 1000;
    private static final long WATCHDOG_GRACE_PERIOD_MS = 3000;
    private static final long WATCHDOG_HEALTHY_RESET_MS = 30000;
    private static final int WATCHDOG_CONSECUTIVE_FAILURES_THRESHOLD = 2;
    private static final long[] WATCHDOG_COOLDOWN_STEPS_MS = {3000, 5000, 10000, 15000};

    // First-frame flag — survives logStats() counter resets
    private volatile boolean firstFrameLogged = false;

    // Session-start burst logging: full frame detail for first 3 seconds after codec start,
    // then silent. Captures natural IDR arrival, requested IDR response, and sync timing
    // without spamming logs for the remainder of the session. Resets on every start() cycle
    // (session start, watchdog reset, resume, manual reset).
    private static final long BURST_LOG_WINDOW_MS = 3000;

    // Sync gate — discard P-frames until first SPS/PPS+IDR arrives.
    // Prevents feeding undecodable frames if initial keyframe bundle is lost.
    private volatile boolean syncAcquired = false;

    // PTS tracking for sync mode: elapsed time from first frame
    private volatile long startDecodeTime = 0;
    private final AtomicLong frameCnt = new AtomicLong(0);

    // Lock for codec lifecycle operations (stop/reset/resume).
    // Prevents races between watchdog, reset(), and stop() running
    // on different threads (executor, main thread).
    private final Object codecLock = new Object();

    // FIFO staging queue (SPSC ring): USB thread writes, feeder thread reads.
    // 4-slot power-of-2 ring (3 usable) absorbs short USB frame bursts. On sustained
    // overflow, the incoming frame is dropped (queued frames preserved) and a
    // reactive keyframe request is fired so we don't render corrupted P-frames.
    // 6 buffers: 1 write + up to 3 in queue + 1 feeder + 1 pool margin.
    private static final int STAGED_FRAME_CAPACITY = 2 * 1024 * 1024;  // 2MB — covers AA 1080p (~1.5MB) and CarPlay up to 2400x960 (~1.7MB) worst-case H.264 frames. NOT enough for CarPlay 4K (~6MB); see resolution-derived sizing if higher tiers are used.
    private static final int STAGED_FRAME_COUNT = 6;               // write + queue(3) + feed + margin
    private static final int STAGING_QUEUE_SLOTS = 4;              // power-of-2, 3 usable slots

    private final StagedFrame[] stagingQueue = new StagedFrame[STAGING_QUEUE_SLOTS];
    private volatile int sqHead = 0;  // written by USB thread only
    private volatile int sqTail = 0;  // written by feeder thread only
    private final ConcurrentLinkedQueue<StagedFrame> framePool = new ConcurrentLinkedQueue<>();
    private StagedFrame writeFrame;                                // USB thread only
    // Backing store for the staging pool. Allocated ONCE per renderer on the first
    // initStaging() and reused across every stopInternal()/start() cycle (reset(), resume(),
    // watchdog reset, display-mode reinit). Previously each start() allocated a fresh
    // 6 x 2 MB = 12 MB set and stopStaging() dropped the old one: 12 MB of large-object
    // garbage plus 12 MB of fresh allocation at exactly the moment the decoder restarts, on
    // a no-swap, LMK-pressured head unit. Slot size and count are unchanged (rev 142 sizing).
    private StagedFrame[] stagingFrames;
    // Bumped on every initStaging(). The feeder captures it at start and stops looping and
    // returning frames once it goes stale, so a feeder that outlived stopStaging()'s 1 s join
    // can never hand a frame back into the NEXT session's pool (which would alias a buffer
    // now that the pool is reused instead of reallocated).
    private volatile int stagingGeneration = 0;
    private volatile Thread feederThread;
    private volatile Thread renderThread;
    private final AtomicLong stagingDropCount = new AtomicLong(0);
    private final AtomicLong oversizedDropCount = new AtomicLong(0);

    // Reactive keyframe request after a staging drop: signal the feeder thread to
    // ask the source for an IDR so we don't render corrupted P-frames until the
    // next natural keyframe. Cooldown bounds how often we re-request.
    private volatile boolean stagingOverwriteDetected = false;
    private volatile long lastReactiveKeyframeTimeNs = 0;
    private static final long REACTIVE_KEYFRAME_COOLDOWN_NS = 500_000_000L;  // 500ms

    // Sync codec timeouts (microseconds)
    private static final long DEQUEUE_INPUT_TIMEOUT_US = 100_000L;   // 100ms
    private static final long DEQUEUE_OUTPUT_TIMEOUT_US = 100_000L;  // 100ms

    // Render-thread resilience (rev 156, surface-freeze option D). A sync-mode
    // mCodec.flush() — flushCodec() on settings-overlay close — cancels the render thread's
    // pending dequeueOutputBuffer with an IllegalStateException. The loop used to `break` on
    // that and the H264-Render thread exited for good: the codec kept accepting input, the
    // output ports filled, the feeder then stalled on dequeueInputBuffer, and nothing drew
    // until the watchdog's zombie reset (Potato captures Z2–Z5, 2.2 s to 46.9 s frozen).
    // Now a dequeue error only backs the thread off briefly; it keeps looping while the
    // renderer is running and its pool generation is current. The backoff is a plain
    // parkNanos — no lock is held while sleeping, and 50 ms keeps a persistently faulting
    // codec from spinning hot until the watchdog resets it.
    private static final long RENDER_ERROR_BACKOFF_NS = 50_000_000L;   // 50ms
    private static final long RENDER_ERROR_LOG_EVERY = 100;            // log 1st, then every 100th

    public H264Renderer(int width, int height, Surface surface, LogCallback logCallback,
                        AppExecutors executors, String preferredDecoderName) {
        this.width = width;
        this.height = height;
        this.surface = surface;
        this.logCallback = logCallback;
        this.executors = executors;
        this.preferredDecoderName = preferredDecoderName;
    }

    public void setKeyframeRequestCallback(KeyframeRequestCallback callback) {
        this.keyframeCallback = callback;
    }

    public void setCsdExtractedCallback(CsdExtractedCallback callback) {
        this.csdCallback = callback;
    }

    /**
     * Enable Android Auto decoder mode.
     *
     * Effects:
     * - first-frame render skip: takes effect immediately on the running codec.
     * - {@code VIDEO_SCALING_MODE_SCALE_TO_FIT_WITH_CROPPING}: only applied inside
     *   {@link #initCodec}, so toggling AA mode on a live codec will NOT change the
     *   scaling mode until the next start/reset.
     */
    public void setAndroidAutoMode(boolean enabled) {
        this.androidAutoMode = enabled;
        log("[VIDEO] Android Auto mode: " + enabled);
    }

    private void log(String message) {
        logCallback.log("H264_RENDERER", message);
    }

    private void logPerf(String message) {
        logCallback.logPerf("VIDEO_PERF", message);
    }

    public void start() {
        if (stopped) return;
        if (running) return;

        running = true;
        totalFramesDecoded.set(0);
        sessionFramesReceived.set(0);
        sessionFramesDecoded.set(0);
        frameCnt.set(0);
        startDecodeTime = 0;
        lastLifecycleEventTime = System.currentTimeMillis();
        firstFrameLogged = false;
        syncAcquired = false;
        aaRenderedFrameCount.set(0);

        log("start - " + width + "x" + height);

        try {
            initCodec(width, height, surface);
            mCodec.start();
            initStaging();
            startWatchdog();
            log("Codec started (sync mode)");
        } catch (Exception e) {
            log("start error: " + e);
            stopStaging();

            // Release the codec created by initCodec() to prevent native MediaCodec leak.
            if (mCodec != null) {
                try { mCodec.release(); } catch (Exception re) { /* ignore */ }
                mCodec = null;
            }

            running = false;

            if (!stopped) {
                log("restarting in 5s");
                retryTimer = new java.util.Timer();
                retryTimer.schedule(new java.util.TimerTask() {
                    @Override
                    public void run() {
                        start();
                    }
                }, 5000);
            }
        }
    }

    public void stop() {
        stopped = true;
        stopInternal();
    }

    /** Internal stop — tears down codec without setting the permanent stopped flag.
     *  Used by reset() and resume() for mid-session restarts. */
    private void stopInternal() {
        // Cancel any pending start() retry to prevent resurrection after stop().
        if (retryTimer != null) {
            retryTimer.cancel();
            retryTimer = null;
        }

        if (!running) return;

        running = false;
        stopWatchdog();
        stopStaging();  // Join feeder AND render threads BEFORE stopping codec

        synchronized (codecLock) {
            if (mCodec != null) {
                log("Codec stopped");
                try {
                    mCodec.flush();
                    mCodec.stop();
                } catch (Exception e) {
                    log("stop error: " + e);
                } finally {
                    // Always release the codec even if stop() threw — prevents
                    // native MediaCodec leak which exhausts the system codec pool.
                    try {
                        mCodec.release();
                    } catch (Exception e) {
                        log("release error: " + e);
                    }
                }
                mCodec = null;
            }
        }

        surface = null;
    }

    public void reset() {
        synchronized (codecLock) {
            long resetCount = codecResetCount.incrementAndGet();
            log("Reset #" + resetCount);
            lastLifecycleEventTime = System.currentTimeMillis();

            Surface savedSurface = this.surface;
            stopInternal();
            this.surface = savedSurface;
            start();
        }

        if (keyframeCallback != null) {
            try {
                keyframeCallback.onKeyframeNeeded();
            } catch (Exception e) {
                log("[VIDEO_ERROR] Keyframe request failed: " + e);
            }
        }
    }

    /** Resume with new surface after returning from background. */
    public void resume(Surface newSurface) {
        log("[LIFECYCLE] resume()");
        lastLifecycleEventTime = System.currentTimeMillis();

        if (newSurface == null || !newSurface.isValid()) {
            log("[LIFECYCLE] Invalid surface");
            return;
        }

        synchronized (codecLock) {
            // If codec is running, just swap the surface without restart
            if (running && mCodec != null) {
                try {
                    mCodec.setOutputSurface(newSurface);
                    this.surface = newSurface;
                    log("[LIFECYCLE] Surface swapped without restart");
                    return;
                } catch (Exception e) {
                    log("[LIFECYCLE] setOutputSurface failed, doing full restart: " + e.getMessage());
                }
            }

            // Full restart needed (codec not running or setOutputSurface failed)
            stopInternal();
            this.surface = newSurface;
            stopped = false;  // Clear shutdown flag — resume is an intentional restart
            start();
        }

        if (keyframeCallback != null) {
            try {
                keyframeCallback.onKeyframeNeeded();
            } catch (Exception e) {
                log("[VIDEO_ERROR] Keyframe request failed: " + e);
            }
        }
    }

    /**
     * Flush the codec to release stalled BufferQueue output buffers.
     * Used when the SurfaceView is re-exposed after being covered by an overlay
     * (settings screen). Unlike Activity onStop/onStart, overlays don't trigger
     * lifecycle events, so the codec may stall while SurfaceFlinger isn't consuming.
     *
     * After flush, the codec needs a new IDR to resume decoding. The caller should
     * request a keyframe from the adapter (for CarPlay) or wait for natural IDR (AA).
     *
     * Rev 156 note (behavior deliberately unchanged here): flush()+start() is the ASYNC
     * idiom left over from before the rev 98 sync rewrite. In sync mode the framework
     * returns the codec to STARTED on its own when the flush completes
     * (MediaCodec kWhatFlushCompleted, non-async branch), so the start() below throws
     * IllegalStateException("start failed") every time and is logged as "Codec flush
     * failed" — the codec itself stays usable. The flush also cancels the render thread's
     * blocked dequeueOutputBuffer; {@link #renderLoop} now survives that instead of
     * exiting (see RENDER_ERROR_BACKOFF_NS), which is what makes this path recover.
     */
    public void flushCodec() {
        synchronized (codecLock) {
            if (!running || mCodec == null) return;
            try {
                mCodec.flush();
                mCodec.start();
                log("[LIFECYCLE] Codec flushed (overlay recovery)");
            } catch (Exception e) {
                log("[LIFECYCLE] Codec flush failed: " + e.getMessage());
            }
        }
    }

    // ==================== Codec Watchdog ====================

    /** Start the codec zombie watchdog. Schedules periodic health checks. */
    private void startWatchdog() {
        stopWatchdog();
        watchdogLastReceivedSnapshot = sessionFramesReceived.get();
        watchdogLastDecodedSnapshot = sessionFramesDecoded.get();
        watchdogConsecutiveFailures = 0;
        watchdogLastResetTimeMs = 0;

        ScheduledExecutorService exec =
                Executors.newSingleThreadScheduledExecutor(r -> {
                    Thread t = new Thread(r, "CodecWatchdog");
                    t.setDaemon(true);
                    return t;
                });
        watchdogExecutor = exec;
        exec.scheduleAtFixedRate(this::watchdogTick, WATCHDOG_INTERVAL_MS, WATCHDOG_INTERVAL_MS,
                TimeUnit.MILLISECONDS);
    }

    /** Stop the codec zombie watchdog. */
    private void stopWatchdog() {
        ScheduledExecutorService exec = watchdogExecutor;
        watchdogExecutor = null;
        if (exec != null) {
            exec.shutdownNow();
        }
    }

    /** Periodic watchdog tick — detects zombie codec (receiving frames but not decoding). */
    private void watchdogTick() {
        try {
            if (!running || surface == null || !surface.isValid()) return;

            long now = System.currentTimeMillis();
            if (now - lastLifecycleEventTime < WATCHDOG_GRACE_PERIOD_MS) return;

            long currentReceived = sessionFramesReceived.get();
            long currentDecoded = sessionFramesDecoded.get();
            long receivedDelta = currentReceived - watchdogLastReceivedSnapshot;
            long decodedDelta = currentDecoded - watchdogLastDecodedSnapshot;
            watchdogLastReceivedSnapshot = currentReceived;
            watchdogLastDecodedSnapshot = currentDecoded;

            // Healthy: decoded > 0 — reset consecutive resets counter after sustained health
            if (decodedDelta > 0) {
                watchdogConsecutiveFailures = 0;
                if (watchdogLastResetTimeMs > 0 && now - watchdogLastResetTimeMs >= WATCHDOG_HEALTHY_RESET_MS) {
                    watchdogConsecutiveResets = 0;
                }
                return;
            }

            // Receiving but not decoding — potential zombie
            if (receivedDelta > 0 && decodedDelta == 0) {
                watchdogConsecutiveFailures++;
            } else {
                watchdogConsecutiveFailures = 0;
                return;
            }

            if (watchdogConsecutiveFailures < WATCHDOG_CONSECUTIVE_FAILURES_THRESHOLD) return;

            // Check cooldown
            int cooldownIndex = Math.min(watchdogConsecutiveResets, WATCHDOG_COOLDOWN_STEPS_MS.length - 1);
            long cooldownMs = WATCHDOG_COOLDOWN_STEPS_MS[cooldownIndex];
            if (watchdogLastResetTimeMs > 0 && now - watchdogLastResetTimeMs < cooldownMs) return;

            // Trigger reset
            watchdogConsecutiveResets++;
            watchdogConsecutiveFailures = 0;
            watchdogLastResetTimeMs = now;

            log("[WATCHDOG] Zombie codec detected — Rx:" + receivedDelta + " Dec:0 for " +
                    WATCHDOG_CONSECUTIVE_FAILURES_THRESHOLD + "s. Reset #" + watchdogConsecutiveResets);

            if (watchdogConsecutiveResets > 5) {
                log("[WATCHDOG] WARNING: >5 resets — possible persistent hardware fault");
            }

            executors.mediaCodec1().execute(() -> reset());
        } catch (Exception e) {
            log("[WATCHDOG] Tick error: " + e.getMessage());
        }
    }

    /**
     * Cache CSD data for future use. In sync mode this is a no-op for codec —
     * data is just cached, not pre-warmed into MediaFormat.
     * Safe to call from any thread (acquires codecLock).
     */
    public void configureWithCsd(byte[] sps, byte[] pps) {
        synchronized (codecLock) {
            // Just cache the data — sync mode feeds CSD as normal frames with flags=0
            cachedSps = sps;
            cachedSpsLength = sps != null ? sps.length : 0;
            cachedPps = pps;
            cachedPpsLength = pps != null ? pps.length : 0;

            log("[VIDEO] configureWithCsd: cached SPS=" + cachedSpsLength +
                    "B, PPS=" + cachedPpsLength + "B (no-op in sync mode)");
        }
    }

    private void initCodec(int width, int height, Surface surface) throws IOException {
        log("init codec: " + width + "x" + height);

        MediaCodec codec = null;
        String codecName = null;

        // Try preferred decoder (Intel platforms)
        if (preferredDecoderName != null && !preferredDecoderName.isEmpty()) {
            try {
                codec = MediaCodec.createByCodecName(preferredDecoderName);
                codecName = preferredDecoderName;
                log("Using decoder: " + codecName);
            } catch (IOException e) {
                log("Preferred decoder unavailable: " + e.getMessage());
            }
        }

        // Fallback to generic decoder
        if (codec == null) {
            codec = MediaCodec.createDecoderByType("video/avc");
            codecName = codec.getName();
            log("Using decoder: " + codecName);
        }

        mCodec = codec;

        // Bare MediaFormat — no KEY_LOW_LATENCY, KEY_PRIORITY, KEY_MAX_INPUT_SIZE, csd-0/csd-1
        // Matches the proven Autokit AvcDecoder approach that avoids P-frame corruption.
        MediaFormat mediaformat = MediaFormat.createVideoFormat("video/avc", width, height);

        log("Init: " + codecName + " @ " + width + "x" + height + " (bare format, sync mode)");

        // NO setCallback — sync mode uses dequeueInputBuffer/dequeueOutputBuffer
        mCodec.configure(mediaformat, surface, null, 0);

        // AA: crop-scale to fit letterboxed content (removes black bars at codec level)
        if (androidAutoMode) {
            try {
                mCodec.setVideoScalingMode(MediaCodec.VIDEO_SCALING_MODE_SCALE_TO_FIT_WITH_CROPPING);
                log("AA video scaling: SCALE_TO_FIT_WITH_CROPPING");
            } catch (Exception e) {
                log("AA scaling mode not supported: " + e.getMessage());
            }
        }

        log("Surface bound: valid=" + (surface != null && surface.isValid()));
    }

    /** Initialize staging buffers and start the feeder thread. Call after mCodec.start(). */
    private void initStaging() {
        // Clear FIFO queue
        for (int i = 0; i < STAGING_QUEUE_SLOTS; i++) {
            stagingQueue[i] = null;
        }
        sqHead = 0;
        sqTail = 0;
        framePool.clear();
        stagingDropCount.set(0);
        oversizedDropCount.set(0);
        stagingOverwriteDetected = false;
        lastReactiveKeyframeTimeNs = 0;

        // 6 StagedFrames: 1 -> writeFrame, 5 -> framePool. Allocated on the first start only;
        // later starts reuse the same buffers (contents are overwritten by the next feedDirect
        // arraycopy, and the queue/pool indices were reset above).
        if (stagingFrames == null) {
            stagingFrames = new StagedFrame[STAGED_FRAME_COUNT];
            for (int i = 0; i < STAGED_FRAME_COUNT; i++) {
                stagingFrames[i] = new StagedFrame(STAGED_FRAME_CAPACITY);
            }
            log("Staging pool allocated: " + STAGED_FRAME_COUNT + " x " + (STAGED_FRAME_CAPACITY / 1024)
                    + "KB (reused across codec restarts)");
        }
        stagingGeneration++;
        writeFrame = stagingFrames[0];
        for (int i = 1; i < STAGED_FRAME_COUNT; i++) {
            framePool.offer(stagingFrames[i]);
        }

        Thread t = new Thread(this::feederLoop, "H264-Feeder");
        t.setDaemon(true);
        feederThread = t;
        t.start();
        logPerf("Feeder started");
    }

    /** Stop the feeder thread, render thread, and release staging buffers. Call before mCodec.stop(). */
    private void stopStaging() {
        // Join feeder thread
        Thread ft = feederThread;
        feederThread = null;
        if (ft != null) {
            LockSupport.unpark(ft);
            try {
                ft.join(1000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            if (ft.isAlive()) {
                logPerf("Feeder thread did not exit within 1s");
            }
        }

        // Join render thread
        Thread rt = renderThread;
        renderThread = null;
        if (rt != null) {
            try {
                rt.join(1000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            if (rt.isAlive()) {
                logPerf("Render thread did not exit within 1s");
            }
        }

        // Clear FIFO queue
        for (int i = 0; i < STAGING_QUEUE_SLOTS; i++) {
            stagingQueue[i] = null;
        }
        sqHead = 0;
        sqTail = 0;
        framePool.clear();
        writeFrame = null;
    }

    /** Offer a frame to the SPSC staging queue. Called from USB thread only.
     *  @return true if enqueued, false if queue full (caller must handle drop). */
    private boolean stagingOffer(StagedFrame frame) {
        int next = (sqHead + 1) & (STAGING_QUEUE_SLOTS - 1);
        if (next == sqTail) return false;  // full
        stagingQueue[sqHead] = frame;
        sqHead = next;  // volatile write publishes
        return true;
    }

    /** Poll a frame from the SPSC staging queue. Called from feeder thread only.
     *  @return next frame, or null if queue empty. */
    private StagedFrame stagingPoll() {
        int t = sqTail;
        if (t == sqHead) return null;  // empty
        StagedFrame f = stagingQueue[t];
        stagingQueue[t] = null;
        sqTail = (t + 1) & (STAGING_QUEUE_SLOTS - 1);  // volatile write
        return f;
    }

    /** Feeder thread main loop — drains FIFO staging queue and feeds to codec via sync dequeueInputBuffer. */
    private void feederLoop() {
        Process.setThreadPriority(Process.THREAD_PRIORITY_DISPLAY);
        // Pool generation this feeder belongs to (see stagingGeneration). initStaging() bumps it
        // before starting the thread, so Thread.start() publishes the current value here.
        final int gen = stagingGeneration;
        try {
            while (running && stagingGeneration == gen) {
                StagedFrame frame = stagingPoll();
                if (frame != null) {
                    decodeFrame(frame, gen);
                    // Return the buffer only while this pool generation is still current — a
                    // stale feeder must never offer into a newer session's (reused) pool.
                    if (stagingGeneration == gen) framePool.offer(frame);

                    // After feeding, if a staging drop occurred, ask the source for an
                    // IDR (cooldown-bounded) so subsequent P-frames decode cleanly.
                    if (stagingOverwriteDetected) {
                        stagingOverwriteDetected = false;
                        long now = System.nanoTime();
                        if (now - lastReactiveKeyframeTimeNs > REACTIVE_KEYFRAME_COOLDOWN_NS) {
                            lastReactiveKeyframeTimeNs = now;
                            KeyframeRequestCallback cb = keyframeCallback;
                            if (cb != null) {
                                executors.mediaCodec1().execute(() -> {
                                    try {
                                        cb.onKeyframeNeeded();
                                        logPerf("Reactive keyframe request after staging drop");
                                    } catch (Exception e) {
                                        logPerf("Reactive request failed: " + e);
                                    }
                                });
                            }
                        }
                    }
                } else {
                    // Block until the USB producer enqueues a frame (feedDirect → unpark)
                    // or stopStaging() unparks for shutdown. park() consumes a permit, so an
                    // unpark racing just before this returns immediately and we re-poll — no
                    // lost frame. Spurious wakeups simply re-loop and re-poll.
                    LockSupport.park();
                }
            }
        } catch (Exception e) {
            logPerf("Feeder exception: " + e);
        }
        logPerf("Feeder stopped");
    }

    /**
     * Decode a staged frame using sync MediaCodec.
     * Called only from feeder thread.
     * Matches Autokit AvcDecoder.decode() pattern:
     * - dequeueInputBuffer with 100ms timeout
     * - PTS from elapsed time
     * - flags=0 for ALL frames (including CSD)
     * - Starts render thread on first frame
     *
     * @param gen the feeder's staging-pool generation; handed to the render thread it
     *            starts so a stale feeder can never spawn a render thread for the next
     *            session's codec (see {@link #renderLoop}).
     */
    private void decodeFrame(StagedFrame frame, int gen) {
        MediaCodec codec = mCodec;
        if (codec == null) return;

        // Gate: discard frames until first SPS/PPS+IDR sync point.
        // Adapter bundles SPS+PPS+IDR as one payload — getNalType() returns SPS (first NAL).
        int nalType = getNalType(frame.data, 0, frame.length);

        if (!syncAcquired) {
            if (nalType == NAL_SPS || nalType == NAL_IDR) {
                syncAcquired = true;
                log("[VIDEO] Sync acquired (" + nalTypeToString(nalType) + "), feeding to codec");
            } else {
                log("[BURST] Pre-sync drop: " + nalTypeToString(nalType) + " " + frame.length + "B");
                return;
            }
        }

        // Extract and cache SPS/PPS for CSD callback (but feed as normal data with flags=0)
        if (nalType == NAL_SPS) {
            extractAndCacheCsd(frame.data, 0, frame.length);
        }

        // PTS from elapsed time (matching Autokit pattern)
        long pts;
        long count = frameCnt.get();
        if (count == 0) {
            startDecodeTime = SystemClock.uptimeMillis();
            pts = 0;
        } else {
            pts = (SystemClock.uptimeMillis() - startDecodeTime) * 1000;
        }

        try {
            boolean inputted = false;
            while (!inputted && running) {
                int inputBufferIndex = codec.dequeueInputBuffer(DEQUEUE_INPUT_TIMEOUT_US);
                if (inputBufferIndex >= 0) {
                    // getInputBuffer(index): allocation-free, API 21+ (minSdk 29). Replaces the
                    // deprecated getInputBuffers()[index] array accessor.
                    ByteBuffer inputBuffer = codec.getInputBuffer(inputBufferIndex);
                    inputBuffer.clear();
                    inputBuffer.put(frame.data, 0, frame.length);
                    // ALL frames fed with flags=0 — no BUFFER_FLAG_CODEC_CONFIG ever
                    codec.queueInputBuffer(inputBufferIndex, 0, frame.length, pts, 0);

                    lastFeedTime = System.currentTimeMillis();
                    feedSuccesses.incrementAndGet();

                    if (count == 0) {
                        // Start render thread on first frame (matching Autokit)
                        startRenderThread(gen);
                    }
                    long frameNum = frameCnt.incrementAndGet();
                    inputted = true;

                    // Session-start burst log: full detail for first 3s after codec start
                    long elapsedMs = SystemClock.uptimeMillis() - startDecodeTime;
                    if (elapsedMs <= BURST_LOG_WINDOW_MS) {
                        log("[BURST] #" + frameNum + " " + nalTypeToString(nalType) +
                                " " + frame.length + "B @" + elapsedMs + "ms" +
                                (nalType == NAL_SPS ? " [SPS+PPS+IDR bundle]" : ""));
                    }

                } else {
                    logPerf("dequeueInputBuffer timeout: " + inputBufferIndex);
                }
            }
        } catch (Exception e) {
            long exCount = feedExceptionCount.incrementAndGet();
            lastFeedException = e.getClass().getSimpleName() + ": " + e.getMessage();
            if (exCount == 1 || exCount % 100 == 0) {
                logPerf("Feed exception (count=" + exCount + "): " + lastFeedException);
            }
        }
    }

    /**
     * Start the render thread. Called on first successfully queued frame.
     * Matches Autokit: render thread loops on dequeueOutputBuffer, releases with render=true.
     *
     * @param gen the staging-pool generation this render thread belongs to (the feeder's).
     */
    private void startRenderThread(final int gen) {
        Thread rt = new Thread(() -> renderLoop(gen), "H264-Render");
        rt.setDaemon(true);
        renderThread = rt;
        rt.start();
        logPerf("Render thread started");
    }

    /**
     * Render thread main loop — dequeues decoded output buffers and releases them to Surface.
     * Matches Autokit AvcDecoder render thread pattern:
     * - THREAD_PRIORITY_URGENT_AUDIO
     * - Initial dequeueOutputBuffer with 100ms timeout
     * - Inner polling loop with timeout=0
     * - All decoded frames rendered immediately (no frame dropping for live UI)
     *
     * Lifetime (rev 156): the thread exits ONLY when {@code running} drops (stop/reset/
     * resume teardown), when its pool generation goes stale (a newer start() happened —
     * the same guard the feeder uses, so a thread that outlived stopStaging()'s 1 s join
     * can never drain the next session's codec alongside its own render thread), or when
     * {@code mCodec} is null. Any exception from the codec — the IllegalStateException a
     * sync-mode flush() raises on the blocked dequeue, a transient CodecException — is
     * logged (rate-limited), followed by a short lock-free backoff, and the loop goes on.
     * In sync mode the codec is back in STARTED once the flush completes, so the very next
     * dequeueOutputBuffer succeeds and decoded frames reach the Surface again as soon as
     * the post-overlay IDR is fed. Nothing here touches the staging queue or pool.
     */
    private void renderLoop(final int gen) {
        Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_AUDIO);
        MediaCodec.BufferInfo bufferInfo = new MediaCodec.BufferInfo();
        long consecutiveErrors = 0;
        long totalErrors = 0;

        while (running && stagingGeneration == gen) {
            MediaCodec codec = mCodec;
            if (codec == null) break;

            try {
                int outputBufferIndex = codec.dequeueOutputBuffer(bufferInfo, DEQUEUE_OUTPUT_TIMEOUT_US);
                if (consecutiveErrors > 0) {
                    logPerf("Render thread recovered after " + consecutiveErrors + " dequeue error(s)");
                    consecutiveErrors = 0;
                }

                do {
                    if (outputBufferIndex == MediaCodec.INFO_TRY_AGAIN_LATER) {
                        // No output yet — fall through to exit do-while; the outer
                        // loop will re-block on dequeueOutputBuffer with the 100ms timeout.
                    } else if (outputBufferIndex == MediaCodec.INFO_OUTPUT_BUFFERS_CHANGED) {
                        // Deprecated since API 21 but still emitted on some devices; ignore.
                    } else if (outputBufferIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                        try {
                            MediaFormat format = codec.getOutputFormat();
                            int w = format.getInteger("width");
                            int h = format.getInteger("height");
                            int color = format.containsKey("color-format") ? format.getInteger("color-format") : -1;
                            log("Format: " + w + "x" + h + " color=" + color);
                        } catch (Exception e) {
                            log("Format query error: " + e.getMessage());
                        }
                    } else if (outputBufferIndex < 0) {
                        // Unexpected negative result — ignore
                    } else {
                        // Valid output buffer
                        if (bufferInfo.size > 0) {
                            totalFramesDecoded.incrementAndGet();
                            sessionFramesDecoded.incrementAndGet();
                            lastRenderTime = System.currentTimeMillis();
                            if (!firstFrameLogged) {
                                firstFrameLogged = true;
                                log("[VIDEO] First frame decoded");
                            }
                        }

                        try {
                            // AA first-frame skip: don't render the first N decoded frames.
                            boolean shouldRender = bufferInfo.size != 0;
                            if (shouldRender && androidAutoMode) {
                                long renderCount = aaRenderedFrameCount.incrementAndGet();
                                if (renderCount <= AA_RENDER_SKIP_COUNT) {
                                    shouldRender = false;
                                    if (renderCount == AA_RENDER_SKIP_COUNT) {
                                        log("[VIDEO] AA warmup complete — rendering enabled");
                                    }
                                }
                            }
                            codec.releaseOutputBuffer(outputBufferIndex, shouldRender);
                        } catch (Exception e) {
                            // Ignore release errors
                        }

                        // Inner polling loop: drain all available output buffers without
                        // blocking. An exception here propagates to the outer catch below
                        // (same backoff-and-continue handling as the blocking dequeue).
                        outputBufferIndex = codec.dequeueOutputBuffer(bufferInfo, 0);
                        continue;  // Process the newly dequeued buffer
                    }

                    // Non-data result (TRY_AGAIN_LATER, FORMAT_CHANGED, etc.): exit
                    // the inner drain loop and re-block on the outer dequeue.
                    outputBufferIndex = -1;
                } while (outputBufferIndex >= 0);
            } catch (Exception e) {
                // Codec error on this thread (flush-cancelled dequeue, transient
                // CodecException, ...). Stay alive unless we are being torn down.
                consecutiveErrors++;
                totalErrors++;
                if (!running || stagingGeneration != gen) break;
                if (consecutiveErrors == 1 || consecutiveErrors % RENDER_ERROR_LOG_EVERY == 0) {
                    logPerf("dequeueOutputBuffer error #" + consecutiveErrors + " (" + e
                            + ") — render thread staying alive, backing off "
                            + (RENDER_ERROR_BACKOFF_NS / 1_000_000L) + "ms");
                }
                // No lock is held here; stopInternal() only needs `running` to go false
                // for the loop to exit within one backoff + one 100 ms dequeue timeout,
                // well inside stopStaging()'s 1 s join.
                LockSupport.parkNanos(RENDER_ERROR_BACKOFF_NS);
            }
        }
        logPerf("Render thread stopped" + (totalErrors > 0 ? " (dequeue errors survived: " + totalErrors + ")" : "")
                + (stagingGeneration != gen ? " [stale generation]" : ""));
    }

    /**
     * Extract SPS/PPS from a CSD bundle and cache them.
     * Notifies CsdExtractedCallback for persistent storage.
     * Does NOT use BUFFER_FLAG_CODEC_CONFIG — just caches for the callback.
     *
     * Compare-before-copy: CarPlay bundles the same SPS+PPS with EVERY IDR (rev 51/57 USB
     * captures) and the app requests an IDR every 30 s, so the parameter sets almost never
     * change. The two copyOfRange allocations and the callback (a SharedPreferences write in
     * CarlinkManager) now only happen when the bytes actually differ from the cached copies.
     * Cache contents and consumers are unchanged (rev 75 pre-stage hook, kept; a documented
     * no-op for the sync-mode codec since rev 98 — see configureWithCsd).
     */
    private void extractAndCacheCsd(byte[] data, int offset, int length) {
        int csdEnd = length;
        int idrOffset = findNalOffset(data, offset + 4, length - 4, NAL_IDR);
        if (idrOffset > 0) {
            csdEnd = idrOffset - offset;
        }

        int ppsOffset = findNalOffset(data, offset + 4, csdEnd - 4, NAL_PPS);
        int spsLen = ppsOffset < 0 ? csdEnd : ppsOffset - offset;
        int ppsLen = ppsOffset < 0 ? 0 : offset + csdEnd - ppsOffset;

        if (csdMatchesCache(data, offset, spsLen, ppsOffset, ppsLen)) {
            return;  // identical to what is already cached (and persisted) — nothing to do
        }

        cachedSps = java.util.Arrays.copyOfRange(data, offset, offset + spsLen);
        cachedSpsLength = spsLen;
        if (ppsLen > 0) {
            cachedPps = java.util.Arrays.copyOfRange(data, ppsOffset, ppsOffset + ppsLen);
            cachedPpsLength = ppsLen;
        } else {
            cachedPps = null;
            cachedPpsLength = 0;
        }

        log("[VIDEO] Cached CSD: SPS=" + cachedSpsLength + "B, PPS=" + cachedPpsLength + "B");

        CsdExtractedCallback cb = csdCallback;
        if (cb != null) {
            cb.onCsdExtracted(cachedSps, cachedSpsLength, cachedPps, cachedPpsLength);
        }
    }

    /** True when the SPS (and PPS, if any) bytes in {@code data} are byte-identical to the cached copies. */
    private boolean csdMatchesCache(byte[] data, int spsOffset, int spsLen, int ppsOffset, int ppsLen) {
        byte[] sps = cachedSps;
        if (sps == null || cachedSpsLength != spsLen || cachedPpsLength != ppsLen) return false;
        if (!rangeEquals(sps, data, spsOffset, spsLen)) return false;
        if (ppsLen == 0) return true;
        byte[] pps = cachedPps;
        return pps != null && rangeEquals(pps, data, ppsOffset, ppsLen);
    }

    /** {@code cached[0..len) == data[dataOffset..dataOffset+len)} without allocating. */
    private static boolean rangeEquals(byte[] cached, byte[] data, int dataOffset, int len) {
        if (cached.length < len) return false;
        for (int i = 0; i < len; i++) {
            if (cached[i] != data[dataOffset + i]) return false;
        }
        return true;
    }

    // H.264 NAL unit types (ITU-T H.264 Table 7-1)
    private static final int NAL_SLICE = 1;       // Non-IDR slice (P/B frame)
    private static final int NAL_IDR = 5;         // IDR slice (keyframe)
    private static final int NAL_SEI = 6;         // Supplemental enhancement info
    private static final int NAL_SPS = 7;         // Sequence parameter set
    private static final int NAL_PPS = 8;         // Picture parameter set

    /**
     * Find the byte offset of a NAL unit with the given type in an Annex B bytestream.
     * Scans for 00 00 00 01 or 00 00 01 start codes.
     * @return offset of the start code, or -1 if not found
     */
    private static int findNalOffset(byte[] data, int offset, int length, int targetNalType) {
        int end = offset + length - 4;
        for (int i = offset; i <= end; i++) {
            if (data[i] == 0 && data[i + 1] == 0) {
                if (data[i + 2] == 0 && data[i + 3] == 1 && i + 4 < offset + length) {
                    if ((data[i + 4] & 0x1F) == targetNalType) return i;
                } else if (data[i + 2] == 1 && i + 3 < offset + length) {
                    if ((data[i + 3] & 0x1F) == targetNalType) return i;
                }
            }
        }
        return -1;
    }

    /**
     * Extract NAL unit type from H.264 Annex B bitstream.
     * Scans for start code (0x00000001 or 0x000001) and returns lower 5 bits of next byte.
     * Only scans first 32 bytes (start code is always at/near beginning).
     *
     * @return NAL unit type (1-31), or -1 if no valid start code found
     */
    private static int getNalType(byte[] data, int offset, int length) {
        int scanEnd = offset + Math.min(length, 32);
        for (int i = offset; i < scanEnd - 3; i++) {
            if (data[i] == 0 && data[i + 1] == 0) {
                // 4-byte start code: 0x00000001
                if (i < scanEnd - 4 && data[i + 2] == 0 && data[i + 3] == 1) {
                    return data[i + 4] & 0x1F;
                }
                // 3-byte start code: 0x000001
                if (data[i + 2] == 1) {
                    return data[i + 3] & 0x1F;
                }
            }
        }
        return -1;
    }

    private static String nalTypeToString(int nalType) {
        switch (nalType) {
            case NAL_SLICE: return "P/B";
            case NAL_IDR:   return "IDR";
            case NAL_SEI:   return "SEI";
            case NAL_SPS:   return "SPS";
            case NAL_PPS:   return "PPS";
            default:        return "NAL:" + nalType;
        }
    }

    /**
     * Stage H.264 data for codec feeding. GC-immune USB thread fast path.
     * Called from the USB read loop.
     *
     * Hot path: {@code System.arraycopy} into a pre-allocated buffer, then SPSC
     * ring offer. No JNI, no codec calls. The feeder thread handles all codec
     * interaction on its own timeline.
     *
     * @return true if frame was staged, false if dropped (oversized, no buffer, or queue full)
     */
    public boolean feedDirect(byte[] data, int offset, int length) {
        if (!running) return false;
        framesReceived.incrementAndGet();
        sessionFramesReceived.incrementAndGet();
        logStats();

        // Guard: reject frames exceeding staging capacity (corrupted USB data)
        if (length > STAGED_FRAME_CAPACITY) {
            oversizedDropCount.incrementAndGet();
            logPerf("Oversized drop: " + length + "B > " + STAGED_FRAME_CAPACITY + "B");
            return false;
        }

        StagedFrame wf = writeFrame;
        if (wf == null) {
            // Pool momentarily drained — writeFrame goes null after a successful stagingOffer()
            // when framePool was empty at that instant; the feeder returns a buffer within ~1ms.
            // Track this drop SYMMETRICALLY with the queue-full path below so an IDR lost here
            // isn't silently unrecovered. NAL type is read from the source buffer since there is
            // no staging buffer to inspect.
            stagingDropCount.incrementAndGet();
            stagingOverwriteDetected = true;
            if (getNalType(data, offset, length) == NAL_IDR) {
                long sessionTotal = sessionIdrDrops.incrementAndGet();
                idrDropCount.incrementAndGet();
                log("Pool drained, IDR dropped (" + length + "B) session=" + sessionTotal);
            }
            return false;
        }

        // The only real work: memcpy into pre-allocated buffer
        System.arraycopy(data, offset, wf.data, 0, length);
        wf.length = length;

        // FIFO enqueue — feeder thread drains in order
        if (stagingOffer(wf)) {
            // Wake the (possibly parked) feeder. LockSupport.unpark(null) is a documented
            // no-op, so the brief start/stop window where feederThread is null is harmless.
            LockSupport.unpark(feederThread);
            // Frame enqueued — get a fresh buffer from pool for next write
            writeFrame = framePool.poll();
            // writeFrame may be null briefly if feeder hasn't returned buffers yet.
            // The next feedDirect() call hits the null guard above, which now drops the
            // incoming frame with the same IDR-aware reactive-keyframe handling as the
            // queue-full path. The feeder returns buffers to the pool within ~1ms.
        } else {
            // Queue full — drop incoming frame (preserves FIFO order of already-queued frames).
            // wf stays as writeFrame for reuse (data will be overwritten next call).
            stagingDropCount.incrementAndGet();
            stagingOverwriteDetected = true;
            int nalType = getNalType(wf.data, 0, wf.length);
            if (nalType == NAL_IDR) {
                long sessionTotal = sessionIdrDrops.incrementAndGet();
                idrDropCount.incrementAndGet();
                log("Staging full, IDR dropped (" + wf.length + "B) session=" + sessionTotal);
            }
            return false;
        }

        return true;
    }

    private void logStats() {
        long currentTime = System.currentTimeMillis();
        if (currentTime - lastPerfLogTime >= PERF_LOG_INTERVAL_MS) {
            // Session stats (always logged via H264_RENDERER tag)
            log("Decoded:" + sessionFramesDecoded.get() + " Resets:" + codecResetCount.get() +
                    " IDR drops:" + sessionIdrDrops.get());

            // Pipeline diagnostic stats (gated by VIDEO_PERF tag)
            long rx = framesReceived.getAndSet(0);
            long dec = totalFramesDecoded.getAndSet(0);
            long successes = feedSuccesses.getAndSet(0);
            long lastFeedAge = lastFeedTime > 0 ? currentTime - lastFeedTime : -1;
            long lastRenderAge = lastRenderTime > 0 ? currentTime - lastRenderTime : -1;
            boolean surfaceValid = surface != null && surface.isValid();

            long exceptions = feedExceptionCount.getAndSet(0);
            long idrDrops = idrDropCount.getAndSet(0);

            StringBuilder sb = new StringBuilder();
            sb.append("Rx:").append(rx).append(" Dec:").append(dec)
              .append(" Fed:").append(successes);

            if (idrDrops > 0) {
                sb.append(" IDR_DROP:").append(idrDrops);
            }

            sb.append(" LastFeed:").append(lastFeedAge).append("ms LastRender:").append(lastRenderAge).append("ms")
              .append(" run=").append(running).append(" codec=").append(mCodec != null)
              .append(" surface=").append(surfaceValid);

            long stageDrops = stagingDropCount.getAndSet(0);
            long oversized = oversizedDropCount.getAndSet(0);
            if (stageDrops > 0 || oversized > 0) {
                sb.append(" STAGE[drop:").append(stageDrops)
                  .append(" oversized:").append(oversized).append("]");
            }

            if (exceptions > 0) {
                sb.append(" FAIL[exc:").append(exceptions).append("]");
                if (lastFeedException != null) {
                    sb.append(" lastExc=").append(lastFeedException);
                }
            }

            logPerf(sb.toString());

            lastPerfLogTime = currentTime;
        }
    }
}
