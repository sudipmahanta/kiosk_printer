import '../enums/print_stage.dart';
import '../enums/printer_error_code.dart';
import 'printer_status.dart';

/// The answer to a print or a status query. Failures are values, not
/// exceptions; see `ThermalPrinter.printOrThrow` for the throwing form.
class PrintResult {
  const PrintResult({
    required this.success,
    required this.errorCode,
    required this.message,
    required this.stage,
    this.confirmed = false,
    this.printerStatus,
    this.empty = false,
  });

  factory PrintResult.fromWire(Map<Object?, Object?> map) {
    final printer = map['printer'];
    return PrintResult(
      success: map['success'] as bool? ?? false,
      errorCode: PrinterErrorCode.fromValue((map['code'] as num?)?.toInt() ?? -13),
      message: map['message'] as String? ?? '',
      stage: PrintStage.parse(map['stage']),
      confirmed: map['confirmed'] as bool? ?? false,
      printerStatus: printer is Map ? PrinterStatus.fromWire(printer) : null,
      empty: map['empty'] as bool? ?? false,
    );
  }

  /// The call itself failed (no plugin, malformed call), not the printer.
  const PrintResult.channelFailure(String message)
      : this(
          success: false,
          errorCode: PrinterErrorCode.channelError,
          message: message,
          stage: PrintStage.status,
        );

  final bool success;
  final PrinterErrorCode errorCode;
  final String message;
  final PrintStage stage;

  /// True only when the printer itself reported the job as printed. A success
  /// with `confirmed == false` means the bytes were accepted by the link; the
  /// printer did not vouch for the print.
  final bool confirmed;
  final PrinterStatus? printerStatus;

  /// The receipt had nothing to print, so the printer was never touched.
  final bool empty;

  /// A failure at `write` or `confirm`: something may be on paper, so tell the
  /// operator to check before printing again.
  bool get mayHavePrinted =>
      !success && (stage == PrintStage.write || stage == PrintStage.confirm);

  @override
  String toString() =>
      'PrintResult(${success ? 'ok' : 'failed'} ${errorCode.name} @${stage.name}: $message)';
}

/// The answer to `connect`.
class ConnectResult {
  const ConnectResult({
    required this.connected,
    required this.message,
    this.needsPermission = false,
    this.timedOut = false,
  });

  factory ConnectResult.fromWire(Map<Object?, Object?> map) => ConnectResult(
        connected: map['connected'] as bool? ?? false,
        message: map['message'] as String? ?? '',
        needsPermission: map['needsPermission'] as bool? ?? false,
        timedOut: map['timedOut'] as bool? ?? false,
      );

  final bool connected;
  final String message;

  /// USB only: the system permission dialog was shown. When it is answered a
  /// `PrinterUsbPermission` event arrives; connect again then.
  final bool needsPermission;

  /// The connection did not open within [PrinterTarget.timeout].
  final bool timedOut;

  @override
  String toString() => 'ConnectResult($connected: $message)';
}
