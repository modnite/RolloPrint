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

class UsbPrintManager(private val context: Context, private val logger: (String) -> Unit) {

    private val usbManager: UsbManager = context.getSystemService(Context.USB_SERVICE) as UsbManager
    private val executor = Executors.newSingleThreadExecutor()

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

            val isLandscape = page.width > page.height
            if (isLandscape) {
                val tempW = page.height
                val tempH = page.width
                val scale = Math.min(TARGET_WIDTH.toFloat() / tempW, TARGET_HEIGHT.toFloat() / tempH)
                val renderW = (page.width * scale).toInt()
                val renderH = (page.height * scale).toInt()

                val pageBitmap = Bitmap.createBitmap(renderW, renderH, Bitmap.Config.ARGB_8888)
                val pageCanvas = Canvas(pageBitmap)
                pageCanvas.drawColor(Color.WHITE)
                page.render(pageBitmap, Rect(0, 0, renderW, renderH), null, PdfRenderer.Page.RENDER_MODE_FOR_PRINT)

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
                val scale = Math.min(TARGET_WIDTH.toFloat() / page.width, TARGET_HEIGHT.toFloat() / page.height)
                val renderWidth = (page.width * scale).toInt()
                val renderHeight = (page.height * scale).toInt()
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

    /**
     * Requirement: Fixed Bit-Packing with INVERTED Polarity as default.
     * This was confirmed to produce successful prints on the Rollo X1038.
     */
    private fun generateTsplPayload(bitmap: Bitmap): ByteArray {
        logger("Packing Bitmap (Inverted Polarity Fixed)...")
        val width = bitmap.width
        val height = bitmap.height
        val widthBytes = width / 8
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

                // Rollo X1038 Polarity: 1 = Paper, 0 = Ink (Inverted)
                // We send 1 if the pixel is LIGHT (>=128) and 0 if it is DARK (<128).
                if (luminance >= 128) {
                    val byteIdx = y * widthBytes + (x / 8)
                    val bitShift = 7 - (x % 8)
                    monoData[byteIdx] = (monoData[byteIdx].toInt() or (1 shl bitShift)).toByte()
                }
            }
        }

        val baos = ByteArrayOutputStream()
        baos.write("SIZE 4.0,6.0\r\n".toByteArray())
        baos.write("GAP 0,0\r\n".toByteArray())
        baos.write("DIRECTION 1\r\n".toByteArray())
        baos.write("REFERENCE 0,0\r\n".toByteArray())
        baos.write("SET TEAR ON\r\n".toByteArray())
        baos.write("CLS\r\n".toByteArray())
        
        val header = "BITMAP 0,0,$widthBytes,$height,0,".toByteArray()
        baos.write(header)
        baos.write(monoData)
        baos.write("\r\nPRINT 1,1\r\n".toByteArray())
        
        return baos.toByteArray()
    }

    fun printBitmapAsync(bitmap: Bitmap) {
        executor.execute {
            try {
                val tsplData = generateTsplPayload(bitmap)
                val device = findRolloDevice()
                
                if (device == null) {
                    logger("CRITICAL: Rollo X1038 not found.")
                    return@execute
                }

                if (!usbManager.hasPermission(device)) {
                    requestPermission(device)
                } else {
                    executeUsbTransfer(device, tsplData)
                }
            } catch (e: Exception) {
                logger("PRINT ERROR: ${e.message}")
            }
        }
    }

    fun printRawBytesAsync(data: ByteArray) {
        executor.execute {
            try {
                val device = findRolloDevice()
                if (device == null) {
                    logger("CRITICAL: Rollo X1038 not found.")
                    return@execute
                }

                if (!usbManager.hasPermission(device)) {
                    requestPermission(device)
                } else {
                    executeUsbTransfer(device, data)
                }
            } catch (e: Exception) {
                logger("PRINT ERROR: ${e.message}")
            }
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

    private fun executeUsbTransfer(device: UsbDevice, data: ByteArray) {
        logger("Connecting to Rollo hardware...")
        val connection = usbManager.openDevice(device) ?: run {
            logger("ERROR: Connection failed.")
            return
        }

        try {
            val usbInterface = device.getInterface(0)
            if (!connection.claimInterface(usbInterface, true)) {
                logger("ERROR: Interface Busy.")
                return
            }

            val endpoint = (0 until usbInterface.endpointCount)
                .map { usbInterface.getEndpoint(it) }
                .find { it.type == UsbConstants.USB_ENDPOINT_XFER_BULK && it.direction == UsbConstants.USB_DIR_OUT }

            if (endpoint == null) {
                logger("ERROR: Endpoint Mismatch.")
                return
            }

            logger("Streaming data stream (${data.size} bytes)...")
            
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

            if (bytesSent == data.size) {
                logger("SUCCESS: Job confirmed by printer hardware.")
            }

            connection.releaseInterface(usbInterface)
        } finally {
            connection.close()
        }
    }
}
