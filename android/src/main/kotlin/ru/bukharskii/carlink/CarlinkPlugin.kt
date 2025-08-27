package ru.bukharskii.carlink

import CrashCallback
import CrashHandler
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.util.Log
import android.util.DisplayMetrics
import android.view.Display
import android.view.WindowManager
import android.hardware.usb.UsbConfiguration
import android.hardware.usb.UsbConstants.USB_DIR_OUT
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbEndpoint
import android.hardware.usb.UsbInterface
import android.hardware.usb.UsbManager
import io.flutter.embedding.engine.plugins.FlutterPlugin
import io.flutter.plugin.common.BasicMessageChannel
import io.flutter.plugin.common.BinaryCodec
import io.flutter.plugin.common.MethodCall
import io.flutter.plugin.common.MethodChannel
import io.flutter.plugin.common.MethodChannel.MethodCallHandler
import io.flutter.plugin.common.MethodChannel.Result
import io.flutter.view.TextureRegistry
import io.flutter.view.TextureRegistry.SurfaceTextureEntry
import java.lang.Exception
import java.nio.ByteBuffer

private const val ACTION_USB_PERMISSION = "ru.bukharskii.carlink.USB_PERMISSION"
private const val TAG = "CARLINK"

private val pendingIntentFlag =
    PendingIntent.FLAG_MUTABLE or PendingIntent.FLAG_UPDATE_CURRENT

private fun pendingPermissionIntent(context: Context) =
    PendingIntent.getBroadcast(context, 0, Intent(ACTION_USB_PERMISSION), pendingIntentFlag)

/** CarlinkPlugin */
class CarlinkPlugin : FlutterPlugin, MethodCallHandler {
    private lateinit var channel: MethodChannel

    private lateinit var textureRegistry: TextureRegistry
    private var surfaceTextureEntry: SurfaceTextureEntry? = null
    
    // Crash recovery tracking
    private var lastResetTime: Long = 0
    private var consecutiveResets: Int = 0
    private val RESET_THRESHOLD = 3 // Trigger cleanup after 3 resets
    private val RESET_WINDOW_MS = 30000 // 30 seconds window

    private var h264Renderer: H264Renderer? = null

    private var applicationContext: Context? = null
    private var usbManager: UsbManager? = null

    private var usbDevice: UsbDevice? = null
    private var usbDeviceConnection: UsbDeviceConnection? = null

    private var readLoopRunning = false

    private val executors: AppExecutors = AppExecutors.getInstance()

    private lateinit var binaryChannel: BasicMessageChannel<ByteBuffer>

    override fun onAttachedToEngine(flutterPluginBinding: FlutterPlugin.FlutterPluginBinding) {

        channel = MethodChannel(flutterPluginBinding.binaryMessenger, "carlink")
        channel.setMethodCallHandler(this)

        textureRegistry = flutterPluginBinding.textureRegistry
        applicationContext = flutterPluginBinding.applicationContext
        usbManager = applicationContext?.getSystemService(UsbManager::class.java)

        Thread.setDefaultUncaughtExceptionHandler(CrashHandler {
            log("[CRASH] UncaughtException: "+it)
        })
    }

    override fun onDetachedFromEngine(binding: FlutterPlugin.FlutterPluginBinding) {
        channel.setMethodCallHandler(null)
        usbManager = null
        applicationContext = null
    }

    fun log(message: String) {
        Log.d(TAG, message)
        executors.mainThread().execute {
            channel.invokeMethod("onLogMessage", message)
        }
    }

    fun readingLoopMessageWithData(type: Int, data: ByteArray) {
        executors.mainThread().execute {
            channel.invokeMethod("onReadingLoopMessage", mapOf("type" to type, "data" to data))
        }
    }

    fun readingLoopMessage(type: Int) {
        executors.mainThread().execute {
            channel.invokeMethod("onReadingLoopMessage", mapOf("type" to type))
        }
    }

    fun readingLoopError(error: String) {
        // Check if this is a codec reset and track for crash recovery
        if (error.contains("reset codec") || error.contains("IllegalStateException")) {
            handleCodecReset()
        }
        
        executors.mainThread().execute {
            channel.invokeMethod("onReadingLoopError", error)
        }
    }
    
