// ignore_for_file: constant_identifier_names, non_constant_identifier_names

import 'dart:convert';
import 'dart:typed_data';
import 'dart:ui';

import 'package:dart_buffer/dart_buffer.dart';

import '../common.dart';
import 'readable.dart';

abstract class SendableMessage {
  final MessageType type;

  SendableMessage(this.type);

  Uint8List serialise() => MessageHeader.asBuffer(type, 0).buffer.asUint8List();
}

abstract class SendableMessageWithPayload extends SendableMessage {
  SendableMessageWithPayload(super.type);

  ByteData getPayload();

  @override
  Uint8List serialise() {
    final data = getPayload();
    final header = MessageHeader.asBuffer(type, data.lengthInBytes);

    return Uint8List.fromList(
        header.buffer.asUint8List() + data.buffer.asUint8List());
  }
}

class SendCommand extends SendableMessageWithPayload {
  final CommandMapping value;

  SendCommand(this.value) : super(MessageType.Command);

  @override
  ByteData getPayload() {
    return ByteData(4)..setUint32(0, value.id, Endian.little);
  }
}

enum TouchAction {
  Down(14),
  Move(15),
  Up(16),

  Unknown(-1);

  final int id;
  const TouchAction(this.id);

  factory TouchAction.fromId(int id) {
    return values.firstWhere((e) => e.id == id, orElse: () => Unknown);
  }
}

class SendTouch extends SendableMessageWithPayload {
  late final TouchAction action;
  late final double x;
  late final double y;

  SendTouch(this.action, this.x, this.y) : super(MessageType.Touch);

  @override
  ByteData getPayload() {
    return ByteData(16)
      ..setUint32(0, action.id, Endian.little)
      ..setUint32(4, clampDouble(10000 * x, 0, 10000).toInt(), Endian.little)
      ..setUint32(8, clampDouble(10000 * y, 0, 10000).toInt(), Endian.little);
  }

  //  const actionB = Buffer.alloc(4)
  // const xB = Buffer.alloc(4)
  // const yB = Buffer.alloc(4)
  // const flags = Buffer.alloc(4)
  // actionB.writeUInt32LE(this.action)

  // const finalX = clamp(10000 * this.x, 0, 10000)
  // const finalY = clamp(10000 * this.y, 0, 10000)

  // xB.writeUInt32LE(finalX)
  // yB.writeUInt32LE(finalY)
  // const data = Buffer.concat([actionB, xB, yB, flags])
}

enum MultiTouchAction {
  Down(1),
  Move(2),
  Up(0),

  Unknown(-1);

  final int id;
  const MultiTouchAction(this.id);

  factory MultiTouchAction.fromId(int id) {
    return values.firstWhere((e) => e.id == id, orElse: () => Unknown);
  }
}

class TouchItem {
  late final double x;
  late final double y;
  late final MultiTouchAction action;
  late final int id;

  TouchItem(this.x, this.y, this.action, this.id);
}

class SendMultiTouch extends SendableMessageWithPayload {
  final List<TouchItem> touches;

  SendMultiTouch(this.touches) : super(MessageType.MultiTouch);

  @override
  ByteData getPayload() {
    final writer = BufferWriter.withCapacity(touches.length * 16);
    for (var touch in touches) {
      writer.setFloat32(touch.x, Endian.little);
      writer.setFloat32(touch.y, Endian.little);
      writer.setUInt32(touch.action.id, Endian.little);
      writer.setUInt32(touch.id, Endian.little);
    }
    return writer.buffer;
  }
}

class SendAudio extends SendableMessageWithPayload {
  final Uint16List data;

  SendAudio(this.data) : super(MessageType.AudioData);

  @override
  ByteData getPayload() {
    final audioData = ByteData(12)
      ..setUint32(0, 5, Endian.little)
      ..setFloat32(4, 0.0, Endian.little)
      ..setUint32(8, 3, Endian.little);

    return Uint8List.fromList(
      [
        ...audioData.buffer.asUint8List(),
        ...data.buffer.asUint8List(),
      ],
    ).buffer.asByteData();
  }
}

class SendFile extends SendableMessageWithPayload {
  final ByteData content;
  final String fileName;

  SendFile(this.content, this.fileName) : super(MessageType.SendFile);

  ByteData _getFileName(String name) =>
      ascii.encode("$name\u0000").buffer.asByteData();

  ByteData _getLength(ByteData data) =>
      ByteData(4)..setUint32(0, data.lengthInBytes, Endian.little);

