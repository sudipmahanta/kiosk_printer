package com.zuchiz.kiosk_printer.receipt

import kotlin.test.Test
import kotlin.test.assertEquals

class PaperProfileTest {

    @Test
    fun `the three widths give their dots, font and characters`() {
        val p58 = PaperProfile.forPaperMm(58)
        val p72 = PaperProfile.forPaperMm(72)
        val p80 = PaperProfile.forPaperMm(80)
        assertEquals(Triple(384, PrinterFont.B, 42), Triple(p58.printableDots, p58.font, p58.charsPerLine))
        assertEquals(Triple(512, PrinterFont.A, 42), Triple(p72.printableDots, p72.font, p72.charsPerLine))
        assertEquals(Triple(576, PrinterFont.A, 48), Triple(p80.printableDots, p80.font, p80.charsPerLine))
    }

    @Test
    fun `other widths round to the nearest supported paper`() {
        assertEquals(80, PaperProfile.forPaperMm(0).paperMm)
        assertEquals(80, PaperProfile.forPaperMm(-5).paperMm)
        assertEquals(58, PaperProfile.forPaperMm(50).paperMm)
        assertEquals(72, PaperProfile.forPaperMm(70).paperMm)
        assertEquals(80, PaperProfile.forPaperMm(110).paperMm)
    }
}
