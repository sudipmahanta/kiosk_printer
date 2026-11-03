package com.zuchiz.kiosk_printer.receipt

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse

class EscPosEncoderTest {

    private val a80 = PaperProfile.forPaperMm(80)
    private val b58 = PaperProfile.forPaperMm(58)

    // The job is wrapped in 5 bytes of prologue and 12 of epilogue.
    private fun body(bytes: ByteArray) = bytes.copyOfRange(5, bytes.size - 12)

    @Test
    fun `rupee sign becomes Rs and other wide characters become a question mark`() {
        assertEquals("Rs 10", String(EscPosEncoder.printable("₹ 10"), Charsets.ISO_8859_1))
        assertEquals("? ok", String(EscPosEncoder.printable("न ok"), Charsets.ISO_8859_1))
        assertEquals("?", String(EscPosEncoder.printable("😀"), Charsets.ISO_8859_1))
        assertContentEquals(byteArrayOf(0x43, 0x61, 0x66, 0xE9.toByte()), EscPosEncoder.printable("Café"))
    }

    @Test
    fun `a job starts with initialise and the paper font, then bold, text and a line feed`() {
        val bytes = EscPosEncoder.encode(listOf(ReceiptLine("Hi", bold = true)), a80)
        val head = byteArrayOf(0x1B, 0x40, 0x1B, 0x4D, 0, 0x1B, 0x45, 1, 0x20, 'H'.code.toByte(), 'i'.code.toByte(), 0x0A)
        assertContentEquals(head, bytes.copyOfRange(0, head.size))
    }

    @Test
    fun `58 mm selects the smaller Font B`() {
        val bytes = EscPosEncoder.encode(listOf(ReceiptLine("x")), b58)
        assertContentEquals(byteArrayOf(0x1B, 0x4D, 1), bytes.copyOfRange(2, 5))
    }

    @Test
    fun `the printer is put back to its defaults at the end`() {
        val bytes = EscPosEncoder.encode(listOf(ReceiptLine("Big", bold = true, big = true)), b58)
        val tail = byteArrayOf(0x1B, 0x45, 0, 0x1D, 0x21, 0, 0x1B, 0x4D, 0, 0x1B, 0x61, 0)
        assertContentEquals(tail, bytes.copyOfRange(bytes.size - tail.size, bytes.size))
    }

    @Test
    fun `alignment is done with spaces on the paper's own width`() {
        assertEquals(1, EscPosEncoder.indentFor("0123456789", ReceiptAlign.LEFT, 48))
        assertEquals(19, EscPosEncoder.indentFor("0123456789", ReceiptAlign.CENTER, 48))
        assertEquals(37, EscPosEncoder.indentFor("0123456789", ReceiptAlign.RIGHT, 48))
        assertEquals(0, EscPosEncoder.indentFor("x".repeat(60), ReceiptAlign.RIGHT, 48))
    }

    @Test
    fun `the rupee sign counts as the two characters it prints`() {
        assertEquals(
            EscPosEncoder.indentFor("Rs 10", ReceiptAlign.RIGHT, 42),
            EscPosEncoder.indentFor("₹ 10", ReceiptAlign.RIGHT, 42),
        )
    }

    @Test
    fun `text lines send no alignment command`() {
        val bytes = EscPosEncoder.encode(
            listOf(ReceiptLine("a", align = ReceiptAlign.CENTER), ReceiptLine("b", align = ReceiptAlign.RIGHT)),
            a80,
        )
        val text = body(bytes)
        assertFalse((0 until text.size - 1).any { text[it] == 0x1B.toByte() && text[it + 1] == 0x61.toByte() })
    }

    @Test
    fun `cut modes and feed use the standard commands`() {
        val bytes = EscPosEncoder.encode(listOf(ReceiptFeed(4), ReceiptCut(false), ReceiptCut(true)), a80)
        assertContentEquals(byteArrayOf(0x1B, 0x64, 4, 0x1D, 0x56, 0x31, 0x1D, 0x56, 0x30), body(bytes))
    }

    @Test
    fun `an empty receipt is just the prologue and epilogue`() {
        assertEquals(17, EscPosEncoder.encode(emptyList(), a80).size)
    }