    private fun handleCodecReset() {
        val currentTime = System.currentTimeMillis()
        
        // Reset counter if outside the window
        if (currentTime - lastResetTime > RESET_WINDOW_MS) {
            consecutiveResets = 0
        }
        
        consecutiveResets++
        lastResetTime = currentTime
        
        log("[CRASH RECOVERY] Reset count: $consecutiveResets in window")
        
        // If we've hit the threshold, perform complete cleanup
        if (consecutiveResets >= RESET_THRESHOLD) {
            log("[CRASH RECOVERY] Threshold reached, performing complete system cleanup")
            performEmergencyCleanup()
            consecutiveResets = 0 // Reset counter after cleanup
        }
    }
    
    private fun performEmergencyCleanup() {
        try {
            log("[EMERGENCY CLEANUP] Starting conservative system cleanup")
            
            // Stop reading loop gracefully
            readLoopRunning = false
            
            // Reset H.264 renderer using proper method
            h264Renderer?.let { renderer ->
                try {
                    renderer.reset() // Uses our corrected reset logic
                } catch (e: Exception) {
                    log("[EMERGENCY CLEANUP] H264 renderer reset failed: ${e.message}")
                }
            }
            // Don't null out h264Renderer - let it recover naturally
            
            // Close USB connection properly per Android documentation
            try {
                usbDeviceConnection?.close() // Releases all system resources
                usbDeviceConnection = null
                usbDevice = null
            } catch (e: Exception) {
                log("[EMERGENCY CLEANUP] USB cleanup failed: ${e.message}")
            }
            
            log("[EMERGENCY CLEANUP] Conservative cleanup finished")
            
            // Notify Flutter layer about the cleanup
            executors.mainThread().execute {
                channel.invokeMethod("onEmergencyCleanup", null)
            }
            
        } catch (e: Exception) {
            log("[EMERGENCY CLEANUP] Failed: ${e.message}")
        }
    }

