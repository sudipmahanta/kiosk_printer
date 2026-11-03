package com.zuchiz.kiosk_printer.printer

object CsnChannels {
    const val METHODS = "com.zuchiz.kiosk_printer/methods"
    const val EVENTS = "com.zuchiz.kiosk_printer/events"
}

object CsnMethods {
    const val CONNECT = "connect"
    const val DISCONNECT = "disconnect"
    const val IS_CONNECTED = "isConnected"
    const val QUERY_STATUS = "queryStatus"

    const val PRINT_BYTES = "printBytes"
    const val PRINT_RECEIPT = "printReceipt"

    const val BEEP = "beep"
    const val KICK_DRAWER = "kickDrawer"

    const val LIST_USB_DEVICES = "listUsbDevices"
    const val REQUEST_USB_PERMISSION = "requestUsbPermission"
}

object CsnEvents {
    const val CONNECTED = "connected"
    const val CONNECT_FAILED = "connectFailed"
    const val DISCONNECTED = "disconnected"
    const val USB_ATTACHED = "usbAttached"
    const val USB_DETACHED = "usbDetached"
    const val USB_PERMISSION = "usbPermission"
    const val PAPER_LOW = "paperLow"
}

/** Transport names accepted by `connect`. */
object CsnTransports {
    const val BLUETOOTH = "BT"
    const val BLE = "BLE"
    const val USB = "USB"
    const val NETWORK = "NET"
}
