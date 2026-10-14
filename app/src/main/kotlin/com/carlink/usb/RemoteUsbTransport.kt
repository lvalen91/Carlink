package com.carlink.usb

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.IBinder
import android.os.RemoteException
import com.carlink.ipc.NaviVideoSingleton
import com.carlink.usbbridge.IUsbBridge
import com.carlink.usbbridge.IUsbBridgeCallback
import com.carlink.usbbridge.UsbBridgeConstants
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/** Play-app transport backed by the sideloaded android.car.usb.handler helper. */
class RemoteUsbTransport(
    context: Context,
    private val logCallback: (String) -> Unit,
) : UsbTransport {
    private val appContext = context.applicationContext
    private val connected = AtomicBoolean(false)
    private val errorExecutor = Executors.newSingleThreadExecutor()

    private fun dispatchError(detail: String) {
        val target = readCallback ?: return
        try {
            errorExecutor.execute {
                if (readCallback === target) target.onError(detail)
            }
        } catch (_: java.util.concurrent.RejectedExecutionException) {
            // Transport already closed; its queued errors are obsolete.
        }
    }
    private val transferIds = AtomicLong(1)
    private val maxCallbackMs = AtomicLong(0)
    private val lastCallbackSummaryNs = AtomicLong(System.nanoTime())
    private val inbound = ConcurrentHashMap<Long, ChunkAccumulator>()
    private var bridge: IUsbBridge? = null
    private var connection: ServiceConnection? = null
    private var ready = CountDownLatch(1)
    private val bindingExecutor: ExecutorService = Executors.newSingleThreadExecutor()
    @Volatile
    var failureDetail: String = ""
        private set
    @Volatile private var readCallback: UsbTransport.ReadingLoopCallback? = null
    private var videoProcessor: UsbTransport.VideoDataProcessor? = null

    override val isOpened: Boolean get() = connected.get()

    private val callback = object : IUsbBridgeCallback.Stub() {
        override fun onState(state: Int, detail: String?) {
            log("[BRIDGE] state=$state detail=$detail")
            if (state == UsbBridgeConstants.STATE_ERROR) {
                failureDetail = detail ?: "USB helper error"
                dispatchError(failureDetail)
            }
            if (state == UsbBridgeConstants.STATE_DISCONNECTED) {
                connected.set(false)
                dispatchError(detail ?: "USB helper disconnected")
            }
        }

        override fun onInboundChunk(
            transferId: Long,
            messageType: Int,
            sourcePtsMs: Int,
            chunkIndex: Int,
            chunkCount: Int,
            totalLength: Int,
            data: ByteArray,
        ) {
            val accumulator = inbound.getOrPut(transferId) {
                ChunkAccumulator(messageType, sourcePtsMs, chunkCount, totalLength)
            }
            if (!accumulator.add(chunkIndex, data)) return
            if (!accumulator.complete()) return
            inbound.remove(transferId)
            val payload = accumulator.join() ?: return
            val startedNs = System.nanoTime()
            try {
                val processor = videoProcessor
                if (messageType == VIDEO_TYPE && processor != null && payload.isNotEmpty()) {
                    processor.processVideoDirect(payload, payload.size, sourcePtsMs)
                    readCallback?.onMessage(messageType, null, 0)
                } else if (messageType == NAVI_VIDEO_TYPE && NaviVideoSingleton.enabled && payload.isNotEmpty()) {
                    NaviVideoSingleton.forwarder.onUsbFrame(payload, payload.size)
                    readCallback?.onMessage(messageType, null, 0)
                } else {
                    readCallback?.onMessage(messageType, payload.takeIf { it.isNotEmpty() }, payload.size)
                }
            } finally {
                val finishedNs = System.nanoTime()
                val elapsedMs = (finishedNs - startedNs) / 1_000_000
                maxCallbackMs.updateAndGet { maxOf(it, elapsedMs) }
                val previous = lastCallbackSummaryNs.get()
                if (finishedNs - previous >= 30_000_000_000L && lastCallbackSummaryNs.compareAndSet(previous, finishedNs)) {
                    log("[USB_CALLBACK_PERF] maxProcessingMs=${maxCallbackMs.getAndSet(0)} lastType=$messageType")
                }
            }
        }
    }

    fun connect(timeoutMs: Long = 5000L): Boolean {
        check(android.os.Looper.myLooper() != android.os.Looper.getMainLooper()) { "USB connect must not run on main" }
        if (bridge != null) return connected.get()
        failureDetail = ""
        ready = CountDownLatch(1)
        val intent = Intent().setComponent(
            ComponentName(UsbBridgeConstants.BRIDGE_PACKAGE, UsbBridgeConstants.BRIDGE_SERVICE_CLASS),
        )
        val resolved = appContext.packageManager.resolveService(intent, 0)
        if (resolved == null) {
            failureDetail = "helper service is not installed for this AAOS user"
            log("[BRIDGE] $failureDetail: ${intent.component}")
            return false
        }
        val serviceConnection = object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName, service: IBinder) {
                bridge = IUsbBridge.Stub.asInterface(service)
                try {
                    bridge?.registerCallback(callback)
                } catch (e: RemoteException) {
                    log("[BRIDGE] callback registration failed: ${e.message}")
                }
                ready.countDown()
            }

            override fun onServiceDisconnected(name: ComponentName) {
                connected.set(false)
                bridge = null
                dispatchError("USB helper service disconnected")
            }

            override fun onNullBinding(name: ComponentName) {
                failureDetail = "helper service returned a null binder"
                log("[BRIDGE] $failureDetail")
                ready.countDown()
            }

            override fun onBindingDied(name: ComponentName) {
                failureDetail = "helper service binding died"
                log("[BRIDGE] $failureDetail")
                ready.countDown()
            }
        }
        connection = serviceConnection
        return try {
            // GM's AAOS service manager can reject bind-only creation for a
            // sideloaded exported service. Start the helper explicitly first;
            // it enters the foreground in onCreate(), then bind to that instance.
            // Use a dedicated callback executor. Calling await() on the main
            // thread would otherwise block the thread that delivers
            // onServiceConnected(), producing a false binding timeout.
            if (!appContext.bindService(intent, Context.BIND_AUTO_CREATE, bindingExecutor, serviceConnection)) {
                failureDetail = "bindService returned false; helper service is unavailable"
                log("[BRIDGE] $failureDetail")
                false
            } else if (!ready.await(timeoutMs, TimeUnit.MILLISECONDS)) {
                failureDetail = "timed out binding helper service"
                log("[BRIDGE] $failureDetail")
                false
            } else {
                connected.set(bridge?.open() == true)
                if (!connected.get() && failureDetail.isBlank()) {
                    failureDetail = "helper opened, but no usable USB adapter was available"
                }
                if (!connected.get()) log("[BRIDGE] $failureDetail")
                connected.get()
            }
        } catch (e: Exception) {
            failureDetail = "connect failed: ${e.message ?: e.javaClass.simpleName}"
            log("[BRIDGE] $failureDetail")
            false
        }
    }

    override fun write(data: ByteArray, timeout: Int): Int {
        if (android.os.Looper.myLooper() == android.os.Looper.getMainLooper()) {
            com.carlink.logging.Logger.e("[USB_MAIN_GUARD] Rejected synchronous USB write on main", "USB")
            return -1
        }
        if (!connected.get()) return -1
        val service = bridge ?: return -1
        val transferId = transferIds.getAndIncrement()
        val count = maxOf(1, (data.size + UsbBridgeConstants.CHUNK_SIZE - 1) / UsbBridgeConstants.CHUNK_SIZE)
        for (index in 0 until count) {
            val offset = index * UsbBridgeConstants.CHUNK_SIZE
            val chunk = data.copyOfRange(offset, minOf(data.size, offset + UsbBridgeConstants.CHUNK_SIZE))
            if (!service.sendChunk(transferId, index, count, data.size, chunk)) return -1
        }
        return data.size
    }

    override fun startReadingLoop(
        callback: UsbTransport.ReadingLoopCallback,
        timeout: Int,
        videoProcessor: UsbTransport.VideoDataProcessor?,
    ) {
        readCallback = callback
        this.videoProcessor = videoProcessor
    }

    override fun stopReadingLoop() {
        readCallback = null
        videoProcessor = null
        inbound.clear()
    }

    override fun close() {
        stopReadingLoop()
        try {
            bridge?.unregisterCallback(callback)
            bridge?.close()
        } catch (_: Exception) {
        }
        connection?.let { runCatching { appContext.unbindService(it) } }
        connection = null
        bridge = null
        connected.set(false)
        bindingExecutor.shutdownNow()
        // close() may run on this worker: do not interrupt its own cleanup.
        errorExecutor.shutdown()
    }

    private fun log(message: String) {
        logCallback(message)
    }

    private class ChunkAccumulator(
        val messageType: Int,
        val sourcePtsMs: Int,
        private val count: Int,
        private val totalLength: Int,
    ) {
        private val chunks = arrayOfNulls<ByteArray>(count)
        fun add(index: Int, data: ByteArray): Boolean {
            if (index !in chunks.indices || chunks[index] != null) return false
            chunks[index] = data.copyOf()
            return true
        }
        fun complete() = chunks.all { it != null }
        fun join(): ByteArray? {
            if (!complete()) return null
            val result = ByteArray(totalLength)
            var offset = 0
            chunks.forEach { chunk ->
                val bytes = chunk ?: return null
                if (offset + bytes.size > result.size) return null
                bytes.copyInto(result, offset)
                offset += bytes.size
            }
            return result.takeIf { offset == totalLength }
        }
    }

    companion object {
        private const val VIDEO_TYPE = 0x06
        private const val NAVI_VIDEO_TYPE = 0x2C
    }
}
