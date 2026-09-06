package com.example.rolloprint

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.graphics.*
import android.graphics.pdf.PdfRenderer
import android.hardware.usb.*
import android.net.Uri
import android.os.Build
import android.os.ParcelFileDescriptor
import java.io.ByteArrayOutputStream
import java.util.concurrent.Executors

enum class HardwareState {
    READY,
    HEAD_OPEN,
    OUT_OF_PAPER,
    PRINTING,
    PAUSED,
    UNKNOWN
}

class UsbPrintManager(private val context: Context, private val logger: (String) -> Unit) {

    private val usbManager: UsbManager = context.getSystemService(Context.USB_SERVICE) as UsbManager
    private val executor = Executors.newSingleThreadExecutor()
    private val usbLock = Any()

    @Volatile
    private var currentHardwareStates: Set<HardwareState> = setOf(HardwareState.UNKNOWN)

    var onHardwareStateChanged: ((Set<HardwareState>) -> Unit)? = null

    companion object {
        const val ACTION_USB_PERMISSION = "com.example.rolloprint.USB_PERMISSION"
        const val TARGET_WIDTH = 816 // 102 bytes * 8
        const val TARGET_HEIGHT = 1218
        const val ROLLO_VID = 2501
        const val ROLLO_PID = 1416
    }

