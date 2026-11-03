/// Result codes of a print or a status query.
///
/// 0 to -13 are the vendor's own table; -14 to -16 are added by the native
/// layer; [channelError] exists only on the Dart side.
enum PrinterErrorCode {
  ok(0),
  paperNearEnd(1),
  paperNearEndAndTicketWaiting(2),
  ticketWaiting(3),
  notPrintedCheckJam(-1),
  cutterError(-2),
  headOverheat(-3),
  offline(-4),
  paperOut(-5),
  coverOpen(-6),
  statusQueryFailed(-7),
  noCommunication(-8),
  paperOutWhilePrinting(-9),
  coverOpenedWhilePrinting(-10),
  connectionLost(-11),
  previousTicketNotRemoved(-12),
  unknown(-13),
  notConnected(-14),
  invalidArgument(-15),
  notConfirmed(-16),
  channelError(-100);

  const PrinterErrorCode(this.value);

  final int value;

  /// The code for [value]; anything not in the table is [unknown].
  static PrinterErrorCode fromValue(int value) => values.firstWhere(
        (c) => c.value == value,
        orElse: () => unknown,
      );
}
