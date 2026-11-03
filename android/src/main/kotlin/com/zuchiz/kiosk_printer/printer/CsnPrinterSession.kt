package com.zuchiz.kiosk_printer.printer

import android.content.Context
import android.os.Handler
import android.os.Looper
import com.csnprintersdk.csnio.CSNBLEPrinting
import com.csnprintersdk.csnio.CSNBTPrinting
import com.csnprintersdk.csnio.CSNNETPrinting
import com.csnprintersdk.csnio.CSNPOS
import com.csnprintersdk.csnio.CSNUSBPrinting
import com.csnprintersdk.csnio.csnbase.CSNIOCallBack
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit

data class CsnConnectRequest(
    val type: String,
    val address: String = "",
    val port: Int = 9100,
    val timeoutMs: Int = 5000,
    val vendorId: Int? = null,
    val productId: Int? = null,
    val deviceName: String? = null,
)

/**
 * One printer connection and everything done on it.
 *
 * The CSN SDK blocks on I/O and is not safe to call from several threads, so
 * every SDK call runs on one single-thread executor, in order; results are
 * posted back to the main thread. A print is: status check, write, then the
 * printer's own "ticket printed" confirmation (`POS_TicketSucceed`).
 */
class CsnPrinterSession(
    private val context: Context,
    private val usb: CsnUsbDevices,
    private val emit: (type: String, data: Map<String, Any?>) -> Unit,
) : CSNIOCallBack {

    private val pos = CSNPOS()
    private val commands = CsnCommands(pos)
    private val statusReader = CsnStatusReader(pos)
    private val writer = CsnWriter(pos)
    private val executor: ExecutorService =
        Executors.newSingleThreadExecutor { r -> Thread(r, "csn-printer") }
    private val main = Handler(Looper.getMainLooper())

    /**
     * Separate from [executor], which is blocked inside the SDK's `Open` while
     * a connection attempt hangs. It closes the link when the time is up, which
     * makes `Open` return.
     */
    private val watchdog: ScheduledExecutorService =
        Executors.newSingleThreadScheduledExecutor { r ->
            Thread(r, "csn-printer-timeout").apply { isDaemon = true }
        }

    @Volatile
    private var timedOut = false

    private var closeTransport: (() -> Unit)? = null
    private var transport: String = ""
    @Volatile
    private var closingByUs = false
    private var ticketCounter = 0

    val isConnected: Boolean
        get() = try {
            pos.GetIO()?.IsOpened() == true
        } catch (_: Exception) {
            false
        }

    //  Connection
    fun connect(request: CsnConnectRequest, done: (Map<String, Any?>) -> Unit) {
        run(done, fallback = { connectResult(false, it) }) { open(request) }
    }

    private fun open(request: CsnConnectRequest): Map<String, Any?> {
        closeCurrent()
        timedOut = false
        transport = request.type.uppercase()

        val ok = when (request.type.uppercase()) {
            CsnTransports.BLUETOOTH -> {
                if (request.address.isBlank()) return missing("address")
                val link = CSNBTPrinting().also { it.SetCallBack(this) }
                pos.Set(link)
                closeTransport = { link.Close() }
                guarded(request.timeoutMs) { link.Open(request.address, context) }
            }
            CsnTransports.BLE -> {
                if (request.address.isBlank()) return missing("address")
                val link = CSNBLEPrinting().also { it.SetCallBack(this) }
                pos.Set(link)
                closeTransport = { link.Close() }
                guarded(request.timeoutMs) { link.Open(request.address, context) }
            }
            CsnTransports.NETWORK -> {
                if (request.address.isBlank()) return missing("address")
                val link = CSNNETPrinting().also { it.SetCallBack(this) }
                pos.Set(link)
                closeTransport = { link.Close() }
                guarded(request.timeoutMs) { link.Open(request.address, request.port, request.timeoutMs, context) }
            }
            CsnTransports.USB -> return openUsb(request)
            else -> return connectResult(false, "Unknown connection type: ${request.type}")
        }

        return if (ok) {
            connectResult(true, "Connected")
        } else {
            closeTransport = null
            failedOpen("the ${request.type} printer", request.timeoutMs)
        }
    }

    private fun openUsb(request: CsnConnectRequest): Map<String, Any?> {
        val vendor = request.vendorId
        val product = request.productId
        if (vendor == null || product == null) return missing("vendorId and productId")

        val device = usb.find(vendor, product, request.deviceName)
            ?: return connectResult(false, "USB printer not found. Is it plugged in?")

        if (!usb.hasPermission(device)) {
            // Ask now; the answer arrives as a usbPermission event and the
            // caller connects again.
            usb.requestPermission(device, request.timeoutMs.toLong())
            return connectResult(
                connected = false,
                message = "USB permission is needed. Allow it and connect again.",
                needsPermission = true,
            )
        }

        val manager = context.getSystemService(Context.USB_SERVICE)
            as android.hardware.usb.UsbManager
        val link = CSNUSBPrinting().also { it.SetCallBack(this) }
        pos.Set(link)
        closeTransport = { link.Close() }

        return if (guarded(request.timeoutMs) { link.Open(manager, device, context) }) {
            connectResult(true, "Connected")
        } else {
            closeTransport = null
            failedOpen("the USB printer", request.timeoutMs)
        }
    }

    fun disconnect(done: (Map<String, Any?>) -> Unit) {
        run(done, fallback = { mapOf("closed" to false, "message" to it) }) {
            closeCurrent()
            mapOf("closed" to true, "message" to "Disconnected")
        }
    }

    private fun closeCurrent() {
        val close = closeTransport ?: return
        closingByUs = true
        try {
            close()
        } catch (_: Exception) {
            // Closing a dead link can throw; it is closed either way.
        } finally {
            closeTransport = null
        }
    }

    //  Status and printing
    fun queryStatus(done: (Map<String, Any?>) -> Unit) {
        outcome(done) {
            if (!isConnected) return@outcome notConnected()
            val status = statusReader.read()
            if (status.ok) {
                CsnOutcome.ok(confirmed = false, printer = status)
            } else {
                CsnOutcome.failure(status.code, CsnStages.STATUS).copy(printer = status)
            }
        }
    }

    /**
     * Sends [data] (ESC/POS bytes). Unless told otherwise it checks the printer's
     * status first and waits for its confirmation afterwards. When it is not
     * connected yet and [connect] is given, it connects first, so one call is
     * enough for a print. After a successful print the status is read again and
     * returned, with a `paperLow` event when the paper sensor says the roll is
     * nearly finished.
     */
    fun printBytes(
        data: ByteArray,
        checkStatus: Boolean,
        confirm: Boolean,
        confirmTimeoutMs: Int,
        connect: CsnConnectRequest? = null,
        done: (Map<String, Any?>) -> Unit,
    ) {
        outcome(done) {
            if (!isConnected && connect != null) {
                val opened = open(connect)
                if (opened["connected"] != true) {
                    return@outcome CsnOutcome.failure(
                        CsnCodes.NOT_CONNECTED,
                        CsnStages.STATUS,
                        opened["message"] as? String,
                    )
                }
            }
            if (!isConnected) return@outcome notConnected()
            if (data.isEmpty()) {
                return@outcome CsnOutcome.failure(
                    CsnCodes.INVALID_ARGUMENT, CsnStages.STATUS,
                )
            }

            var printerAnswers = true
            if (checkStatus) {
                val status = statusReader.read()
                printerAnswers = status.readable
                if (!status.ok && status.readable) {
                    return@outcome CsnOutcome.failure(status.code, CsnStages.STATUS)
                        .copy(printer = status)
                }
            }

            if (!writer.write(data, transport)) {
                return@outcome CsnOutcome.failure(
                    CsnCodes.CONNECTION_LOST, CsnStages.WRITE,
                )
            }

            var confirmed = false
            if (confirm && printerAnswers) {
                val result = pos.POS_TicketSucceed(++ticketCounter, confirmTimeoutMs)
                val code = CsnStatusDecoder.fromTicketResult(result)
                if (code != CsnCodes.OK) {
                    return@outcome CsnOutcome.failure(code, CsnStages.CONFIRM)
                }
                confirmed = true
            }

            val after = afterPrintStatus()
            CsnOutcome.ok(confirmed = confirmed, printer = after)
        }
    }

    // Internals
    private fun afterPrintStatus(): CsnPrinterStatus? {
        val status = try {
            statusReader.read()
        } catch (_: Exception) {
            return null
        }
        if (status.paperNearEnd) {
            main.post { emit(CsnEvents.PAPER_LOW, status.toMap()) }
        }
        return status
    }

    /**
     * Runs one SDK command from [CsnCommands] and answers with its outcome.
     * `false` from the SDK means to write failed, i.e. the link is gone.
     */
    fun command(done: (Map<String, Any?>) -> Unit, send: (CsnCommands) -> Boolean) {
        outcome(done) {
            when {
                !isConnected -> notConnected()
                send(commands) -> CsnOutcome.ok(confirmed = false)
                else -> CsnOutcome.failure(CsnCodes.CONNECTION_LOST, CsnStages.WRITE)
            }
        }
    }

    private fun outcome(done: (Map<String, Any?>) -> Unit, work: () -> CsnOutcome) {
        run(
            done = done,
            fallback = {
                CsnOutcome.failure(CsnCodes.UNKNOWN, CsnStages.STATUS, it).toMap()
            },
        ) { work().toMap() }
    }

    /**
     * Runs [work] on the printer thread and delivers its result on the main
     * thread. An exception (the SDK throws on a dropped link) becomes
     * [fallback]'s answer instead of crashing the app.
     */
    private fun run(
        done: (Map<String, Any?>) -> Unit,
        fallback: (String) -> Map<String, Any?>,
        work: () -> Map<String, Any?>,
    ) {
        try {
            executor.execute {
                val result = try {
                    work()
                } catch (t: Throwable) {
                    fallback(t.message ?: t.javaClass.simpleName)
                }
                main.post { done(result) }
            }
        } catch (_: RejectedExecutionException) {
            done(fallback("The printer service is closed."))
        }
    }

    /**
     * Runs the SDK's blocking `Open` and closes the link if it is still
     * running after [timeoutMs]. The close makes `Open` return false.
     */
    private fun guarded(timeoutMs: Int, open: () -> Boolean): Boolean {
        val limit = timeoutMs.coerceAtLeast(MIN_TIMEOUT_MS).toLong()
        val timer = watchdog.schedule({
            timedOut = true
            closeCurrent()
        }, limit, TimeUnit.MILLISECONDS)
        return try {
            open()
        } finally {
            timer.cancel(false)
        }
    }

    private fun failedOpen(what: String, timeoutMs: Int): Map<String, Any?> =
        if (timedOut) {
            connectResult(
                false,
                "Timed out opening $what after ${timeoutMs.coerceAtLeast(MIN_TIMEOUT_MS) / 1000.0}s.",
                timedOut = true,
            )
        } else {
            connectResult(false, "Could not open $what.")
        }

    private fun notConnected() = CsnOutcome.failure(CsnCodes.NOT_CONNECTED, CsnStages.STATUS)

    private fun missing(what: String) =
        connectResult(false, "Missing $what for this connection type.")

    private fun connectResult(
        connected: Boolean,
        message: String,
        needsPermission: Boolean = false,
        timedOut: Boolean = false,
    ): Map<String, Any?> = mapOf(
        "connected" to connected,
        "message" to message,
        "needsPermission" to needsPermission,
        "timedOut" to timedOut,
    )

    //  SDK callbacks (arrive on a background thread)

    override fun OnOpen() {
        main.post { emit(CsnEvents.CONNECTED, emptyMap()) }
    }

    override fun OnOpenFailed() {
        main.post { emit(CsnEvents.CONNECT_FAILED, emptyMap()) }
    }

    override fun OnClose() {
        val unexpected = !closingByUs
        closingByUs = false
        main.post { emit(CsnEvents.DISCONNECTED, mapOf("unexpected" to unexpected)) }
    }

    fun dispose() {
        try {
            executor.execute { closeCurrent() }
        } catch (_: RejectedExecutionException) {
            // Already shut down.
        }
        executor.shutdown()
        watchdog.shutdownNow()
    }

    private companion object {
        const val MIN_TIMEOUT_MS = 1000
    }

}