    fun renderPdfToBitmap(uri: Uri): Bitmap? {
        logger("Opening PDF for rendering...")
        return try {
            val fd: ParcelFileDescriptor? = context.contentResolver.openFileDescriptor(uri, "r")
            if (fd == null) {
                logger("ERROR: Null File Descriptor")
                return null
            }
            val renderer = PdfRenderer(fd)
            val page = renderer.openPage(0)

            val bitmap = Bitmap.createBitmap(TARGET_WIDTH, TARGET_HEIGHT, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(bitmap)
            canvas.drawColor(Color.WHITE)

            val pdfWidth = page.width.toFloat()
            val pdfHeight = page.height.toFloat()

            val isLandscape = pdfWidth > pdfHeight

            if (isLandscape) {
                val scale = Math.min(TARGET_WIDTH.toFloat() / pdfHeight, TARGET_HEIGHT.toFloat() / pdfWidth)
                val renderW = (pdfWidth * scale).toInt()
                val renderH = (pdfHeight * scale).toInt()

                val pageBitmap = Bitmap.createBitmap(renderW, renderH, Bitmap.Config.ARGB_8888)
                page.render(pageBitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_PRINT)

                val matrix = Matrix().apply {
                    postRotate(90f)
                    postTranslate(
                        (TARGET_WIDTH + renderH) / 2f,
                        (TARGET_HEIGHT - renderW) / 2f
                    )
                }
                canvas.drawBitmap(pageBitmap, matrix, null)
                pageBitmap.recycle()
            } else {
                val scale = Math.min(TARGET_WIDTH.toFloat() / pdfWidth, TARGET_HEIGHT.toFloat() / pdfHeight)
                val renderWidth = (pdfWidth * scale).toInt()
                val renderHeight = (pdfHeight * scale).toInt()
                val left = (TARGET_WIDTH - renderWidth) / 2
                val top = (TARGET_HEIGHT - renderHeight) / 2

                val destRect = Rect(left, top, left + renderWidth, top + renderHeight)
                page.render(bitmap, destRect, null, PdfRenderer.Page.RENDER_MODE_FOR_PRINT)
            }

            page.close()
            renderer.close()
            fd.close()

            logger("PDF Rendered successfully (816x1218)")
            bitmap
        } catch (e: Exception) {
            logger("PDF RENDER ERROR: ${e.message}")
            null
        }
    }

    private fun generateTsplPayload(bitmap: Bitmap): ByteArray {
        logger("Packing Bitmap (816x1218)...")
        val width = bitmap.width
        val height = bitmap.height
        val widthBytes = 102
        val totalBytes = widthBytes * height

        val monoData = ByteArray(totalBytes)
        val pixels = IntArray(width * height)
        bitmap.getPixels(pixels, 0, width, 0, 0, width, height)

        for (y in 0 until height) {
            for (x in 0 until width) {
                val pixel = pixels[y * width + x]
                val r = (pixel shr 16) and 0xFF
                val g = (pixel shr 8) and 0xFF
                val b = pixel and 0xFF
                val luminance = (0.299 * r + 0.587 * g + 0.114 * b)

                if (luminance >= 128) {
                    val byteIdx = y * widthBytes + (x / 8)
                    val bitShift = 7 - (x % 8)
                    monoData[byteIdx] = (monoData[byteIdx].toInt() or (1 shl bitShift)).toByte()
                }
            }
        }

        val baos = ByteArrayOutputStream()
        baos.write(byteArrayOf(0x7E.toByte(), 0x40.toByte(), 0x0D.toByte(), 0x0A.toByte()))
        baos.write("SIZE 102 mm,153 mm\n".toByteArray())
        baos.write("REFERENCE 0,0\n".toByteArray())
        baos.write("DIRECTION 0,0\n".toByteArray())
        baos.write("GAP 3 mm,0 mm\n".toByteArray())
        baos.write("OFFSET 0 mm\n".toByteArray())
        baos.write("DENSITY 8\n".toByteArray())
        baos.write("SPEED 6\n".toByteArray())
        baos.write("CLS\n".toByteArray())
        baos.write("BITMAP 0,0,102,1218,1,".toByteArray())
        baos.write(monoData)
        baos.write("\nPRINT 1,1\n".toByteArray())

        return baos.toByteArray()
    }

    fun printBitmapAsync(bitmap: Bitmap, onComplete: ((Boolean) -> Unit)? = null) {
        executor.execute {
            try {
                val tsplData = generateTsplPayload(bitmap)
                val device = findRolloDevice()

                if (device == null) {
                    logger("CRITICAL: Rollo X1038 not found.")
                    onComplete?.invoke(false)
                    return@execute
                }

                if (!usbManager.hasPermission(device)) {
                    requestPermission(device)
                    onComplete?.invoke(false)
                } else {
                    val success = executeUsbTransfer(device, tsplData)
                    onComplete?.invoke(success)
                }
            } catch (e: Exception) {
                logger("PRINT ERROR: ${e.message}")
                onComplete?.invoke(false)
            }
        }
    }

    /**
     * Purges printer's onboard RAM buffer memory and halts label feeding via ~!C (0x7E, 0x21, 0x43)
     */
    fun clearHardwareQueue(): Boolean {
        synchronized(usbLock) {
            val device = findRolloDevice() ?: return false
            if (!usbManager.hasPermission(device)) return false

            val connection = usbManager.openDevice(device) ?: return false
            try {
                val usbInterface = device.getInterface(0)
                if (!connection.claimInterface(usbInterface, true)) return false

                val outEndpoint = (0 until usbInterface.endpointCount)
                    .map { usbInterface.getEndpoint(it) }
                    .find { it.type == UsbConstants.USB_ENDPOINT_XFER_BULK && it.direction == UsbConstants.USB_DIR_OUT }

                if (outEndpoint != null) {
                    logger("[HARDWARE] Purging printer RAM buffer & halting feed (~!C)...")
                    val cancelCmd = byteArrayOf(0x7E.toByte(), 0x21.toByte(), 0x43.toByte()) // ~!C
                    val result = connection.bulkTransfer(outEndpoint, cancelCmd, cancelCmd.size, 1000)
                    connection.releaseInterface(usbInterface)
                    return result >= 0
                }
                connection.releaseInterface(usbInterface)
                return false
            } catch (e: Exception) {
                logger("[HARDWARE] Error sending ~!C: ${e.message}")
                return false
            } finally {
                connection.close()
            }
        }
    }

    /**
     * Real-time hardware status polling using <ESC>!? (0x1B, 0x21, 0x3F)
     */
    fun pollHardwareStatus(): Set<HardwareState> {
        synchronized(usbLock) {
            val device = findRolloDevice() ?: run {
                val newState = setOf(HardwareState.UNKNOWN)
                updateHardwareState(newState)
                return newState
            }

            if (!usbManager.hasPermission(device)) {
                val newState = setOf(HardwareState.UNKNOWN)
                updateHardwareState(newState)
                return newState
            }

            val connection = usbManager.openDevice(device) ?: run {
                val newState = setOf(HardwareState.UNKNOWN)
                updateHardwareState(newState)
                return newState
            }

            try {
                val usbInterface = device.getInterface(0)
                if (!connection.claimInterface(usbInterface, true)) {
                    val newState = setOf(HardwareState.UNKNOWN)
                    updateHardwareState(newState)
                    return newState
                }

                val outEndpoint = (0 until usbInterface.endpointCount)
                    .map { usbInterface.getEndpoint(it) }
                    .find { it.type == UsbConstants.USB_ENDPOINT_XFER_BULK && it.direction == UsbConstants.USB_DIR_OUT }

                val inEndpoint = (0 until usbInterface.endpointCount)
                    .map { usbInterface.getEndpoint(it) }
                    .find { it.type == UsbConstants.USB_ENDPOINT_XFER_BULK && it.direction == UsbConstants.USB_DIR_IN }

                if (outEndpoint == null || inEndpoint == null) {
                    val newState = setOf(HardwareState.UNKNOWN)
                    updateHardwareState(newState)
                    connection.releaseInterface(usbInterface)
                    return newState
                }

                val queryCmd = byteArrayOf(0x1B.toByte(), 0x21.toByte(), 0x3F.toByte()) // <ESC>!?
                val sent = connection.bulkTransfer(outEndpoint, queryCmd, queryCmd.size, 1000)

                if (sent <= 0) {
                    val newState = setOf(HardwareState.UNKNOWN)
                    updateHardwareState(newState)
                    connection.releaseInterface(usbInterface)
                    return newState
                }

                val buffer = ByteArray(8)
                val bytesRead = connection.bulkTransfer(inEndpoint, buffer, buffer.size, 1000)

                val states = mutableSetOf<HardwareState>()
                if (bytesRead > 0) {
                    val status = buffer[0].toInt() and 0xFF
                    if (status == 0x00) {
                        states.add(HardwareState.READY)
                    } else {
                        // Bit 0 (0x01): Cover / Print Head Open
                        if ((status and 0x01) != 0) {
                            states.add(HardwareState.HEAD_OPEN)
                        }
                        // Bits 1 or 2 (0x02 / 0x04): Media empty / Gap sensor clear
                        if ((status and 0x06) != 0) {
                            states.add(HardwareState.OUT_OF_PAPER)
                        }
                        // Bit 5 (0x20): Paused
                        if ((status and 0x20) != 0) {
                            states.add(HardwareState.PAUSED)
                        }
                        // Bit 6 (0x40): Active printing
                        if ((status and 0x40) != 0) {
                            states.add(HardwareState.PRINTING)
                        }
                    }
                } else {
                    states.add(HardwareState.UNKNOWN)
                }

                connection.releaseInterface(usbInterface)
                updateHardwareState(states)
                return states
            } catch (_: Exception) {
                val newState = setOf(HardwareState.UNKNOWN)
                updateHardwareState(newState)
                return newState
            } finally {
                connection.close()
            }
        }
    }

    private fun updateHardwareState(newState: Set<HardwareState>) {
        if (currentHardwareStates != newState) {
            currentHardwareStates = newState
            logger("[HARDWARE_STATE] Transitioned to: [${newState.joinToString(", ")}]")
            onHardwareStateChanged?.invoke(newState)
        }
    }

    fun getCurrentHardwareState(): Set<HardwareState> = currentHardwareStates

    fun runPrinterDiagnosticsAsync() {
        executor.execute {
            val states = pollHardwareStatus()
            logger("[DIAGNOSTIC] Current Rollo Hardware States: ${states.joinToString(", ")}")
        }
    }

    private fun findRolloDevice(): UsbDevice? {
        return usbManager.deviceList.values.find { it.vendorId == ROLLO_VID && it.productId == ROLLO_PID }
    }

    private fun requestPermission(device: UsbDevice) {
        logger("Requesting USB Permission...")
        val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) PendingIntent.FLAG_MUTABLE else 0
        val intent = Intent(ACTION_USB_PERMISSION).setPackage(context.packageName)
        val permissionIntent = PendingIntent.getBroadcast(context, 0, intent, flags)
        usbManager.requestPermission(device, permissionIntent)
    }

