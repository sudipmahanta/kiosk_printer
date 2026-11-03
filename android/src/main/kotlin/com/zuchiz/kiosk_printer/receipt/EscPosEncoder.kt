package com.zuchiz.kiosk_printer.receipt

import java.io.ByteArrayOutputStream

/**
 * Turns receipt elements into ESC/POS bytes. Plain text only: bold, a taller
 * heading, the paper's font, feed and cut, which every ESC/POS printer
 * understands.
 *
 * Alignment is done here with spaces, against the paper's own characters per
 * line, and not with the printer's `ESC a`. The layout builds fixed-width rows,
 * and the printer's alignment would place each of them against the full paper
 * width instead: on a paper wider than the layout the rows, borders and notes
 * would sit at three different places. Every line now starts from the same
 * left margin, whatever its alignment.
 */
object EscPosEncoder {
    private const val ESC = 0x1B
    private const val GS = 0x1D
    private const val LF = 0x0A

    /** Blank columns kept at each side (a full line is wrapped twice by some printers). */
    const val MARGIN = 1

    /**
     * Where [text] starts on a line of [width] characters: after the margin when
     * left, centred on the full line, or ending one margin before the edge.
     */
    fun indentFor(text: String, align: ReceiptAlign, width: Int): Int {
        // As printed: the rupee sign comes out as two characters ("Rs").
        val length = printable(text).size
        return when (align) {
            ReceiptAlign.LEFT -> MARGIN
            ReceiptAlign.CENTER -> ((width - length) / 2).coerceAtLeast(0)
            ReceiptAlign.RIGHT -> (width - MARGIN - length).coerceAtLeast(0)
        }
    }

    /** The text lines as they print, with their indent (for checking the layout). */
    fun printedLines(elements: List<ReceiptElement>, profile: PaperProfile): List<String> =
        elements.filterIsInstance<ReceiptLine>().flatMap { line ->
            pieces(line.text, profile.charsPerLine).map {
                " ".repeat(indentFor(it, line.align, profile.charsPerLine)) +
                    String(printable(it), Charsets.ISO_8859_1)
            }
        }

    /**
     * [text] split so no piece prints wider than [width] characters. The Dart
     * layout already wraps; this keeps a line that is still too long from being
     * wrapped by the printer, which would throw the columns off.
     */
    fun pieces(text: String, width: Int): List<String> {
        if (width <= 0 || printable(text).size <= width) return listOf(text)
        val out = mutableListOf<String>()
        val current = StringBuilder()
        var used = 0
        text.codePoints().forEach { cp ->
            val cost = printable(String(Character.toChars(cp))).size
            if (used + cost > width) {
                out += current.toString()
                current.setLength(0)
                used = 0
            }
            current.appendCodePoint(cp)
            used += cost
        }
        out += current.toString()
        return out
    }

    fun encode(
        elements: List<ReceiptElement>,
        profile: PaperProfile = PaperProfile.DEFAULT,
    ): ByteArray {
        val out = ByteArrayOutputStream()
        // Initialise, choose the paper's font, left alignment.
        out.write(byteArrayOf(ESC.toByte(), '@'.code.toByte()))
        out.write(byteArrayOf(ESC.toByte(), 'M'.code.toByte(), profile.font.command.toByte()))
        var bold = false
        var big = false

        for (element in elements) {
            when (element) {
                is ReceiptLine -> {
                    if (element.bold != bold) {
                        bold = element.bold
                        out.write(byteArrayOf(ESC.toByte(), 'E'.code.toByte(), if (bold) 1 else 0))
                    }
                    if (element.big != big) {
                        big = element.big
                        out.write(byteArrayOf(GS.toByte(), '!'.code.toByte(), if (big) 0x01 else 0x00))
                    }
                    for (piece in pieces(element.text, profile.charsPerLine)) {
                        val indent = indentFor(piece, element.align, profile.charsPerLine)
                        out.write(ByteArray(indent) { ' '.code.toByte() })
                        out.write(printable(piece))
                        out.write(LF)
                    }
                }
                is ReceiptFeed -> {
                    out.write(byteArrayOf(ESC.toByte(), 'd'.code.toByte(), element.lines.coerceIn(0, 255).toByte()))
                }
                is ReceiptCut -> {
                    val mode: Byte = if (element.full) 0x30 else 0x31
                    out.write(byteArrayOf(GS.toByte(), 'V'.code.toByte(), mode))
                }
                is ReceiptQr -> graphic(out, element.align) { writeQr(it, element) }
                is ReceiptBarcode -> graphic(out, element.align) { writeBarcode(it, element) }
                is ReceiptImage -> graphic(out, element.align) { writeImage(it, element) }
            }
        }
        // Back to the printer's defaults so the next job does not inherit this one.
        out.write(
            byteArrayOf(
                ESC.toByte(), 'E'.code.toByte(), 0,
                GS.toByte(), '!'.code.toByte(), 0,
                ESC.toByte(), 'M'.code.toByte(), PrinterFont.A.command.toByte(),
                ESC.toByte(), 'a'.code.toByte(), 0,
            ),
        )
        return out.toByteArray()
    }

