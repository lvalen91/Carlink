import 'dart:async';

import '../common.dart';

import 'readable.dart';
import 'sendable.dart';
import 'usb/usb_device_wrapper.dart';

class Dongle {
  final UsbDeviceWrapper _usbDevice;

  Function(Message)? _messageHandler;
  Function({String? error})? _errorHandler;

  final Function(String) _logHandler;

  Timer? _heartBeat;

  late final int _readTimeout;
  late final int _writeTimeout;

  Dongle(this._usbDevice, this._messageHandler, this._errorHandler,
      this._logHandler,
      {int readTimeout = 30000, writeTimeout = 1000}) {
    _readTimeout = readTimeout;
    _writeTimeout = writeTimeout;
  }

  start() async {
    _logHandler('Dongle initializing');

    if (!_usbDevice.isOpened) {
      _logHandler('usbDevice not opened');
      _errorHandler?.call(error: 'usbDevice not opened');
      return;
    }

    final config = DEFAULT_CONFIG;

    final initMessages = [
      SendNumber(config.dpi, FileAddress.DPI),
      SendOpen(config),
      SendBoolean(config.nightMode, FileAddress.NIGHT_MODE),
      SendNumber(config.hand.id, FileAddress.HAND_DRIVE_MODE),
      SendBoolean(true, FileAddress.CHARGE_MODE),
      SendString(config.boxName, FileAddress.BOX_NAME),
      SendString(_generateAirplayConfig(config), FileAddress.AIRPLAY_CONFIG),
      SendBoxSettings(config, null),
      SendCommand(CommandMapping.wifiEnable),
      SendCommand(config.wifiType == '5ghz'
          ? CommandMapping.wifi5g
          : CommandMapping.wifi24g),
      SendCommand(
          config.micType == 'box' ? CommandMapping.boxMic : CommandMapping.mic),
      SendCommand(
        config.audioTransferMode
            ? CommandMapping.audioTransferOn
            : CommandMapping.audioTransferOff,
      ),
      if (config.androidWorkMode == true)
        SendBoolean(config.androidWorkMode!, FileAddress.ANDROID_WORK_MODE),
    ];

    for (final message in initMessages) {
      await send(message);
    }

    _heartBeat?.cancel();
    _logHandler('Heartbeat started (every 2s)');
    _heartBeat = Timer.periodic(const Duration(seconds: 2), (timer) async {
      await send(HeartBeat());
    });

    await _readLoop();
  }

  close() async {
    if (_heartBeat != null) {
      _logHandler('Heartbeat stopped');
      _heartBeat?.cancel();
      _heartBeat = null;
    }

    _errorHandler = null;
    _messageHandler = null;

    await _usbDevice.stopReadingLoop();
    await _usbDevice.close();
  }

  Future<bool> send(SendableMessage message) async {
    try {
      final data = message.serialise();

      // Skip logging individual heartbeats to reduce noise
      if (message.type != MessageType.HeartBeat) {
        if (message is SendCommand) {
          // Special logging for audio/mic configuration commands
          String commandDescription = '';
          switch (message.value) {
            case CommandMapping.mic:
              commandDescription = 'MicSource: os (host app microphone)';
              break;
            case CommandMapping.boxMic:
              commandDescription = 'MicSource: box (dongle built-in mic)';
              break;
            case CommandMapping.audioTransferOn:
              commandDescription = 'AudioTransfer: ON (direct Bluetooth to car)';
              break;
            case CommandMapping.audioTransferOff:
              commandDescription = 'AudioTransfer: OFF (through dongle)';
              break;
            default:
              commandDescription = message.value.name;
          }
          _logHandler('[SEND] Command 0x${message.value.id.toRadixString(16).padLeft(2, '0').toUpperCase()} ($commandDescription)');
        } else {
          _logHandler('[SEND] ${message.type.name}');
        }
      }

      final length = await _usbDevice.write(data, timeout: _writeTimeout);

      if (data.length == length) {
        return true;
      }
    } catch (e) {
      _logHandler("send error $e");
      _errorHandler?.call(error: e.toString());
    }

    return false;
  }

  _readLoop() async {
    await _usbDevice.startReadingLoop(
      //
      onMessage: (type, data) async {
        final header =
            MessageHeader(data?.length ?? 0, MessageType.fromId(type));
        final message = header.toMessage(data?.buffer.asByteData());
        if (message != null) {
          // Enhanced logging for AudioData with parsed details
          if (message is AudioData) {
            _logHandler("[RECV] ${message.toString()}, length: ${data?.lengthInBytes ?? 0}");
          } else {
            _logHandler(
                "[RECV] ${message.header.type.name} ${(message is Command ? message.value.name : "")}, length: ${data?.lengthInBytes ?? 0}");
          }

          try {
            _messageHandler?.call(message);
          } catch (e) {
            _logHandler("Error handling message, ${e.toString()}");
          }

          if (message is Opened) {
            await send(SendCommand(CommandMapping.wifiConnect));
          }
        }
      },
      //
      onError: (error) {
        _logHandler("ReadingLoopError $error");
        _errorHandler?.call(error: "ReadingLoopError $error");
      },
      //
      timeout: _readTimeout,
    );
  }

  /// Generate AirPlay configuration string for the dongle
  String _generateAirplayConfig(DongleConfig config) {
    return '''oem_icon_visible=${config.oemIconVisible ? '1' : '0'}
name=${config.boxName}
model=Magic-Car-Link-1.00
oem_icon_path=/etc/oem_icon.png
oem_icon_label=${config.boxName}
''';
  }
}
