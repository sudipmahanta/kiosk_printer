/// How a printer is connected. [wire] is the name the native side expects.
enum PrinterTransport {
  usb('USB'),
  bluetooth('BT'),
  ble('BLE'),
  network('NET');

  const PrinterTransport(this.wire);

  final String wire;
}
