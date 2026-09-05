package com.example.rolloprint

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.pdf.PdfDocument
import android.net.Uri
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.net.Inet4Address
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketTimeoutException
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger

class IppServer(
    private val context: Context,
    private val printManager: UsbPrintManager,
    private val logger: (String) -> Unit,
    private val onStatusChanged: (Boolean, String?) -> Unit
) {
    private var serverSocket: ServerSocket? = null
    @Volatile
    private var isRunning = false
    private val serverExecutor = Executors.newCachedThreadPool()
    private val jobIdCounter = AtomicInteger(1)

    companion object {
        const val PORT = 8631
    }

    fun start() {
        if (isRunning) return
        isRunning = true
        val ipAddress = getLocalIpAddress()
        logger("[IPP] Server starting on $ipAddress:$PORT...")
        onStatusChanged(true, ipAddress)

        serverExecutor.execute {
            try {
                serverSocket = ServerSocket().apply {
                    reuseAddress = true
                    bind(InetSocketAddress(PORT))
                }
                logger("[IPP] Listening for IPP Everywhere print jobs on port $PORT...")

                while (isRunning && serverSocket?.isClosed == false) {
                    try {
                        val clientSocket = serverSocket?.accept() ?: break
                        handleClientSocket(clientSocket)
                    } catch (e: Exception) {
                        if (isRunning && serverSocket?.isClosed == false) {
                            logger("[IPP] Server accept error: ${e.message}")
                        }
                    }
                }
            } catch (e: Exception) {
                logger("[IPP] Failed to bind IPP port $PORT: ${e.message}")
                isRunning = false
                onStatusChanged(false, null)
            }
        }
    }

    fun stop() {
        isRunning = false
        try {
            serverSocket?.close()
        } catch (_: Exception) {}
        serverSocket = null
        logger("[IPP] Server stopped.")
        onStatusChanged(false, null)
    }

    private fun handleClientSocket(socket: Socket) {
        val clientIp = socket.inetAddress?.hostAddress ?: "Unknown"
        Executors.newSingleThreadExecutor().execute {
            try {
                socket.soTimeout = 3000
                val input = socket.getInputStream()
                val baos = ByteArrayOutputStream()
                val buffer = ByteArray(4096)
                var bytesRead: Int

                // Read HTTP Headers
                var headerEnd = -1
                while (isRunning) {
                    bytesRead = input.read(buffer)
                    if (bytesRead == -1) break
                    baos.write(buffer, 0, bytesRead)
                    val data = baos.toByteArray()
                    headerEnd = findHeaderEnd(data)
                    if (headerEnd != -1) break
                }

                if (headerEnd == -1) {
                    socket.close()
                    return@execute
                }

                val allData = baos.toByteArray()
                val headerBytes = allData.copyOfRange(0, headerEnd)
                val headerStr = String(headerBytes, Charsets.US_ASCII)

                // Parse Content-Length if present
                val contentLength = parseContentLength(headerStr)

                // Read remaining HTTP body if needed
                val bodyBaos = ByteArrayOutputStream()
                val initialBodyBytes = allData.copyOfRange(headerEnd, allData.size)
                bodyBaos.write(initialBodyBytes)

                while (contentLength > 0 && bodyBaos.size() < contentLength && isRunning) {
                    bytesRead = input.read(buffer)
                    if (bytesRead == -1) break
                    bodyBaos.write(buffer, 0, bytesRead)
                }

                val ippData = bodyBaos.toByteArray()

                if (ippData.size < 8) {
                    sendHttpResponse(socket, 400, "Bad Request")
                    socket.close()
                    return@execute
                }

                // Process IPP Frame
                processIppRequest(socket, ippData, clientIp)

                try {
                    socket.close()
                } catch (_: Exception) {}
            } catch (e: Exception) {
                logger("[IPP] Error handling client $clientIp: ${e.message}")
            }
        }
    }

    private fun findHeaderEnd(data: ByteArray): Int {
        for (i in 0 until data.size - 3) {
            if (data[i] == 0x0D.toByte() && data[i + 1] == 0x0A.toByte() &&
                data[i + 2] == 0x0D.toByte() && data[i + 3] == 0x0A.toByte()) {
                return i + 4
            }
        }
        return -1
    }

    private fun parseContentLength(headers: String): Int {
        val lines = headers.lines()
        for (line in lines) {
            if (line.lowercase().startsWith("content-length:")) {
                return line.substringAfter(":").trim().toIntOrNull() ?: 0
            }
        }
        return 0
    }

    private fun processIppRequest(socket: Socket, ippData: ByteArray, clientIp: String) {
        val opId = ((ippData[2].toInt() and 0xFF) shl 8) or (ippData[3].toInt() and 0xFF)
        val requestId = ippData.copyOfRange(4, 8)

        when (opId) {
            0x000B -> { // Get-Printer-Attributes
                logger("[IPP] Get-Printer-Attributes request from $clientIp")
                sendIppPrinterAttributesResponse(socket, requestId)
            }
            0x000A -> { // Validate-Job
                logger("[IPP] Validate-Job request from $clientIp")
                sendIppSimpleResponse(socket, requestId, 0x0000)
            }
            0x0002, 0x0006 -> { // Print-Job (0x0002) or Send-Document (0x0006)
                val jobId = jobIdCounter.getAndIncrement()
                logger("[IPP] Print-Job #$jobId received (${ippData.size} bytes) from $clientIp")
                
                // Extract document binary payload (following End-Of-Attributes Tag 0x03)
                val docStart = findEndOfAttributesTag(ippData)
                val docData = if (docStart != -1 && docStart < ippData.size) {
                    ippData.copyOfRange(docStart, ippData.size)
                } else {
                    ippData
                }

                sendIppPrintJobResponse(socket, requestId, jobId)

                // Convert payload to PDF and dispatch to TSPL rasterizer
                if (docData.isNotEmpty()) {
                    processIncomingDocumentPayload(docData, jobId)
                }
            }
            0x0009 -> { // Get-Job-Attributes
                logger("[IPP] Get-Job-Attributes request from $clientIp")
                sendIppJobAttributesResponse(socket, requestId)
            }
            else -> {
                logger("[IPP] Operation 0x${opId.toString(16)} requested from $clientIp")
                sendIppSimpleResponse(socket, requestId, 0x0000)
            }
        }
    }

    private fun findEndOfAttributesTag(data: ByteArray): Int {
        for (i in 8 until data.size) {
            if (data[i] == 0x03.toByte()) { // end-of-attributes-tag
                return i + 1
            }
        }
        return -1
    }

    private fun sendIppPrinterAttributesResponse(socket: Socket, requestId: ByteArray) {
        val ippWriter = IppResponseWriter(requestId)
        ippWriter.startGroup(0x01) // operation-attributes-tag
        ippWriter.addAttribute(0x47, "attributes-charset", "utf-8")
        ippWriter.addAttribute(0x48, "attributes-natural-language", "en")

        ippWriter.startGroup(0x04) // printer-attributes-tag
        ippWriter.addIntAttribute(0x23, "printer-state", 3) // 3 = idle
        ippWriter.addAttribute(0x44, "printer-state-reasons", "none")
        ippWriter.addBooleanAttribute("printer-is-accepting-jobs", true)
        ippWriter.addAttribute(0x44, "ipp-versions-supported", "2.0")
        ippWriter.addAttribute(0x49, "document-format-supported", "application/pdf")
        ippWriter.addResolutionAttribute("printer-resolution-supported", 203, 203)
        ippWriter.addAttribute(0x44, "media-supported", "oe_4x6-label_4x6in")
        ippWriter.addAttribute(0x44, "media-default", "oe_4x6-label_4x6in")
        ippWriter.addAttribute(0x42, "printer-name", "Rollo Thermal 4x6")
        ippWriter.addAttribute(0x42, "printer-info", "Rollo Thermal 4x6 Printer")
        ippWriter.addAttribute(0x45, "printer-uri-supported", "ipp://${getLocalIpAddress()}:$PORT/ipp/print")
        ippWriter.addAttribute(0x44, "uri-authentication-supported", "none")
        ippWriter.addAttribute(0x44, "uri-security-supported", "none")

        val responseBytes = ippWriter.build()
        sendIppHttpResponse(socket, responseBytes)
    }

    private fun sendIppPrintJobResponse(socket: Socket, requestId: ByteArray, jobId: Int) {
        val ippWriter = IppResponseWriter(requestId)
        ippWriter.startGroup(0x01) // operation-attributes-tag
        ippWriter.addAttribute(0x47, "attributes-charset", "utf-8")
        ippWriter.addAttribute(0x48, "attributes-natural-language", "en")

        ippWriter.startGroup(0x02) // job-attributes-tag
        ippWriter.addIntAttribute(0x21, "job-id", jobId)
        ippWriter.addAttribute(0x45, "job-uri", "ipp://${getLocalIpAddress()}:$PORT/ipp/print/$jobId")
        ippWriter.addIntAttribute(0x23, "job-state", 9) // 9 = completed

        val responseBytes = ippWriter.build()
        sendIppHttpResponse(socket, responseBytes)
    }

    private fun sendIppJobAttributesResponse(socket: Socket, requestId: ByteArray) {
        val ippWriter = IppResponseWriter(requestId)
        ippWriter.startGroup(0x01) // operation-attributes-tag
        ippWriter.addAttribute(0x47, "attributes-charset", "utf-8")
        ippWriter.addAttribute(0x48, "attributes-natural-language", "en")

        ippWriter.startGroup(0x02) // job-attributes-tag
        ippWriter.addIntAttribute(0x23, "job-state", 9) // 9 = completed

        val responseBytes = ippWriter.build()
        sendIppHttpResponse(socket, responseBytes)
    }

    private fun sendIppSimpleResponse(socket: Socket, requestId: ByteArray, statusCode: Short) {
        val ippWriter = IppResponseWriter(requestId, statusCode)
        ippWriter.startGroup(0x01) // operation-attributes-tag
        ippWriter.addAttribute(0x47, "attributes-charset", "utf-8")
        ippWriter.addAttribute(0x48, "attributes-natural-language", "en")

        val responseBytes = ippWriter.build()
        sendIppHttpResponse(socket, responseBytes)
    }

    private fun sendIppHttpResponse(socket: Socket, ippBytes: ByteArray) {
        try {
            val output = socket.getOutputStream()
            val header = "HTTP/1.1 200 OK\r\n" +
                    "Content-Type: application/ipp\r\n" +
                    "Content-Length: ${ippBytes.size}\r\n" +
                    "Connection: close\r\n\r\n"
            output.write(header.toByteArray(Charsets.US_ASCII))
            output.write(ippBytes)
            output.flush()
        } catch (_: Exception) {}
    }

    private fun sendHttpResponse(socket: Socket, statusCode: Int, statusText: String) {
        try {
            val output = socket.getOutputStream()
            val header = "HTTP/1.1 $statusCode $statusText\r\nContent-Length: 0\r\nConnection: close\r\n\r\n"
            output.write(header.toByteArray(Charsets.US_ASCII))
            output.flush()
        } catch (_: Exception) {}
    }

    private fun processIncomingDocumentPayload(data: ByteArray, jobId: Int) {
        val pdfFile = File(context.cacheDir, "ipp_job_$jobId.pdf")

        try {
            // 1. Check if incoming payload contains PDF (%PDF-)
            val pdfHeader = "%PDF-".toByteArray()
            val pdfStart = findByteSequence(data, pdfHeader)
            if (pdfStart != -1) {
                val pdfBytes = data.copyOfRange(pdfStart, data.size)
                FileOutputStream(pdfFile).use { it.write(pdfBytes) }
                logger("[IPP] Extracted PDF document (${pdfBytes.size} bytes) for Job #$jobId")
                ingestPdfToLocalPrint(pdfFile)
                return
            }

            // 2. Check if incoming payload is a PNG or JPEG image
            val pngHeader = byteArrayOf(0x89.toByte(), 'P'.code.toByte(), 'N'.code.toByte(), 'G'.code.toByte())
            val pngStart = findByteSequence(data, pngHeader)
            val jpgHeader = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte())
            val jpgStart = findByteSequence(data, jpgHeader)

            val imgStart = if (pngStart != -1) pngStart else jpgStart
            if (imgStart != -1) {
                val imgBytes = data.copyOfRange(imgStart, data.size)
                val bitmap = BitmapFactory.decodeByteArray(imgBytes, 0, imgBytes.size)
                if (bitmap != null) {
                    logger("[IPP] Extracted Image document for Job #$jobId. Converting to 4x6 PDF...")
                    createPdfFromBitmap(bitmap, pdfFile)
                    ingestPdfToLocalPrint(pdfFile)
                    return
                }
            }

            // 3. Fallback: Text/PostScript stream -> Convert to 4x6 PDF
            logger("[IPP] Converting Text/PostScript stream to 4x6 PDF for Job #$jobId...")
            val textContent = String(data, Charsets.UTF_8)
            createPdfFromText(textContent, pdfFile)
            ingestPdfToLocalPrint(pdfFile)

        } catch (e: Exception) {
            logger("[IPP] ERROR processing IPP job #$jobId: ${e.message}")
        } finally {
            if (pdfFile.exists()) {
                pdfFile.delete()
            }
        }
    }

    private fun createPdfFromBitmap(srcBitmap: Bitmap, outputFile: File) {
        var bitmap = srcBitmap
        if (bitmap.width > bitmap.height) {
            val matrix = Matrix().apply { postRotate(90f) }
            bitmap = Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
        }

        val pdfDoc = PdfDocument()
        val pageInfo = PdfDocument.PageInfo.Builder(UsbPrintManager.TARGET_WIDTH, UsbPrintManager.TARGET_HEIGHT, 1).create()
        val page = pdfDoc.startPage(pageInfo)
        val canvas = page.canvas
        canvas.drawColor(Color.WHITE)

        val scale = Math.min(
            UsbPrintManager.TARGET_WIDTH.toFloat() / bitmap.width,
            UsbPrintManager.TARGET_HEIGHT.toFloat() / bitmap.height
        )
        val renderW = bitmap.width * scale
        val renderH = bitmap.height * scale
        val left = (UsbPrintManager.TARGET_WIDTH - renderW) / 2f
        val top = (UsbPrintManager.TARGET_HEIGHT - renderH) / 2f

        canvas.drawBitmap(bitmap, null, RectF(left, top, left + renderW, top + renderH), null)
        pdfDoc.finishPage(page)

        FileOutputStream(outputFile).use { pdfDoc.writeTo(it) }
        pdfDoc.close()
    }

    private fun createPdfFromText(textContent: String, outputFile: File) {
        val pdfDoc = PdfDocument()
        val pageInfo = PdfDocument.PageInfo.Builder(UsbPrintManager.TARGET_WIDTH, UsbPrintManager.TARGET_HEIGHT, 1).create()
        val page = pdfDoc.startPage(pageInfo)
        val canvas = page.canvas
        canvas.drawColor(Color.WHITE)

        val paint = Paint().apply {
            color = Color.BLACK
            textSize = 28f
            isAntiAlias = true
        }

        val lines = textContent.lines().filter { !it.startsWith("%") && it.trim().isNotEmpty() }
        var y = 60f
        for (line in lines) {
            canvas.drawText(line.take(55), 40f, y, paint)
            y += 36f
            if (y > UsbPrintManager.TARGET_HEIGHT - 40) break
        }

        pdfDoc.finishPage(page)
        FileOutputStream(outputFile).use { pdfDoc.writeTo(it) }
        pdfDoc.close()
    }

    private fun ingestPdfToLocalPrint(pdfFile: File) {
        val uri = Uri.fromFile(pdfFile)
        val bitmap = printManager.renderPdfToBitmap(uri)
        if (bitmap != null) {
            logger("[IPP] PDF converted to 816x1218 bitmap. Streaming to Rollo printer...")
            printManager.printBitmapAsync(bitmap)
        } else {
            logger("[IPP] ERROR: Local PDF rendering engine failed.")
        }
    }

    private fun findByteSequence(data: ByteArray, pattern: ByteArray): Int {
        if (pattern.isEmpty() || data.size < pattern.size) return -1
        for (i in 0..data.size - pattern.size) {
            var match = true
            for (j in pattern.indices) {
                if (data[i + j] != pattern[j]) {
                    match = false
                    break
                }
            }
            if (match) return i
        }
        return -1
    }

    fun getLocalIpAddress(): String {
        try {
            val interfaces = NetworkInterface.getNetworkInterfaces()
            while (interfaces.hasMoreElements()) {
                val networkInterface = interfaces.nextElement()
                val addresses = networkInterface.inetAddresses
                while (addresses.hasMoreElements()) {
                    val inetAddress = addresses.nextElement()
                    if (!inetAddress.isLoopbackAddress && inetAddress is Inet4Address) {
                        return inetAddress.hostAddress ?: "127.0.0.1"
                    }
                }
            }
        } catch (_: Exception) {}
        return "127.0.0.1"
    }
}

