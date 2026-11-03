package com.zuchiz.kiosk_printer.receipt

/**
 * The two character fonts every ESC/POS printer has: Font A (12 dots wide) and
 * the smaller Font B (9 dots wide). [command] is the `n` of `ESC M n`.
 */
enum class PrinterFont(val command: Int, val dotsWide: Int) {
    A(0, 12),
    B(1, 9),
}

/**
 * What one paper width can print: the printable dots (not the paper width: the
 * rest is margin), the font that suits it, and the characters per line those two
 * give. The whole receipt is laid out for [charsPerLine], so one layout serves
 * every paper.
 *
 * | paper | printable dots | font | characters |
 * |-------|---------------|------|------------|
 * | 58 mm | 384           | B    | 42         |
 * | 72 mm | 512           | A    | 42         |
 * | 80 mm | 576           | A    | 48         |
 *
 * 58 mm uses the smaller Font B so it prints as many characters per line as 72
 * mm does (Font A would give 32, which is too tight for the item table).
 */
class PaperProfile private constructor(
    val paperMm: Int,
    val printableDots: Int,
    val font: PrinterFont,
) {
    val charsPerLine: Int get() = printableDots / font.dotsWide

    companion object {
        fun forPaperMm(paperMm: Int): PaperProfile = when {
            paperMm <= 0 -> PaperProfile(80, 576, PrinterFont.A)
            paperMm <= 58 -> PaperProfile(58, 384, PrinterFont.B)
            paperMm <= 72 -> PaperProfile(72, 512, PrinterFont.A)
            else -> PaperProfile(80, 576, PrinterFont.A)
        }

        /** Characters per line of an unknown printer: a plain 80 mm. */
        val DEFAULT: PaperProfile = forPaperMm(80)
    }
}
