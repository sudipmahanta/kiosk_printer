/// How far a print got before it stopped.
enum PrintStage {
  /// Nothing was sent: not connected, a fault before printing, a bad argument.
  status,

  /// Sending failed: the link is gone.
  write,

  /// Sent, but the printer did not confirm that it printed.
  confirm,

  /// Finished.
  done;

  static PrintStage parse(Object? name) => switch (name) {
        'write' => write,
        'confirm' => confirm,
        'done' => done,
        _ => status,
      };
}
