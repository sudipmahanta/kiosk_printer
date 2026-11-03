/// The seam between the facade and the native side, so tests can fake it.
///
/// It speaks the channel's wire format (maps); the typed API is built on top in
/// `ThermalPrinter`.
abstract interface class PrinterPlatform {
  /// Calls [method] with [arguments] and returns the raw reply.
  ///
  /// May throw `PlatformException` or `MissingPluginException`; the facade
  /// turns those into failed results.
  Future<Object?> invoke(String method, [Map<String, Object?>? arguments]);

  /// Raw events from the native side (maps with a `type`).
  Stream<Map<Object?, Object?>> get events;
}
