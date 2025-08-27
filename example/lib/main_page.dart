// import 'package:android_automotive_plugin/android_automotive_plugin.dart';
import 'package:carlink/carlink.dart';
import 'package:carlink/carlink_platform_interface.dart';
import 'package:carlink/driver/sendable.dart';
import 'package:flutter/cupertino.dart';
import 'package:flutter/material.dart';
import 'package:flutter/services.dart';

import 'file_writer.dart';
import 'logger.dart';
import 'settings_page.dart';

class MainPage extends StatefulWidget {
  const MainPage({super.key});

  @override
  State<StatefulWidget> createState() => _MainPageState();
}

class _MainPageState extends State<MainPage> with WidgetsBindingObserver {
  Carlink? _carlink;
  int? _textureId;

  final DongleConfig _dongleConfig = DEFAULT_CONFIG;

  bool loading = true;

  // final AndroidAutomotivePlugin _automotivePlugin = AndroidAutomotivePlugin();

  final List<TouchItem> _multitouch = [];

  @override
  void initState() {
    super.initState();
    WidgetsBinding.instance.addObserver(this);
    _setImmersiveMode();

    Future.delayed(const Duration(seconds: 3), () {
      _start();
    });
  }

  @override
  void dispose() {
    WidgetsBinding.instance.removeObserver(this);
    super.dispose();
  }

  @override
  void didChangeAppLifecycleState(AppLifecycleState state) {
    if (state == AppLifecycleState.resumed) {
      _setImmersiveMode();
    }
  }

  void _setImmersiveMode() {
    SystemChrome.setEnabledSystemUIMode(SystemUiMode.immersiveSticky);
  }

  void _openSettings(BuildContext context) {
    Navigator.push(
      context,
      MaterialPageRoute(
        builder: (context) => SettingsPage(carlink: _carlink),
      ),
    );
  }

  bool _initialized = false;
  void _start() async {
    if (_initialized) return;

    // Get Flutter's display metrics
    final displaySize = MediaQuery.of(context).size;
    final pixelRatio = MediaQuery.of(context).devicePixelRatio;

    if (displaySize.width == 0 || displaySize.height == 0) {
      return;
    }

    // Get Android's native hardware display metrics
    final hardwareMetrics = await CarlinkPlatform.instance.getDisplayMetrics();
    final hardwareWidth = hardwareMetrics['widthPixels'] as int;
    final hardwareHeight = hardwareMetrics['heightPixels'] as int;
    final hardwareDpi = hardwareMetrics['densityDpi'] as int;
    final refreshRate = hardwareMetrics['refreshRate'] as double;

    // Calculate Flutter's physical pixels for comparison
    final flutterPhysicalWidth = (displaySize.width * pixelRatio).toInt();
    final flutterPhysicalHeight = (displaySize.height * pixelRatio).toInt();
    
    // Use Android's native hardware resolution for CarPlay dongle
    _dongleConfig.width = hardwareWidth;
    _dongleConfig.height = hardwareHeight;
    
    _dongleConfig.dpi = hardwareDpi;
    _dongleConfig.fps = refreshRate.toInt();

    Logger.log(
        "[INIT] Flutter display: ${displaySize.width}x${displaySize.height}, pixelRatio: $pixelRatio, calculated: ${flutterPhysicalWidth}x${flutterPhysicalHeight}");
    Logger.log(
        "[INIT] Android hardware: ${hardwareWidth}x${hardwareHeight}, DPI: $hardwareDpi, refreshRate: ${refreshRate}Hz");
    Logger.log(
        "[INIT] Carlink config: ${_dongleConfig.width}x${_dongleConfig.height}, DPI: ${_dongleConfig.dpi}, FPS: ${_dongleConfig.fps}");
    Logger.log(
        "[INIT] Device config: boxName: ${_dongleConfig.boxName}, micType: ${_dongleConfig.micType}, wifiType: ${_dongleConfig.wifiType}");
    Logger.log(
        "[INIT] Audio config: transferMode: ${_dongleConfig.audioTransferMode}, nightMode: ${_dongleConfig.nightMode}, hand: ${_dongleConfig.hand}");

    _startCarlink(_dongleConfig);

    _initialized = true;
  }

  _startCarlink(DongleConfig config) async {
    _carlink = Carlink(
      config: config,
      onTextureChanged: (textureId) async {
        Logger.log("[TEXTURE] Created texture ID: $textureId, Size: ${_dongleConfig.width}x${_dongleConfig.height}");
        setState(() {
          _textureId = textureId;
        });
      },
      onStateChanged: (carlinkState) {
        Logger.log("[STATE] Carlink state changed: ${carlinkState.name}");
        
        switch (carlinkState) {
          case CarlinkState.connecting:
            Logger.log("[STATE] Searching for USB dongle device...");
            break;
          case CarlinkState.deviceConnected:
            Logger.log("[STATE] USB device connected, initializing protocol...");
            break;
          case CarlinkState.streaming:
            Logger.log("[STATE] Video streaming active");
            break;
          case CarlinkState.disconnected:
            Logger.log("[STATE] Device disconnected");
            break;
        }

        setState(() {
          loading = carlinkState != CarlinkState.streaming;
        });
      },
      onMediaInfoChanged: (mediaInfo) {
        try {
          Logger.log(
              "[MEDIA] Now playing: ${mediaInfo.songTitle} by ${mediaInfo.songArtist}, Album: ${mediaInfo.albumName}, App: ${mediaInfo.appName}, Cover: ${mediaInfo.albumCoverImageData?.length ?? 0} bytes");
          _setInfoAndCover(
            mediaInfo.songTitle,
            mediaInfo.songArtist,
            mediaInfo.appName,
            mediaInfo.albumName,
            mediaInfo.albumCoverImageData,
          );
        } catch (e) {
          Logger.log("[ERROR] Media info processing failed: ${e.toString()}");
        }
      },
      onLogMessage: (log) {
        Logger.log("[DONGLE] $log");
      },
      onHostUIPressed: () {
        Logger.log("[UI] Host UI button pressed - opening settings");
        _openSettings(context);
      },
    );

    Logger.log("[DONGLE] Starting Carlink connection...");
    _carlink?.start();
  }

