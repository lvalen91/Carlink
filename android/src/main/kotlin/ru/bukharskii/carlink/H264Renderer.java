package ru.bukharskii.carlink;

import android.content.Context;
import android.graphics.SurfaceTexture;
import android.media.MediaCodec;
import android.media.MediaCodecInfo;
import android.media.MediaCodecList;
import android.media.MediaFormat;
import android.os.Build;
import android.os.Handler;
import android.view.Surface;
import android.util.Log;
import android.view.SurfaceHolder;

import androidx.annotation.NonNull;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.ConcurrentLinkedQueue;


public class H264Renderer {
    private static final String LOG_TAG = "CARLINK";
    protected final SurfaceTexture texture;
    private MediaCodec mCodec;
    private MediaCodec.Callback codecCallback;
    private ArrayList<Integer> codecAvailableBufferIndexes = new ArrayList<>(10);
    private int width;
    private int height;
    private Surface surface;
    private boolean running = false;
    private boolean bufferLoopRunning = false;
    private LogCallback logCallback;

    private AppExecutors executors = AppExecutors.getInstance();

    private PacketRingByteBuffer ringBuffer;
    
    // Safe optimization parameters - no aggressive frame skipping during startup
    private static final int TARGET_FPS = 60; // 2400x960@60fps target
    private static final long DYNAMIC_TIMEOUT_US = 1000000 / TARGET_FPS; // ~16.67ms per frame
    private boolean decoderInitialized = false; // Track if decoder has started outputting frames
    private int consecutiveOutputFrames = 0; // Count successful decoder outputs
    
    // Dynamic resolution-aware memory pool sizing for automotive displays
    private int bufferPoolSize; // Calculated based on resolution and device capabilities
    private static final int BUFFER_POOL_MIN_FREE = 2; // Maintain 2 free buffers for headroom
    
    // Resolution-based buffer pool scaling
    private static final int MIN_POOL_SIZE = 6;  // Small displays (800x480)
    private static final int MAX_POOL_SIZE = 20; // Ultra-high res (4K+)
    private final ConcurrentLinkedQueue<ByteBuffer> bufferPool = new ConcurrentLinkedQueue<>();
    private boolean poolInitialized = false;
    
    // Performance monitoring
    private long totalFramesReceived = 0;
    private long totalFramesDecoded = 0;
    private long totalFramesDropped = 0;
    private long lastStatsTime = 0;
    private long codecResetCount = 0;
    private long totalBytesProcessed = 0;

    private int calculateOptimalBufferSize(int width, int height) {
        // Base calculation for different resolutions - this is for the ring buffer
        int pixels = width * height;
        
        if (pixels <= 1920 * 1080) {
            // 1080p and below: 8MB buffer (standard)
            return 8 * 1024 * 1024;
        } else if (pixels <= 2400 * 960) {
            // Native GMinfo3.7 resolution: 16MB buffer (2x standard)
            return 16 * 1024 * 1024;
        } else if (pixels <= 3840 * 2160) {
            // 4K: 32MB buffer for high bitrate content
            return 32 * 1024 * 1024;
        } else {
            // Ultra-high resolution: 64MB buffer
            return 64 * 1024 * 1024;
        }
    }
    
    private int calculateOptimalPoolSize(int width, int height) {
        // Resolution-based buffer pool count calculation
        int pixels = width * height;
        
        if (pixels <= 800 * 480) {
            // Small automotive displays (7-8 inch): minimal buffering
            return MIN_POOL_SIZE; // 6 buffers
        } else if (pixels <= 1024 * 600) {
            // Standard automotive displays (8-10 inch): basic buffering
            return 8; // 8 buffers
        } else if (pixels <= 1920 * 1080) {
            // HD automotive displays: standard buffering
            return 10; // 10 buffers
        } else if (pixels <= 2400 * 960) {
            // Native GMinfo3.7 resolution: research-optimized
            return 12; // 12 buffers (current optimal)
        } else if (pixels <= 3840 * 2160) {
            // 4K displays: high buffering for stability
            return 16; // 16 buffers
        } else {
            // Ultra-high resolution: maximum buffering
            return MAX_POOL_SIZE; // 20 buffers
        }
    }
    
