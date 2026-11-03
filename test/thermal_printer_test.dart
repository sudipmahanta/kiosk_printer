import 'dart:async';
import 'package:flutter/services.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:kiosk_printer/kiosk_printer.dart';

class FakePlatform implements PrinterPlatform {
  final calls = <(String, Map<String, Object?>?)>[];
  final _events = StreamController<Map<Object?, Object?>>.broadcast();
  Object? Function(String method, Map<String, Object?>? args) handler =
      (_, _) => null;

  @override
  Future<Object?> invoke(String method, [Map<String, Object?>? arguments]) async {
    calls.add((method, arguments));
    return handler(method, arguments);
  }

  @override
  Stream<Map<Object?, Object?>> get events => _events.stream;

  void emit(Map<Object?, Object?> event) => _events.add(event);
}

Map<String, Object?> outcome({
  bool success = true,
  int code = 0,
  String stage = 'done',
  bool confirmed = true,
  Map<String, Object?>? printer,
  bool? empty,
}) =>
    {
      'success': success,
      'code': code,
      'message': 'm',
      'stage': stage,
      'confirmed': confirmed,
      'printer': printer,
      'empty': ?empty,
    };

void main() {
  late FakePlatform platform;
  late ThermalPrinter printer;

  setUp(() {
    platform = FakePlatform();
    printer = ThermalPrinter(platform: platform);
  });

  group('print', () {
    test('an empty receipt succeeds without touching the printer', () async {
      final result = await printer.print(Receipt());
      expect(result.success, isTrue);
      expect(result.empty, isTrue);
      expect(platform.calls, isEmpty);
    });

    test('sends the elements, the paper width and the options', () async {
      platform.handler = (_, _) => outcome();
      final receipt = Receipt(paper: PaperSize.mm58)
        ..text('Hi', bold: true)
        ..cut();
      final result = await printer.print(
        receipt,
        connectTo: const PrinterTarget.network('10.0.0.5'),
        confirm: false,
        confirmTimeout: const Duration(seconds: 3),
      );

      expect(result.success, isTrue);
      expect(result.confirmed, isTrue);
      final (method, args) = platform.calls.single;
      expect(method, 'printReceipt');
      expect(args!['paperWidthMm'], 58);
      expect(args['confirm'], false);
      expect(args['confirmTimeoutMs'], 3000);
      expect((args['elements'] as List).length, 2);
      expect((args['connection'] as Map)['type'], 'NET');
      expect((args['connection'] as Map)['port'], 9100);
    });

    test('no connection key without connectTo', () async {
      platform.handler = (_, _) => outcome();
      await printer.print(Receipt()..text('x'));
      expect(platform.calls.single.$2!.containsKey('connection'), isFalse);
    });

    test('a fault before printing is a failure at the status stage', () async {
      platform.handler = (_, _) => outcome(
            success: false,
            code: -6,
            stage: 'status',
            confirmed: false,
            printer: {'code': -6, 'message': 'open', 'coverOpen': true},
          );
      final result = await printer.print(Receipt()..text('x'));
      expect(result.success, isFalse);
      expect(result.errorCode, PrinterErrorCode.coverOpen);
      expect(result.stage, PrintStage.status);
      expect(result.mayHavePrinted, isFalse);
      expect(result.printerStatus!.coverOpen, isTrue);
    });

    test('a failure at write or confirm may have printed', () async {
      for (final stage in ['write', 'confirm']) {
        platform.handler = (_, _) =>
            outcome(success: false, code: -11, stage: stage, confirmed: false);
        final result = await printer.print(Receipt()..text('x'));
        expect(result.mayHavePrinted, isTrue, reason: stage);
      }
    });

    test('a platform error becomes a channel failure, not an exception', () async {
      platform.handler = (_, _) => throw PlatformException(
            code: 'INVALID_ARGUMENT',
            message: 'bad',
          );
      final result = await printer.print(Receipt()..text('x'));
      expect(result.success, isFalse);
      expect(result.errorCode, PrinterErrorCode.channelError);
      expect(result.message, 'bad');
    });

    test('a missing plugin becomes a channel failure', () async {
      platform.handler = (_, _) => throw MissingPluginException();
      final result = await printer.queryStatus();
      expect(result.errorCode, PrinterErrorCode.channelError);
    });

    test('an unknown code becomes unknown', () async {
      platform.handler = (_, _) => outcome(success: false, code: -999);
      final result = await printer.printRaw(Uint8List(1));
      expect(result.errorCode, PrinterErrorCode.unknown);
    });

    test('printOrThrow throws the exception that fits the failure', () async {
      platform.handler = (_, _) =>
          outcome(success: false, code: -14, stage: 'status', confirmed: false);
      await expectLater(
        printer.printOrThrow(Receipt()..text('x')),
        throwsA(isA<PrinterNotConnectedException>()),
      );

      platform.handler = (_, _) =>
          outcome(success: false, code: -11, stage: 'write', confirmed: false);
      await expectLater(
        printer.printOrThrow(Receipt()..text('x')),
        throwsA(
          isA<PrinterPrintFailedException>()
              .having((e) => e.mayHavePrinted, 'mayHavePrinted', isTrue),
        ),
      );

      platform.handler = (_, _) =>
          outcome(success: false, code: -5, stage: 'status', confirmed: false);
      await expectLater(
        printer.printOrThrow(Receipt()..text('x')),
        throwsA(isA<PrinterFaultException>()),
      );
    });
  });

  group('connection', () {
    test('connect sends the target and parses the answer', () async {
      platform.handler = (_, _) => {
            'connected': false,
            'message': 'USB permission is needed.',
            'needsPermission': true,
          };
      final result = await printer.connect(
        const PrinterTarget.usb(vendorId: 1046, productId: 20497),
      );
      expect(result.connected, isFalse);
      expect(result.needsPermission, isTrue);
      final args = platform.calls.single.$2!;
      expect(args['type'], 'USB');
      expect(args['vendorId'], 1046);
      expect(args['productId'], 20497);
      expect(args.containsKey('deviceName'), isFalse);
    });

    test('connect never throws', () async {
      platform.handler = (_, _) => throw PlatformException(code: 'X', message: 'no');
      final result = await printer.connect(const PrinterTarget.bluetooth('AA'));
      expect(result.connected, isFalse);
      expect(result.message, 'no');
    });

    test('isConnected and disconnect', () async {
      platform.handler = (m, _) =>
          m == 'isConnected' ? true : {'closed': true, 'message': 'Disconnected'};
      expect(await printer.isConnected(), isTrue);
      expect(await printer.disconnect(), isTrue);
    });

    test('lists USB devices', () async {
      platform.handler = (_, _) => [
            {
              'vendorId': 1046,
              'productId': 20497,
              'deviceName': '/dev/bus/usb/001/003',
              'product': 'POS',
              'hasPermission': true,
              'isPrinterClass': false,
              'isKnownPrinter': true,
            },
          ];
      final devices = await printer.listUsbPrinters();
      expect(devices.single.looksLikePrinter, isTrue);
      final target = devices.single.toTarget();
      expect(target.deviceName, '/dev/bus/usb/001/003');
    });

    test('requestUsbPermission reports whether the device was found', () async {
      platform.handler = (_, _) => {'requested': false, 'message': 'USB printer not found.'};
      const device = UsbPrinterInfo(vendorId: 1, productId: 2, deviceName: 'd');
      expect(await printer.requestUsbPermission(device), isFalse);
    });
  });

  group('events', () {
    test('are parsed into typed events and unknown ones are dropped', () async {
      final seen = <PrinterEvent>[];
      final sub = printer.events.listen(seen.add);

      platform.emit({'type': 'connected'});
      platform.emit({'type': 'disconnected', 'unexpected': true});
      platform.emit({'type': 'usbPermission', 'vendorId': 1, 'productId': 2, 'granted': true});
      platform.emit({'type': 'paperLow', 'code': 1, 'message': 'low', 'paperNearEnd': true});
      platform.emit({'type': 'from-the-future'});
      await Future<void>.delayed(Duration.zero);
      await sub.cancel();

      expect(seen.length, 4);
      expect(seen[0], isA<PrinterConnected>());
      expect((seen[1] as PrinterDisconnected).unexpected, isTrue);
      expect((seen[2] as PrinterUsbPermission).granted, isTrue);
      expect((seen[3] as PrinterPaperLow).status.paperNearEnd, isTrue);
    });
  });

  group('Receipt', () {
    test('wraps long text at spaces', () {
      final lines = Receipt.wrapText('aaaa bbbb cccc', 9);
      expect(lines, ['aaaa bbbb', 'cccc']);
      expect(Receipt.wrapText('abcdefghij', 4), ['abcd', 'efgh', 'ij']);
    });

    test('row puts the right text flush with the end of the row', () {
      final line = Receipt.twoColumns('Samosa', '14.29', 46);
      expect(line.length, 46);
      expect(line.startsWith('Samosa'), isTrue);
      expect(line.endsWith('14.29'), isTrue);
    });

    test('a long left side is shortened', () {
      final line = Receipt.twoColumns('x' * 60, '1.00', 46);
      expect(line.length, 46);
      expect(line.contains('...'), isTrue);
      expect(line.endsWith('1.00'), isTrue);
    });

    test('the same receipt fits each paper', () {
      for (final paper in PaperSize.values) {
        final r = Receipt(paper: paper)
          ..row(left: 'Item', right: '9.99')
          ..divider();
        final wire = r.toWire();
        expect((wire[0]['text'] as String).length, paper.rowWidth);
        expect((wire[1]['text'] as String).length, paper.charsPerLine);
      }
    });

    test('feed is clamped and optional values are left out of the wire', () {
      final r = Receipt()
        ..feed(999)
        ..image(Uint8List(1));
      final wire = r.toWire();
      expect(wire[0]['lines'], 255);
      expect(wire[1].containsKey('widthDots'), isFalse);
    });

    test('toWire is a copy', () {
      final r = Receipt()..text('a');
      r.toWire().clear();
      expect(r.length, 1);
    });
  });

  group('PrinterTarget timeout', () {
    test('each transport has a default and accepts its own', () {
      expect(const PrinterTarget.usb(vendorId: 1, productId: 2).toWire()['timeoutMs'], 30000);
      expect(const PrinterTarget.bluetooth('AA').toWire()['timeoutMs'], 15000);
      expect(const PrinterTarget.ble('AA').toWire()['timeoutMs'], 15000);
      expect(const PrinterTarget.network('h').toWire()['timeoutMs'], 5000);
      expect(
        const PrinterTarget.bluetooth('AA', timeout: Duration(seconds: 3)).toWire()['timeoutMs'],
        3000,
      );
      expect(
        const PrinterTarget.usb(vendorId: 1, productId: 2, timeout: Duration(seconds: 8)).toWire()['timeoutMs'],
        8000,
      );
    });

    test('ConnectResult reads timedOut', () {
      final r = ConnectResult.fromWire({'connected': false, 'message': 'x', 'timedOut': true});
      expect(r.timedOut, isTrue);
    });
  });

  group('Receipt columns3', () {
    test('lines up item, quantity and amount on every paper', () {
      for (final paper in PaperSize.values) {
        final line = Receipt.threeColumns('Samosa', '2', '28.58', paper.rowWidth);
        expect(line.length, paper.rowWidth);
        expect(line.startsWith('Samosa'), isTrue);
        expect(line.endsWith('28.58'), isTrue);
        expect(line.substring(line.length - 10 - 6, line.length - 10).trim(), '2');
      }
    });

    test('a long item name is shortened and the row never exceeds the width', () {
      final line = Receipt.threeColumns('A very long item name that cannot fit', '12', '1,234.00', 40);
      expect(line.length, 40);
      expect(line.contains('...'), isTrue);
      expect(line.endsWith('1,234.00'), isTrue);
    });

    test('oversized column widths are clamped so the name keeps room', () {
      final line = Receipt.threeColumns('Tea', '1', '5.00', 40, middleWidth: 30, rightWidth: 30);
      expect(line.length, 40);
      expect(line.startsWith('Tea'), isTrue);
    });

    test('the builder adds one unwrapped line', () {
      final r = Receipt(paper: PaperSize.mm58)
        ..columns3(left: 'Tea', middle: '2', right: '10.00');
      final wire = r.toWire();
      expect(wire.length, 1);
      expect((wire.first['text'] as String).length, PaperSize.mm58.rowWidth);
    });
  });
}
