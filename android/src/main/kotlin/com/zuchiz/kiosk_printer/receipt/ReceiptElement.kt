package com.zuchiz.kiosk_printer.receipt

/**
 * What the printed slip is made of, before it becomes printer bytes. The layout
 * only produces these, so a whole receipt can be checked in a plain JVM test
 * with no printer attached.
 */
enum class ReceiptAlign { LEFT, CENTER, RIGHT }

sealed class ReceiptElement

/** One printed line. [big] is the taller heading size. */
data class ReceiptLine(
    val text: String,
    val align: ReceiptAlign = ReceiptAlign.LEFT,
    val bold: Boolean = false,
    val big: Boolean = false,
) : ReceiptElement()

/** Blank paper feed. */
data class ReceiptFeed(val lines: Int = 1) : ReceiptElement()

/** End of one slip. A partial cut keeps slips of one job together. */
data class ReceiptCut(val full: Boolean = false) : ReceiptElement()

/** Error correction of a QR code, lowest to highest. */
enum class QrLevel { L, M, Q, H }

/** Standard ESC/POS QR code (`GS ( k`). Not exercised on hardware. */
data class ReceiptQr(
    val data: String,
    val moduleSize: Int = 6,
    val level: QrLevel = QrLevel.M,
    val align: ReceiptAlign = ReceiptAlign.CENTER,
) : ReceiptElement()

/** `n` of `GS k m`, the 1D symbologies of "function B" (`m` = 65..73). */
enum class BarcodeType(val code: Int) {
    UPC_A(65), UPC_E(66), EAN13(67), EAN8(68), CODE39(69),
    ITF(70), CODABAR(71), CODE93(72), CODE128(73),
}

/** Standard ESC/POS barcode (`GS k`). Not exercised on hardware. */
data class ReceiptBarcode(
    val data: String,
    val type: BarcodeType = BarcodeType.CODE128,
    val height: Int = 80,
    val width: Int = 2,
    val showText: Boolean = true,
    val align: ReceiptAlign = ReceiptAlign.CENTER,
) : ReceiptElement()

/**
 * A 1-bit raster, [widthBytes] bytes per row, most significant bit leftmost, a
 * set bit = black. Printed with `GS v 0`. Not exercised on hardware.
 */
class ReceiptImage(
    val widthBytes: Int,
    val heightDots: Int,
    val raster: ByteArray,
    val align: ReceiptAlign = ReceiptAlign.CENTER,
) : ReceiptElement() {
    init {
        require(widthBytes > 0 && heightDots > 0) { "Empty image." }
        require(raster.size == widthBytes * heightDots) { "Raster size does not match." }
    }
}
