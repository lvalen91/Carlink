package ru.bukharskii.carlink;


import android.os.Handler;
import android.os.Looper;

import androidx.annotation.NonNull;

import java.util.concurrent.Executor;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

public class AppExecutors
{
    private static class BackgroundThreadExecutor implements Executor
    {
        private final Executor executor;

        public BackgroundThreadExecutor()
        {
            executor = Executors.newSingleThreadExecutor();
        }

        @Override
        public void execute(@NonNull Runnable command)
        {
            executor.execute(command);
        }
    }

    // Optimized thread pool for Intel Atom x7-A3960 quad-core
    private static class OptimizedMediaCodecExecutor implements Executor
    {
        private final ThreadPoolExecutor executor;

        public OptimizedMediaCodecExecutor(String threadName, int androidPriority)
        {
            // Get available CPU cores for Intel Atom x7-A3960 (should be 4)
            int numberOfCores = Runtime.getRuntime().availableProcessors();
            
            // Create optimized thread pool based on Android best practices
            // Core pool: half cores, Max pool: all cores to utilize quad-core efficiently  
            executor = new ThreadPoolExecutor(
                Math.max(1, numberOfCores / 2), // corePoolSize - utilize half cores initially
                numberOfCores, // maximumPoolSize - can scale to all cores under load
                60L, // keepAliveTime - standard Android recommendation
                TimeUnit.SECONDS,
                new LinkedBlockingQueue<>(128), // Larger queue for 6GB RAM system
                r -> {
                    Thread t = new Thread(r, threadName);
                    // Don't set Thread priority here - it will be set in the execute method
                    return t;
                }
            );
            executor.allowCoreThreadTimeOut(true); // Allow core threads to timeout for efficiency
            this.androidPriority = androidPriority;
        }
        
        private final int androidPriority;

        @Override
        public void execute(@NonNull Runnable command)
        {
            // Wrap command to set Android thread priority properly
            executor.execute(() -> {
                android.os.Process.setThreadPriority(androidPriority);
                command.run();
            });
        }
    }

    private static class MainThreadExecutor implements Executor
    {
        private Handler mainThreadHandler = new Handler(Looper.getMainLooper());

        @Override
        public void execute(@NonNull Runnable command)
        {
            mainThreadHandler.post(command);
        }
    }

    private static volatile AppExecutors INSTANCE;

    private final BackgroundThreadExecutor usbIn;
    private final BackgroundThreadExecutor usbOut;
    private final OptimizedMediaCodecExecutor mediaCodec1;
    private final OptimizedMediaCodecExecutor mediaCodec2;
    private final BackgroundThreadExecutor mediaCodec;
    private final MainThreadExecutor mainThread;

    private AppExecutors()
    {
        usbIn = new BackgroundThreadExecutor();
        usbOut = new BackgroundThreadExecutor();
        mediaCodec = new BackgroundThreadExecutor();
        
        // Conservative MediaCodec executors - reduce aggressiveness for stability
        // Use less aggressive priorities to prevent race conditions during high load
        mediaCodec1 = new OptimizedMediaCodecExecutor("MediaCodec-Input", android.os.Process.THREAD_PRIORITY_DISPLAY);
        mediaCodec2 = new OptimizedMediaCodecExecutor("MediaCodec-Output", android.os.Process.THREAD_PRIORITY_DEFAULT);
        
        mainThread = new MainThreadExecutor();
    }

    public static AppExecutors getInstance()
    {
        if(INSTANCE == null)
        {
            synchronized(AppExecutors.class)
            {
                if(INSTANCE == null)
                {
                    INSTANCE = new AppExecutors();
                }
            }
        }
        return INSTANCE;
    }

    public Executor usbIn()
    {
        return usbIn;
    }

    public Executor usbOut()
    {
        return usbOut;
    }

    public Executor mediaCodec() {
        return mediaCodec;
    }

    public Executor mediaCodec1() {
        return mediaCodec1;
    }

    public Executor mediaCodec2() {
        return mediaCodec2;
    }

    public Executor mainThread()
    {
        return mainThread;
    }
}