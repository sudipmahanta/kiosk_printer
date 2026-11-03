import 'printer_target.dart';

/// A USB device the system reports. Every device is listed, printer or not;
/// [looksLikePrinter] is information, not a filter, and [toTarget] connects to
/// any of them.
class UsbPrinterInfo {
  const UsbPrinterInfo({
    required this.vendorId,
    required this.productId,
    required this.deviceName,
    this.manufacturer,
    this.product,
    this.hasPermission = false,
    this.isPrinterClass = false,
    this.isKnownPrinter = false,
  });

  factory UsbPrinterInfo.fromWire(Map<Object?, Object?> map) => UsbPrinterInfo(
        vendorId: (map['vendorId'] as num?)?.toInt() ?? 0,
        productId: (map['productId'] as num?)?.toInt() ?? 0,
        deviceName: map['deviceName'] as String? ?? '',
        manufacturer: map['manufacturer'] as String?,
        product: map['product'] as String?,
        hasPermission: map['hasPermission'] as bool? ?? false,
        isPrinterClass: map['isPrinterClass'] as bool? ?? false,
        isKnownPrinter: map['isKnownPrinter'] as bool? ?? false,
      );

  final int vendorId;
  final int productId;

  /// The system path, e.g. `/dev/bus/usb/001/003`.
  final String deviceName;
  final String? manufacturer;
  final String? product;
  final bool hasPermission;

  /// The device or one of its interfaces is of the USB printer class.
  final bool isPrinterClass;

  /// The ids were seen on a supported printer.
  final bool isKnownPrinter;

  bool get looksLikePrinter => isPrinterClass || isKnownPrinter;

  PrinterTarget toTarget({Duration timeout = PrinterTarget.defaultUsbTimeout}) =>
      PrinterTarget.usb(
        vendorId: vendorId,
        productId: productId,
        deviceName: deviceName,
        timeout: timeout,
      );

  @override
  String toString() =>
      'UsbPrinterInfo($vendorId:$productId ${product ?? deviceName})';
}
