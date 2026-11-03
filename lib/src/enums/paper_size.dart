/// The paper widths the printer layout supports.
///
/// Mirrors the native `PaperProfile`: the printable dots (not the paper width,
/// the rest is margin), the font that suits it, and the characters per line
/// those two give. 58 mm uses the smaller Font B so it prints as many
/// characters per line as 72 mm. The dot widths are the common values for these
/// papers; a printer with another head width is not detected.
enum PaperSize {
  mm58(58, 384, 9, 42),
  mm72(72, 512, 12, 42),
  mm80(80, 576, 12, 48);

  const PaperSize(
    this.widthMm,
    this.printableDots,
    this.fontDotsWide,
    this.charsPerLine,
  );

  final int widthMm;
  final int printableDots;
  final int fontDotsWide;
  final int charsPerLine;

  /// Width of a row of text: one blank character is kept at each side.
  int get rowWidth => charsPerLine - 2;
}
