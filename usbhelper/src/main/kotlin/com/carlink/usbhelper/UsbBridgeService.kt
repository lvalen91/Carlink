package com.carlink.usbhelper

import android.app.Service
import android.content.Context
import android.content.Intent
import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbEndpoint
import android.hardware.usb.UsbInterface
import android.hardware.usb.UsbManager
import android.os.IBinder
import android.os.RemoteException
import com.carlink.usbbridge.IUsbBridge
import com.carlink.usbbridge.IUsbBridgeCallback
import com.carlink.usbbridge.UsbBridgeConstants
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/**
 * USB owner for the GM fixed-handler workaround.
 *
 * The system grants this package's UID permission before launching the fixed-handler
 * activity. This service then proxies complete CPC200 messages to the Play application.
 */
class UsbBridgeService : Service() {
    private val usbManager by lazy { getSystemService(Context.USB_SERVICE) as UsbManager }
    private val callbacks = ConcurrentHashMap.newKeySet<IUsbBridgeCallback>()
    private val running = AtomicBoolean(false)
    private val writeLock = Any()
    private var connection: UsbDeviceConnection? = null
    private var inEndpoint: UsbEndpoint? = null
    private var outEndpoint: UsbEndpoint? = null
    private var reader: Thread? = null
    private val pendingWrites = ConcurrentHashMap<Long, ChunkAccumulator>()
    private val deliveryGeneration = AtomicLong(0)
    private val transferIds = AtomicLong(1)
    private val lastReadNs = AtomicLong(0)
    private val lastWriteNs = AtomicLong(0)
    private val lastAudioReadNs = AtomicLong(0)
    private val maxAudioGapMs = AtomicLong(0)
    private val maxAudioQueueMs = AtomicLong(0)
    private val maxAudioCallbackMs = AtomicLong(0)
    private val lastDeliverySummaryNs = AtomicLong(System.nanoTime())
    @Volatile private var lastWriteResult = 0
    @Volatile private var lastWriteElapsedMs = 0L

    // Never perform Binder callbacks on the USB reader thread. A slow app-side
    // callback (codec, media metadata, or audio routing) must not stop USB reads
    // for every other message type. One bounded worker preserves protocol order.
    private val realtimeDispatch =
        ThreadPoolExecutor(
            1,
            1,
            0L,
            TimeUnit.MILLISECONDS,
            ArrayBlockingQueue(128),
        )