  _processMultitouchEvent(
      MultiTouchAction action, int id, Offset offset) async {
    // Map touch coordinates from screen space to texture coordinate space (0.0 to 1.0)
    final screenSize = MediaQuery.of(context).size;
    
    final touch = TouchItem(
      offset.dx / screenSize.width,
      offset.dy / screenSize.height,
      action,
      id,
    );

    final index = _multitouch.indexWhere((e) => e.id == id);
    if (action == MultiTouchAction.Down) {
      _multitouch.add(touch);
    } else if (index != -1) {
      if (action == MultiTouchAction.Up) {
        _multitouch[index] = touch;
      } else if (action == MultiTouchAction.Move) {
        final existed = _multitouch[index];
        final dx = (existed.x * 1000 - touch.x * 1000).abs();
        final dy = (existed.y * 1000 - touch.y * 1000).abs();

        if ((dx > 3 || dy > 3)) {
          _multitouch[index] = touch;
        } else {
          return;
        }
      }
    } else {
      return;
    }

    // Logger.log(
    //     "${_multitouch.map((e) => "${e.id}(${_multitouch.indexOf(e)})|${e.action.name}|${e.x}|${e.y}")}");

    _carlink?.sendMultiTouch(_multitouch
        .map((e) => TouchItem(e.x, e.y, e.action, _multitouch.indexOf(e)))
        .toList());

    _multitouch.removeWhere((e) => e.action == MultiTouchAction.Up);
  }

  @override
  Widget build(BuildContext context) {
    return Scaffold(
      backgroundColor: Colors.black,
      body: Center(
        child: Stack(
          children: [
            Positioned.fill(
              child: Listener(
                onPointerDown: (p) async => await _processMultitouchEvent(
                  MultiTouchAction.Down,
                  p.pointer,
                  p.localPosition,
                ),
                onPointerMove: (p) async => await _processMultitouchEvent(
                  MultiTouchAction.Move,
                  p.pointer,
                  p.localPosition,
                ),
                onPointerUp: (p) async => await _processMultitouchEvent(
                  MultiTouchAction.Up,
                  p.pointer,
                  p.localPosition,
                ),
                onPointerCancel: (p) async => await _processMultitouchEvent(
                  MultiTouchAction.Up,
                  p.pointer,
                  p.localPosition,
                ),
                //
                child: _textureId != null
                    ? FittedBox(
                        fit: BoxFit.contain,  // Maintain aspect ratio, should now match perfectly
                        child: SizedBox(
                          width: _dongleConfig.width.toDouble(),
                          height: _dongleConfig.height.toDouble(),
                          child: Texture(textureId: _textureId!),
                        ),
                      )
                    : Container(),
              ),
            ),
            if (loading)
              Positioned.fill(
                child: Container(
                  color: Colors.black.withValues(alpha: 0.7),
                  child: Center(
                    child: Column(
                      mainAxisSize: MainAxisSize.min,
                      children: [
                        Image.asset(
                          "assets/projection_icon.png",
                          height: 220,
                        ),
                        const SizedBox(height: 24),
                        const CupertinoActivityIndicator(
                          color: Colors.white,
                        ),
                      ],
                    ),
                  ),
                ),
              ),
            if (loading)
              Positioned(
                top: 24,
                right: 24,
                child: IconButton(
                  icon: const Icon(Icons.settings),
                  onPressed: () => _openSettings(context),
                ),
              )
            // Positioned(
            //     right: 20,
            //     bottom: 20,
            //     width: 500,
            //     height: 150,
            //     child: SingleChildScrollView(
            //       child: Text(
            //         log,
            //         style: TextStyle(color: Colors.white60, fontSize: 18),
            //       ),
            //     ))
          ],
        ),
      ),
    );
  }

  Future<void> _setInfoAndCover(
    String? mediaSongName,
    String? mediaArtistName,
    String? mediaAppName,
    String? mediaAlbumName,
    Uint8List? coverData,
  ) async {
    // String? path;
    if (coverData != null) {
      final file =
          await FileWriter.writeFileToDownloadsDir(coverData, "cover.jpg");
      // path = file?.absolute.path.replaceAll('//', '/');
      file?.absolute.path.replaceAll('//', '/');
    }

    // try {
    //   if (path != null) {
    //     await _automotivePlugin
    //         .setVehicleSettingMusicAlbumPictureFilePath(path);
    //   }

    //   await _automotivePlugin.setDoubleMediaMusicSource(
    //     playingId: 1,
    //     programName: mediaAlbumName ?? mediaAppName ?? " ",
    //     singerName: mediaArtistName ?? " ",
    //     songName: mediaSongName ?? mediaAppName ?? " ",
    //     sourceType: 25,
    //   );

    //   if (path != null) {
    //     await _automotivePlugin.setDoubleMediaMusicAlbumPictureFilePath(
    //       doublePlayingId: 1,
    //       songId: "test-song",
    //       path: path,
    //     );
    //   }
    // } catch (e) {
    //   // ignore
    // }
  }
}
