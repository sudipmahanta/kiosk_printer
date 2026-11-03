package com.zuchiz.kiosk_printer.printer

import com.csnprintersdk.csnio.CSNPOS

/**
 * Sends raw bytes to the open printer link in chunks.
 *
 * Bluetooth printers get small chunks with a short pause between them: cheap
 * SPP / BLE modules hand data to the print MCU over a UART without flow
 * control, and a big job sent at full speed overflows it (nothing, or garbage,
 * comes out). Same pacing as the plugin's Bluetooth connector (512 bytes,
 * 5 ms). USB and network go full speed.
 */
class CsnWriter(private val pos: CSNPOS) {

    /** True when every byte was accepted by the link. */
    fun write(data: ByteArray, transport: String): Boolean {
        val io = pos.GetIO() ?: return false
        val paced = transport == CsnTransports.BLUETOOTH || transport == CsnTransports.BLE
        val chunk = if (paced) PACED_CHUNK else CHUNK
        var offset = 0
        while (offset < data.size) {
            val length = minOf(chunk, data.size - offset)
            if (io.Write(data, offset, length) != length) return false
            offset += length
            if (paced && offset < data.size) {
                try {
                    Thread.sleep(PACED_DELAY_MS)
                } catch (_: InterruptedException) {
                    Thread.currentThread().interrupt()
                    return false
                }
            }
        }
        return true
    }

    private companion object {
        const val CHUNK = 4096
        const val PACED_CHUNK = 512
        const val PACED_DELAY_MS = 5L
    }
}
