package com.zuchiz.kiosk_printer.printer

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.os.Build
import android.os.Handler
import android.os.Looper

/**
 * USB side of the CSN printer layer: what is plugged in, whether we may use it,
 * and a permission request whose answer is actually handled.
 *
 * The vendor demo asks for permission and then waits for the operator to tap
 * again; it never listens for the answer. Here the answer arrives as a
 * [CsnEvents.USB_PERMISSION] event, and plug / unplug as attach / detach events,
 * so the caller can retry to connect on its own.
 */
class CsnUsbDevices(
    private val context: Context,
    private val emit: (type: String, data: Map<String, Any?>) -> Unit,
) {
    private val usb: UsbManager =
        context.getSystemService(Context.USB_SERVICE) as UsbManager

    /** Derived from the host app's id, so two apps using the package never share it. */
    private val permissionAction = "${context.packageName}.THERMAL_PRINTER_USB_PERMISSION"

    private var registered = false

    private val main = Handler(Looper.getMainLooper())

    /** Permission requests still unanswered, by device name, each with its timeout. */
    private val pending = java.util.concurrent.ConcurrentHashMap<String, Runnable>()

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context?, intent: Intent?) {
            val device = intent?.let(::deviceOf)
            val info = device?.let(::describe) ?: emptyMap()
            when (intent?.action) {
                permissionAction -> {
                    device?.let { pending.remove(it.deviceName)?.let(main::removeCallbacks) }
                    emit(
                        CsnEvents.USB_PERMISSION,
                        info + ("granted" to intent.getBooleanExtra(
                            UsbManager.EXTRA_PERMISSION_GRANTED, false,
                        )),
                    )
                }
                UsbManager.ACTION_USB_DEVICE_ATTACHED ->
                    emit(CsnEvents.USB_ATTACHED, info)
                UsbManager.ACTION_USB_DEVICE_DETACHED ->
                    emit(CsnEvents.USB_DETACHED, info)
            }
        }
    }

    /** Starts listening for permission answers and plug/unplug. Idempotent. */
    fun register() {
        if (registered) return
        val filter = IntentFilter().apply {
            addAction(permissionAction)
            addAction(UsbManager.ACTION_USB_DEVICE_ATTACHED)
            addAction(UsbManager.ACTION_USB_DEVICE_DETACHED)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            context.registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            @Suppress("UnspecifiedRegisterReceiverFlag")
            context.registerReceiver(receiver, filter)
        }
        registered = true
    }

    fun unregister() {
        if (!registered) return
        try {
            context.unregisterReceiver(receiver)
        } catch (_: IllegalArgumentException) {
            // Already gone with its context.
        }
        registered = false
        pending.values.forEach(main::removeCallbacks)
        pending.clear()
    }

    fun list(): List<Map<String, Any?>> =
        usb.deviceList.values.map(::describe)

    /**
     * The device with this vendor and product id. When two devices share an id
     * (the same model twice) [deviceName] picks one; without it the first wins.
     */
    fun find(vendorId: Int, productId: Int, deviceName: String? = null): UsbDevice? =
        usb.deviceList.values.firstOrNull {
            it.vendorId == vendorId &&
                it.productId == productId &&
                (deviceName == null || it.deviceName == deviceName)
        }

    fun hasPermission(device: UsbDevice): Boolean = usb.hasPermission(device)

    /**
     * Shows the system permission dialog. The answer comes back through
     * [CsnEvents.USB_PERMISSION]; a device we already have permission for is
     * answered straight away.
     */
    fun requestPermission(device: UsbDevice, timeoutMs: Long = PERMISSION_TIMEOUT_MS) {
        if (usb.hasPermission(device)) {
            emit(CsnEvents.USB_PERMISSION, describe(device) + ("granted" to true))
            return
        }
        usb.requestPermission(device, permissionIntent())
        // The system dialog can be dismissed or never shown (screen off, kiosk
        // lock); without this the caller would wait for an answer for ever.
        pending.remove(device.deviceName)?.let(main::removeCallbacks)
        val timeout = Runnable {
            pending.remove(device.deviceName)
            emit(
                CsnEvents.USB_PERMISSION,
                describe(device) + mapOf("granted" to false, "timedOut" to true),
            )
        }
        pending[device.deviceName] = timeout
        main.postDelayed(timeout, timeoutMs.coerceAtLeast(1000L))
    }

    private fun permissionIntent(): PendingIntent {
        val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            PendingIntent.FLAG_MUTABLE
        } else {
            0
        }
        val intent = Intent(permissionAction).setPackage(context.packageName)
        return PendingIntent.getBroadcast(context, 0, intent, flags)
    }

    private fun describe(device: UsbDevice): Map<String, Any?> = mapOf(
        "vendorId" to device.vendorId,
        "productId" to device.productId,
        "deviceName" to device.deviceName,
        "manufacturer" to safe { device.manufacturerName },
        "product" to safe { device.productName },
        "hasPermission" to usb.hasPermission(device),
        "isPrinterClass" to isPrinterClass(device),
        "isKnownPrinter" to isKnownPrinter(device),
    )

    private fun isPrinterClass(device: UsbDevice): Boolean {
        if (device.deviceClass == UsbConstants.USB_CLASS_PRINTER) return true
        for (i in 0 until device.interfaceCount) {
            if (device.getInterface(i).interfaceClass == UsbConstants.USB_CLASS_PRINTER) {
                return true
            }
        }
        return false
    }

    private fun isKnownPrinter(device: UsbDevice): Boolean =
        KNOWN_PRINTERS.contains(device.vendorId to device.productId)

    private fun safe(read: () -> String?): String? =
        try {
            read()
        } catch (_: SecurityException) {
            null
        }

    private fun deviceOf(intent: Intent): UsbDevice? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            intent.getParcelableExtra(UsbManager.EXTRA_DEVICE, UsbDevice::class.java)
        } else {
            @Suppress("DEPRECATION")
            intent.getParcelableExtra(UsbManager.EXTRA_DEVICE)
        }

    companion object {
        /** Default for how long a permission request may go unanswered. */
        const val PERMISSION_TIMEOUT_MS = 30_000L

        /**
         * Vendor / product ids already seen in this project: the kiosk's
         * supported printers and the CSN demo's attach filter.
         */
        val KNOWN_PRINTERS: Set<Pair<Int, Int>> = setOf(
            1046 to 20497,
            4070 to 33054,
            19267 to 17219,
            1659 to 8963,
        )
    }
}
