package com.carlink.platform

import android.graphics.SurfaceTexture
import android.media.MediaCodec
import android.media.MediaFormat
import android.os.Handler
import android.os.SystemClock
import android.view.Surface
import com.carlink.logging.Logger
import com.carlink.logging.logDebugOnly
import com.carlink.logging.logWarn
import java.io.IOException

/**
 * Clamps the video size requested from the adapter to one the local H.264 decoder will start.
 *
 * The adapter encodes whatever frame size the OPEN message asks for, normally the head unit's
 * usable display area. Some decoders cannot start at that size even though
 * [android.media.MediaCodecInfo.VideoCapabilities] says they can, and the result is a permanent
 * black screen: USB video keeps arriving, `MediaCodec.start()` throws, and the renderer
 * restarts forever.
 *
 * Observed on a Samsung Galaxy Tab A9+ (SM-X210, Snapdragon SM6375, Android 15): the panel is
 * 1920x1200 and `media_codecs.xml` advertises 4096x2176 for c2.qti.avc.decoder, but the
 * msm_vidc kernel driver refuses anything above 8160 macroblocks (1920x1088):
 *
 *     msm_vidc h264d: Unsupported mbpf 8165, max 8160
 *     QC2V4l2Decoder: start: Failed to set resolution (1840x1136) input port
 *
 * The advertised capabilities derive from `media_codecs.xml`, not the driver table, so the
 * only reliable test is to configure and start a throwaway decoder. Design points:
 *
 * - The probe uses the SAME codec the renderer will use ([PlatformDetector.hardwareH264DecoderName],
 *   `createByCodecName`, with the `createDecoderByType` fallback H264Renderer also uses), and
 *   configures with a real Surface so the vendor's surfaced allocation path is exercised.
 * - The size limit is monotone, so the search is a binary search on the longer side in
 *   16 px steps. Any limit is found in at most seven probes.
 * - Transient or resource failures (`CodecException.isTransient`, `isRecoverable`,
 *   `ERROR_INSUFFICIENT_RESOURCE`, `ERROR_RECLAIMED`, codec creation failure) abort the search
 *   and return the input UNCACHED. A busy codec at ignition boot must not shrink the stream
 *   for the rest of the process lifetime.
 * - A wall-clock deadline, checked between probes, bounds the total search. On expiry the
 *   input is returned uncached. It cannot interrupt a probe already in progress, so callers
 *   on the main thread use [adjustAsync], which probes on its own thread and falls back to
 *   the input after [ASYNC_TIMEOUT_MS].
 * - Only a confirmed native-size pass or a confirmed shrink is memoized per (codec, size).
 *
 * A device whose decoder starts at the display size pays one create/configure/start/release
 * of that decoder per process and keeps its own size. All logging goes through [Logger] so
 * the outcome appears in the exported file log.
 */
object DecoderCompat {
    private const val TAG = Logger.Tags.PLATFORM
    private const val MIME = MediaFormat.MIMETYPE_VIDEO_AVC

    /** Macroblock size; every probed dimension is a multiple of this. */
    private const val ALIGN = 16

    /** Never propose a stream with either side below this. */
    internal const val MIN_DIMENSION = 320

    /** Total wall-clock budget for one adjust() call, including the native-size probe. */
    internal const val DEADLINE_MS = 500L

    /** Outcome of one probe. */
    internal enum class Probe {
        /** Decoder configured and started at this size. */
        ACCEPTED,

        /** Decoder rejected this size for a reason that looks size-related. */
        REJECTED,

        /** Failure unrelated to size (busy, reclaimed, cannot create). Stop probing. */
        ABORT,
    }

    /**
     * How long [adjustAsync] waits for the probe thread before delivering the input unchanged.
     * [DEADLINE_MS] is only checked between probes, so one slow probe can overrun it; this
     * leaves room for that while bounding how long startup waits on a wedged decoder.
     */
    internal const val ASYNC_TIMEOUT_MS = 2_000L

    private val cache = mutableMapOf<String, Pair<Int, Int>>()

    /**
     * Last probe thread [adjustAsync] gave up on. While it is still alive it holds this
     * object's monitor inside a stuck codec call, so a new probe thread would only block
     * behind it; later calls skip probing instead of piling up blocked threads.
     */
    @Volatile
    private var abandonedProbe: Thread? = null

