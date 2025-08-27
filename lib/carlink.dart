import 'dart:async';

import 'package:carlink/carlink_platform_interface.dart';
import 'package:flutter/foundation.dart';

import 'driver/dongle_driver.dart';
import 'driver/sendable.dart';
import 'driver/readable.dart';
import 'driver/usb/usb_device_wrapper.dart';
import 'common.dart';

// ignore: constant_identifier_names
const USB_WAIT_PERIOD_MS = 3000;

enum CarlinkState {
  disconnected,
  connecting,
  deviceConnected,
  streaming,
}

class CarlinkMediaInfo {
  final String? songTitle;
  final String? songArtist;
  final String? albumName;
  final String? appName;
  final Uint8List? albumCoverImageData;

  CarlinkMediaInfo(
      {required this.songTitle,
      required this.songArtist,
      required this.albumName,
      required this.appName,
      required this.albumCoverImageData});
}

class Carlink {
  Timer? _pairTimeout;
  Timer? _frameInterval;

  Dongle? _dongleDriver;

  CarlinkState state = CarlinkState.connecting;

  bool _connectedOverWifi = false;

  late final DongleConfig _config;

  late final Function(int?) _textureHandler;
  late final Function(String)? _logHandler;
  late final Function()? _hostUIHandler;
  late final Function(CarlinkState)? _stateHandler;
  late final Function(CarlinkMediaInfo mediaInfo)? _metadataHandler;

  Carlink({
    required DongleConfig config,
    required Function(int? textureId) onTextureChanged,
    Function(CarlinkState)? onStateChanged,
    Function(CarlinkMediaInfo)? onMediaInfoChanged,
    Function(String)? onLogMessage,
    Function()? onHostUIPressed,
  }) {
    _config = config;
    _textureHandler = onTextureChanged;
    _metadataHandler = onMediaInfoChanged;
    _stateHandler = onStateChanged;
    _logHandler = onLogMessage;
    _hostUIHandler = onHostUIPressed;

    CarlinkPlatform.setLogHandler(_logHandler);

    // create texture
    CarlinkPlatform.instance
        .createTexture(_config.width, _config.height)
        .then(_textureHandler);
  }

  Future<UsbDeviceWrapper> _findDevice() async {
    UsbDeviceWrapper? device;
    bool loggedSearching = false;

    while (device == null) {
      try {
        final deviceList =
            await UsbManagerWrapper.lookupForUsbDevice(knownDevices);
        device = deviceList.firstOrNull;
      } catch (err) {
        if (!loggedSearching) {
          _log('Searching for Carlinkit device...');
          loggedSearching = true;
        }
        // ^ requestDevice throws an error when no device is found, so keep retrying
      }

      if (device == null) {
        if (!loggedSearching) {
          _log('Searching for Carlinkit device...');
          loggedSearching = true;
        }
        await Future.delayed(const Duration(milliseconds: USB_WAIT_PERIOD_MS));
      }
    }

    _log('Carlinkit device found!');
    return device;
  }

  _setState(CarlinkState newState) {
    if (state != newState) {
      state = newState;
      if (_stateHandler != null) {
        _stateHandler(state);
      }
    }
  }

  start() async {
    _setState(CarlinkState.connecting);

    if (_dongleDriver != null) {
      await stop();
    }

    _connectedOverWifi = false;

    await CarlinkPlatform.instance.resetH264Renderer();

    // Find device to "reset" first
    var device = await _findDevice();

    await device.open();
    await device.reset();
    await device.close();
    // Resetting the device causes an unplug event in node-usb
    // so subsequent writes fail with LIBUSB_ERROR_NO_DEVICE
    // or LIBUSB_TRANSFER_ERROR

    _log('Reset device, finding again...');
    await Future.delayed(const Duration(milliseconds: USB_WAIT_PERIOD_MS));
    // ^ Device disappears after reset for 1-3 seconds

    device = await _findDevice();
    _log('found & opening');

    _dongleDriver =
        Dongle(device, _handleDongleMessage, _handleDongleError, _log);

    await device.open();
    await _dongleDriver?.start();

    _clearPairTimeout();
    _pairTimeout = Timer(const Duration(seconds: 15), () async {
      await _dongleDriver?.send(SendCommand(CommandMapping.wifiPair));
    });
  }

  restart() async {
    await stop();
    await Future.delayed(const Duration(seconds: 2), start);
  }

  stop() async {
    try {
      _clearPairTimeout();
      _clearFrameInterval();
      await _dongleDriver?.close();
    } catch (err) {
      _log(err.toString());
    }

    _setState(CarlinkState.disconnected);
  }

  Future<bool> sendKey(CommandMapping action) {
    return _dongleDriver!.send(SendCommand(action));
  }

  Future<bool> sendTouch(TouchAction type, double x, double y) {
    return _dongleDriver!
        .send(SendTouch(type, x / _config.width, y / _config.height));
  }

