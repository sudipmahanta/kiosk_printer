package com.zuchiz.kiosk_printer.printer

import kotlin.test.Test
import kotlin.test.assertEquals

class CsnStatusDecoderTest {

    @Test
    fun `error status bits`() {
        assertEquals(CsnCodes.OK, CsnStatusDecoder.fromErrorStatus(0))
        assertEquals(CsnCodes.CUTTER_ERROR, CsnStatusDecoder.fromErrorStatus(0x08))
        assertEquals(CsnCodes.HEAD_OVERHEAT, CsnStatusDecoder.fromErrorStatus(0x40))
        assertEquals(CsnCodes.CUTTER_ERROR, CsnStatusDecoder.fromErrorStatus(0x48))
    }

    @Test
    fun `offline status bits`() {
        assertEquals(CsnCodes.OK, CsnStatusDecoder.fromOfflineStatus(0))
        assertEquals(CsnCodes.COVER_OPEN, CsnStatusDecoder.fromOfflineStatus(0x04))
        assertEquals(CsnCodes.PAPER_OUT, CsnStatusDecoder.fromOfflineStatus(0x20))
        assertEquals(CsnCodes.COVER_OPEN, CsnStatusDecoder.fromOfflineStatus(0x24))
    }

    @Test
    fun `ticket confirmation results map to codes that read right after a print`() {
        listOf(0, 1, 2, 3).forEach { assertEquals(CsnCodes.OK, CsnStatusDecoder.fromTicketResult(it)) }
        listOf(-1, -2, -3).forEach { assertEquals(CsnCodes.CONNECTION_LOST, CsnStatusDecoder.fromTicketResult(it)) }
        assertEquals(CsnCodes.OFFLINE, CsnStatusDecoder.fromTicketResult(-4))
        assertEquals(CsnCodes.PAPER_OUT_WHILE_PRINTING, CsnStatusDecoder.fromTicketResult(-5))
        assertEquals(CsnCodes.NOT_CONFIRMED, CsnStatusDecoder.fromTicketResult(-9))
    }

    @Test
    fun `every code has an operator message`() {
        (-16..3).forEach { assert(!CsnCodes.message(it).startsWith("Printer error")) { "code $it" } }
    }
}
