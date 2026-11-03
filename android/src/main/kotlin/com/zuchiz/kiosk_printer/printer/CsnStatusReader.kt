package com.zuchiz.kiosk_printer.printer

import com.csnprintersdk.csnio.CSNPOS

/**
 * What the printer reports about itself, from the ESC/POS real-time status
 * bytes (`DLE EOT 2 / 3 / 4`). [paperSensorKnown] is false when the printer did
 * not answer the paper-sensor query, so [paperNearEnd] says nothing then.
 *
 * [readable] is false when the printer answered none of the queries: its
 * state is unknown, which is not the same as a fault. A print is not blocked
 * on it (the printer's own confirmation still decides the outcome).
 */
data class CsnPrinterStatus(
    val code: Int,
    val coverOpen: Boolean = false,
    val paperOut: Boolean = false,
    val paperNearEnd: Boolean = false,
    val cutterError: Boolean = false,
    val overheat: Boolean = false,
    val paperSensorKnown: Boolean = false,
    val readable: Boolean = true,
) {
    val ok: Boolean get() = code == CsnCodes.OK

    fun toMap(): Map<String, Any?> = mapOf(
        "code" to code,
        "message" to CsnCodes.message(code),
        "coverOpen" to coverOpen,
        "paperOut" to paperOut,
        "paperNearEnd" to paperNearEnd,
        "cutterError" to cutterError,
        "overheat" to overheat,
        "paperSensorKnown" to paperSensorKnown,
        "readable" to readable,
    )
}

/**
 * Reads the status the way the vendor demo does (error byte, offline byte),
 * plus the paper sensor for the "paper is about to finish" warning. Each query
 * stands alone: a printer that answers only some of them (many do not answer
 * every `DLE EOT`) still gives a usable result. Blocking; call it on the
 * printer thread.
 */
class CsnStatusReader(private val pos: CSNPOS) {

    private fun query(byte: ByteArray, type: Int): Boolean =
        pos.POS_RTQueryStatus(byte, type, TIMEOUT_MS, RETRIES)

    fun read(): CsnPrinterStatus {
        val byte = ByteArray(1)

        val errorOk = query(byte, CsnStatusDecoder.TYPE_ERROR)
        val errorBits = if (errorOk) byte[0].toInt() and 0xFF else 0

        if (!errorOk) {
            return CsnPrinterStatus(CsnCodes.NO_COMMUNICATION, readable = false)
        }

        val offlineOk = query(byte, CsnStatusDecoder.TYPE_OFFLINE)
        val offlineBits = if (offlineOk) byte[0].toInt() and 0xFF else 0

        val sensorOk = query(byte, CsnStatusDecoder.TYPE_PAPER)
        val sensorBits = if (sensorOk) byte[0].toInt() and 0xFF else 0
        val nearEnd = sensorOk && sensorBits and 0x0C != 0
        val paperEnd = sensorOk && sensorBits and 0x60 != 0

        val fault = CsnStatusDecoder.fromErrorStatus(errorBits).takeIf { it != CsnCodes.OK }
            ?: CsnStatusDecoder.fromOfflineStatus(offlineBits).takeIf { it != CsnCodes.OK }
            ?: if (paperEnd) CsnCodes.PAPER_OUT else CsnCodes.OK

        return CsnPrinterStatus(
            code = fault,
            coverOpen = offlineBits and 0x04 != 0,
            paperOut = offlineBits and 0x20 != 0 || paperEnd,
            paperNearEnd = nearEnd,
            cutterError = errorBits and 0x08 != 0,
            overheat = errorBits and 0x40 != 0,
            paperSensorKnown = sensorOk,
        )
    }

    private companion object {
        const val TIMEOUT_MS = 1000
        const val RETRIES = 1
    }
}
