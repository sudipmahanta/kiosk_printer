# CSN printer SDK

`CsnPrinterLibsSdk.jar` is the vendor's CSN printer SDK (package
`com.csnprintersdk`), taken from `Android SDK/latest` (the 2021-01-28 release).
Its native libraries are in `../src/main/jniLibs/<abi>/`:

- `libcsn_serial_port.so` from `Android SDK/latest/so`
- `libcompress-lib.so` from the demo project (`Sample61`); the `latest` folder
  does not ship it, but the jar loads it for image printing

Only the ABIs Flutter builds are kept (arm64-v8a, armeabi-v7a, x86_64, x86).
Vendor binaries: check the licence before redistributing outside the project.
Used by `com.zuchiz.kiosk_printer.printer`.

