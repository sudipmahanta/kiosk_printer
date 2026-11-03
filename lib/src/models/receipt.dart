import 'dart:typed_data';

import '../enums/paper_size.dart';
import '../enums/receipt_align.dart';

/// Error correction of a QR code, lowest to highest.
enum QrLevel { l, m, q, h }

/// 1D barcode symbologies the printer's `GS k` command supports.
enum BarcodeSymbology {
  upcA('UPC_A'),
  upcE('UPC_E'),
  ean13('EAN13'),
  ean8('EAN8'),
  code39('CODE39'),
  itf('ITF'),
  codabar('CODABAR'),
  code93('CODE93'),
  code128('CODE128');

  const BarcodeSymbology(this.wire);

  final String wire;
}

/// A receipt described in lines, columns, feed, cut, image, QR and barcode.
///
/// Columns are computed here from [paper], so the same receipt fits every
/// paper width. Plain text is what every ESC/POS printer prints: bold, a
/// taller heading, the paper's font. Characters above Latin-1 are replaced
/// when printing (the rupee sign becomes "Rs").
///
/// QR codes, barcodes and images are exposed but **have not been exercised on
/// a printer yet**.
class Receipt {
  Receipt({this.paper = PaperSize.mm80});

  final PaperSize paper;
  final List<Map<String, Object?>> _elements = [];

  /// True when nothing was added; printing it never touches the printer.
  bool get isEmpty => _elements.isEmpty;

  int get length => _elements.length;

  /// Adds [text] as one or more lines, broken at spaces to fit a row.
  Receipt text(
    String text, {
    ReceiptAlign align = ReceiptAlign.left,
    bool bold = false,
    bool big = false,
    bool wrap = true,
  }) {
    final lines = wrap ? wrapText(text, paper.rowWidth) : [text];
    for (final line in lines) {
      _elements.add({
        'type': 'line',
        'text': line,
        'align': align.name,
        'bold': bold,
        'big': big,
      });
    }
    return this;
  }

  /// [left] at the start of the row and [right] flush with its end. A long
  /// [left] is shortened with "..." so the row never wraps.
  Receipt row({required String left, required String right, bool bold = false}) {
    return text(twoColumns(left, right, paper.rowWidth), bold: bold, wrap: false);
  }

  /// A three-column row, e.g. item, quantity, amount. [middle] and [right] get
  /// fixed widths ([middleWidth], [rightWidth]) and are aligned to the end of
  /// their column; [left] takes the rest and is shortened with "..." so the row
  /// never wraps. Widths are clamped so [left] keeps at least 8 characters.
  Receipt columns3({
    required String left,
    required String middle,
    required String right,
    int middleWidth = 6,
    int rightWidth = 10,
    bool bold = false,
  }) {
    return text(
      threeColumns(left, middle, right, paper.rowWidth,
          middleWidth: middleWidth, rightWidth: rightWidth),
      bold: bold,
      wrap: false,
    );
  }

  /// A line of dashes across the paper.
  Receipt divider([String char = '-']) {
    return text(char * paper.charsPerLine, wrap: false);
  }

  Receipt blank([int lines = 1]) => feed(lines);

  Receipt feed([int lines = 1]) {
    _elements.add({'type': 'feed', 'lines': lines.clamp(0, 255)});
    return this;
  }

  /// Cuts the paper: a partial cut by default, a full cut when [full].
  Receipt cut({bool full = false}) {
    _elements.add({'type': 'cut', 'full': full});
    return this;
  }

  /// Not exercised on hardware yet.
  Receipt qr(
    String data, {
    int moduleSize = 6,
    QrLevel level = QrLevel.m,
    ReceiptAlign align = ReceiptAlign.center,
  }) {
    _elements.add({
      'type': 'qr',
      'data': data,
      'moduleSize': moduleSize,
      'level': level.name,
      'align': align.name,
    });
    return this;
  }

  /// Not exercised on hardware yet.
  Receipt barcode(
    String data, {
    BarcodeSymbology symbology = BarcodeSymbology.code128,
    int height = 80,
    int width = 2,
    bool showText = true,
    ReceiptAlign align = ReceiptAlign.center,
  }) {
    _elements.add({
      'type': 'barcode',
      'data': data,
      'symbology': symbology.wire,
      'height': height,
      'width': width,
      'showText': showText,
      'align': align.name,
    });
    return this;
  }

  /// A PNG / JPEG scaled to [widthDots] (the paper's printable width when
  /// null) and printed in black and white. Not exercised on hardware yet.
  Receipt image(
    Uint8List bytes, {
    int? widthDots,
    ReceiptAlign align = ReceiptAlign.center,
  }) {
    _elements.add({
      'type': 'image',
      'bytes': bytes,
      'widthDots': ?widthDots,
      'align': align.name,
    });
    return this;
  }

  /// What the native side receives. A copy, so the receipt can keep growing.
  List<Map<String, Object?>> toWire() => [
        for (final e in _elements) Map<String, Object?>.of(e),
      ];

  /// Breaks [text] into lines of at most [maxWidth], at spaces when it can.
  static List<String> wrapText(String text, int maxWidth) {
    final clean = text.trim();
    if (maxWidth <= 0 || clean.length <= maxWidth) return [clean];
    final lines = <String>[];
    var rest = clean;
    while (rest.length > maxWidth) {
      var cut = rest.lastIndexOf(' ', maxWidth);
      if (cut <= 0) cut = maxWidth;
      lines.add(rest.substring(0, cut).trimRight());
      rest = rest.substring(cut).trimLeft();
    }
    if (rest.isNotEmpty) lines.add(rest);
    return lines;
  }

  /// [left] at the start of a row of [width], [right] at its end.
  static String twoColumns(String left, String right, int width) {
    final room = width - right.length - 1;
    final l = left.length > room ? _ellipsize(left, room) : left;
    return l.padRight(width - right.length) + right;
  }

  /// [left] fills the row except for the two fixed-width columns, which are
  /// right-aligned inside their own width (a longer value is shortened).
  static String threeColumns(
    String left,
    String middle,
    String right,
    int width, {
    int middleWidth = 6,
    int rightWidth = 10,
  }) {
    const minLeft = 8;
    var r = rightWidth.clamp(1, width);
    var m = middleWidth.clamp(1, width);
    if (width - r - m < minLeft) {
      // Shrink the middle first, then the right column.
      m = (width - r - minLeft).clamp(1, m);
      if (width - r - m < minLeft) r = (width - m - minLeft).clamp(1, r);
    }
    final leftWidth = width - r - m;
    final l = _ellipsize(left, leftWidth - 1).padRight(leftWidth);
    final mid = _ellipsize(middle, m).padLeft(m);
    final rt = _ellipsize(right, r).padLeft(r);
    return '$l$mid$rt';
  }

  static String _ellipsize(String text, int width) {
    if (width <= 0) return '';
    if (text.length <= width) return text;
    if (width <= 3) return text.substring(0, width);
    return '${text.substring(0, width - 3)}...';
  }
}
