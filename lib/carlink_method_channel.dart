import 'package:flutter/foundation.dart';
import 'package:flutter/services.dart';

import 'carlink_platform_interface.dart';
import 'usb.dart';

/// An implementation of [CarlinkPlatform] that uses method channels.
class MethodChannelCarlink extends CarlinkPlatform {
  /// The method channel used to interact with the native platform.
  @visibleForTesting
  final methodChannel = const MethodChannel('carlink');

  Function(String)? _logHandler;
  Function(int, Uint8List?)? _readingLoopMessageHandler;
  Function(String)? _readingLoopErrorHandler;

  MethodChannelCarlink() {
    methodChannel.setMethodCallHandler((call) async {
      if (call.method == "onLogMessage") {
        _logHandler?.call(call.arguments);
      } else if (call.method == "onReadingLoopMessage") {
        final type = call.arguments["type"];
        final data = Uint8List.fromList(call.arguments["data"]);

        if (_readingLoopMessageHandler != null) {
          _readingLoopMessageHandler!(type, data);
        }
      } else if (call.method == "onReadingLoopError") {
        _readingLoopErrorHandler?.call(call.arguments);
      }
    });
  }

  setLogHandler(Function(String)? logHandler) {
    _logHandler = logHandler;
  }

  @override
  Future<void> startReadingLoop(
    UsbEndpoint endpoint,
    int timeout, {
    required Function(int, Uint8List?) onMessage,
    required Function(String) onError,
  }) async {
    assert(endpoint.direction == UsbEndpoint.DIRECTION_IN,
        'Endpoint\'s direction should be in');

    _readingLoopMessageHandler = onMessage;
    _readingLoopErrorHandler = onError;

    return await methodChannel.invokeMethod('startReadingLoop', {
      'endpoint': endpoint.toMap(),
      'timeout': timeout,
    });
  }

  @override
  Future<void> stopReadingLoop() async {
    await methodChannel.invokeMethod<int>('stopReadingLoop');
  }

  @override
  Future<Map<String, dynamic>> getDisplayMetrics() async {
    final metrics = await methodChannel.invokeMethod<Map<Object?, Object?>>('getDisplayMetrics');
    return metrics!.cast<String, dynamic>();
  }

  @override
  Future<int> createTexture(int width, int height) async {
    final textureId = await methodChannel
        .invokeMethod<int>('createTexture', {"width": width, "height": height});
    return textureId!;
  }

  @override
  Future<void> removeTexture() async {
    await methodChannel.invokeMethod<int>('removeTexture');
  }

  @override
  Future<void> resetH264Renderer() async {
    await methodChannel.invokeMethod<void>('resetH264Renderer');
  }

  @override
  Future<List<UsbDevice>> getDeviceList() async {
    List<Map<dynamic, dynamic>> devices =
        (await methodChannel.invokeListMethod('getDeviceList'))!;
    return devices.map((device) => UsbDevice.fromMap(device)).toList();
  }

  @override
  Future<List<UsbDeviceDescription>> getDevicesWithDescription({
    bool requestPermission = true,
  }) async {
    var devices = await getDeviceList();
    var result = <UsbDeviceDescription>[];
    for (var device in devices) {
      result.add(await getDeviceDescription(
        device,
        requestPermission: requestPermission,
      ));
    }
    return result;
  }

  @override
  Future<UsbDeviceDescription> getDeviceDescription(
    UsbDevice usbDevice, {
    bool requestPermission = true,
  }) async {
    var result = await methodChannel.invokeMethod('getDeviceDescription', {
      'device': usbDevice.toMap(),
      'requestPermission': requestPermission,
    });
    return UsbDeviceDescription(
      device: usbDevice,
      manufacturer: result['manufacturer'],
      product: result['product'],
      serialNumber: result['serialNumber'],
    );
  }

  @override
  Future<bool> hasPermission(UsbDevice usbDevice) async {
    return await methodChannel.invokeMethod('hasPermission', usbDevice.toMap());
  }

  @override
  Future<bool> requestPermission(UsbDevice usbDevice) async {
    return await methodChannel.invokeMethod(
        'requestPermission', usbDevice.toMap());
  }

  @override
  Future<bool> openDevice(UsbDevice usbDevice) async {
    return await methodChannel.invokeMethod('openDevice', usbDevice.toMap());
  }

  @override
  Future<void> closeDevice() {
    _readingLoopErrorHandler = null;
    _readingLoopMessageHandler = null;

    return methodChannel.invokeMethod('closeDevice');
  }

  @override
  Future<bool> resetDevice() async {
    return await methodChannel.invokeMethod('resetDevice');
  }

  @override
  Future<UsbConfiguration> getConfiguration(int index) async {
    var map = await methodChannel.invokeMethod('getConfiguration', {
      'index': index,
    });
    return UsbConfiguration.fromMap(map);
  }

  @override
  Future<bool> setConfiguration(UsbConfiguration config) async {
    return await methodChannel.invokeMethod('setConfiguration', config.toMap());
  }

  @override
  Future<bool> claimInterface(UsbInterface intf) async {
    return await methodChannel.invokeMethod('claimInterface', intf.toMap());
  }

  @override
  Future<bool> releaseInterface(UsbInterface intf) async {
    return await methodChannel.invokeMethod('releaseInterface', intf.toMap());
  }

  @override
  Future<Uint8List> bulkTransferIn(
      UsbEndpoint endpoint, int maxLength, int timeout,
      {bool isVideoData = false}) async {
    assert(endpoint.direction == UsbEndpoint.DIRECTION_IN,
        'Endpoint\'s direction should be in');

    final data = await methodChannel.invokeMethod('bulkTransferIn', {
      'endpoint': endpoint.toMap(),
      'maxLength': maxLength,
      'timeout': timeout,
      'isVideoData': isVideoData,
    });

    return Uint8List.fromList(data.cast<int>());
  }

  @override
  Future<int> bulkTransferOut(
      UsbEndpoint endpoint, Uint8List data, int timeout) async {
    assert(endpoint.direction == UsbEndpoint.DIRECTION_OUT,
        'Endpoint\'s direction should be out');

    return await methodChannel.invokeMethod('bulkTransferOut', {
      'endpoint': endpoint.toMap(),
      'data': data,
      'timeout': timeout,
    });
  }
}