    @Test
    fun `feed is clamped to a byte`() {
        val bytes = EscPosEncoder.encode(listOf(ReceiptFeed(999)), a80)
        assertContentEquals(byteArrayOf(0x1B, 0x64, 255.toByte()), body(bytes))
    }

    @Test
    fun `qr code uses model 2 size level store and print`() {
        val bytes = EscPosEncoder.encode(listOf(ReceiptQr("AB", moduleSize = 5, level = QrLevel.H)), a80)
        val expected = byteArrayOf(
            0x1B, 0x61, 1, // centre
            0x1D, 0x28, 0x6B, 4, 0, 0x31, 0x41, 0x32, 0,
            0x1D, 0x28, 0x6B, 3, 0, 0x31, 0x43, 5,
            0x1D, 0x28, 0x6B, 3, 0, 0x31, 0x45, 0x33,
            0x1D, 0x28, 0x6B, 5, 0, 0x31, 0x50, 0x30, 'A'.code.toByte(), 'B'.code.toByte(),
            0x1D, 0x28, 0x6B, 3, 0, 0x31, 0x51, 0x30,
            0x1B, 0x61, 0, // back to left
        )
        assertContentEquals(expected, body(bytes))
    }

    @Test
    fun `empty qr data is rejected`() {
        assertFailsWith<IllegalArgumentException> { EscPosEncoder.encode(listOf(ReceiptQr("")), a80) }
    }

    @Test
    fun `code128 barcode gets the code set selector`() {
        val bytes = EscPosEncoder.encode(
            listOf(ReceiptBarcode("12", height = 60, width = 3, showText = false, align = ReceiptAlign.LEFT)),
            a80,
        )
        val expected = byteArrayOf(
            0x1B, 0x61, 0,
            0x1D, 0x68, 60, 0x1D, 0x77, 3, 0x1D, 0x48, 0,
            0x1D, 0x6B, 73, 4, '{'.code.toByte(), 'B'.code.toByte(), '1'.code.toByte(), '2'.code.toByte(),
            0x1B, 0x61, 0,
        )
        assertContentEquals(expected, body(bytes))
    }

    @Test
    fun `rasterize makes dark opaque pixels black and pads rows to whole bytes`() {
        val black = 0xFF000000.toInt()
        val white = 0xFFFFFFFF.toInt()
        val clear = 0x00000000
        // 10 wide, 1 tall: black at x = 0 and x = 9, a transparent pixel at x = 1.
        val pixels = IntArray(10) { white }.also { it[0] = black; it[1] = clear; it[9] = black }
        val image = EscPosEncoder.rasterize(10, 1, pixels)
        assertEquals(2, image.widthBytes)
        assertContentEquals(byteArrayOf(0x80.toByte(), 0x40), image.raster)
    }

    @Test
    fun `a tall image is sent in bands of 128 rows`() {
        val image = ReceiptImage(1, 300, ByteArray(300), ReceiptAlign.LEFT)
        val body = body(EscPosEncoder.encode(listOf(image), a80))
        // 3 bands: 128 + 128 + 44 rows; each is 8 header bytes + rows, plus ESC a before and after.
        assertEquals(3 + 3 * 8 + 300 + 3, body.size)
        assertContentEquals(byteArrayOf(0x1D, 0x76, 0x30, 0, 1, 0, 128.toByte(), 0), body.copyOfRange(3, 11))
    }

    @Test
    fun `a line wider than the paper is split instead of left to the printer`() {
        val lines = EscPosEncoder.printedLines(listOf(ReceiptLine("x".repeat(100))), a80)
        assertEquals(3, lines.size)
        assertEquals(true, lines.all { it.trimStart().length <= a80.charsPerLine })
        assertEquals(100, lines.sumOf { it.trimStart().length })
    }

    @Test
    fun `barcode data the symbology cannot carry is rejected`() {
        fun bad(data: String, type: BarcodeType) = assertFailsWith<IllegalArgumentException> {
            EscPosEncoder.encode(listOf(ReceiptBarcode(data, type)), a80)
        }
        bad("12345", BarcodeType.EAN13)
        bad("abcdefghijkl", BarcodeType.EAN13)
        bad("123", BarcodeType.ITF)
        bad("abc", BarcodeType.CODE39)
        bad("é", BarcodeType.CODE128)
        EscPosEncoder.encode(listOf(ReceiptBarcode("123456789012", BarcodeType.EAN13)), a80)
    }
}
