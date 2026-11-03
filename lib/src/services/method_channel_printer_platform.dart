import 'package:flutter/services.dart';

import '../interfaces/printer_platform.dart';

/// The real [PrinterPlatform]: one `MethodChannel` and one `EventChannel`.
class MethodChannelPrinterPlatform implements PrinterPlatform {
  MethodChannelPrinterPlatform();

  static const methodChannelName = 'com.zuchiz.kiosk_printer/methods';
  static const eventChannelName = 'com.zuchiz.kiosk_printer/events';

  final MethodChannel _methods = const MethodChannel(methodChannelName);
  final EventChannel _events = const EventChannel(eventChannelName);

  @override
  Future<Object?> invoke(String method, [Map<String, Object?>? arguments]) =>
      _methods.invokeMethod<Object?>(method, arguments);

  @override
  Stream<Map<Object?, Object?>> get events => _events
      .receiveBroadcastStream()
      .where((e) => e is Map)
      .map((e) => e as Map<Object?, Object?>);
}
