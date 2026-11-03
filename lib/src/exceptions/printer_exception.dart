import '../enums/print_stage.dart';
import '../enums/printer_error_code.dart';
import '../models/print_result.dart';

/// Thrown only by the helpers that prefer to throw (`printOrThrow`). The normal
/// API returns [PrintResult] values.
sealed class PrinterException implements Exception {
  const PrinterException(this.result);

  final PrintResult result;

  String get message => result.message;

  @override
  String toString() => '$runtimeType: $message';

  /// The exception that describes a failed [result].
  factory PrinterException.from(PrintResult result) {
    switch (result.errorCode) {
      case PrinterErrorCode.notConnected:
        return PrinterNotConnectedException(result);
      case PrinterErrorCode.invalidArgument:
        return PrinterInvalidArgumentException(result);
      case PrinterErrorCode.channelError:
        return PrinterChannelException(result);
      default:
        return result.stage == PrintStage.status
            ? PrinterFaultException(result)
            : PrinterPrintFailedException(result);
    }
  }
}

class PrinterNotConnectedException extends PrinterException {
  const PrinterNotConnectedException(super.result);
}

class PrinterInvalidArgumentException extends PrinterException {
  const PrinterInvalidArgumentException(super.result);
}

class PrinterChannelException extends PrinterException {
  const PrinterChannelException(super.result);
}

/// The printer reported a fault before anything was sent (cover open, paper
/// out ...).
class PrinterFaultException extends PrinterException {
  const PrinterFaultException(super.result);
}

/// Sending or confirming failed; see [PrintResult.mayHavePrinted].
class PrinterPrintFailedException extends PrinterException {
  const PrinterPrintFailedException(super.result);

  bool get mayHavePrinted => result.mayHavePrinted;
}
