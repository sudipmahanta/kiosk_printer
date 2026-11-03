package com.zuchiz.kiosk_printer

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import com.zuchiz.kiosk_printer.printer.CsnChannels
import com.zuchiz.kiosk_printer.printer.CsnCodes
import com.zuchiz.kiosk_printer.printer.CsnConnectRequest
import com.zuchiz.kiosk_printer.printer.CsnMethods
import com.zuchiz.kiosk_printer.printer.CsnOutcome
import com.zuchiz.kiosk_printer.printer.CsnPrinterSession
import com.zuchiz.kiosk_printer.printer.CsnStages
import com.zuchiz.kiosk_printer.printer.CsnUsbDevices
import com.zuchiz.kiosk_printer.receipt.EscPosEncoder
import com.zuchiz.kiosk_printer.receipt.PaperProfile
import com.zuchiz.kiosk_printer.receipt.ReceiptAlign
import com.zuchiz.kiosk_printer.receipt.ReceiptDecoder
import com.zuchiz.kiosk_printer.receipt.ReceiptImage
import io.flutter.embedding.engine.plugins.FlutterPlugin
import io.flutter.plugin.common.EventChannel
import io.flutter.plugin.common.MethodCall
import io.flutter.plugin.common.MethodChannel

/**
 * The Flutter plugin: one USB listener and one printer session per engine,
 * created on attach and disposed on detach. It uses the application context
 * only (no Activity is needed for Bluetooth, network or the USB receiver), so
 * nothing leaks an Activity.
 */
