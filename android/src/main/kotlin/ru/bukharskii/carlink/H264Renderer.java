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
    
    // Performance monitoring
    private long totalFramesReceived = 0;
    private long totalFramesDecoded = 0;
    private long totalFramesDropped = 0;
    private long lastStatsTime = 0;
    private long codecResetCount = 0;
    private long totalBytesProcessed = 0;

    private int calculateOptimalBufferSize(int width, int height) {
        // Base calculation for different resolutions
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

        // Try Intel hardware decoder first, fallback to generic
        MediaCodec codec = null;
        String codecName = null;
        
        try {
            // Intel Quick Sync decoder for x86 Android systems
            codec = MediaCodec.createByCodecName("OMX.Intel.VideoDecoder.AVC");
            codecName = "OMX.Intel.VideoDecoder.AVC (Intel Quick Sync)";
            log("Using Intel hardware decoder: " + codecName);
        } catch (Exception e) {
            log("Intel decoder not available, trying generic hardware decoder");
            try {
                codec = MediaCodec.createDecoderByType("video/avc");
                codecName = codec.getName();
            } catch (Exception e2) {
                throw new Exception("No H.264 decoder available", e2);
            }
        }
        
        mCodec = codec;
        log("codec created: " + codecName);

        final MediaFormat mediaformat = MediaFormat.createVideoFormat("video/avc", width, height);
        
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
        
        // Log performance stats every 60 frames (~2 seconds at 30fps)
        if (totalFramesReceived % 60 == 0) {
            logPerformanceStats();
        }
        
        ringBuffer.directWriteToBuffer(length, skipBytes, callback);
        feedCodec();
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
            
            // Warning if performance is suboptimal for target hardware
            if (fps < 55.0 && totalFramesReceived > 120) {
                perfMsg += " [WARNING: Low FPS on Intel HD Graphics 505]";
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