// IPP Binary Response Writer Helper Class
private class IppResponseWriter(private val requestId: ByteArray, private val statusCode: Short = 0x0000) {
    private val baos = ByteArrayOutputStream()

    init {
        // IPP Version 2.0 (0x02 0x00)
        baos.write(byteArrayOf(0x02, 0x00))
        // Status code successful-ok (0x0000)
        baos.write((statusCode.toInt() shr 8) and 0xFF)
        baos.write(statusCode.toInt() and 0xFF)
        // Request ID (4 bytes)
        baos.write(requestId)
    }

    fun startGroup(tag: Byte) {
        baos.write(tag.toInt())
    }

    fun addAttribute(valueTag: Byte, name: String, value: String) {
        val nameBytes = name.toByteArray(Charsets.UTF_8)
        val valueBytes = value.toByteArray(Charsets.UTF_8)

        baos.write(valueTag.toInt())
        baos.write((nameBytes.size shr 8) and 0xFF)
        baos.write(nameBytes.size and 0xFF)
        baos.write(nameBytes)
        baos.write((valueBytes.size shr 8) and 0xFF)
        baos.write(valueBytes.size and 0xFF)
        baos.write(valueBytes)
    }

    fun addIntAttribute(valueTag: Byte, name: String, value: Int) {
        val nameBytes = name.toByteArray(Charsets.UTF_8)

        baos.write(valueTag.toInt())
        baos.write((nameBytes.size shr 8) and 0xFF)
        baos.write(nameBytes.size and 0xFF)
        baos.write(nameBytes)
        baos.write(0)
        baos.write(4)
        baos.write((value shr 24) and 0xFF)
        baos.write((value shr 16) and 0xFF)
        baos.write((value shr 8) and 0xFF)
        baos.write(value and 0xFF)
    }

