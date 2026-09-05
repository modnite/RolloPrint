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
import com.hp.jipp.encoding.AttributeGroup
import com.hp.jipp.encoding.IntOrIntRange
import com.hp.jipp.encoding.IppInputStream
import com.hp.jipp.encoding.IppOutputStream
import com.hp.jipp.encoding.IppPacket
import com.hp.jipp.encoding.MutableAttributeGroup
import com.hp.jipp.encoding.Resolution
import com.hp.jipp.encoding.ResolutionUnit
import com.hp.jipp.encoding.Tag
import com.hp.jipp.model.Finishing
import com.hp.jipp.model.JobState
import com.hp.jipp.model.MediaColDatabase
import com.hp.jipp.model.Operation
import com.hp.jipp.model.Orientation
import com.hp.jipp.model.PrintQuality
import com.hp.jipp.model.PrinterState
import com.hp.jipp.model.PrinterStateReason
import com.hp.jipp.model.Status
import com.hp.jipp.model.Types
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.net.Inet4Address
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketTimeoutException
import java.net.URI
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger

class IppServer(
    private val context: Context,
    private val printManager: UsbPrintManager,
    private val logger: (String) -> Unit,
    private val onStatusChanged: (Boolean, String?) -> Unit,
    private val onNetworkBitmapRendered: ((Bitmap) -> Unit)? = null
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
                socket.soTimeout = 5000
                val input = socket.getInputStream()
                val baos = ByteArrayOutputStream()
                val buffer = ByteArray(4096)
                var bytesRead: Int

                // 1. Read HTTP Request Headers
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

                // Handle Expect: 100-continue if sent by client
                if (headerStr.contains("Expect: 100-continue", ignoreCase = true)) {
                    val continueResponse = "HTTP/1.1 100 Continue\r\n\r\n".toByteArray(Charsets.US_ASCII)
                    socket.getOutputStream().write(continueResponse)
                    socket.getOutputStream().flush()
                }

                val contentLength = parseContentLength(headerStr)

                // 2. Read HTTP Body
                val bodyBaos = ByteArrayOutputStream()
                val initialBodyBytes = allData.copyOfRange(headerEnd, allData.size)
                bodyBaos.write(initialBodyBytes)

                while (contentLength > 0 && bodyBaos.size() < contentLength && isRunning) {
                    bytesRead = input.read(buffer)
                    if (bytesRead == -1) break
                    bodyBaos.write(buffer, 0, bytesRead)
                }

                val bodyData = bodyBaos.toByteArray()
                if (bodyData.isEmpty()) {
                    sendHttpResponse(socket, 400, "Bad Request")
                    socket.close()
                    return@execute
                }

                // 3. Parse IPP Request Packet using HP jipp-core
                val ippInputStream = IppInputStream(ByteArrayInputStream(bodyData))
                val ippRequestPacket = ippInputStream.readPacket()

                val version = ippRequestPacket.versionNumber
                val operation = ippRequestPacket.operation
                val requestId = ippRequestPacket.requestId

                when (operation) {
                    Operation.getPrinterAttributes -> {
                        logger("[IPP] Get-Printer-Attributes request (req-id=$requestId, v=$version) from $clientIp")
                        sendGetPrinterAttributesResponse(socket, version, requestId)
                    }
                    Operation.validateJob -> {
                        logger("[IPP] Validate-Job request (req-id=$requestId, v=$version) from $clientIp")
                        sendSimpleIppResponse(socket, version, requestId, Status.successfulOk)
                    }
                    Operation.printJob, Operation.sendDocument -> {
                        val jobId = jobIdCounter.getAndIncrement()
                        logger("[IPP] Print-Job #$jobId received (${bodyData.size} bytes, req-id=$requestId, v=$version) from $clientIp")

                        sendPrintJobResponse(socket, version, requestId, jobId)

                        // Document payload follows the IPP packet
                        val docStart = findByteSequence(bodyData, "%PDF-".toByteArray())
                        val docData = if (docStart != -1) {
                            bodyData.copyOfRange(docStart, bodyData.size)
                        } else {
                            bodyData
                        }

                        if (docData.isNotEmpty()) {
                            processIncomingDocumentPayload(docData, jobId)
                        }
                    }
                    Operation.getJobAttributes -> {
                        logger("[IPP] Get-Job-Attributes request (req-id=$requestId, v=$version) from $clientIp")
                        sendGetJobAttributesResponse(socket, version, requestId)
                    }
                    else -> {
                        logger("[IPP] Operation $operation requested (req-id=$requestId, v=$version) from $clientIp")
                        sendSimpleIppResponse(socket, version, requestId, Status.successfulOk)
                    }
                }

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

    private fun sendGetPrinterAttributesResponse(socket: Socket, version: Int, requestId: Int) {
        val printerUri = URI("ipp://${getLocalIpAddress()}:$PORT/ipp/print")
        val printerMoreInfo = URI("http://${getLocalIpAddress()}:$PORT/")
        val printerUuid = URI("urn:uuid:e5b02130-1c4b-9a99-000000000001")

        val opGroup = MutableAttributeGroup(
            Tag.operationAttributes,
            listOf(
                Types.attributesCharset.of("utf-8"),
                Types.attributesNaturalLanguage.of("en")
            )
        )

        val mediaSize = MediaColDatabase.MediaSize(
            xDimension = IntOrIntRange(10160),
            yDimension = IntOrIntRange(15240)
        )
        val mediaColDatabase = MediaColDatabase(
            mediaSize = mediaSize,
            mediaTopMargin = 0,
            mediaBottomMargin = 0,
            mediaLeftMargin = 0,
            mediaRightMargin = 0
        )

        val printerGroup = MutableAttributeGroup(
            Tag.printerAttributes,
            listOf(
                Types.charsetConfigured.of("utf-8"),
                Types.charsetSupported.of("utf-8"),
                Types.naturalLanguageConfigured.of("en"),
                Types.generatedNaturalLanguageSupported.of("en"),
                Types.printerState.of(PrinterState.idle),
                Types.printerStateReasons.of(PrinterStateReason.none),
                Types.printerIsAcceptingJobs.of(true),
                Types.queuedJobCount.of(0),
                Types.ippVersionsSupported.of("1.1", "2.0"),
                Types.operationsSupported.of(
                    Operation.printJob,
                    Operation.validateJob,
                    Operation.getJobAttributes,
                    Operation.getPrinterAttributes
                ),
                Types.documentFormatSupported.of(
                    "application/pdf",
                    "image/pwg-raster",
                    "image/png",
                    "image/jpeg",
                    "application/octet-stream"
                ),
                Types.documentFormatDefault.of("application/pdf"),
                Types.pwgRasterDocumentResolutionSupported.of(Resolution(203, 203, ResolutionUnit.dotsPerInch)),
                Types.pwgRasterDocumentSheetBack.of("normal"),
                Types.pwgRasterDocumentTypeSupported.of("black_1"),
                Types.copiesDefault.of(1),
                Types.copiesSupported.of(IntRange(1, 99)),
                Types.orientationRequestedDefault.of(Orientation.portrait),
                Types.orientationRequestedSupported.of(Orientation.portrait),
                Types.printQualityDefault.of(PrintQuality.normal),
                Types.printQualitySupported.of(PrintQuality.normal),
                Types.sidesDefault.of("one-sided"),
                Types.sidesSupported.of("one-sided"),
                Types.finishingsDefault.of(Finishing.none),
                Types.finishingsSupported.of(Finishing.none),
                Types.outputBinDefault.of("face-down"),
                Types.outputBinSupported.of("face-down"),
                Types.mediaSupported.of("oe_4x6-label_4x6in", "na_index-4x6_4x6in", "custom_min_4x6in"),
                Types.mediaDefault.of("oe_4x6-label_4x6in"),
                Types.mediaReady.of("oe_4x6-label_4x6in"),
                Types.mediaColDatabase.of(mediaColDatabase),
                Types.printerResolutionSupported.of(Resolution(203, 203, ResolutionUnit.dotsPerInch)),
                Types.printerResolutionDefault.of(Resolution(203, 203, ResolutionUnit.dotsPerInch)),
                Types.printerName.of("Rollo Printer"),
                Types.printerInfo.of("Rollo Thermal Printer 4x6"),
                Types.printerLocation.of("Local Network"),
                Types.printerMakeAndModel.of("Rollo Thermal Printer 4x6"),
                Types.printerMoreInfo.of(printerMoreInfo),
                Types.printerUuid.of(printerUuid),
                Types.printerUriSupported.of(printerUri),
                Types.uriAuthenticationSupported.of("none"),
                Types.uriSecuritySupported.of("none"),
                Types.pdlOverrideSupported.of("not-attempted"),
                Types.colorSupported.of(false),
                Types.pagesPerMinute.of(60),
                Types.printerUpTime.of((System.currentTimeMillis() / 1000).toInt())
            )
        )

        val responsePacket = IppPacket(
            versionNumber = version,
            code = Status.successfulOk.code,
            requestId = requestId,
            attributeGroups = listOf(opGroup, printerGroup)
        )
        sendIppPacketResponse(socket, responsePacket)
    }

    private fun sendPrintJobResponse(socket: Socket, version: Int, requestId: Int, jobId: Int) {
        val jobUri = URI("ipp://${getLocalIpAddress()}:$PORT/ipp/print/$jobId")

        val opGroup = MutableAttributeGroup(
            Tag.operationAttributes,
            listOf(
                Types.attributesCharset.of("utf-8"),
                Types.attributesNaturalLanguage.of("en")
            )
        )

        val jobGroup = MutableAttributeGroup(
            Tag.jobAttributes,
            listOf(
                Types.jobId.of(jobId),
                Types.jobUri.of(jobUri),
                Types.jobState.of(JobState.completed)
            )
        )

        val responsePacket = IppPacket(
            versionNumber = version,
            code = Status.successfulOk.code,
            requestId = requestId,
            attributeGroups = listOf(opGroup, jobGroup)
        )
        sendIppPacketResponse(socket, responsePacket)
    }

    private fun sendGetJobAttributesResponse(socket: Socket, version: Int, requestId: Int) {
        val opGroup = MutableAttributeGroup(
            Tag.operationAttributes,
            listOf(
                Types.attributesCharset.of("utf-8"),
                Types.attributesNaturalLanguage.of("en")
            )
        )

        val jobGroup = MutableAttributeGroup(
            Tag.jobAttributes,
            listOf(
                Types.jobState.of(JobState.completed)
            )
        )

        val responsePacket = IppPacket(
            versionNumber = version,
            code = Status.successfulOk.code,
            requestId = requestId,
            attributeGroups = listOf(opGroup, jobGroup)
        )
        sendIppPacketResponse(socket, responsePacket)
    }

    private fun sendSimpleIppResponse(socket: Socket, version: Int, requestId: Int, status: Status) {
        val opGroup = MutableAttributeGroup(
            Tag.operationAttributes,
            listOf(
                Types.attributesCharset.of("utf-8"),
                Types.attributesNaturalLanguage.of("en")
            )
        )

        val responsePacket = IppPacket(
            versionNumber = version,
            code = status.code,
            requestId = requestId,
            attributeGroups = listOf(opGroup)
        )
        sendIppPacketResponse(socket, responsePacket)
    }

    private fun sendIppPacketResponse(socket: Socket, packet: IppPacket) {
        try {
            val baos = ByteArrayOutputStream()
            val ippOutputStream = IppOutputStream(baos)
            ippOutputStream.write(packet)
            val responseBytes = baos.toByteArray()

            val output = socket.getOutputStream()
            val header = "HTTP/1.1 200 OK\r\n" +
                    "Content-Type: application/ipp\r\n" +
                    "Content-Length: ${responseBytes.size}\r\n" +
                    "Connection: close\r\n\r\n"
            output.write(header.toByteArray(Charsets.US_ASCII))
            output.write(responseBytes)
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
            logger("[IPP] PDF converted to 816x1218 bitmap.")
            if (onNetworkBitmapRendered != null) {
                logger("[IPP] Displaying Print Preview Dialog for Network Job...")
                onNetworkBitmapRendered.invoke(bitmap)
            } else {
                logger("[IPP] Streaming bitmap directly to Rollo printer...")
                printManager.printBitmapAsync(bitmap)
            }
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