    private val binder = object : IUsbBridge.Stub() {
        override fun open(): Boolean = openDevice()

        override fun close() {
            closeDevice("closed by client")
        }

        override fun registerCallback(callback: IUsbBridgeCallback) {
            callbacks += callback
            emitState(UsbBridgeConstants.STATE_CONNECTED, "callback registered")
        }

        override fun unregisterCallback(callback: IUsbBridgeCallback) {
            callbacks -= callback
        }

        override fun sendChunk(
            transferId: Long,
            chunkIndex: Int,
            chunkCount: Int,
            totalLength: Int,
            data: ByteArray,
        ): Boolean {
            if (chunkCount <= 0 || chunkIndex !in 0 until chunkCount ||
                totalLength !in 0..UsbBridgeConstants.MAX_MESSAGE_SIZE ||
                data.size > UsbBridgeConstants.CHUNK_SIZE
            ) return false

            val accumulator = pendingWrites.getOrPut(transferId) {
                ChunkAccumulator(chunkCount, totalLength)
            }
            if (!accumulator.add(chunkIndex, data)) return false
            if (!accumulator.complete()) return true

            pendingWrites.remove(transferId)
            val message = accumulator.join() ?: return false
            val conn = connection ?: return false
            val endpoint = outEndpoint ?: return false
            return synchronized(writeLock) {
                val started = System.nanoTime()
                val result = conn.bulkTransfer(endpoint, message, message.size, WRITE_TIMEOUT)
                lastWriteElapsedMs = (System.nanoTime() - started) / 1_000_000
                lastWriteResult = result
                lastWriteNs.set(System.nanoTime())
                result == message.size
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        emitState(UsbBridgeConstants.STATE_DISCONNECTED, "waiting for CPC200 adapter")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // The fixed GM handler has already granted this UID access to the attached
        // device before starting the activity. Resolve the current adapter from
        // UsbManager so the same path also works when the Play app binds directly.
        openDevice()
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder = binder

    override fun onDestroy() {
        closeDevice("service destroyed")
        realtimeDispatch.shutdownNow()
        super.onDestroy()
    }

    @Synchronized
    private fun openDevice(requested: UsbDevice? = null): Boolean {
        if (running.get()) return true
        // USB_DEVICE_ATTACHED and the UsbManager device list are not updated
        // atomically on all GM builds. Give enumeration and the fixed-handler
        // permission grant a short window to settle before failing.
        var devices = usbManager.deviceList.values.toList()
        var device = requested ?: devices.firstOrNull(::isKnownDevice)
        for (attempt in 0 until 5) {
            if (device != null) break
            Thread.sleep(200)
            devices = usbManager.deviceList.values.toList()
            device = requested ?: devices.firstOrNull(::isKnownDevice)
        }
        if (device == null || !isKnownDevice(device)) {
            emitState(
                UsbBridgeConstants.STATE_ERROR,
                "CPC200 adapter not found; USB devices=${describeDevices(devices)}",
            )
            return false
        }
        if (!usbManager.hasPermission(device)) {
            emitState(UsbBridgeConstants.STATE_ERROR, "USB permission was not granted")
            return false
        }

        emitState(UsbBridgeConstants.STATE_CONNECTING, device.deviceName)
        val conn = usbManager.openDevice(device) ?: run {
            emitState(UsbBridgeConstants.STATE_ERROR, "UsbManager.openDevice failed")
            return false
        }
        val endpoints = findBulkEndpoints(device)
        if (endpoints == null || !conn.claimInterface(endpoints.first, true)) {
            conn.close()
            emitState(UsbBridgeConstants.STATE_ERROR, "No claimable bulk USB interface")
            return false
        }

        connection = conn
        inEndpoint = endpoints.second
        outEndpoint = endpoints.third
        running.set(true)
        emitState(UsbBridgeConstants.STATE_CONNECTED, "${device.vendorId}:${device.productId}")
        val generation = deliveryGeneration.incrementAndGet()
        lastReadNs.set(0)
        lastWriteNs.set(0)
        lastAudioReadNs.set(0)
        maxAudioGapMs.set(0)
        maxAudioQueueMs.set(0)
        maxAudioCallbackMs.set(0)
        lastDeliverySummaryNs.set(System.nanoTime())
        reader = Thread({ readLoop(conn, endpoints.second, generation, device.deviceName) }, "CL-USB-Helper").apply {
            isDaemon = true
            start()
        }
        return true
    }

    @Synchronized
    private fun closeDevice(reason: String, expectedGeneration: Long? = null) {
        if (expectedGeneration != null && expectedGeneration != deliveryGeneration.get()) return
        deliveryGeneration.incrementAndGet()
        if (!running.getAndSet(false) && connection == null) return
        reader?.interrupt()
        reader = null
        connection?.close()
        connection = null
        inEndpoint = null
        outEndpoint = null
        pendingWrites.clear()
        realtimeDispatch.queue.clear()
        emitState(UsbBridgeConstants.STATE_DISCONNECTED, reason)
    }

    private fun readLoop(conn: UsbDeviceConnection, endpoint: UsbEndpoint, generation: Long, deviceName: String) {
        val header = ByteArray(HEADER_SIZE)
        var reason = "USB read loop stopped"
        try {
            while (running.get() && generation == deliveryGeneration.get()) {
                if (!readExact(conn, endpoint, generation, header, READ_TIMEOUT, "header", deviceName)) break
                val length = littleEndianInt(header, 4)
                val type = littleEndianInt(header, 8)
                if (length !in 0..UsbBridgeConstants.MAX_MESSAGE_SIZE) {
                    reason = "USB invalid CPC200 payload length: $length type=$type"
                    break
                }
                val payload = ByteArray(length)
                if (length > 0 && !readExact(conn, endpoint, generation, payload, READ_TIMEOUT, "payload type=$type", deviceName)) break
                val pts = if (type == VIDEO_TYPE && payload.size >= 16) littleEndianInt(payload, 12) else 0
                sendInbound(type, pts, payload, generation)
            }
        } catch (e: Exception) {
            reason = "USB read failed: ${e.message ?: e.javaClass.simpleName}"
        } finally {
            closeDevice(reason, generation)
        }
    }

    private fun readExact(conn: UsbDeviceConnection, endpoint: UsbEndpoint, generation: Long, buffer: ByteArray, timeout: Int, phase: String, deviceName: String): Boolean {
        var offset = 0
        while (offset < buffer.size && running.get() && generation == deliveryGeneration.get()) {
            val started = System.nanoTime()
            val n = conn.bulkTransfer(endpoint, buffer, offset, buffer.size - offset, timeout)
            val finished = System.nanoTime()
            if (generation != deliveryGeneration.get() || !running.get()) return false
            if (n <= 0) {
                fun ageMs(time: Long): Long = if (time == 0L) -1 else (finished - time) / 1_000_000
                val elapsedMs = (finished - started) / 1_000_000
                val present = usbManager.deviceList.containsKey(deviceName)
                throw java.io.IOException("phase=$phase result=$n bytes=$offset/${buffer.size} elapsedMs=$elapsedMs timeoutMs=$timeout " +
                    "timeoutLike=${elapsedMs >= timeout - 250} devicePresent=$present lastReadAgeMs=${ageMs(lastReadNs.get())} " +
                    "lastWriteAgeMs=${ageMs(lastWriteNs.get())} lastWriteResult=$lastWriteResult lastWriteElapsedMs=$lastWriteElapsedMs " +
                    "dispatchQueued=${realtimeDispatch.queue.size}")
            }
            lastReadNs.set(finished)
            offset += n
        }
        return offset == buffer.size && generation == deliveryGeneration.get()
    }

    private fun sendInbound(type: Int, pts: Int, payload: ByteArray, generation: Long) {
        val queuedNs = System.nanoTime()
        if (type == 7) {
            val previous = lastAudioReadNs.getAndSet(queuedNs)
            if (previous != 0L) maxAudioGapMs.updateAndGet { maxOf(it, (queuedNs - previous) / 1_000_000) }
        }
        val delivery = Runnable {
            if (running.get() && generation == deliveryGeneration.get()) {
                val startedNs = System.nanoTime()
                if (type == 7) maxAudioQueueMs.updateAndGet { maxOf(it, (startedNs - queuedNs) / 1_000_000) }
                deliverInbound(type, pts, payload, generation)
                val finishedNs = System.nanoTime()
                if (type == 7) maxAudioCallbackMs.updateAndGet { maxOf(it, (finishedNs - startedNs) / 1_000_000) }
                if (finishedNs - lastDeliverySummaryNs.get() >= 30_000_000_000L) {
                    lastDeliverySummaryNs.set(finishedNs)
                    emitState(UsbBridgeConstants.STATE_CONNECTED, "[USB_DELIVERY] audioMaxArrivalGapMs=${maxAudioGapMs.getAndSet(0)} " +
                        "audioMaxQueueWaitMs=${maxAudioQueueMs.getAndSet(0)} audioMaxCallbackMs=${maxAudioCallbackMs.getAndSet(0)} " +
                        "queued=${realtimeDispatch.queue.size}")
                }
            }
        }
        try {
            // Preserve wire order across PLUGGED, audio commands and video. A
            // separate video worker races decoder setup against incoming frames.
            realtimeDispatch.execute(delivery)
        } catch (_: RejectedExecutionException) {
            // Never silently lose H.264 reference frames or queue old call audio
            // indefinitely. End this session and let the client reconnect.
            Thread {
                synchronized(this@UsbBridgeService) {
                    if (generation != deliveryGeneration.get() || !running.get()) return@synchronized
                    emitState(UsbBridgeConstants.STATE_ERROR, "USB delivery backlog exceeded limit")
                    closeDevice("USB delivery backlog", generation)
                }
            }.start()
        }
    }

    private fun deliverInbound(type: Int, pts: Int, payload: ByteArray, generation: Long) {
        val transferId = transferIds.getAndIncrement()
        val count = maxOf(1, (payload.size + UsbBridgeConstants.CHUNK_SIZE - 1) / UsbBridgeConstants.CHUNK_SIZE)
        for (index in 0 until count) {
            if (generation != deliveryGeneration.get() || !running.get()) return
            val offset = index * UsbBridgeConstants.CHUNK_SIZE
            val chunk = payload.copyOfRange(offset, minOf(payload.size, offset + UsbBridgeConstants.CHUNK_SIZE))
            callbacks.forEach { callback ->
                try {
                    callback.onInboundChunk(transferId, type, pts, index, count, payload.size, chunk)
                } catch (_: RemoteException) {
                    callbacks -= callback
                }
            }
        }
    }

    private fun emitState(state: Int, detail: String) {
        callbacks.forEach { callback ->
            try {
                callback.onState(state, detail)
            } catch (_: RemoteException) {
                callbacks -= callback
            }
        }
    }

    private fun findBulkEndpoints(device: UsbDevice): Triple<UsbInterface, UsbEndpoint, UsbEndpoint>? {
        for (i in 0 until device.interfaceCount) {
            val iface = device.getInterface(i)
            var input: UsbEndpoint? = null
            var output: UsbEndpoint? = null
            for (j in 0 until iface.endpointCount) {
                val endpoint = iface.getEndpoint(j)
                if (endpoint.type != UsbConstants.USB_ENDPOINT_XFER_BULK) continue
                if (endpoint.direction == UsbConstants.USB_DIR_IN) input = endpoint else output = endpoint
            }
            if (input != null && output != null) return Triple(iface, input, output)
        }
        return null
    }

    private fun isKnownDevice(device: UsbDevice): Boolean =
        (device.vendorId == 4884 && device.productId in setOf(5408, 5409)) ||
            (device.vendorId == 2276 && device.productId == 448)

    private fun describeDevices(devices: List<UsbDevice>): String =
        devices.joinToString(prefix = "[", postfix = "]") {
            "${it.deviceName}:0x${it.vendorId.toString(16)}:0x${it.productId.toString(16)}"
        }.ifEmpty { "none" }

    private class ChunkAccumulator(private val count: Int, private val totalLength: Int) {
        private val chunks = arrayOfNulls<ByteArray>(count)
        fun add(index: Int, data: ByteArray): Boolean {
            if (index !in chunks.indices || chunks[index] != null) return false
            chunks[index] = data.copyOf()
            return true
        }
        fun complete(): Boolean = chunks.all { it != null }
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
        const val ACTION_USB_ATTACHED = "com.carlink.usbhelper.USB_ATTACHED"
        private const val HEADER_SIZE = 16
        private const val READ_TIMEOUT = 30_000
        private const val WRITE_TIMEOUT = 1_000
        private const val VIDEO_TYPE = 0x06
        private const val NAVI_VIDEO_TYPE = 0x2C

        private fun littleEndianInt(data: ByteArray, offset: Int): Int =
            ByteBuffer.wrap(data, offset, 4).order(ByteOrder.LITTLE_ENDIAN).int
    }
}
