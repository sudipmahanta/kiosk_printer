# kiosk_printer

Print receipts on thermal ESC/POS printers from Flutter (Android). Extracted from
the Point of Connections app's native CSN layer; see
`docs/point_of_connections/printer_package/` for the analysis it is based on.

Printers must be openable by the vendor **CSN SDK**. Other ESC/POS printers work
only through `printRaw` over a transport the SDK can open.

## Use

```dart
final printer = ThermalPrinter();

final usb = await printer.listUsbPrinters();            // every USB device; see looksLikePrinter
var r = await printer.connect(usb.first.toTarget());    // or PrinterTarget.bluetooth / .ble / .network
if (r.needsPermission) { /* wait for PrinterUsbPermission(granted) on printer.events, connect again */ }

final receipt = Receipt(paper: PaperSize.mm80)
  ..text('SHOP', align: ReceiptAlign.center, bold: true, big: true)
  ..row(left: 'Samosa', right: '14.29')
  ..divider()
  ..feed(4)
  ..cut();

final out = await printer.print(receipt);               // never throws for printer problems
if (!out.success) {
  // out.errorCode, out.message, out.stage, out.printerStatus
  if (out.mayHavePrinted) { /* tell the operator to check the paper before reprinting */ }
}
```

- Failures are values (`PrintResult`, `ConnectResult`). `printOrThrow` throws a `PrinterException` instead.
- `PrintResult.confirmed` is true only when the printer itself reported the job printed. Pass `confirm: false` for firmware without the ticket confirmation (otherwise it times out as `notConfirmed`).
- `print` can connect first: `print(receipt, connectTo: target)`.
- One connection at a time; connecting again closes the previous one.
- Events: `connected`, `connectFailed`, `disconnected(unexpected)`, `usbAttached/Detached`, `usbPermission(granted)`, `paperLow`.

## Not in this package

- Scanning for Bluetooth / BLE / network printers (only USB is listed). The caller supplies the address.
- Retries, print-duration measurement, logging.
- Multiple simultaneous printers.

## Not exercised on hardware

QR, barcode, image, `beep`, `kickDrawer`, Font B on 58 mm, the dot widths on CSN models. They are exposed but must not be relied on until the checklist below has been run. QR and barcode are standard ESC/POS (`GS ( k`, `GS k`), no longer the SDK calls the old layer used.

## Vendor SDK and licence

`android/libs/CsnPrinterLibsSdk.jar` and `android/src/main/jniLibs/**` are vendor binaries whose licence has **not been checked** (KI-015). `publish_to: none` is set for that reason: use this as a path / git dependency until the licence allows redistribution.

## Tests

```
flutter test                                                    # Dart, fake platform
cd example/android && gradlew :kiosk_printer:testDebugUnitTest   # Kotlin JVM
```

Covered: encoder bytes for every element, paper profiles, receipt decoding, status decoder, facade results / errors / events.

## Manual hardware checklist (not automatable)

Run the example app on a tablet with a CSN printer. Tick each on every paper width you ship (58 / 72 / 80 mm).

- [ ] USB: plug in, device listed with `looksLikePrinter`
- [ ] USB: Connect shows the permission dialog; allow, `usbPermission(granted)` arrives, Connect again succeeds
- [ ] USB: deny permission; Connect asks again
- [ ] Sample receipt prints, columns aligned, rupee sign as `Rs`, cut works
- [ ] Status button: normal, cover open, paper out
- [ ] Print with cover open: fails at stage `status`, nothing printed
- [ ] Unplug during a print: `disconnected(unexpected: true)`, result `connectionLost`, `mayHavePrinted` true
- [ ] Confirm on/off: `confirmed` true / false as expected
- [ ] Bluetooth, BLE, network: connect by address and print
- [ ] 58 mm: Font B is legible and 42 characters fit
- [ ] Release build: the keep rules in `consumer-rules.pro` stop R8 breaking the SDK
- [ ] QR, barcode, image, beep, drawer (then drop the "untested" notes)

## Switching Point of Connections to this package

Done (path dependency `../kiosk_printer`). The app's order layout (token / invoice / charges) lives in Dart in `lib/features/receipt/layout/` and `NativeReceiptPrinter` hands the laid-out lines to `ThermalPrinter.print`. `test/receipt_parity_test.dart` in the app checks it against `test/support/receipt_golden.json`. The app's own Kotlin printer layer, vendor jar and `.so` files were removed; they now come from this package.
# kiosk_printer