    /**
     * QR codes, barcodes and images cannot be padded with spaces, so these (and
     * only these) use the printer's `ESC a`, and put it back to left after.
     */
    private fun graphic(out: ByteArrayOutputStream, align: ReceiptAlign, body: (ByteArrayOutputStream) -> Unit) {
        val mode = when (align) {
            ReceiptAlign.LEFT -> 0
            ReceiptAlign.CENTER -> 1
            ReceiptAlign.RIGHT -> 2
        }
        out.write(byteArrayOf(ESC.toByte(), 'a'.code.toByte(), mode.toByte()))
        body(out)
        out.write(byteArrayOf(ESC.toByte(), 'a'.code.toByte(), 0))
    }

    /** `GS ( k`: model 2, module size, error level, store, print. */
    private fun writeQr(out: ByteArrayOutputStream, qr: ReceiptQr) {
        val data = qr.data.toByteArray(Charsets.UTF_8)
        // 7089 is the largest QR payload (numeric, level L); the store length is 16 bits.
        require(data.isNotEmpty() && data.size <= 7089) { "QR data must be 1..7089 bytes." }
        val gs = GS.toByte()
        val k = 'k'.code.toByte()
        val open = '('.code.toByte()
        out.write(byteArrayOf(gs, open, k, 4, 0, 0x31, 0x41, 0x32, 0))
        out.write(byteArrayOf(gs, open, k, 3, 0, 0x31, 0x43, qr.moduleSize.coerceIn(1, 16).toByte()))
        out.write(byteArrayOf(gs, open, k, 3, 0, 0x31, 0x45, (0x30 + qr.level.ordinal).toByte()))
        val length = data.size + 3
        out.write(byteArrayOf(gs, open, k, (length and 0xFF).toByte(), (length shr 8).toByte(), 0x31, 0x50, 0x30))
        out.write(data)
        out.write(byteArrayOf(gs, open, k, 3, 0, 0x31, 0x51, 0x30))
    }

    /** `GS h`, `GS w`, `GS H`, then `GS k m n data`. */
    private fun writeBarcode(out: ByteArrayOutputStream, barcode: ReceiptBarcode) {
        validateBarcode(barcode)
        var data = barcode.data.toByteArray(Charsets.ISO_8859_1)
        // Code 128 needs a code set selector first; "{B" is the general-purpose one.
        if (barcode.type == BarcodeType.CODE128) data = "{B".toByteArray() + data
        require(barcode.data.isNotEmpty() && data.size <= 255) { "Barcode data must be 1..253 characters." }
        out.write(byteArrayOf(GS.toByte(), 'h'.code.toByte(), barcode.height.coerceIn(1, 255).toByte()))
        out.write(byteArrayOf(GS.toByte(), 'w'.code.toByte(), barcode.width.coerceIn(2, 6).toByte()))
        out.write(byteArrayOf(GS.toByte(), 'H'.code.toByte(), if (barcode.showText) 2 else 0))
        out.write(byteArrayOf(GS.toByte(), 'k'.code.toByte(), barcode.type.code.toByte(), data.size.toByte()))
        out.write(data)
    }