    override fun onMethodCall(call: MethodCall, result: Result) {
        when (call.method) {

            "getDisplayMetrics" -> {
                val context = applicationContext ?: return result.error(
                    "IllegalState",
                    "applicationContext null",
                    null
                )
                
                val windowManager = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
                val display = windowManager.defaultDisplay
                val metrics = DisplayMetrics()
                
                // Get real display metrics (including status bar, navigation bar)
                display.getRealMetrics(metrics)
                
                val displayInfo = mapOf(
                    "widthPixels" to metrics.widthPixels,
                    "heightPixels" to metrics.heightPixels,
                    "densityDpi" to metrics.densityDpi,
                    "density" to metrics.density,
                    "scaledDensity" to metrics.scaledDensity,
                    "xdpi" to metrics.xdpi,
                    "ydpi" to metrics.ydpi,
                    "refreshRate" to display.refreshRate
                )
                
                log("[DISPLAY] Hardware resolution: ${metrics.widthPixels}x${metrics.heightPixels}, DPI: ${metrics.densityDpi}, Density: ${metrics.density}, Refresh: ${display.refreshRate}Hz")
                
                result.success(displayInfo)
            }

            "createTexture" -> {
                surfaceTextureEntry = textureRegistry.createSurfaceTexture()

                var texture = surfaceTextureEntry!!.surfaceTexture()

                val width = call.argument<Int>("width")!!
                val height = call.argument<Int>("height")!!

                if (h264Renderer != null) {
                    h264Renderer?.stop()
                }

                h264Renderer = H264Renderer(applicationContext, width, height, texture,
                    surfaceTextureEntry!!.id().toInt(), LogCallback { m ->  log("[H264Renderer] " + m) })

                h264Renderer?.start()

                result.success(surfaceTextureEntry!!.id())
            }

            "removeTexture" -> {
                h264Renderer?.stop()
                h264Renderer = null

                surfaceTextureEntry?.release()

                result.success(null)
            }

            "resetH264Renderer" -> {
                h264Renderer?.reset()
                result.success(null)
            }

            "getDeviceList" -> {
                val manager =
                    usbManager ?: return result.error("IllegalState", "usbManager null", null)
                log("[USB] Scanning for Carlinkit devices...")
                
                // Known Carlinkit VID/PID combinations
                // Known Carlinkit VID/PID combinations (must match as pairs)
                // Primary: VID 0x1314 with PID 0x1520/0x1521
                // Alternate: VID 0x08e4 with PID 0x01c0
                
                val usbDeviceList = manager.deviceList.entries.map {
                    val device = it.value
                    val isCarlinkit = (device.vendorId == 0x1314 && (device.productId == 0x1520 || device.productId == 0x1521)) ||
                                     (device.vendorId == 0x08e4 && device.productId == 0x01c0)
                    
                    if (isCarlinkit) {
                        log("[USB] Found Carlinkit device: ${it.key}, VID:${String.format("%04X", device.vendorId)}, PID:${String.format("%04X", device.productId)}")
                    }
                    
                    mapOf(
                        "identifier" to it.key,
                        "vendorId" to device.vendorId,
                        "productId" to device.productId,
                        "configurationCount" to device.configurationCount,
                    )
                }
                
                val carlinkDevices = usbDeviceList.filter { device ->
                    val vid = device["vendorId"] as Int
                    val pid = device["productId"] as Int
                    (vid == 0x1314 && (pid == 0x1520 || pid == 0x1521)) ||
                    (vid == 0x08e4 && pid == 0x01c0)
                }
                
                if (carlinkDevices.isEmpty()) {
                    log("[USB] No Carlinkit devices found (${usbDeviceList.size} total USB devices)")
                } else {
                    log("[USB] Found ${carlinkDevices.size} Carlinkit device(s)")
                }
                
                result.success(usbDeviceList)
            }

            "getDeviceDescription" -> {
                val context = applicationContext ?: return result.error(
                    "IllegalState",
                    "applicationContext null",
                    null
                )
                val manager =
                    usbManager ?: return result.error("IllegalState", "usbManager null", null)
                val identifier = call.argument<Map<String, Any>>("device")!!["identifier"]!!;
                val device = manager.deviceList[identifier] ?: return result.error(
                    "IllegalState",
                    "usbDevice null",
                    null
                )
                val requestPermission = call.argument<Boolean>("requestPermission")!!;

                val hasPermission = manager.hasPermission(device)
                if (requestPermission && !hasPermission) {
                    val permissionReceiver = object : BroadcastReceiver() {
                        override fun onReceive(context: Context, intent: Intent) {
                            context.unregisterReceiver(this)
                            val granted =
                                intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false);
                            result.success(
                                mapOf(
                                    "manufacturer" to device.manufacturerName,
                                    "product" to device.productName,
                                    "serialNumber" to if (granted) device.serialNumber else null,
                                )
                            )
                        }
                    }
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                        context.registerReceiver(
                            permissionReceiver,
                            IntentFilter(ACTION_USB_PERMISSION),
                            Context.RECEIVER_NOT_EXPORTED
                        )
                    } else {
                        // For API 31-32, use standard registration
                        context.registerReceiver(permissionReceiver, IntentFilter(ACTION_USB_PERMISSION))
                    }
                    manager.requestPermission(device, pendingPermissionIntent(context))
                } else {
                    result.success(
                        mapOf(
                            "manufacturer" to device.manufacturerName,
                            "product" to device.productName,
                            "serialNumber" to if (hasPermission) device.serialNumber else null,
                        )
                    )
                }
            }

            "hasPermission" -> {
                val manager =
                    usbManager ?: return result.error("IllegalState", "usbManager null", null)
                val identifier = call.argument<String>("identifier")
                val device = manager.deviceList[identifier]
                result.success(manager.hasPermission(device))
            }