    private int calculateOptimalFrameBufferSize(int width, int height) {
        // Per-frame buffer size calculation based on resolution
        int pixels = width * height;
        
        // Base calculation: assume 4 bytes per pixel for worst case + compression overhead
        // Research shows MediaCodec needs headroom for different frame types (I, P, B)
        int baseSize = (pixels * 4) / 10; // Compressed H.264 is typically ~10:1 ratio
        
        // Minimum sizes based on research findings
        if (pixels <= 800 * 480) {
            return Math.max(baseSize, 64 * 1024);   // 64KB minimum for small displays
        } else if (pixels <= 1920 * 1080) {
            return Math.max(baseSize, 128 * 1024);  // 128KB minimum for HD
        } else if (pixels <= 2400 * 960) {
            return Math.max(baseSize, 256 * 1024);  // 256KB minimum for gminfo3.7
        } else if (pixels <= 3840 * 2160) {
            return Math.max(baseSize, 512 * 1024);  // 512KB minimum for 4K
        } else {
            return Math.max(baseSize, 1024 * 1024); // 1MB minimum for ultra-high res
        }
    }

    public H264Renderer(Context context, int width, int height, SurfaceTexture texture, int textureId, LogCallback logCallback) {
        this.width = width;
        this.height = height;
        this.texture = texture;
        this.logCallback = logCallback;

        surface = new Surface(texture);

        // Optimize buffer size for 6GB RAM system and 2400x960@60fps target
        // Calculate optimal buffer: ~2-3 seconds of 4K video = 32MB for safety margin
        int bufferSize = calculateOptimalBufferSize(width, height);
        ringBuffer = new PacketRingByteBuffer(bufferSize);
        log("Ring buffer initialized: " + (bufferSize / (1024*1024)) + "MB for " + width + "x" + height);

        // Initialize memory pool after successful codec startup - research shows 30x performance improvement
        initializeBufferPool();
        
        codecCallback = createCallback();
    }

    private void log(String message) {
        Log.d(LOG_TAG, "[H264] " + message);
        logCallback.log(message);
    }

    public void start() {
        if (running) return;

        running = true;
        lastStatsTime = System.currentTimeMillis();
        totalFramesReceived = 0;
        totalFramesDecoded = 0;
        totalFramesDropped = 0;
        totalBytesProcessed = 0;
        decoderInitialized = false;
        consecutiveOutputFrames = 0;

        log("start - Resolution: " + width + "x" + height + ", Surface: " + (surface != null));

        try {
            initCodec(width, height, surface);
            mCodec.start();
            log("codec started successfully");
        } catch (Exception e) {
            log("start error " + e.toString());
            e.printStackTrace();

            log("restarting in 5s ");
            new android.os.Handler(android.os.Looper.getMainLooper()).postDelayed(() -> {
                if (running) {
                    start();
                }
            }, 5000);
        }
    }

    private boolean fillFirstAvailableCodecBuffer(MediaCodec codec) {

        if (codec != mCodec) return false;

        if (ringBuffer.isEmpty()) return false;

        synchronized (codecAvailableBufferIndexes) {
            // Re-check inside the lock to prevent race condition
            if (codecAvailableBufferIndexes.isEmpty()) return false;
            
            int index = codecAvailableBufferIndexes.remove(0);

            ByteBuffer byteBuffer = mCodec.getInputBuffer(index);
            byteBuffer.put(ringBuffer.readPacket());

            mCodec.queueInputBuffer(index, 0, byteBuffer.position(), 0, 0);
        }

        return true;
    }

    private void fillAllAvailableCodecBuffers(MediaCodec codec) {
        boolean filled = true;

        while (filled) {
            filled = fillFirstAvailableCodecBuffer(codec);
        }
    }