  Future<bool> sendMultiTouch(List<TouchItem> touches) {
    return _dongleDriver!.send(SendMultiTouch(touches));
  }

  Future<bool> sendMessage(SendableMessage message) {
    return _dongleDriver!.send(message);
  }

  //------------------------------
  // Private
  //------------------------------

  _log(String message) {
    _logHandler?.call(message);
  }

  _handleDongleMessage(Message message) async {
    if (message is Plugged) {
      _clearPairTimeout();
      _clearFrameInterval();

      final phoneTypeConfig = _config.phoneConfig[message.phoneType];
      final interval = phoneTypeConfig?["frameInterval"];
      if (interval != null) {
        _frameInterval =
            Timer.periodic(Duration(milliseconds: interval), (timer) async {
          await _dongleDriver?.send(SendCommand(CommandMapping.frame));
        });
      }
      _setState(CarlinkState.deviceConnected);
    }
    //
    else if (message is Unplugged) {
      await restart();
    }
    //
    else if (message is VideoData) {
      _clearPairTimeout();

      if (state != CarlinkState.streaming) {
        _setState(CarlinkState.streaming);
      }
    }
    //
    else if (message is AudioData) {
      _clearPairTimeout();
    }
    //
    else if (message is MediaData) {
      _clearPairTimeout();
      _processMediaMetadata(message.payload);
    }
    //
    else if (message is Command) {
      if (message.value == CommandMapping.requestHostUI) {
        _hostUIHandler?.call();
      } else if (message.value == CommandMapping.wifiConnected) {
        _connectedOverWifi = true;
      } else if (message.value == CommandMapping.wifiDisconnected) {
        restart();
      }
    }

    // Trigger internal event logic
    if (message is AudioData && message.command != null) {
      switch (message.command) {
        case AudioCommand.AudioSiriStart:
        case AudioCommand.AudioPhonecallStart:
//            mic.start()
          break;
        case AudioCommand.AudioSiriStop:
        case AudioCommand.AudioPhonecallStop:
//            mic.stop()
          break;

        default:
          break;
      }
    }
  }

  _handleDongleError({String? error}) async {
    _clearPairTimeout();
    _clearFrameInterval();

    // restart
    await restart();
  }

  _clearPairTimeout() {
    _pairTimeout?.cancel();
    _pairTimeout = null;
  }

  _clearFrameInterval() {
    _frameInterval?.cancel();
    _frameInterval = null;
  }

  ///////////////

  String? _lastMediaLyrics;
  String? _lastMediaArtistName;
  String? _lastMediaSongName;
  String? _lastMediaAlbumName;
  String? _lastMediaAPPName;
  Uint8List? _lastAlbumCover;

  _processMediaMetadata(Map<String, dynamic> metadata) {
    // final mdata = metadata;
    if (metadata.length == 1 && metadata.keys.contains("MediaSongPlayTime")) {
      // skip timing
    } else {
      final String? mediaLyrics = metadata["MediaLyrics"];
      final String? mediaArtistName = metadata["MediaArtistName"];
      final String? mediaSongName = metadata["MediaSongName"];
      final String? mediaAlbumName = metadata["MediaAlbumName"];
      final String? mediaAPPName = metadata["MediaAPPName"];
      final Uint8List? albumCover = metadata["AlbumCover"];

      // on app name or lyrics update - reset
      if (mediaAPPName != null ||
          (mediaLyrics != null && _lastMediaLyrics != mediaLyrics))
      //
      {
        _lastMediaLyrics = null;
        _lastMediaSongName = null;

        _lastMediaArtistName = null;
        _lastMediaAlbumName = null;

        _lastAlbumCover = null;
      }

      if (mediaAPPName != null && mediaAPPName.isNotEmpty) {
        _lastMediaAPPName = mediaAPPName;
      }
      if (mediaArtistName != null && mediaArtistName.isNotEmpty) {
        _lastMediaArtistName = mediaArtistName;
      }
      if (mediaSongName != null && mediaSongName.isNotEmpty) {
        _lastMediaSongName = mediaSongName;
      }
      if (mediaAlbumName != null && mediaAlbumName.isNotEmpty) {
        _lastMediaAlbumName = mediaAlbumName;
      }
      if (mediaLyrics != null && mediaLyrics.isNotEmpty) {
        _lastMediaLyrics = mediaLyrics;
      }
      if (albumCover != null) {
        _lastAlbumCover = albumCover;
      }

      if (_metadataHandler != null) {
        _metadataHandler(
          CarlinkMediaInfo(
            songTitle: (_lastMediaLyrics ?? _lastMediaSongName) ?? " ",
            songArtist: _lastMediaArtistName ?? " ",
            albumName: _lastMediaAlbumName,
            appName: _lastMediaAPPName,
            albumCoverImageData: _lastAlbumCover,
          ),
        );
      }
    }
  }
}
