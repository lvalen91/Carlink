import 'package:flutter/foundation.dart';

class Logger {
  static const ENABLED = true;

  static start() async {
    if (!ENABLED) return;

    log("\n\n");
    log("---");
    log("STARTING SESSION");
    log("---");
  }

  static log(String value) async {
    if (ENABLED) {
      final string =
          "${DateTime.now().toString().substring(11, 23)} > [CARLINK] $value";

      debugPrint(string);
    }
  }
}