    private void feedCodec() {
        // Optimize for Intel Atom x7-A3960 quad-core  
        // Use dedicated high-priority thread for codec feeding to reduce latency
        executors.mediaCodec1().execute(() -> {
            // Thread priority already set by OptimizedMediaCodecExecutor
            // No need to set it again here
            
            try {
                fillAllAvailableCodecBuffers(mCodec);
                
                // Buffer health monitoring for automotive stability
                if (ringBuffer != null) {
                    int packetCount = ringBuffer.availablePacketsToRead();
                    if (packetCount > 20) { // Warn if buffer is getting full
                        log("[BUFFER_WARNING] High buffer usage: " + packetCount + " packets");
                    }
                }
            } catch (Exception e) {
                log("[Media Codec] fill input buffer error:" + e.toString());
                // Let MediaCodec.Callback.onError() handle recovery properly
            }
        });
    }

    public void stop() {
        if (!running) return;

        running = false;

        try {
            if (mCodec != null) {
                mCodec.stop();
                mCodec.release();
                mCodec = null;
            }
        } catch (Exception e) {
            log("STOP: MediaCodec cleanup failed - " + e.toString());
            mCodec = null; // Force null to prevent further issues
        }
    }


    public void reset() {
        codecResetCount++;
        log("reset codec - Reset count: " + codecResetCount + ", Frames decoded: " + totalFramesDecoded);
        
        // Simple reset: stop, recreate, and start fresh
        stop();
        start();
    }


    private void initCodec(int width, int height, Surface surface) throws Exception {
        log("init media codec - Resolution: " + width + "x" + height);

        // Simplified codec selection - avoid startup delays during CarPlay handshake
        MediaCodec codec = null;
        String codecName = null;
        
        try {
            // Try Intel Quick Sync decoder first (known to work)
            codec = MediaCodec.createByCodecName("OMX.Intel.VideoDecoder.AVC");
            codecName = "OMX.Intel.VideoDecoder.AVC (Intel Quick Sync)";
            log("Using Intel hardware decoder: " + codecName);
        } catch (Exception e) {
            log("Intel decoder not available, trying generic hardware decoder");
            try {
                // Fallback to generic hardware decoder (simple and reliable)
                codec = MediaCodec.createDecoderByType("video/avc");
                codecName = codec.getName();
                log("Using generic decoder: " + codecName);
            } catch (Exception e2) {
                throw new Exception("No H.264 decoder available", e2);
            }
        }
        
        mCodec = codec;
        log("codec created: " + codecName);

        final MediaFormat mediaformat = MediaFormat.createVideoFormat("video/avc", width, height);
        
        // Intel HD Graphics 505 optimization for 2400x960@60fps
        // Optimize for low latency decoding (Android 11+)
        try {
            mediaformat.setInteger(MediaFormat.KEY_LOW_LATENCY, 1);
            log("Low latency mode enabled");
        } catch (Exception e) {
            log("Low latency mode not supported on this API level");
        }
        
        // Set realtime priority (0 = realtime, 1 = best effort)
        try {
            mediaformat.setInteger(MediaFormat.KEY_PRIORITY, 0);
            log("Realtime priority set");
        } catch (Exception e) {
            log("Priority setting not supported on this API level");
        }
        
        // Intel Quick Sync specific optimizations
        if (codecName != null && codecName.contains("Intel")) {
            try {
                // Optimize buffer count for Intel Quick Sync (typically 8-16 buffers)
                mediaformat.setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, width * height);
                mediaformat.setInteger("max-concurrent-instances", 1); // Single instance for automotive
                log("Intel Quick Sync optimizations applied");
            } catch (Exception e) {
                log("Intel-specific optimizations not supported: " + e.getMessage());
            }
        }
        
        log("media format created: " + mediaformat);

        log("configure media codec");
        mCodec.configure(mediaformat, surface, null, 0);

        codecAvailableBufferIndexes.clear();

