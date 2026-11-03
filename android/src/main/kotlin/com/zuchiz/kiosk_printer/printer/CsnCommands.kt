package com.zuchiz.kiosk_printer.printer

import com.csnprintersdk.csnio.CSNPOS

/** One-shot SDK commands. Receipt content goes through the ESC/POS encoder instead. */
class CsnCommands(private val pos: CSNPOS) {

    fun beep(count: Int, duration: Int): Boolean = pos.POS_Beep(count, duration)

    fun kickDrawer(port: Int, duration: Int): Boolean = pos.POS_KickDrawer(port, duration)
}