            "requestPermission" -> {
                val context = applicationContext ?: return result.error(
                    "IllegalState",
                    "applicationContext null",
                    null
                )
                val manager =
                    usbManager ?: return result.error("IllegalState", "usbManager null", null)
                val identifier = call.argument<String>("identifier")
                val device = manager.deviceList[identifier]
                if (manager.hasPermission(device)) {
                    result.success(true)
                } else {
                    val receiver = object : BroadcastReceiver() {
                        override fun onReceive(context: Context, intent: Intent) {
                            context.unregisterReceiver(this)
                            val usbDevice =
                                intent.getParcelableExtra<UsbDevice>(UsbManager.EXTRA_DEVICE)
                            val granted =
                                intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)
                            result.success(granted);
                        }
                    }
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                        context.registerReceiver(receiver, IntentFilter(ACTION_USB_PERMISSION), Context.RECEIVER_NOT_EXPORTED)
                    } else {
                        // For API 31-32, use standard registration  
                        context.registerReceiver(receiver, IntentFilter(ACTION_USB_PERMISSION))
                    }
                    manager.requestPermission(device, pendingPermissionIntent(context))
                }
            }

            "openDevice" -> {
                val manager =
                    usbManager ?: return result.error("IllegalState", "usbManager null", null)
                val identifier = call.argument<String>("identifier")
                log("[USB] Opening device: $identifier")
                usbDevice = manager.deviceList[identifier]
                usbDeviceConnection = manager.openDevice(usbDevice)
                val success = usbDeviceConnection != null
                log("[USB] Device open result: $success")
                if (success) {
                    log("[USB] Device info: ${usbDevice?.productName} by ${usbDevice?.manufacturerName}")
                }
                result.success(success)
            }

            "closeDevice" -> {
                log("[USB] Closing device connection")
                usbDeviceConnection?.close()
                usbDeviceConnection = null
                usbDevice = null
                log("[USB] Device connection closed")
                result.success(null)
            }

            "resetDevice" -> {
                log("[USB] Attempting device reset")
                try {
                    var resetMethod = usbDeviceConnection?.javaClass?.getDeclaredMethod("resetDevice")
                    resetMethod?.invoke(usbDeviceConnection)
                    log("[USB] Device reset successful")
                    result.success(true)
                } catch (e: Exception) {
                    log("[USB] Device reset failed: " + e.toString())
                    result.success(false)
                }
            }

            "getConfiguration" -> {
                val device =
                    usbDevice ?: return result.error("IllegalState", "usbDevice null", null)
                val index = call.argument<Int>("index")!!
                val configuration = device.getConfiguration(index)
                val map = configuration.toMap() + ("index" to index)
                result.success(map)
            }

            "setConfiguration" -> {
                val device =
                    usbDevice ?: return result.error("IllegalState", "usbDevice null", null)
                val connection = usbDeviceConnection ?: return result.error(
                    "IllegalState",
                    "usbDeviceConnection null",
                    null
                )
                val index = call.argument<Int>("index")!!
                val configuration = device.getConfiguration(index)
                log("[USB] Setting configuration $index (ID: ${configuration.id})")
                val success = connection.setConfiguration(configuration)
                log("[USB] Configuration set result: $success")
                result.success(success)
            }

            "claimInterface" -> {
                val device =
                    usbDevice ?: return result.error("IllegalState", "usbDevice null", null)
                val connection = usbDeviceConnection ?: return result.error(
                    "IllegalState",
                    "usbDeviceConnection null",
                    null
                )
                val id = call.argument<Int>("id")!!
                val alternateSetting = call.argument<Int>("alternateSetting")!!
                log("[USB] Claiming interface ID:$id, Alt:$alternateSetting")
                val usbInterface = device.findInterface(id, alternateSetting)
                val success = connection.claimInterface(usbInterface, true)
                log("[USB] Interface claim result: $success (Endpoints: ${usbInterface?.endpointCount})")
                result.success(success)
            }

            "releaseInterface" -> {
                val device =
                    usbDevice ?: return result.error("IllegalState", "usbDevice null", null)
                val connection = usbDeviceConnection ?: return result.error(
                    "IllegalState",
                    "usbDeviceConnection null",
                    null
                )
                val id = call.argument<Int>("id")!!
                val alternateSetting = call.argument<Int>("alternateSetting")!!
                log("[USB] Releasing interface ID:$id, Alt:$alternateSetting")
                val usbInterface = device.findInterface(id, alternateSetting)
                val success = connection.releaseInterface(usbInterface)
                log("[USB] Interface release result: $success")
                result.success(success)
            }

            "stopReadingLoop" -> {
                readLoopRunning = false

                result.success(null)
            }

            "startReadingLoop" -> {
                if (readLoopRunning) {
                    return result.error("IllegalState", "readingLoop running", null)
                }

                val device =
                    usbDevice ?: return result.error("IllegalState", "usbDevice null", null)
                
                val connection = usbDeviceConnection
                    ?: return result.error(
                        "IllegalState",
                        "usbDeviceConnection null",
                        null
                    )

                val endpointMap = call.argument<Map<String, Any>>("endpoint")!!
                val endpoint =
                    device.findEndpoint(
                        endpointMap["endpointNumber"] as Int,
                        endpointMap["direction"] as Int
                    )!!
                    
                val timeout = call.argument<Int>("timeout")!!

                executors.usbIn().execute {

                    log("[USB] Read loop started")
                    readLoopRunning = true;

                    var streamingNotified = false

                    val headerBuffer = ByteBuffer.allocate(CarLinkMessageHeader.MESSAGE_LENGTH)
                    val header = CarLinkMessageHeader(0,0)

                    var actualLength = 0

                    while (readLoopRunning) {

                        // read header
                        headerBuffer.clear()
                        actualLength = readByChunks(connection, endpoint, headerBuffer.array(), 0, CarLinkMessageHeader.MESSAGE_LENGTH, timeout)

                        if (actualLength < 0) {
                            break
                        }

//                        log("[READ LOOP] read header, actualLength="+actualLength)

                        try {
                            header.readFromBuffer(headerBuffer)

                          // log("[READ LOOP] header, type="+header.type+", length="+header.length)
                            if (header.length > 0) {
                                // video data direct render
                                if (header.isVideoData) {
                                    h264Renderer?.processDataDirect(header.length, 20) { bytes, offset ->
                                        actualLength = readByChunks(connection, endpoint, bytes, offset, header.length, timeout)
                                    }

                                    if (actualLength < 0) {
                                        break
                                    }

                                    // notify once
                                    // video streaming started
                                    if (!streamingNotified) {
                                        streamingNotified = true
                                        readingLoopMessageWithData(header.type, ByteArray(0))
                                    }
                                } else {
                                    var bodyBytes = ByteArray(header.length)
                                    actualLength = readByChunks(connection, endpoint, bodyBytes, 0, header.length, timeout)

                                    if (actualLength < 0) {
                                        break
                                    }

                                    readingLoopMessageWithData(header.type, bodyBytes)
                                }
                            }
                            else {
                                readingLoopMessage(header.type)
                            }
                        } catch (e: Exception) {
                            readingLoopError(e.toString())
                            break
                        }
                    }

                    log("[USB] Read loop stopped")

                    readLoopRunning = false;
                    readingLoopError("USBReadError readingLoopError error, return actualLength=-1")
                }

                result.success(null)
            }

            "bulkTransferIn" -> {
                val isVideoData = call.argument<Boolean>("isVideoData")!!
                val device =
                    usbDevice ?: return result.error("IllegalState", "usbDevice null", null)
                val connection = usbDeviceConnection
                    ?: return result.error(
                        "IllegalState",
                        "usbDeviceConnection null",
                        null
                    )
                val endpointMap = call.argument<Map<String, Any>>("endpoint")!!
                val maxLength = call.argument<Int>("maxLength")!!
                val endpoint =
                    device.findEndpoint(
                        endpointMap["endpointNumber"] as Int,
                        endpointMap["direction"] as Int
                    )!!
                val timeout = call.argument<Int>("timeout")!!

                executors.usbIn().execute {
                   // var buffer = readByChunks(connection, endpoint, maxLength, timeout);

                    executors.mainThread().execute {
//                        if (buffer == null) {
//                            result.error(
//                                "USBReadError",
//                                "bulkTransferIn error, return actuallength=-1",
//                                ""
//                            )
//                        } else {
//                            if (isVideoData) {
//                                h264Renderer?.processData(buffer.array(), 20, maxLength - 20)
//                                result.success(ByteArray(0))
//                            } else {
//                                result.success(buffer)
//                            }
//                        }
                    }
                }
            }

            "bulkTransferOut" -> {
                val device =
                    usbDevice ?: return result.error("IllegalState", "usbDevice null", null)
                val connection = usbDeviceConnection
                    ?: return result.error(
                        "IllegalState",
                        "usbDeviceConnection null",
                        null
                    )
                val endpointMap = call.argument<Map<String, Any>>("endpoint")!!
                val endpoint =
                    device.findEndpoint(
                        endpointMap["endpointNumber"] as Int,
                        endpointMap["direction"] as Int
                    )
                val timeout = call.argument<Int>("timeout")!!
                val data = call.argument<ByteArray>("data")!!

                executors.usbOut().execute {
                    val actualLength =
                        connection.bulkTransfer(endpoint, data, data.count(), timeout)

                    executors.mainThread().execute {
                        if (actualLength < 0) {
                            result.error("USBWriteError", "bulkTransferOut error, actualLength=-1", null)
                        } else {
                            result.success(actualLength)
                        }
                    }
                }
            }

            else -> {
                result.notImplemented()
            }
        }
    }
}