    fun addBooleanAttribute(name: String, value: Boolean) {
        val nameBytes = name.toByteArray(Charsets.UTF_8)

        baos.write(0x22) // boolean tag
        baos.write((nameBytes.size shr 8) and 0xFF)
        baos.write(nameBytes.size and 0xFF)
        baos.write(nameBytes)
        baos.write(0)
        baos.write(1)
        baos.write(if (value) 1 else 0)
    }

    fun addResolutionAttribute(name: String, xRes: Int, yRes: Int, units: Byte = 0x03) {
        val nameBytes = name.toByteArray(Charsets.UTF_8)

        baos.write(0x32) // resolution tag
        baos.write((nameBytes.size shr 8) and 0xFF)
        baos.write(nameBytes.size and 0xFF)
        baos.write(nameBytes)
        baos.write(0)
        baos.write(9)
        baos.write((xRes shr 24) and 0xFF)
        baos.write((xRes shr 16) and 0xFF)
        baos.write((xRes shr 8) and 0xFF)
        baos.write(xRes and 0xFF)
        baos.write((yRes shr 24) and 0xFF)
        baos.write((yRes shr 16) and 0xFF)
        baos.write((yRes shr 8) and 0xFF)
        baos.write(yRes and 0xFF)
        baos.write(units.toInt())
    }

    fun build(): ByteArray {
        baos.write(0x03) // end-of-attributes-tag
        return baos.toByteArray()
    }
}