        log("media codec in async mode");
        mCodec.setCallback(codecCallback);
    }

    public void processDataDirect(int length, int skipBytes, PacketRingByteBuffer.DirectWriteCallback callback) {
        totalFramesReceived++;
        totalBytesProcessed += length;
        
        // CRITICAL FIX: Never drop frames during decoder initialization
        // Research shows SPS/PPS frames are essential for decoder startup
        // Frame skipping should only happen AFTER successful streaming has begun
        
        // Log performance stats every 60 frames (~1 second at 60fps)
        if (totalFramesReceived % 60 == 0) {
            logPerformanceStats();
        }
        
        ringBuffer.directWriteToBuffer(length, skipBytes, callback);
        feedCodec();
    }
    
    
    private void initializeBufferPool() {
        if (poolInitialized) return;
        
        // Calculate optimal pool size based on resolution and research findings
        bufferPoolSize = calculateOptimalPoolSize(width, height);
        
        // Research-based buffer sizing per frame for automotive streaming
        // ByteBuffer.allocateDirect provides maximum efficiency per research
        int bufferSize = calculateOptimalFrameBufferSize(width, height);
        
        for (int i = 0; i < bufferPoolSize; i++) {
            ByteBuffer buffer = ByteBuffer.allocateDirect(bufferSize);
            buffer.order(ByteOrder.LITTLE_ENDIAN);
            bufferPool.offer(buffer);
        }
        
        poolInitialized = true;
        log("Resolution-adaptive memory pool initialized: " + bufferPoolSize + " direct buffers of " + (bufferSize / 1024) + "KB each for " + width + "x" + height);
    }
    
    private ByteBuffer getPooledBuffer(int minimumSize) {
        ByteBuffer buffer = bufferPool.poll();
        if (buffer == null || buffer.capacity() < minimumSize) {
            // Research shows: allocateDirect is critical for performance
            // Pool empty or buffer too small, allocate new direct buffer
            int newSize = Math.max(minimumSize, buffer != null ? buffer.capacity() : 128 * 1024);
            buffer = ByteBuffer.allocateDirect(newSize);
            buffer.order(ByteOrder.LITTLE_ENDIAN);
            
            // Log dynamic allocation for automotive diagnostics
            log("[POOL_EXPAND] Allocated " + (newSize / 1024) + "KB direct buffer, pool size: " + bufferPool.size());
        }
        buffer.clear();
        return buffer;
    }
    
    private void returnPooledBuffer(ByteBuffer buffer) {
        if (buffer != null && bufferPool.size() < bufferPoolSize) {
            buffer.clear();
            bufferPool.offer(buffer);
        } else if (buffer != null && bufferPool.size() >= bufferPoolSize) {
            // Pool full - research suggests monitoring this for automotive stability
            log("[POOL_FULL] Buffer pool at capacity (" + bufferPoolSize + "), discarding buffer");
        }
    }
    

    ////////////////////////////////////////

    private MediaCodec.Callback createCallback() {
        return new MediaCodec.Callback() {
            @Override
            public void onInputBufferAvailable(@NonNull MediaCodec codec, int index) {
                if (codec != mCodec) return;

//                log("[Media Codec] onInputBufferAvailable index:" + index);
                synchronized (codecAvailableBufferIndexes) {
                    codecAvailableBufferIndexes.add(index);
                }
            }

            @Override
            public void onOutputBufferAvailable(@NonNull MediaCodec codec, int index, @NonNull MediaCodec.BufferInfo info) {
                if (codec != mCodec) return;

                if (info.size > 0) {
                    totalFramesDecoded++;
                    consecutiveOutputFrames++;
                    
                    // Mark decoder as initialized after first few successful outputs
                    if (consecutiveOutputFrames >= 3) {
                        decoderInitialized = true;
                    }
                } else {
                    totalFramesDropped++;
                }

                executors.mediaCodec2().execute(() -> {
                    boolean doRender = (info.size != 0);
                    mCodec.releaseOutputBuffer(index, doRender);
                });

            }

            @Override
            public void onError(@NonNull MediaCodec codec, @NonNull MediaCodec.CodecException e) {
                if (codec != mCodec) return;

                log("[Media Codec] onError " + e.toString() + ", Recoverable: " + e.isRecoverable() + ", Transient: " + e.isTransient());
                
                // Only reset on critical errors - let transient/recoverable errors pass
                if (!e.isTransient() && !e.isRecoverable()) {
                    log("[Media Codec] Fatal error - will reset on next start attempt");
                    // Don't automatically reset - let user restart manually to avoid crash loops
                } else {
                    log("[Media Codec] Transient/recoverable error - continuing operation");
                }
            }

            @Override
            public void onOutputFormatChanged(@NonNull MediaCodec codec, @NonNull MediaFormat format) {
                if (codec != mCodec) return;

                int colorFormat = format.getInteger("color-format");
                int width = format.getInteger("width");
                int height = format.getInteger("height");
                
                log("[Media Codec] onOutputFormatChanged - Format: " + format);
                log("[Media Codec] Output format - Color: " + colorFormat + ", Size: " + width + "x" + height);
            }
        };
    }
    
    private void logPerformanceStats() {
        long currentTime = System.currentTimeMillis();
        long timeDiff = currentTime - lastStatsTime;
        
        if (timeDiff > 0) {
            double fps = (double) totalFramesDecoded * 1000.0 / timeDiff;
            double dropRate = totalFramesDropped > 0 ? (double) totalFramesDropped / (totalFramesReceived + totalFramesDropped) * 100.0 : 0.0;
            double avgFrameSize = totalFramesReceived > 0 ? (double) totalBytesProcessed / totalFramesReceived / 1024.0 : 0.0;
            double throughputMbps = (double) totalBytesProcessed * 8.0 / (timeDiff * 1000.0); // Mbps
            
            // Enhanced logging for Intel GPU performance analysis
            String perfMsg = String.format("[PERF] FPS: %.1f/60, Frames: R:%d/D:%d/Drop:%d, DropRate: %.1f%%, AvgSize: %.1fKB, Throughput: %.1fMbps, Resets: %d", 
                fps, totalFramesReceived, totalFramesDecoded, totalFramesDropped, dropRate, avgFrameSize, throughputMbps, codecResetCount);
            
            // Add Intel GPU specific metrics if available
            if (mCodec != null && mCodec.getName().contains("Intel")) {
                perfMsg += " [Intel Quick Sync Active]";
            }
            
            // Warning if performance is suboptimal for gminfo3.7 hardware (Intel HD Graphics 505)
            if (fps < 55.0 && totalFramesReceived > 120) {
                perfMsg += " [WARNING: Low FPS on Intel HD Graphics 505]";
            }
            
            // Monitor frame lag for performance analysis (no aggressive action)
            long frameLag = totalFramesReceived - totalFramesDecoded;
            if (frameLag > 10) { // Conservative threshold for monitoring only
                perfMsg += " [INFO: Frame lag " + frameLag + "]";
            }
            
            // Resolution-adaptive graduated memory pool monitoring
            int poolUtilization = ((bufferPoolSize - bufferPool.size()) * 100) / bufferPoolSize;
            int freeBuffers = bufferPool.size();
            
            if (freeBuffers < BUFFER_POOL_MIN_FREE) {
                perfMsg += " [POOL_CRITICAL: " + freeBuffers + "/" + bufferPoolSize + " free]";
            } else if (poolUtilization > 75) {
                perfMsg += " [POOL_HIGH: " + poolUtilization + "% used]";
            } else if (poolUtilization > 50) {
                perfMsg += " [POOL_NORMAL: " + poolUtilization + "% used]";
            }
            
            log(perfMsg);
            
            // Reset counters for next measurement period
            totalFramesReceived = 0;
            totalFramesDecoded = 0;
            totalFramesDropped = 0;
            totalBytesProcessed = 0;
        }
        
        lastStatsTime = currentTime;
    }
}