fun readByChunks(connection: UsbDeviceConnection, endpoint: UsbEndpoint, buffer: ByteArray, bufferOffset: Int, maxLength: Int, timeout: Int): Int {
    var limit = 16384
    var offset = 0
    
    // Critical bounds checking to prevent ArrayIndexOutOfBoundsException
    if (bufferOffset < 0 || maxLength < 0) {
        Log.e("CARLINK", "[USB] CRITICAL: Invalid parameters - bufferOffset: $bufferOffset, maxLength: $maxLength")
        return -1
    }
    
    if (bufferOffset + maxLength > buffer.size) {
        Log.e("CARLINK", "[USB] CRITICAL: Buffer overrun - buffer.size: ${buffer.size}, bufferOffset: $bufferOffset, maxLength: $maxLength")
        return -1
    }

    while (offset < maxLength) {
        var lengthToRead = minOf(limit, maxLength - offset)
        
        // Additional safety check for each read
        if (bufferOffset + offset + lengthToRead > buffer.size) {
            Log.w("CARLINK", "[USB] WARNING: Truncating read to prevent buffer overrun")
            lengthToRead = buffer.size - bufferOffset - offset
            if (lengthToRead <= 0) {
                Log.e("CARLINK", "[USB] CRITICAL: No space left in buffer")
                return offset // Return what we've read so far
            }
        }
        
        var actualLength = connection.bulkTransfer(endpoint, buffer, bufferOffset + offset, lengthToRead, timeout)

        if (actualLength < 0) {
            return actualLength
        }
        else {
            offset += actualLength
        }
    }

    return maxLength
}

