package com.zuchiz.kiosk_printer.receipt

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertTrue

class ReceiptDecoderTest {

    private val profile = PaperProfile.forPaperMm(80)
    private val noImages = ReceiptDecoder.ImageLoader { _, _, _ -> null }

    private fun decode(vararg elements: Map<String, Any?>, loader: ReceiptDecoder.ImageLoader = noImages) =
        ReceiptDecoder.decode(elements.toList(), profile, loader)

    @Test
    fun `lines, feed and cut are decoded with their defaults`() {
        val result = decode(
            mapOf("type" to "line", "text" to "Hi", "align" to "center", "bold" to true),
            mapOf("type" to "feed"),
            mapOf("type" to "cut", "full" to true),
        )
        assertEquals(ReceiptLine("Hi", ReceiptAlign.CENTER, bold = true), result[0])
        assertEquals(ReceiptFeed(1), result[1])
        assertEquals(ReceiptCut(true), result[2])
    }

    @Test
    fun `qr and barcode options are read`() {
        val result = decode(
            mapOf("type" to "qr", "data" to "x", "level" to "h", "moduleSize" to 8),
            mapOf("type" to "barcode", "data" to "1", "symbology" to "EAN13", "showText" to false),
        )
        assertEquals(ReceiptQr("x", 8, QrLevel.H), result[0])
        assertEquals(BarcodeType.EAN13, (result[1] as ReceiptBarcode).type)
        assertTrue(!(result[1] as ReceiptBarcode).showText)
    }

    @Test
    fun `an image is scaled to the paper's printable width by default`() {
        var asked = 0
        val loader = ReceiptDecoder.ImageLoader { _, width, align ->
            asked = width
            ReceiptImage(1, 1, ByteArray(1), align)
        }
        val result = decode(mapOf("type" to "image", "bytes" to ByteArray(3)), loader = loader)
        assertIs<ReceiptImage>(result[0])
        assertEquals(576, asked)
    }

    @Test
    fun `bad input names the element`() {
        val e = assertFailsWith<IllegalArgumentException> {
            decode(mapOf("type" to "line"), mapOf("type" to "nope"))
        }
        assertTrue(e.message!!.startsWith("Receipt element 1"))
        assertFailsWith<IllegalArgumentException> { decode(mapOf("type" to "qr")) }
        assertFailsWith<IllegalArgumentException> { decode(mapOf("type" to "line", "align" to "middle")) }
        assertFailsWith<IllegalArgumentException> { decode(mapOf("type" to "line", "bold" to "yes")) }
        assertFailsWith<IllegalArgumentException> { decode(mapOf("type" to "image", "bytes" to ByteArray(1))) }
        assertFailsWith<IllegalArgumentException> {
            decode(mapOf("type" to "image", "bytes" to ByteArray(1), "widthDots" to 9999))
        }
        assertFailsWith<IllegalArgumentException> { ReceiptDecoder.decode(listOf("x"), profile, noImages) }
    }
}
