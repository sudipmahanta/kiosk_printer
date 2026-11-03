import '../enums/printer_error_code.dart';

/// What the printer reports about itself (ESC/POS real-time status).
class PrinterStatus {
  const PrinterStatus({
    required this.code,
    required this.message,
    this.coverOpen = false,
    this.paperOut = false,
    this.paperNearEnd = false,
    this.cutterError = false,
    this.overheat = false,
    this.paperSensorKnown = false,
    this.readable = true,
  });

  factory PrinterStatus.fromWire(Map<Object?, Object?> map) => PrinterStatus(
        code: PrinterErrorCode.fromValue((map['code'] as num?)?.toInt() ?? -13),
        message: map['message'] as String? ?? '',
        coverOpen: map['coverOpen'] as bool? ?? false,
        paperOut: map['paperOut'] as bool? ?? false,
        paperNearEnd: map['paperNearEnd'] as bool? ?? false,
        cutterError: map['cutterError'] as bool? ?? false,
        overheat: map['overheat'] as bool? ?? false,
        paperSensorKnown: map['paperSensorKnown'] as bool? ?? false,
        readable: map['readable'] as bool? ?? true,
      );

  final PrinterErrorCode code;
  final String message;
  final bool coverOpen;
  final bool paperOut;

  /// Only meaningful when [paperSensorKnown].
  final bool paperNearEnd;
  final bool cutterError;
  final bool overheat;

  /// False when the printer did not answer the paper-sensor query, so no
  /// "paper low" can be said.
  final bool paperSensorKnown;

  /// False when the printer answered none of the queries: its state is
  /// unknown, which is not the same as a fault.
  final bool readable;

  bool get ok => code == PrinterErrorCode.ok;

  @override
  String toString() => 'PrinterStatus(${code.name}: $message)';
}
