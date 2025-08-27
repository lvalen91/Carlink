// ignore_for_file: constant_identifier_names

import 'dart:typed_data';

import 'package:dart_buffer/dart_buffer.dart';

import 'driver/readable.dart';

enum CommandMapping {
  invalid(0), //'invalid',
  startRecordAudio(1),
  stopRecordAudio(2),
  requestHostUI(3), //'Carplay Interface My Car button clicked',
  siri(5), //'Siri Button',
  mic(7), //'Car Microphone',
  boxMic(15), //'Box Microphone',
  enableNightMode(16), // night mode
  disableNightMode(17), // disable night mode
  wifi24g(24), //'2.4G Wifi',
  wifi5g(25), //'5G Wifi',
  left(100), //'Button Left',
  right(101), //'Button Right',
  frame(12),
  audioTransferOn(
      22), // Phone will Stream audio directly to car system and not dongle
  audioTransferOff(
      23), // DEFAULT - Phone will stream audio to the dongle and it will send it over the link
  selectDown(104), //'Button Select Down',
  selectUp(105), //'Button Select Up',
  back(106), //'Button Back',
  down(114), //'Button Down',
  home(200), //'Button Home',
  play(201), //'Button Play',
  pause(202), //'Button Pause',
  next(204), //'Button Next Track',
  prev(205), //'Button Prev Track',
  requestVideoFocus(500),
  releaseVideoFocus(501),
  wifiEnable(1000),
  autoConnectEnable(1001),
  wifiConnect(1002),
  scanningDevice(1003),
  deviceFound(1004),
  deviceNotFound(1005),
  connectDeviceFailed(1006),
  btConnected(1007),
  btDisconnected(1008),
  wifiConnected(1009),
  wifiDisconnected(1010),
  btPairStart(1011),
  wifiPair(1012);

  final int id;
  const CommandMapping(this.id);

  factory CommandMapping.fromId(int id) {
    return values.firstWhere((e) => e.id == id, orElse: () => invalid);
  }
}

enum MessageType {
  Open(0x01),
  Plugged(0x02),
  Phase(0x03),
  Unplugged(0x04),
  Touch(0x05),
  VideoData(0x06),
  AudioData(0x07),
  Command(0x08),
  LogoType(0x09),
  DisconnectPhone(0xf),
  CloseDongle(0x15),
  BluetoothAddress(0x0a),
  BluetoothPIN(0x0c),
  BluetoothDeviceName(0x0d),
  WifiDeviceName(0x0e),
  BluetoothPairedList(0x12),
  ManufacturerInfo(0x14),
  MultiTouch(0x17),
  HiCarLink(0x18),
  BoxSettings(0x19),
  MediaData(0x2a),
  SendFile(0x99),
  HeartBeat(0xaa),
  SoftwareVersion(0xcc),

  Unknown(-1);

  final int id;
  const MessageType(this.id);

  factory MessageType.fromId(int id) {
    return values.firstWhere((e) => e.id == id, orElse: () => Unknown);
  }
}

enum AudioCommand {
  AudioOutputStart(1),
  AudioOutputStop(2),
  AudioInputConfig(3),
  AudioPhonecallStart(4),
  AudioPhonecallStop(5),
  AudioNaviStart(6),
  AudioNaviStop(7),
  AudioSiriStart(8),
  AudioSiriStop(9),
  AudioMediaStart(10),
  AudioMediaStop(11),
  AudioAlertStart(12),
  AudioAlertStop(13),

  Unknown(-1);

  final int id;
  const AudioCommand(this.id);

  factory AudioCommand.fromId(int id) {
    return values.firstWhere((e) => e.id == id, orElse: () => Unknown);
  }
}

enum FileAddress {
  DPI('/tmp/screen_dpi'),
  NIGHT_MODE('/tmp/night_mode'),
  HAND_DRIVE_MODE('/tmp/hand_drive_mode'),
  CHARGE_MODE('/tmp/charge_mode'),
  BOX_NAME('/etc/box_name'),
  OEM_ICON('/etc/oem_icon.png'),
  AIRPLAY_CONFIG('/etc/airplay.conf'),
  ICON_120('/etc/icon_120x120.png'),
  ICON_180('/etc/icon_180x180.png'),
  ICON_250('/etc/icon_256x256.png'),
  ANDROID_WORK_MODE('/etc/android_work_mode'),

  Unknown("");

  final String path;
  const FileAddress(this.path);

  factory FileAddress.fromId(String path) {
    return values.firstWhere((e) => e.path == path, orElse: () => Unknown);
  }
}

class HeaderBuildError extends Error {
  final String message;

  HeaderBuildError(this.message);
}

class MessageHeader {
  final int length;
  final MessageType type;

  MessageHeader(this.length, this.type);

  @override
  String toString() => "MessageHeader: length=$length, type=${type.name}";

  static MessageHeader fromBuffer(BufferReader data) {
    if (data.length != 16) {
      throw HeaderBuildError(
          'Invalid buffer size - Expecting 16, got ${data.length}');
    }
    final magic = data.getUInt32();
    if (magic != MessageHeader.magic) {
      throw HeaderBuildError('Invalid magic number, received $magic');
    }
    final length = data.getUInt32();
    final typeInt = data.getUInt32();
    final msgType = MessageType.fromId(typeInt);

    if (msgType != MessageType.Unknown) {
      final typeCheck = data.getUInt32();
      if (typeCheck != ((msgType.id ^ -1) & 0xffffffff) >>> 0) {
        throw HeaderBuildError('Invalid type check, received $typeCheck');
      }
    }

    return MessageHeader(length, msgType);
  }

  static ByteData asBuffer(MessageType messageType, int byteLength) {
    final data = BufferWriter.withCapacity(4 + 4 + 4 + 4);
    data.setUInt32(MessageHeader.magic);
    data.setUInt32(byteLength);
    data.setUInt32(messageType.id);
    data.setUInt32(((messageType.id ^ -1) & 0xffffffff) >>> 0);
    return data.buffer;
  }

  Message? toMessage(ByteData? data) {
    if (data != null) {
      switch (type) {
        case MessageType.AudioData:
          return AudioData(this, data);
        case MessageType.VideoData:
          return VideoData(this, data);
        case MessageType.MediaData:
          return MediaData(this, data);
        case MessageType.BluetoothAddress:
          return BluetoothAddress(this, data);
        case MessageType.BluetoothDeviceName:
          return BluetoothDeviceName(this, data);
        case MessageType.BluetoothPIN:
          return BluetoothPIN(this, data);
        case MessageType.ManufacturerInfo:
          return ManufacturerInfo(this, data);
        case MessageType.SoftwareVersion:
          return SoftwareVersion(this, data);
        case MessageType.Command:
          return Command(this, data);
        case MessageType.Plugged:
          return Plugged(this, data);
        case MessageType.WifiDeviceName:
          return WifiDeviceName(this, data);
        case MessageType.HiCarLink:
          return HiCarLink(this, data);
        case MessageType.BluetoothPairedList:
          return BluetoothPairedList(this, data);
        case MessageType.Open:
          return Opened(this, data);
        case MessageType.BoxSettings:
          return BoxInfo(this, data);
        case MessageType.Phase:
          return Phase(this, data);
        default:
          return UnknownMessage(this, data);
      }
    } else {
      switch (type) {
        case MessageType.Unplugged:
          return Unplugged(this);

        default:
          return UnknownMessage(this, null);
      }
    }
  }

  static int dataLength = 16;
  static int magic = 0x55aa55aa;
}