class KioskPrinterPlugin :
    FlutterPlugin,
    MethodChannel.MethodCallHandler,
    EventChannel.StreamHandler {

    private var sink: EventChannel.EventSink? = null
    private var methodChannel: MethodChannel? = null
    private var eventChannel: EventChannel? = null
    private var usb: CsnUsbDevices? = null
    private var session: CsnPrinterSession? = null

    override fun onAttachedToEngine(binding: FlutterPlugin.FlutterPluginBinding) {
        val context = binding.applicationContext
        val usbDevices = CsnUsbDevices(context, ::send)
        usb = usbDevices
        session = CsnPrinterSession(context, usbDevices, ::send)

        methodChannel = MethodChannel(binding.binaryMessenger, CsnChannels.METHODS).also {
            it.setMethodCallHandler(this)
        }
        eventChannel = EventChannel(binding.binaryMessenger, CsnChannels.EVENTS).also {
            it.setStreamHandler(this)
        }
        usbDevices.register()
    }

    override fun onDetachedFromEngine(binding: FlutterPlugin.FlutterPluginBinding) {
        methodChannel?.setMethodCallHandler(null)
        eventChannel?.setStreamHandler(null)
        methodChannel = null
        eventChannel = null
        sink = null
        usb?.unregister()
        session?.dispose()
        usb = null
        session = null
    }

    private fun send(type: String, data: Map<String, Any?>) {
        sink?.success(mapOf("type" to type) + data)
    }

    //  EventChannel
    override fun onListen(arguments: Any?, events: EventChannel.EventSink?) {
        sink = events
    }

    override fun onCancel(arguments: Any?) {
        sink = null
    }

    //  MethodChannel
    override fun onMethodCall(call: MethodCall, result: MethodChannel.Result) {
        val session = session
        val usb = usb
        if (session == null || usb == null) {
            result.error("DETACHED", "The plugin is not attached to an engine.", null)
            return
        }
        val reply: (Map<String, Any?>) -> Unit = { result.success(it) }
        try {
            when (call.method) {
                CsnMethods.CONNECT ->
                    session.connect(connectRequest(call.arguments as? Map<*, *>), reply)
                CsnMethods.DISCONNECT -> session.disconnect(reply)
                CsnMethods.IS_CONNECTED -> result.success(session.isConnected)
                CsnMethods.QUERY_STATUS -> session.queryStatus(reply)

                CsnMethods.PRINT_BYTES -> session.printBytes(
                    data = call.require<ByteArray>("bytes"),
                    checkStatus = call.flag("checkStatus", true),
                    confirm = call.flag("confirm", true),
                    confirmTimeoutMs = call.int("confirmTimeoutMs", 30000),
                    done = reply,
                )
                CsnMethods.PRINT_RECEIPT -> printReceipt(session, call, reply)

                CsnMethods.BEEP -> {
                    val count = call.int("count", 1)
                    val duration = call.int("duration", 5)
                    session.command(reply) { it.beep(count, duration) }
                }
                CsnMethods.KICK_DRAWER -> {
                    val port = call.int("port", 0)
                    val duration = call.int("duration", 100)
                    session.command(reply) { it.kickDrawer(port, duration) }
                }

                CsnMethods.LIST_USB_DEVICES -> result.success(usb.list())
                CsnMethods.REQUEST_USB_PERMISSION -> requestUsbPermission(usb, call, result)

                else -> result.notImplemented()
            }
        } catch (e: IllegalArgumentException) {
            result.error("INVALID_ARGUMENT", e.message, null)
        } catch (e: ClassCastException) {
            // A value of the wrong type (a string where a number belongs).
            result.error("INVALID_ARGUMENT", "Argument has the wrong type.", null)
        }
    }

    private fun requestUsbPermission(
        usb: CsnUsbDevices,
        call: MethodCall,
        result: MethodChannel.Result,
    ) {
        val vendor = call.require<Int>("vendorId")
        val product = call.require<Int>("productId")
        val device = usb.find(vendor, product, call.argument<String>("deviceName"))
        if (device == null) {
            result.success(mapOf("requested" to false, "message" to "USB printer not found."))
            return
        }
        usb.requestPermission(device, call.int("timeoutMs", 30_000).toLong())
        result.success(mapOf("requested" to true, "message" to "Permission requested."))
    }

    /**
     * A generic receipt in, a printed receipt out: the elements are decoded,
     * encoded to ESC/POS for the paper width, then sent with the status check
     * and confirmation of [CsnPrinterSession.printBytes]. `connection` (same
     * fields as `connect`) is used when no printer is connected yet.
     */
    private fun printReceipt(
        session: CsnPrinterSession,
        call: MethodCall,
        reply: (Map<String, Any?>) -> Unit,
    ) {
        val wire = call.argument<List<*>>("elements")
            ?: throw IllegalArgumentException("Missing or invalid argument: elements")
        val profile = PaperProfile.forPaperMm(call.int("paperWidthMm", 80))

        val bytes = try {
            val elements = ReceiptDecoder.decode(wire, profile, ::loadImage)
            if (elements.isEmpty()) {
                reply(CsnOutcome.ok(confirmed = false).toMap() + ("empty" to true))
                return
            }
            EscPosEncoder.encode(elements, profile)
        } catch (e: IllegalArgumentException) {
            reply(
                CsnOutcome.failure(
                    CsnCodes.INVALID_ARGUMENT,
                    CsnStages.STATUS,
                    "Could not prepare the receipt: ${e.message}",
                ).toMap() + ("empty" to false),
            )
            return
        }

        val connection = call.argument<Map<*, *>>("connection")?.let(::connectRequest)
        session.printBytes(
            data = bytes,
            checkStatus = call.flag("checkStatus", true),
            confirm = call.flag("confirm", true),
            confirmTimeoutMs = call.int("confirmTimeoutMs", 30000),
            connect = connection,
        ) { outcome -> reply(outcome + ("empty" to false)) }
    }

    private fun loadImage(
        bytes: ByteArray,
        widthDots: Int,
        align: ReceiptAlign,
    ): ReceiptImage? {
        val source = BitmapFactory.decodeByteArray(bytes, 0, bytes.size) ?: return null
        val scaled = if (source.width == widthDots) {
            source
        } else {
            val height = (source.height * widthDots / source.width).coerceAtLeast(1)
            Bitmap.createScaledBitmap(source, widthDots, height, true)
        }
        try {
            val pixels = IntArray(scaled.width * scaled.height)
            scaled.getPixels(pixels, 0, scaled.width, 0, 0, scaled.width, scaled.height)
            return EscPosEncoder.rasterize(scaled.width, scaled.height, pixels, align)
        } finally {
            if (scaled !== source) scaled.recycle()
            source.recycle()
        }
    }

    private fun connectRequest(args: Map<*, *>?): CsnConnectRequest {
        val map = args ?: throw IllegalArgumentException("Missing arguments.")
        fun number(key: String, fallback: Int) =
            (map[key] as? Number)?.toInt() ?: fallback
        return CsnConnectRequest(
            type = map["type"] as? String
                ?: throw IllegalArgumentException("Missing or invalid argument: type"),
            address = map["address"] as? String ?: "",
            port = number("port", 9100),
            timeoutMs = number("timeoutMs", 5000),
            vendorId = (map["vendorId"] as? Number)?.toInt(),
            productId = (map["productId"] as? Number)?.toInt(),
            deviceName = map["deviceName"] as? String,
        )
    }

    private inline fun <reified T> MethodCall.require(key: String): T =
        argument<T>(key)
            ?: throw IllegalArgumentException("Missing or invalid argument: $key")

    private fun MethodCall.int(key: String, fallback: Int): Int =
        (argument<Any>(key) as? Number)?.toInt() ?: fallback

    private fun MethodCall.flag(key: String, fallback: Boolean): Boolean =
        argument<Boolean>(key) ?: fallback
}
