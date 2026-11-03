import '../enums/printer_transport.dart';

/// Which printer to connect to. Discovery of Bluetooth, BLE and network
/// printers is not part of this package (only USB is listed); the caller
/// supplies the address.
class PrinterTarget {
  const PrinterTarget._(
    this.transport, {
    this.address = '',
    this.port = 9100,
    required this.timeout,
    this.vendorId,
    this.productId,
    this.deviceName,
  });

  /// A USB printer. [deviceName] tells two identical models apart.
  /// [timeout] limits both the wait for the user to answer the permission
  /// dialog and opening the device.
  const PrinterTarget.usb({
    required int vendorId,
    required int productId,
    String? deviceName,
    Duration timeout = defaultUsbTimeout,
  }) : this._(
          PrinterTransport.usb,
          timeout: timeout,
          vendorId: vendorId,
          productId: productId,
          deviceName: deviceName,
        );

  /// A classic Bluetooth printer by MAC address.
  /// [timeout] limits opening the connection.
  const PrinterTarget.bluetooth(
    String address, {
    Duration timeout = defaultBluetoothTimeout,
  }) : this._(PrinterTransport.bluetooth, address: address, timeout: timeout);

  /// A BLE printer by its address / id.
  /// [timeout] limits opening the connection.
  const PrinterTarget.ble(
    String address, {
    Duration timeout = defaultBluetoothTimeout,
  }) : this._(PrinterTransport.ble, address: address, timeout: timeout);

  /// A network printer; [timeout] limits opening the socket.
  const PrinterTarget.network(
    String host, {
    int port = 9100,
    Duration timeout = defaultNetworkTimeout,
  }) : this._(
          PrinterTransport.network,
          address: host,
          port: port,
          timeout: timeout,
        );

  /// Default time allowed to connect, per transport.
  static const defaultUsbTimeout = Duration(seconds: 30);
  static const defaultBluetoothTimeout = Duration(seconds: 15);
  static const defaultNetworkTimeout = Duration(seconds: 5);

  final PrinterTransport transport;
  final String address;
  final int port;

  /// How long connecting may take before it fails with
  /// [ConnectResult.timedOut]. Must be positive.
  final Duration timeout;
  final int? vendorId;
  final int? productId;
  final String? deviceName;

  Map<String, Object?> toWire() => {
        'type': transport.wire,
        'address': address,
        'port': port,
        'timeoutMs': timeout.inMilliseconds,
        'vendorId': ?vendorId,
        'productId': ?productId,
        'deviceName': ?deviceName,
      };
}