    /**
     * Runs [adjust] on a dedicated thread and delivers the result on [handler]'s thread,
     * exactly once.
     *
     * The probe is a chain of synchronous binder calls into the vendor codec HAL and cannot be
     * interrupted, so it must not run on the main thread: a single stuck call there blocks
     * input dispatch and window-focus handling until the system declares an ANR. If the thread
     * has not answered within [timeoutMs], [onResult] receives the input unchanged (the same
     * outcome as an inconclusive probe, nothing cached) and the thread is abandoned, not
     * joined. A confirmed result it produces later is still memoized by [adjust] and used by
     * the next call.
     */
    fun adjustAsync(
        width: Int,
        height: Int,
        decoderName: String?,
        handler: Handler,
        timeoutMs: Long = ASYNC_TIMEOUT_MS,
        onResult: (Pair<Int, Int>) -> Unit,
    ) {
        val input = width to height
        val stuck = abandonedProbe
        if (stuck != null && stuck.isAlive) {
            logWarn(
                "[DECODER_PROBE] Earlier probe thread still blocked in the codec; " +
                    "leaving ${width}x$height unchanged without probing",
                TAG,
            )
            handler.post { onResult(input) }
            return
        }

        // Only touched on the handler's thread, so the two deliveries below are serialized.
        var delivered = false
        val worker =
            Thread(
                {
                    val result =
                        try {
                            adjust(width, height, decoderName)
                        } catch (t: Throwable) {
                            logWarn("[DECODER_PROBE] Probe thread failed: ${t.javaClass.simpleName}", TAG)
                            input
                        }
                    handler.post {
                        if (!delivered) {
                            delivered = true
                            onResult(result)
                        }
                    }
                },
                "DecoderProbe",
            ).apply { isDaemon = true }
        handler.postDelayed({
            if (!delivered) {
                delivered = true
                abandonedProbe = worker
                logWarn(
                    "[DECODER_PROBE] No answer within $timeoutMs ms; leaving ${width}x$height " +
                        "unchanged and abandoning the probe thread",
                    TAG,
                )
                onResult(input)
            }
        }, timeoutMs)
        worker.start()
    }

    /**
     * Returns [width] x [height] when [decoderName] (or the platform default AVC decoder when
     * null) starts at that size, otherwise the largest 16-aligned size with the same aspect
     * ratio that it will start. Returns the input unchanged, uncached, when probing is
     * inconclusive.
     */
    @Synchronized
    fun adjust(
        width: Int,
        height: Int,
        decoderName: String?,
    ): Pair<Int, Int> =
        adjust(
            width = width,
            height = height,
            cacheKey = "${decoderName ?: "default"}:${width}x$height",
            deadlineMs = DEADLINE_MS,
            clock = SystemClock::elapsedRealtime,
            probe = { w, h -> canStart(w, h, decoderName) },
        )

    /**
     * Search core, separated so the shrink logic is unit-testable without MediaCodec.
     * Binary-search decoder-sizing core: guard-clause returns per probe outcome; clock/probe are
     * DI seams for the unit tests. Branch/return count is the search, not refactorable noise.
     */
    @Suppress("CyclomaticComplexMethod", "LongMethod", "LongParameterList", "ReturnCount")
    internal fun adjust(
        width: Int,
        height: Int,
        cacheKey: String,
        deadlineMs: Long,
        clock: () -> Long,
        probe: (Int, Int) -> Probe,
    ): Pair<Int, Int> {
        val input = width to height
        if (width <= 0 || height <= 0) return input
        cache[cacheKey]?.let { return it }

        val start = clock()
        val deadline = start + deadlineMs
        var probes = 0

        fun timedProbe(
            w: Int,
            h: Int,
        ): Probe? {
            if (clock() > deadline) return null
            probes++
            val t0 = clock()
            val result = probe(w, h)
            logDebugOnly(TAG) { "[DECODER_PROBE] ${w}x$h -> $result (${clock() - t0} ms)" }
            return result
        }

        when (timedProbe(width, height)) {
            Probe.ACCEPTED -> {
                cache[cacheKey] = input
                return input
            }
            Probe.REJECTED -> Unit
            Probe.ABORT, null -> {
                logWarn("[DECODER_PROBE] Inconclusive at ${width}x$height; leaving it unchanged", TAG)
                return input
            }
        }

        // Binary search on the longer side, in ALIGN units, for the largest size that starts.
        val landscape = width >= height
        val longSide = if (landscape) width else height
        val shortSide = if (landscape) height else width

        fun sizeFor(long: Int): Pair<Int, Int>? {
            // Round (not floor) the derived side to the nearest macroblock to minimise aspect drift.
            val short = (((long.toLong() * shortSide) / longSide + ALIGN / 2) / ALIGN * ALIGN).toInt()
            if (short < MIN_DIMENSION) return null
            return if (landscape) long to short else short to long
        }

        var lo = MIN_DIMENSION
        var hi = (longSide - 1) / ALIGN * ALIGN // largest aligned value strictly below the input
        var best: Pair<Int, Int>? = null
        while (lo <= hi) {
            val mid = ((lo + hi) / 2) / ALIGN * ALIGN
            val candidate = sizeFor(mid)
            if (candidate == null) {
                // Derived side too small: treat as rejected and search upward.
                lo = mid + ALIGN
                continue
            }
            when (timedProbe(candidate.first, candidate.second)) {
                Probe.ACCEPTED -> {
                    best = candidate
                    lo = mid + ALIGN
                }
                Probe.REJECTED -> hi = mid - ALIGN
                Probe.ABORT, null -> {
                    logWarn(
                        "[DECODER_PROBE] Inconclusive after $probes probes (${clock() - start} ms); " +
                            "leaving ${width}x$height unchanged",
                        TAG,
                    )
                    return input
                }
            }
        }

        if (best == null) {
            logWarn(
                "[DECODER_PROBE] No decodable size found below ${width}x$height " +
                    "($probes probes, ${clock() - start} ms); leaving it unchanged",
                TAG,
            )
            return input
        }

        logWarn(
            "[DECODER_PROBE] Decoder rejected ${width}x$height; using ${best.first}x${best.second} " +
                "($probes probes, ${clock() - start} ms)",
            TAG,
        )
        cache[cacheKey] = best
        return best
    }