  @override
  ByteData getPayload() {
    final newFileName = _getFileName(fileName);
    final nameLength = _getLength(newFileName);
    final contentLength = _getLength(content);

    return Uint8List.fromList(
      [
        ...nameLength.buffer.asUint8List(),
        ...newFileName.buffer.asUint8List(),
        ...contentLength.buffer.asUint8List(),
        ...content.buffer.asUint8List(),
      ],
    ).buffer.asByteData();
  }
}

class SendNumber extends SendFile {
  SendNumber(int number, FileAddress file)
      : super(ByteData(4)..setUint32(0, number, Endian.little), file.path);
}

class SendBoolean extends SendNumber {
  SendBoolean(bool value, FileAddress file) : super(value ? 1 : 0, file);
}

class SendString extends SendFile {
  SendString(String string, FileAddress file)
      : super(ascii.encode(string).buffer.asByteData(), file.path);
}

class HeartBeat extends SendableMessage {
  HeartBeat() : super(MessageType.HeartBeat);
}

enum HandDriveType {
  LHD(0),
  RHD(1),

  Unknown(-1);

  final int id;
  const HandDriveType(this.id);

  factory HandDriveType.fromId(int id) {
    return values.firstWhere((e) => e.id == id, orElse: () => Unknown);
  }
}

class DongleConfig {
  final bool? androidWorkMode;
  int width;
  int height;
  int fps;
  int dpi;
  final int format;
  final int iBoxVersion;
  final int packetMax;
  final int phoneWorkMode;
  final bool nightMode;
  final String boxName;
  final HandDriveType hand;
  final int mediaDelay;
  final bool audioTransferMode;
  final String wifiType; // '2.4ghz' | '5ghz'
  final String micType; // 'box' | 'os'
  final bool oemIconVisible;
  final dynamic phoneConfig;

  DongleConfig({
    this.androidWorkMode,
    required this.width,
    required this.height,
    required this.fps,
    required this.dpi,
    required this.format,
    required this.iBoxVersion,
    required this.packetMax,
    required this.phoneWorkMode,
    required this.nightMode,
    required this.boxName,
    required this.hand,
    required this.mediaDelay,
    required this.audioTransferMode,
    required this.wifiType,
    required this.micType,
    required this.oemIconVisible,
    required this.phoneConfig,
  });
}

final DEFAULT_CONFIG = DongleConfig(
  androidWorkMode: false,
  width: 1920,
  height: 720,
  fps: 60,
  dpi: 160,
  format: 5,
  iBoxVersion: 2,
  phoneWorkMode: 2,
  packetMax: 49152,
  boxName: 'carlink',
  nightMode: false,
  hand: HandDriveType.LHD,
  mediaDelay: 300,
  audioTransferMode: true,
  wifiType: '5ghz',
  micType: 'os',
  oemIconVisible: false,
  phoneConfig: {
    PhoneType.CarPlay: {
      'frameInterval': 5000,
    },
    PhoneType.AndroidAuto: {
      'frameInterval': null,
    },
  },
);

const knownDevices = [
  {0x1314: 0x1520},
  {0x1314: 0x1521},
];

class SendOpen extends SendableMessageWithPayload {
  final DongleConfig config;

  SendOpen(this.config) : super(MessageType.Open);

  @override
  ByteData getPayload() => ByteData(28)
    ..setUint32(0, config.width, Endian.little)
    ..setUint32(4, config.height, Endian.little)
    ..setUint32(8, config.fps, Endian.little)
    ..setUint32(12, config.format, Endian.little)
    ..setUint32(16, config.packetMax, Endian.little)
    ..setUint32(20, config.iBoxVersion, Endian.little)
    ..setUint32(24, config.phoneWorkMode, Endian.little);
}

class SendBoxSettings extends SendableMessageWithPayload {
  final int? syncTime;
  final DongleConfig config;

  SendBoxSettings(this.config, this.syncTime) : super(MessageType.BoxSettings);

  @override
  ByteData getPayload() {
    final json = {
      "mediaDelay": config.mediaDelay,
      "syncTime": syncTime ?? DateTime.now().millisecondsSinceEpoch ~/ 1000,
      "androidAutoSizeW": config.width,
      "androidAutoSizeH": config.height,
    };

    final jsonString = jsonEncode(json);

    final data = ascii.encode(jsonString);

    return data.buffer.asByteData();
  }
}