    /**
     * Rejects data the symbology cannot carry, which would otherwise print as
     * an unreadable code (or not at all). Throws [IllegalArgumentException].
     */
    fun validateBarcode(barcode: ReceiptBarcode) {
        val d = barcode.data
        val digits = d.all { it in '0'..'9' }
        val ascii = d.all { it.code in 0..127 }
        val problem: String? = when (barcode.type) {
            BarcodeType.UPC_A -> if (!digits || d.length !in 11..12) "UPC-A needs 11 or 12 digits." else null
            BarcodeType.UPC_E -> if (!digits || d.length !in listOf(6, 7, 8, 11, 12)) "UPC-E needs 6, 7, 8, 11 or 12 digits." else null
            BarcodeType.EAN13 -> if (!digits || d.length !in 12..13) "EAN-13 needs 12 or 13 digits." else null
            BarcodeType.EAN8 -> if (!digits || d.length !in 7..8) "EAN-8 needs 7 or 8 digits." else null
            BarcodeType.ITF -> if (!digits || d.length < 2 || d.length % 2 != 0) "ITF needs an even number of digits (2 or more)." else null
            BarcodeType.CODE39 -> if (d.isEmpty() || !d.all { it in '0'..'9' || it in 'A'..'Z' || it in " -.$/+%*" }) "Code 39 allows 0-9, A-Z, space and -.$/+%." else null
            BarcodeType.CODABAR -> if (d.isEmpty() || !d.all { it in '0'..'9' || it in "ABCD-$:/.+" }) "Codabar allows 0-9, A-D and -$:/.+." else null
            BarcodeType.CODE93, BarcodeType.CODE128 -> if (!ascii) "${barcode.type} allows ASCII characters only." else null
        }
        require(problem == null) { problem ?: "" }
    }

    /** `GS v 0`, sent in bands so a tall image does not overflow the printer's buffer. */
    private fun writeImage(out: ByteArrayOutputStream, image: ReceiptImage) {
        var row = 0
        while (row < image.heightDots) {
            val rows = minOf(BAND_ROWS, image.heightDots - row)
            out.write(
                byteArrayOf(
                    GS.toByte(), 'v'.code.toByte(), '0'.code.toByte(), 0,
                    (image.widthBytes and 0xFF).toByte(), (image.widthBytes shr 8).toByte(),
                    (rows and 0xFF).toByte(), (rows shr 8).toByte(),
                ),
            )
            out.write(image.raster, row * image.widthBytes, rows * image.widthBytes)
            row += rows
        }
    }

    private const val BAND_ROWS = 128

    /**
     * Greyscale-to-1-bit raster for [ReceiptImage]: [argb] is `width * height`
     * pixels; dark and opaque pixels become black. Transparent counts as white.
     */
    fun rasterize(
        width: Int,
        height: Int,
        argb: IntArray,
        align: ReceiptAlign = ReceiptAlign.CENTER,
        threshold: Int = 128,
    ): ReceiptImage {
        require(argb.size == width * height) { "Pixel count does not match." }
        val widthBytes = (width + 7) / 8
        val raster = ByteArray(widthBytes * height)
        for (y in 0 until height) {
            for (x in 0 until width) {
                val p = argb[y * width + x]
                val alpha = p ushr 24
                val luma = (((p shr 16) and 0xFF) * 299 + ((p shr 8) and 0xFF) * 587 + (p and 0xFF) * 114) / 1000
                if (alpha >= 128 && luma < threshold) {
                    raster[y * widthBytes + x / 8] =
                        (raster[y * widthBytes + x / 8].toInt() or (0x80 shr (x % 8))).toByte()
                }
            }
        }
        return ReceiptImage(widthBytes, height, raster, align)
    }

    /**
     * The default code page cannot print characters outside Latin-1, so those
     * are replaced instead of coming out as garbage (the rupee sign is "Rs").
     */
    fun printable(text: String): ByteArray {
        val out = ByteArrayOutputStream(text.length)
        // By code point, so one character outside Latin-1 is one '?'.
        text.codePoints().forEach { cp ->
            when {
                cp == 0x20B9 -> out.write("Rs".toByteArray(Charsets.ISO_8859_1))
                cp > 0xFF -> out.write('?'.code)
                else -> out.write(cp)
            }
        }
        return out.toByteArray()
    }
}
