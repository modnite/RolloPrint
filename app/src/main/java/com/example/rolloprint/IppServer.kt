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
import com.hp.jipp.model.MediaCol
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
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger

class IppServer(
    private val context: Context,
    private val printManager: UsbPrintManager,
    private val jobQueueManager: JobQueueManager,
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

                // 1. Read HTTP Request Headers
                val headerBytes = readHttpHeaderBytes(input)
                val headerText = String(headerBytes, Charsets.US_ASCII)

                if (headerText.isEmpty()) {
                    socket.close()
                    return@execute
                }

                // Handle Expect: 100-continue header for CUPS / Windows IPP
                if (headerText.contains("Expect: 100-continue", ignoreCase = true)) {
                    val continueResp = "HTTP/1.1 100 Continue\r\n\r\n".toByteArray(Charsets.US_ASCII)
                    socket.getOutputStream().write(continueResp)
                    socket.getOutputStream().flush()
                }

                // 2. Read HTTP Body Payload
                val contentLength = parseContentLength(headerText)
                val isChunked = headerText.contains("Transfer-Encoding: chunked", ignoreCase = true)

                val bodyData = if (isChunked) {
                    decodeChunkedBody(input)
                } else if (contentLength > 0) {
                    val buf = ByteArray(contentLength)
                    var read = 0
                    while (read < contentLength) {
                        val r = input.read(buf, read, contentLength - read)
                        if (r == -1) break
                        read += r
                    }
                    buf
                } else {
                    ByteArray(0)
                }

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
                    Operation.createJob -> {
                        val jobId = jobIdCounter.getAndIncrement()
                        logger("[IPP] Create-Job request #$jobId (req-id=$requestId, v=$version) from $clientIp")
                        sendCreateJobResponse(socket, version, requestId, jobId)
                    }
                    Operation.sendDocument, Operation.printJob -> {
                        val jobId = jobIdCounter.getAndIncrement()
                        logger("[IPP] Print-Job / Send-Document #$jobId received (${bodyData.size} bytes, req-id=$requestId, v=$version) from $clientIp")

                        sendPrintJobResponse(socket, version, requestId, jobId)

                        val docData = extractDocumentBytes(bodyData)
                        if (docData.isNotEmpty()) {
                            processIncomingDocumentPayload(docData, jobId, clientIp)
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

    private fun readHttpHeaderBytes(input: InputStream): ByteArray {
        val baos = ByteArrayOutputStream()
        var prev3 = -1
        var prev2 = -1
        var prev1 = -1

        while (isRunning) {
            val b = input.read()
            if (b == -1) break
            baos.write(b)
            if (prev3 == 0x0D && prev2 == 0x0A && prev1 == 0x0D && b == 0x0A) { // \r\n\r\n
                break
            }
            prev3 = prev2
            prev2 = prev1
            prev1 = b
        }
        return baos.toByteArray()
    }

    private fun decodeChunkedBody(input: InputStream): ByteArray {
        val baos = ByteArrayOutputStream()
        try {
            while (isRunning) {
                val line = readAsciiLine(input)
                val chunkSizeHex = line.substringBefore(";").trim()
                val chunkSize = chunkSizeHex.toIntOrNull(16) ?: 0
                if (chunkSize <= 0) {
                    readAsciiLine(input) // trailing \r\n
                    break
                }
                val chunkBuf = ByteArray(chunkSize)
                var totalRead = 0
                while (totalRead < chunkSize && isRunning) {
                    val r = input.read(chunkBuf, totalRead, chunkSize - totalRead)
                    if (r == -1) break
                    totalRead += r
                }
                baos.write(chunkBuf, 0, totalRead)
                readAsciiLine(input)
            }
        } catch (_: Exception) {}
        return baos.toByteArray()
    }

    private fun readAsciiLine(input: InputStream): String {
        val baos = ByteArrayOutputStream()
        var prev = -1
        while (isRunning) {
            val b = input.read()
            if (b == -1) break
            if (prev == 0x0D && b == 0x0A) {
                val bytes = baos.toByteArray()
                return String(bytes, 0, Math.max(0, bytes.size - 1), Charsets.US_ASCII)
            }
            baos.write(b)
            prev = b
        }
        return String(baos.toByteArray(), Charsets.US_ASCII).trim()
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

    private fun extractDocumentBytes(data: ByteArray): ByteArray {
        val pdfHeader = "%PDF-".toByteArray()
        val pdfStart = findByteSequence(data, pdfHeader)
        if (pdfStart != -1) {
            return data.copyOfRange(pdfStart, data.size)
        }

        val pngHeader = byteArrayOf(0x89.toByte(), 'P'.code.toByte(), 'N'.code.toByte(), 'G'.code.toByte())
        val pngStart = findByteSequence(data, pngHeader)
        if (pngStart != -1) return data.copyOfRange(pngStart, data.size)

        val jpgHeader = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte())
        val jpgStart = findByteSequence(data, jpgHeader)
        if (jpgStart != -1) return data.copyOfRange(jpgStart, data.size)

        for (i in 8 until data.size) {
            if (data[i] == 0x03.toByte() && i + 1 < data.size) {
                return data.copyOfRange(i + 1, data.size)
            }
        }

        return data
    }

    private fun sendGetPrinterAttributesResponse(socket: Socket, version: Int, requestId: Int) {
        val printerUri = URI("ipp://${getLocalIpAddress()}:$PORT/ipp/print")
        val printerMoreInfo = URI("http://${getLocalIpAddress()}:$PORT/")
        val printerUuid = URI("urn:uuid:e5b02130-1c4b-483b-9a99-000000000001")

        val states = printManager.getCurrentHardwareState()
        val printerState = if (states.contains(HardwareState.HEAD_OPEN) || states.contains(HardwareState.OUT_OF_PAPER)) {
            PrinterState.stopped
        } else {
            PrinterState.idle
        }

        val stateReasons = mutableListOf<String>()
        if (states.contains(HardwareState.HEAD_OPEN)) {
            stateReasons.add("door-open-error")
        }
        if (states.contains(HardwareState.OUT_OF_PAPER)) {
            stateReasons.add("media-empty-error")
        }
        if (states.contains(HardwareState.PAUSED)) {
            stateReasons.add("paused")
        }
        if (stateReasons.isEmpty()) {
            stateReasons.add("none")
        }

        val opGroup = MutableAttributeGroup(
            Tag.operationAttributes,
            listOf(
                Types.attributesCharset.of("utf-8"),
                Types.attributesNaturalLanguage.of("en")
            )
        )

        val mediaDbSize = MediaColDatabase.MediaSize(
            xDimension = IntOrIntRange(10160),
            yDimension = IntOrIntRange(15240)
        )
        val mediaColDatabase = MediaColDatabase(
            mediaSize = mediaDbSize,
            mediaTopMargin = 0,
            mediaBottomMargin = 0,
            mediaLeftMargin = 0,
            mediaRightMargin = 0
        )

        val mediaDefSize = MediaCol.MediaSize(
            xDimension = 10160,
            yDimension = 15240
        )
        val mediaColDefault = MediaCol(
            mediaSize = mediaDefSize,
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
                Types.printerState.of(printerState),
                Types.printerStateReasons.of(stateReasons),
                Types.printerIsAcceptingJobs.of(true),
                Types.queuedJobCount.of(jobQueueManager.getQueueSize()),
                Types.ippVersionsSupported.of("1.1", "2.0"),
                Types.operationsSupported.of(
                    Operation.printJob,
                    Operation.validateJob,
                    Operation.createJob,
                    Operation.sendDocument,
                    Operation.getJobAttributes,
                    Operation.getPrinterAttributes
                ),
                Types.compressionSupported.of("none"),
                Types.documentFormatSupported.of(
                    "image/pwg-raster",
                    "application/pdf",
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
                Types.mediaColDatabase.of(mediaColDatabase),
                Types.mediaColDefault.of(mediaColDefault),
                Types.mediaSupported.of("custom_4x6in", "na_letter_8.5x11in", "iso_a4_210x297mm", "oe_4x6in_4x6in"),
                Types.mediaReady.of("custom_4x6in"),
                Types.mediaDefault.of("custom_4x6in"),
                Types.mediaTypeSupported.of("labels", "stationery"),
                Types.pdfVersionsSupported.of("iso-19005-1_2005", "1.7", "1.6", "1.5", "1.4"),
                Types.printerDeviceId.of("MFG:Rollo;MDL:X1038;CMD:TSPL;"),
                Types.printerMoreInfo.of(printerMoreInfo),
                Types.printerUuid.of(printerUuid),
                Types.printerUriSupported.of(printerUri),
                Types.uriAuthenticationSupported.of("none"),
                Types.uriSecuritySupported.of("none"),
                Types.printerName.of("Rollo Printer"),
                Types.printerInfo.of("Rollo Thermal Printer 4x6"),
                Types.printerMakeAndModel.of("Rollo X1038 Thermal Printer"),
                Types.pdlOverrideSupported.of("not-attempted")
            )
        )

        val responsePacket = IppPacket(Status.successfulOk, requestId, opGroup, printerGroup)
        sendIppResponsePacket(socket, version, responsePacket)
    }

    private fun sendCreateJobResponse(socket: Socket, version: Int, requestId: Int, jobId: Int) {
        val printerUri = URI("ipp://${getLocalIpAddress()}:$PORT/ipp/print")
        val jobUri = URI("ipp://${getLocalIpAddress()}:$PORT/ipp/print/job-$jobId")

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
                Types.jobPrinterUri.of(printerUri),
                Types.jobState.of(JobState.pending),
                Types.jobStateReasons.of("job-incoming")
            )
        )

        val responsePacket = IppPacket(Status.successfulOk, requestId, opGroup, jobGroup)
        sendIppResponsePacket(socket, version, responsePacket)
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
                Types.jobId.of(1),
                Types.jobState.of(JobState.completed),
                Types.jobStateReasons.of("job-completed-successfully")
            )
        )

        val responsePacket = IppPacket(Status.successfulOk, requestId, opGroup, jobGroup)
        sendIppResponsePacket(socket, version, responsePacket)
    }

    private fun sendPrintJobResponse(socket: Socket, version: Int, requestId: Int, jobId: Int) {
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
                Types.jobState.of(JobState.processing),
                Types.jobStateReasons.of("job-printing")
            )
        )

        val responsePacket = IppPacket(Status.successfulOk, requestId, opGroup, jobGroup)
        sendIppResponsePacket(socket, version, responsePacket)
    }

    private fun sendSimpleIppResponse(socket: Socket, version: Int, requestId: Int, status: Status) {
        val opGroup = MutableAttributeGroup(
            Tag.operationAttributes,
            listOf(
                Types.attributesCharset.of("utf-8"),
                Types.attributesNaturalLanguage.of("en")
            )
        )
        val responsePacket = IppPacket(status, requestId, opGroup)
        sendIppResponsePacket(socket, version, responsePacket)
    }

    private fun sendIppResponsePacket(socket: Socket, version: Int, packet: IppPacket) {
        val packetBaos = ByteArrayOutputStream()
        val ippOutputStream = IppOutputStream(packetBaos)
        ippOutputStream.write(packet.copy(versionNumber = version))

        val ippBytes = packetBaos.toByteArray()
        val httpHeader = "HTTP/1.1 200 OK\r\n" +
                "Content-Type: application/ipp\r\n" +
                "Content-Length: ${ippBytes.size}\r\n" +
                "Connection: close\r\n\r\n"

        val out = socket.getOutputStream()
        out.write(httpHeader.toByteArray(Charsets.US_ASCII))
        out.write(ippBytes)
        out.flush()
    }

    private fun processIncomingDocumentPayload(data: ByteArray, jobId: Int, clientIp: String) {
        try {
            val textContent = String(data, 0, Math.min(data.size, 200), Charsets.US_ASCII)

            if (textContent.contains("#PDF-BANNER", ignoreCase = true)) {
                logger("[IPP] Received CUPS Test Page Banner. Rendering RolloPrint 4x6 Test Label...")
                val testPdf = createCupsTestPagePdf()
                if (testPdf != null) {
                    val bitmap = printManager.renderPdfToBitmap(testPdf)
                    if (bitmap != null) {
                        logger("[IPP] Adding Test Page Job #$jobId (from $clientIp) to Print Queue...")
                        jobQueueManager.addJob(bitmap, "Test Page from $clientIp")
                    }
                }
                return
            }

            val tempFile = File(context.cacheDir, "temp_incoming_$jobId.pdf")
            FileOutputStream(tempFile).use { it.write(data) }

            val uri = Uri.fromFile(tempFile)
            val bitmap = printManager.renderPdfToBitmap(uri)

            if (bitmap != null) {
                val prefs = context.getSharedPreferences("rollo_prefs", Context.MODE_PRIVATE)
                val showNetworkPreview = prefs.getBoolean("PREF_NETWORK_PREVIEW", false)

                if (showNetworkPreview && onNetworkBitmapRendered != null) {
                    logger("[IPP] Displaying Print Preview Dialog for Network Job #$jobId...")
                    onNetworkBitmapRendered.invoke(bitmap)
                } else {
                    logger("[IPP] Adding Network Job #$jobId (from $clientIp) to Print Queue...")
                    jobQueueManager.addJob(bitmap, "Network Job #$jobId from $clientIp")
                }
            } else {
                logger("[IPP] ERROR: Failed to render bitmap for Network Job #$jobId")
            }
        } catch (e: Exception) {
            logger("[IPP] ERROR processing payload for Job #$jobId: ${e.message}")
        }
    }

    private fun createCupsTestPagePdf(): Uri? {
        return try {
            val pdfDoc = PdfDocument()
            val pageInfo = PdfDocument.PageInfo.Builder(288, 432, 1).create() // 4x6 inches at 72 points
            val page = pdfDoc.startPage(pageInfo)
            val canvas = page.canvas

            canvas.drawColor(Color.WHITE)

            val borderPaint = Paint().apply {
                color = Color.BLACK
                style = Paint.Style.STROKE
                strokeWidth = 3f
            }
            canvas.drawRect(RectF(10f, 10f, 278f, 422f), borderPaint)

            val titlePaint = Paint().apply {
                color = Color.BLACK
                textSize = 20f
                isFakeBoldText = true
                textAlign = Paint.Align.CENTER
            }
            canvas.drawText("RolloPrint Test Page", 144f, 50f, titlePaint)

            val bodyPaint = Paint().apply {
                color = Color.BLACK
                textSize = 12f
            }

            val timestamp = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date())
            canvas.drawText("Status: Printer Active & Ready", 25f, 100f, bodyPaint)
            canvas.drawText("Resolution: 203 DPI Thermal", 25f, 130f, bodyPaint)
            canvas.drawText("Protocol: IPP Everywhere (Port 8631)", 25f, 160f, bodyPaint)
            canvas.drawText("Timestamp: $timestamp", 25f, 190f, bodyPaint)

            pdfDoc.finishPage(page)

            val outFile = File(context.cacheDir, "cups_test_page.pdf")
            FileOutputStream(outFile).use { pdfDoc.writeTo(it) }
            pdfDoc.close()

            Uri.fromFile(outFile)
        } catch (e: Exception) {
            logger("[IPP] Test Page Render Error: ${e.message}")
            null
        }
    }

    private fun sendHttpResponse(socket: Socket, code: Int, message: String) {
        val resp = "HTTP/1.1 $code $message\r\nContent-Length: 0\r\nConnection: close\r\n\r\n"
        socket.getOutputStream().write(resp.toByteArray(Charsets.US_ASCII))
        socket.getOutputStream().flush()
    }

    private fun findByteSequence(data: ByteArray, seq: ByteArray): Int {
        for (i in 0..data.size - seq.size) {
            var match = true
            for (j in seq.indices) {
                if (data[i + j] != seq[j]) {
                    match = false
                    break
                }
            }
            if (match) return i
        }
        return -1
    }

    private fun getLocalIpAddress(): String {
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
