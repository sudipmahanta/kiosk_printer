package com.zuchiz.kiosk_printer.printer

/**
 * Result codes. 0 and the negative values 1..-13 are the vendor's own table
 * (demo `Prints.ResultCodeToString`); -14 and below are added here for cases the
 * SDK has no code for.
 */
object CsnCodes {
    const val OK = 0
    const val PAPER_NEAR_END = 1
    const val PAPER_NEAR_END_AND_TICKET_WAITING = 2
    const val TICKET_WAITING = 3
    const val NOT_PRINTED_CHECK_JAM = -1
    const val CUTTER_ERROR = -2
    const val HEAD_OVERHEAT = -3
    const val OFFLINE = -4
    const val PAPER_OUT = -5
    const val COVER_OPEN = -6
    const val STATUS_QUERY_FAILED = -7
    const val NO_COMMUNICATION = -8
    const val PAPER_OUT_WHILE_PRINTING = -9
    const val COVER_OPENED_WHILE_PRINTING = -10
    const val CONNECTION_LOST = -11
    const val PREVIOUS_TICKET_NOT_REMOVED = -12
    const val UNKNOWN = -13
    const val NOT_CONNECTED = -14
    const val INVALID_ARGUMENT = -15
    const val NOT_CONFIRMED = -16

    fun message(code: Int): String = when (code) {
        OK -> "Ready"
        PAPER_NEAR_END -> "Paper is running low. Replace the roll soon."
        PAPER_NEAR_END_AND_TICKET_WAITING ->
            "Paper is running low and a receipt is waiting at the exit."
        TICKET_WAITING -> "A receipt is waiting at the exit. Take it."
        NOT_PRINTED_CHECK_JAM -> "Nothing was printed. Check for a paper jam."
        CUTTER_ERROR -> "Cutter error. Clear it by hand."
        HEAD_OVERHEAT -> "Print head is too hot. Wait for it to cool."
        OFFLINE -> "Printer is offline."
        PAPER_OUT -> "Printer is out of paper."
        COVER_OPEN -> "Printer cover is open."
        STATUS_QUERY_FAILED -> "Could not read the printer status."
        NO_COMMUNICATION ->
            "No answer from the printer. Check the connection."
        PAPER_OUT_WHILE_PRINTING ->
            "Ran out of paper while printing. Check the receipt is complete."
        COVER_OPENED_WHILE_PRINTING ->
            "Cover was opened while printing. Print the receipt again."
        CONNECTION_LOST -> "Lost the connection to the printer."
        PREVIOUS_TICKET_NOT_REMOVED -> "Take the previous receipt first."
        UNKNOWN -> "Unknown printer error. Restart the printer and retry."
        NOT_CONNECTED -> "No printer is connected."
        INVALID_ARGUMENT -> "The print request was not valid."
        NOT_CONFIRMED ->
            "The printer did not confirm the print (cover open, or no answer in time). Check the receipt."
        else -> "Printer error ($code)."
    }
}

object CsnStages {
    const val STATUS = "status"
    const val WRITE = "write"
    const val CONFIRM = "confirm"
    const val DONE = "done"
}

/**
 * The answer to a print or a status query, as the channel returns it.
 *
 * [success] is true only when nothing went wrong. [confirmed] is true only when
 * the printer itself reported the job as printed (`POS_TicketSucceed`); a print
 * sent without asking for confirmation is `success` but not `confirmed`.
 */
data class CsnOutcome(
    val success: Boolean,
    val code: Int,
    val stage: String,
    val confirmed: Boolean = false,
    val message: String = CsnCodes.message(code),
    val printer: CsnPrinterStatus? = null,
) {
    fun toMap(): Map<String, Any?> = mapOf(
        "success" to success,
        "code" to code,
        "message" to message,
        "stage" to stage,
        "confirmed" to confirmed,
        "printer" to printer?.toMap(),
    )

    companion object {
        fun failure(code: Int, stage: String, message: String? = null) =
            CsnOutcome(
                success = false,
                code = code,
                stage = stage,
                message = message ?: CsnCodes.message(code),
            )

        fun ok(confirmed: Boolean, printer: CsnPrinterStatus? = null) = CsnOutcome(
            success = true,
            code = CsnCodes.OK,
            stage = CsnStages.DONE,
            confirmed = confirmed,
            printer = printer,
        )
    }
}

/**
 * Turns the printer's real-time status bytes into a code. Pure, so it is
 * testable without a printer.
 *
 * Byte layout, from the vendor demo:
 * - type 3 (error status): 0x08 cutter error, 0x40 print head overheat
 * - type 2 (offline status): 0x04 cover open, 0x20 paper out
 */
object CsnStatusDecoder {
    const val TYPE_OFFLINE = 2
    const val TYPE_ERROR = 3
    const val TYPE_PAPER = 4

    fun fromErrorStatus(status: Int): Int = when {
        status and 0x08 != 0 -> CsnCodes.CUTTER_ERROR
        status and 0x40 != 0 -> CsnCodes.HEAD_OVERHEAT
        else -> CsnCodes.OK
    }

    fun fromOfflineStatus(status: Int): Int = when {
        status and 0x04 != 0 -> CsnCodes.COVER_OPEN
        status and 0x20 != 0 -> CsnCodes.PAPER_OUT
        else -> CsnCodes.OK
    }

    /**
     * `POS_TicketSucceed` answers 0 when the printer confirmed the job and a
     * negative number otherwise. The SDK's numbers are not the same table as the
     * pre-print status codes, so they are mapped here to ones that read right
     * *after* a print was sent.
     */
    fun fromTicketResult(result: Int): Int = when (result) {
        // 1..3 are the vendor's warnings (paper low, ticket waiting): printed.
        0, 1, 2, 3 -> CsnCodes.OK
        -1, -2, -3 -> CsnCodes.CONNECTION_LOST
        -4 -> CsnCodes.OFFLINE
        -5 -> CsnCodes.PAPER_OUT_WHILE_PRINTING
        else -> CsnCodes.NOT_CONFIRMED
    }
}
