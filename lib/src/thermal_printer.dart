import 'package:flutter/services.dart';

import 'enums/print_stage.dart';
import 'enums/printer_error_code.dart';
import 'exceptions/printer_exception.dart';
import 'interfaces/printer_platform.dart';
import 'models/print_result.dart';
import 'models/printer_event.dart';
import 'models/printer_status.dart';
import 'models/printer_target.dart';
import 'models/receipt.dart';
import 'models/usb_printer_info.dart';
import 'services/method_channel_printer_platform.dart';

/// Connects to a thermal receipt printer and prints on it.
///
/// One instance talks to one native session, which holds **one** connection at
/// a time: connecting again closes the previous one. Printer problems are
/// returned as values ([PrintResult], [ConnectResult]); nothing here throws for
/// them.
///
/// Only printers the vendor CSN SDK can open are supported. Bluetooth, BLE and
/// network printers are connected by address; only USB devices are listed.
class ThermalPrinter {
  /// [platform] exists so tests can fake the native side.
  ThermalPrinter({PrinterPlatform? platform})
      : _platform = platform ?? MethodChannelPrinterPlatform();

  final PrinterPlatform _platform;

  /// Connection and USB events. Broadcast; events of an unknown type are
  /// dropped.
  late final Stream<PrinterEvent> events = _platform.events
      .map(PrinterEvent.fromWire)
      .where((e) => e != null)
      .cast<PrinterEvent>();

  // Discovery

  /// Every USB device the system reports, printer or not. See
  /// [UsbPrinterInfo.looksLikePrinter].
  Future<List<UsbPrinterInfo>> listUsbPrinters() async {
    final reply = await _platform.invoke('listUsbDevices');
    if (reply is! List) return const [];
    return [
      for (final d in reply)
        if (d is Map) UsbPrinterInfo.fromWire(d),
    ];
  }

  /// Shows the system permission dialog. The answer arrives as a
  /// [PrinterUsbPermission] event. Returns false when the device is absent.
  Future<bool> requestUsbPermission(
    UsbPrinterInfo device, {
    Duration timeout = PrinterTarget.defaultUsbTimeout,
  }) async {
    final reply = await _platform.invoke('requestUsbPermission', {
      'vendorId': device.vendorId,
      'productId': device.productId,
      'deviceName': device.deviceName,
      'timeoutMs': timeout.inMilliseconds,
    });
    return reply is Map && reply['requested'] == true;
  }

  // Connection

  /// Opens [target], giving up after [PrinterTarget.timeout] (the result then
  /// has [ConnectResult.timedOut]). For USB without permission this asks for it and returns
  /// [ConnectResult.needsPermission]; connect again when the
  /// [PrinterUsbPermission] event says it was granted.
  Future<ConnectResult> connect(PrinterTarget target) async {
    try {
      final reply = await _platform.invoke('connect', target.toWire());
      return reply is Map
          ? ConnectResult.fromWire(reply)
          : const ConnectResult(connected: false, message: 'No answer.');
    } on PlatformException catch (e) {
      return ConnectResult(connected: false, message: e.message ?? e.code);
    } on MissingPluginException {
      return const ConnectResult(
        connected: false,
        message: 'The printer plugin is not available on this platform.',
      );
    }
  }

  Future<bool> isConnected() async {
    try {
      return await _platform.invoke('isConnected') == true;
    } on PlatformException {
      return false;
    } on MissingPluginException {
      return false;
    }
  }

  /// Closes the link. Returns false if closing failed.
  Future<bool> disconnect() async {
    try {
      final reply = await _platform.invoke('disconnect');
      return reply is Map && reply['closed'] == true;
    } on PlatformException {
      return false;
    } on MissingPluginException {
      return false;
    }
  }

  // Printing

  /// Prints [receipt]: checks the printer's status, sends it, then waits for
  /// the printer to confirm. An empty receipt succeeds with
  /// [PrintResult.empty] and never touches the printer.
  ///
  /// When [connectTo] is given and no printer is connected, it connects first,
  /// so one call is enough for a print.
  ///
  /// [confirm] needs the firmware to support the vendor's ticket confirmation;
  /// a printer that does not times out as [PrinterErrorCode.notConfirmed]. Pass
  /// `confirm: false` for such a printer (the result then has
  /// `confirmed == false`).
  Future<PrintResult> print(
    Receipt receipt, {
    PrinterTarget? connectTo,
    bool checkStatus = true,
    bool confirm = true,
    Duration confirmTimeout = const Duration(seconds: 30),
  }) {
    if (receipt.isEmpty) {
      return Future.value(
        const PrintResult(
          success: true,
          errorCode: PrinterErrorCode.ok,
          message: 'Nothing to print.',
          stage: PrintStage.done,
          empty: true,
        ),
      );
    }
    return _printResult('printReceipt', {
      'elements': receipt.toWire(),
      'paperWidthMm': receipt.paper.widthMm,
      'connection': ?connectTo?.toWire(),
      'checkStatus': checkStatus,
      'confirm': confirm,
      'confirmTimeoutMs': confirmTimeout.inMilliseconds,
    });
  }

  /// Like [print] but throws a [PrinterException] when it fails.
  Future<PrintResult> printOrThrow(
    Receipt receipt, {
    PrinterTarget? connectTo,
    bool checkStatus = true,
    bool confirm = true,
    Duration confirmTimeout = const Duration(seconds: 30),
  }) async {
    final result = await print(
      receipt,
      connectTo: connectTo,
      checkStatus: checkStatus,
      confirm: confirm,
      confirmTimeout: confirmTimeout,
    );
    if (!result.success) throw PrinterException.from(result);
    return result;
  }

  /// Sends ESC/POS [bytes] you built yourself, with the same status check and
  /// confirmation as [print].
  Future<PrintResult> printRaw(
    Uint8List bytes, {
    bool checkStatus = true,
    bool confirm = true,
    Duration confirmTimeout = const Duration(seconds: 30),
  }) =>
      _printResult('printBytes', {
        'bytes': bytes,
        'checkStatus': checkStatus,
        'confirm': confirm,
        'confirmTimeoutMs': confirmTimeout.inMilliseconds,
      });

  /// Reads the printer's status. A printer that answers only some queries
  /// still gives a usable result; see [PrinterStatus.readable].
  Future<PrintResult> queryStatus() => _printResult('queryStatus');

  /// Not exercised on hardware yet.
  Future<PrintResult> beep({int count = 1, int duration = 5}) =>
      _printResult('beep', {'count': count, 'duration': duration});

  /// Opens the cash drawer. Not exercised on hardware yet.
  Future<PrintResult> kickDrawer({int port = 0, int duration = 100}) =>
      _printResult('kickDrawer', {'port': port, 'duration': duration});

  Future<PrintResult> _printResult(
    String method, [
    Map<String, Object?>? arguments,
  ]) async {
    try {
      final reply = await _platform.invoke(method, arguments);
      return reply is Map
          ? PrintResult.fromWire(reply)
          : const PrintResult.channelFailure('No answer from the plugin.');
    } on PlatformException catch (e) {
      return PrintResult.channelFailure(e.message ?? e.code);
    } on MissingPluginException {
      return const PrintResult.channelFailure(
        'The printer plugin is not available on this platform.',
      );
    }
  }
}
