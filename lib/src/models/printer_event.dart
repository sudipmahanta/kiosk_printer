import 'printer_status.dart';
import 'usb_printer_info.dart';

/// Something that happened to the printer link outside a call.
sealed class PrinterEvent {
  const PrinterEvent();

  /// Null for a type this version does not know.
  static PrinterEvent? fromWire(Map<Object?, Object?> map) {
    UsbPrinterInfo device() => UsbPrinterInfo.fromWire(map);
    return switch (map['type']) {
      'connected' => const PrinterConnected(),
      'connectFailed' => const PrinterConnectFailed(),
      'disconnected' =>
        PrinterDisconnected(unexpected: map['unexpected'] as bool? ?? false),
      'usbAttached' => PrinterUsbAttached(device()),
      'usbDetached' => PrinterUsbDetached(device()),
      'usbPermission' => PrinterUsbPermission(
          device(),
          granted: map['granted'] as bool? ?? false,
          timedOut: map['timedOut'] as bool? ?? false,
        ),
      'paperLow' => PrinterPaperLow(PrinterStatus.fromWire(map)),
      _ => null,
    };
  }
}

class PrinterConnected extends PrinterEvent {
  const PrinterConnected();
}

class PrinterConnectFailed extends PrinterEvent {
  const PrinterConnectFailed();
}

class PrinterDisconnected extends PrinterEvent {
  const PrinterDisconnected({required this.unexpected});

  /// True when the link dropped and we did not close it (unplugged, out of
  /// range).
  final bool unexpected;
}

class PrinterUsbAttached extends PrinterEvent {
  const PrinterUsbAttached(this.device);
  final UsbPrinterInfo device;
}

class PrinterUsbDetached extends PrinterEvent {
  const PrinterUsbDetached(this.device);
  final UsbPrinterInfo device;
}

/// The answer to a USB permission request. Connect again when [granted].
class PrinterUsbPermission extends PrinterEvent {
  const PrinterUsbPermission(
    this.device, {
    required this.granted,
    this.timedOut = false,
  });
  final UsbPrinterInfo device;
  final bool granted;

  /// True when nobody answered the dialog in time; [granted] is false then.
  final bool timedOut;
}

/// The paper sensor says the roll is nearly finished.
class PrinterPaperLow extends PrinterEvent {
  const PrinterPaperLow(this.status);
  final PrinterStatus status;
}