    /** Test hook. */
    @Synchronized
    internal fun clearCache() = cache.clear()

    /**
     * Configures and starts a throwaway decoder at this size, on a real Surface, using the
     * same codec-selection order as H264Renderer.initCodec. Some drivers accept the format and
     * only fail in `start()`, so both calls are exercised.
     */
    private fun canStart(
        width: Int,
        height: Int,
        decoderName: String?,
    ): Probe {
        var codec: MediaCodec? = null
        var texture: SurfaceTexture? = null
        var surface: Surface? = null
        return try {
            codec = createCodec(decoderName)
            texture = SurfaceTexture(false)
            surface = Surface(texture)
            codec.configure(MediaFormat.createVideoFormat(MIME, width, height), surface, null, 0)
            codec.start()
            Probe.ACCEPTED
        } catch (e: MediaCodec.CodecException) {
            val resource =
                e.errorCode == MediaCodec.CodecException.ERROR_INSUFFICIENT_RESOURCE ||
                    e.errorCode == MediaCodec.CodecException.ERROR_RECLAIMED
            if (e.isTransient || e.isRecoverable || resource) {
                logWarn("[DECODER_PROBE] ${width}x$height inconclusive: ${e.diagnosticInfo}", TAG)
                Probe.ABORT
            } else {
                Probe.REJECTED
            }
        } catch (e: IllegalArgumentException) {
            // configure() rejected the format outright. Expected path; logged at debug only.
            logDebugOnly(TAG) { "[DECODER_PROBE] ${width}x$height rejected by configure(): ${e.message}" }
            Probe.REJECTED
        } catch (e: IllegalStateException) {
            logDebugOnly(TAG) { "[DECODER_PROBE] ${width}x$height rejected in start(): ${e.message}" }
            Probe.REJECTED
        } catch (e: IOException) {
            logWarn("[DECODER_PROBE] Cannot create decoder ${decoderName ?: "default"}: ${e.message}", TAG)
            Probe.ABORT
        } catch (t: Throwable) {
            logWarn("[DECODER_PROBE] ${width}x$height inconclusive: ${t.javaClass.simpleName}", TAG)
            Probe.ABORT
        } finally {
            runCatching { codec?.stop() }
            runCatching { codec?.release() }
            runCatching { surface?.release() }
            runCatching { texture?.release() }
        }
    }

    /** Mirrors H264Renderer.initCodec: preferred name first, generic type as fallback. */
    private fun createCodec(decoderName: String?): MediaCodec {
        if (!decoderName.isNullOrEmpty()) {
            try {
                return MediaCodec.createByCodecName(decoderName)
            } catch (e: IOException) {
                logWarn("[DECODER_PROBE] Preferred decoder $decoderName unavailable: ${e.message}", TAG)
            } catch (e: IllegalArgumentException) {
                logWarn("[DECODER_PROBE] Preferred decoder $decoderName unknown: ${e.message}", TAG)
            }
        }
        return MediaCodec.createDecoderByType(MIME)
    }
}