    private fun executeUsbTransfer(device: UsbDevice, data: ByteArray): Boolean {
        synchronized(usbLock) {
            val hwStateStr = currentHardwareStates.joinToString(", ")

            // PRE-TRANSFER GUARD: Abort USB transfer if printer is Out of Paper or Cover Open!
            if (currentHardwareStates.contains(HardwareState.OUT_OF_PAPER) || currentHardwareStates.contains(HardwareState.HEAD_OPEN)) {
                logger("[JOB_TRANSFER] ABORTED: Rollo printer is OUT OF PAPER / COVER OPEN ($hwStateStr). Holding job in app memory queue.")
                return false
            }

            logger("[JOB_TRANSFER] Starting USB transfer (${data.size} bytes) | Hardware State: [$hwStateStr]")

            val connection = usbManager.openDevice(device) ?: run {
                logger("ERROR: Connection failed.")
                return false
            }

            try {
                val usbInterface = device.getInterface(0)
                if (!connection.claimInterface(usbInterface, true)) {
                    logger("ERROR: Interface Busy.")
                    return false
                }

                val endpoint = (0 until usbInterface.endpointCount)
                    .map { usbInterface.getEndpoint(it) }
                    .find { it.type == UsbConstants.USB_ENDPOINT_XFER_BULK && it.direction == UsbConstants.USB_DIR_OUT }

                if (endpoint == null) {
                    logger("ERROR: Endpoint Mismatch.")
                    return false
                }

                val chunkSize = 1024
                var bytesSent = 0
                while (bytesSent < data.size) {
                    val length = Math.min(chunkSize, data.size - bytesSent)
                    val result = connection.bulkTransfer(endpoint, data, bytesSent, length, 5000)

                    if (result < 0) {
                        logger("ERROR: Hardware Transfer Failure.")
                        break
                    }

                    bytesSent += result
                    Thread.sleep(10)
                }

                connection.releaseInterface(usbInterface)

                val postHwStateStr = currentHardwareStates.joinToString(", ")
                if (bytesSent == data.size) {
                    logger("[JOB_TRANSFER] SUCCESS: Sent $bytesSent/${data.size} bytes | Hardware State: [$postHwStateStr]")
                    return true
                } else {
                    logger("[JOB_TRANSFER] FAIL: Sent $bytesSent/${data.size} bytes | Hardware State: [$postHwStateStr]")
                    return false
                }
            } finally {
                connection.close()
            }
        }
    }
}