fun UsbDevice.findInterface(id: Int, alternateSetting: Int): UsbInterface? {
    for (i in 0..interfaceCount) {
        val usbInterface = getInterface(i)
        if (usbInterface.id == id && usbInterface.alternateSetting == alternateSetting) {
            return usbInterface
        }
    }
    return null
}

fun UsbDevice.findEndpoint(endpointNumber: Int, direction: Int): UsbEndpoint? {
    for (i in 0..interfaceCount) {
        val usbInterface = getInterface(i)
        for (j in 0..usbInterface.endpointCount) {
            val endpoint = usbInterface.getEndpoint(j)
            if (endpoint.endpointNumber == endpointNumber && endpoint.direction == direction) {
                return endpoint
            }
        }
    }
    return null
}

fun UsbConfiguration.toMap() = mapOf(
    "id" to id,
    "interfaces" to List(interfaceCount) { getInterface(it).toMap() }
)

fun UsbInterface.toMap() = mapOf(
    "id" to id,
    "alternateSetting" to alternateSetting,
    "endpoints" to List(endpointCount) { getEndpoint(it).toMap() }
)

fun UsbEndpoint.toMap() = mapOf(
    "endpointNumber" to endpointNumber,
    "direction" to direction,
    "maxPacketSize" to maxPacketSize
)
