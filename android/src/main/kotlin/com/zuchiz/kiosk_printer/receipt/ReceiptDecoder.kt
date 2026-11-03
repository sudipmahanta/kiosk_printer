package com.zuchiz.kiosk_printer.receipt

/**
 * Turns the receipt the Dart side sends (a list of maps, see `Receipt.toWire`)
 * into [ReceiptElement]s. Pure, so it is tested without a device; the only
 * Android piece, picture decoding, comes in as [loadImage].
 *
 * Any malformed element throws [IllegalArgumentException], which the plugin
 * answers as an `INVALID_ARGUMENT` outcome before anything reaches the printer.
 */
object ReceiptDecoder {

    /** Decodes [bytes] and scales to [widthDots]; null when they are not a picture. */
    fun interface ImageLoader {
        fun load(bytes: ByteArray, widthDots: Int, align: ReceiptAlign): ReceiptImage?
    }

    fun decode(
        wire: List<*>,
        profile: PaperProfile,
        loadImage: ImageLoader,
    ): List<ReceiptElement> = wire.mapIndexed { index, raw ->
        val map = raw as? Map<*, *>
            ?: throw IllegalArgumentException("Receipt element $index is not a map.")
        try {
            element(map, profile, loadImage)
        } catch (e: ClassCastException) {
            throw IllegalArgumentException("Receipt element $index has a wrong type.")
        } catch (e: IllegalArgumentException) {
            throw IllegalArgumentException("Receipt element $index: ${e.message}")
        }
    }

    private fun element(m: Map<*, *>, profile: PaperProfile, loadImage: ImageLoader): ReceiptElement =
        when (val type = m["type"] as? String) {
            "line" -> ReceiptLine(
                text = m["text"] as? String ?: "",
                align = align(m),
                bold = m["bold"] as Boolean? ?: false,
                big = m["big"] as Boolean? ?: false,
            )
            "feed" -> ReceiptFeed((m["lines"] as? Number)?.toInt() ?: 1)
            "cut" -> ReceiptCut(m["full"] as Boolean? ?: false)
            "qr" -> ReceiptQr(
                data = m["data"] as? String ?: throw IllegalArgumentException("qr needs data."),
                moduleSize = (m["moduleSize"] as? Number)?.toInt() ?: 6,
                level = enumOf(m["level"] as? String ?: "M", QrLevel.values()),
                align = align(m, ReceiptAlign.CENTER),
            )
            "barcode" -> ReceiptBarcode(
                data = m["data"] as? String ?: throw IllegalArgumentException("barcode needs data."),
                type = enumOf(m["symbology"] as? String ?: "CODE128", BarcodeType.values()),
                height = (m["height"] as? Number)?.toInt() ?: 80,
                width = (m["width"] as? Number)?.toInt() ?: 2,
                showText = m["showText"] as Boolean? ?: true,
                align = align(m, ReceiptAlign.CENTER),
            )
            "image" -> {
                val bytes = m["bytes"] as? ByteArray
                    ?: throw IllegalArgumentException("image needs bytes.")
                val width = (m["widthDots"] as? Number)?.toInt() ?: profile.printableDots
                require(width in 8..profile.printableDots) {
                    "image width must be 8..${profile.printableDots} dots."
                }
                loadImage.load(bytes, width, align(m, ReceiptAlign.CENTER))
                    ?: throw IllegalArgumentException("image bytes are not a picture.")
            }
            else -> throw IllegalArgumentException("unknown type: $type")
        }

    private fun align(m: Map<*, *>, default: ReceiptAlign = ReceiptAlign.LEFT): ReceiptAlign =
        (m["align"] as String?)?.let { enumOf(it, ReceiptAlign.values()) } ?: default

    private fun <T : Enum<T>> enumOf(name: String, values: Array<T>): T =
        values.firstOrNull { it.name.equals(name, ignoreCase = true) }
            ?: throw IllegalArgumentException("unknown value: $name")
}
