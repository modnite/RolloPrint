package com.example.rolloprint

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.net.Uri
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
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
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors

class NetworkPrintServer(
    private val context: Context,
    private val printManager: UsbPrintManager,
    private val logger: (String) -> Unit,
    private val onStatusChanged: (Boolean, String?) -> Unit
) {
    private val serverSockets = CopyOnWriteArrayList<ServerSocket>()
    @Volatile
    private var isRunning = false
    private val serverExecutor = Executors.newCachedThreadPool()
    private var nsdManager: NsdManager? = null
    private var registrationListener: NsdManager.RegistrationListener? = null

    companion object {
        val PORTS = intArrayOf(9100, 631, 515)
        const val SERVICE_TYPE = "_pdl-datastream._tcp."
        const val SERVICE_NAME = "Rollo Thermal Printer"
    }

    fun start() {
        if (isRunning) return
        isRunning = true
        val ipAddress = getLocalIpAddress()
        logger("Print Server active on $ipAddress (Ports: 9100, 631, 515)")
        onStatusChanged(true, ipAddress)
        registerNsdService()

        for (port in PORTS) {
            serverExecutor.execute {
                try {
                    val socket = ServerSocket().apply {
                        reuseAddress = true
                        bind(InetSocketAddress(port))
                    }
                    serverSockets.add(socket)

                    while (isRunning && !socket.isClosed) {
                        try {
                            val clientSocket = socket.accept() ?: break
                            handleClientSocket(clientSocket, port)
                        } catch (e: Exception) {
                            if (isRunning && !socket.isClosed) {
                                logger("Server Port $port Error: ${e.message}")
                            }
                        }
                    }
                } catch (e: Exception) {
                    if (isRunning) {
                        logger("Failed to bind Port $port: ${e.message}")
                    }
                }
            }
        }
    }

    fun stop() {
        isRunning = false
        unregisterNsdService()
        for (ss in serverSockets) {
            try {
                ss.close()
            } catch (_: Exception) {}
        }
        serverSockets.clear()
        logger("Print Server Stopped.")
        onStatusChanged(false, null)
    }

    private fun registerNsdService() {
        try {
            nsdManager = context.getSystemService(Context.NSD_SERVICE) as NsdManager
            val serviceInfo = NsdServiceInfo().apply {
                serviceName = SERVICE_NAME
                serviceType = SERVICE_TYPE
                port = 9100
                setAttribute("txtvers", "1")
                setAttribute("ty", "Rollo X1038")
                setAttribute("product", "(Rollo X1038)")
                setAttribute("pdl", "application/pdf,image/png,image/jpeg,application/octet-stream")
                setAttribute("rp", "raw")
                setAttribute("note", "RolloPrint Server")
                setAttribute("qtotal", "1")
                setAttribute("usb_MFG", "Rollo")
                setAttribute("usb_MDL", "X1038")
            }

            registrationListener = object : NsdManager.RegistrationListener {
                override fun onServiceRegistered(serviceInfo: NsdServiceInfo) {
                    logger("mDNS Service Discovery Registered: ${serviceInfo.serviceName}")
                }
                override fun onRegistrationFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {
                    logger("mDNS Registration Warning: $errorCode")
                }
                override fun onServiceUnregistered(serviceInfo: NsdServiceInfo) {}
                override fun onUnregistrationFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {}
            }

            nsdManager?.registerService(serviceInfo, NsdManager.PROTOCOL_DNS_SD, registrationListener)
        } catch (e: Exception) {
            logger("mDNS Setup Notice: ${e.message}")
        }
    }

    private fun unregisterNsdService() {
        try {
            if (registrationListener != null) {
                nsdManager?.unregisterService(registrationListener)
                registrationListener = null
            }
        } catch (_: Exception) {}
    }

    private fun handleClientSocket(socket: Socket, port: Int) {
        val clientIp = socket.inetAddress?.hostAddress ?: "Unknown"
        logger("Incoming job connection from $clientIp on port $port")
        Executors.newSingleThreadExecutor().execute {
            try {
                socket.soTimeout = 1500

                val input: InputStream = socket.getInputStream()
                val baos = ByteArrayOutputStream()
                val buffer = ByteArray(4096)
                var bytesRead: Int

                try {
                    while (isRunning) {
                        bytesRead = input.read(buffer)
                        if (bytesRead == -1) break
                        baos.write(buffer, 0, bytesRead)

                        val currentData = baos.toByteArray()
                        if (isCompleteDocument(currentData)) {
                            logger("Complete label document signature detected (${currentData.size} bytes)")
                            break
                        }
                    }
                } catch (_: SocketTimeoutException) {
                    // Socket timeout triggers when client finishes streaming payload
                }

                val jobData = baos.toByteArray()

                // If HTTP request (IPP/CUPS), send HTTP 200 OK back to client to unblock macOS CUPS queue
                val isHttp = jobData.size > 4 && String(jobData.take(4).toByteArray(), Charsets.US_ASCII).startsWith("POST")
                if (isHttp) {
                    try {
                        val output: OutputStream = socket.getOutputStream()
                        val response = "HTTP/1.1 200 OK\r\nContent-Type: application/ipp\r\nContent-Length: 0\r\n\r\n"
                        output.write(response.toByteArray())
                        output.flush()
                    } catch (_: Exception) {}
                }

                try {
                    socket.close()
                } catch (_: Exception) {}

                if (jobData.isEmpty()) {
                    logger("Received empty job payload from $clientIp")
                    return@execute
                }

                logger("Processing network print job (${jobData.size} bytes) from $clientIp...")
                processIncomingJobData(jobData, clientIp)
            } catch (e: Exception) {
                logger("Error processing network job from $clientIp: ${e.message}")
            }
        }
    }

    private fun isCompleteDocument(data: ByteArray): Boolean {
        if (data.size < 10) return false
        val checkLen = Math.min(data.size, 100)
        val tailBytes = data.copyOfRange(data.size - checkLen, data.size)
        val tailStr = String(tailBytes, Charsets.US_ASCII)

        if (tailStr.contains("%%EOF")) return true
        if (tailStr.contains("IEND")) return true
        if ((data[data.size - 2].toInt() and 0xFF) == 0xFF && (data[data.size - 1].toInt() and 0xFF) == 0xD9) return true

        return false
    }

    private fun processIncomingJobData(data: ByteArray, clientIp: String) {
        // 1. Search for PDF Header (%PDF-) anywhere in stream (extracts PDF even if wrapped in HTTP IPP headers!)
        val pdfHeader = "%PDF-".toByteArray()
        val pdfStart = findByteSequence(data, pdfHeader)
        if (pdfStart != -1) {
            val pdfData = data.copyOfRange(pdfStart, data.size)
            logger("Extracted PDF label (${pdfData.size} bytes) from $clientIp")
            processPdfJob(pdfData)
            return
        }

        // 2. Search for PNG or JPEG image header anywhere in stream
        val pngHeader = byteArrayOf(0x89.toByte(), 'P'.code.toByte(), 'N'.code.toByte(), 'G'.code.toByte())
        val pngStart = findByteSequence(data, pngHeader)
        if (pngStart != -1) {
            val pngData = data.copyOfRange(pngStart, data.size)
            logger("Extracted PNG image (${pngData.size} bytes) from $clientIp")
            processImageJob(pngData)
            return
        }

        val jpgHeader = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte())
        val jpgStart = findByteSequence(data, jpgHeader)
        if (jpgStart != -1) {
            val jpgData = data.copyOfRange(jpgStart, data.size)
            logger("Extracted JPEG image (${jpgData.size} bytes) from $clientIp")
            processImageJob(jpgData)
            return
        }

        // 3. Fallback for raw text / ASCII / PostScript label
        if (isAsciiText(data)) {
            logger("Extracted Text/PostScript label from $clientIp")
            val textContent = String(data, Charsets.UTF_8)
            // Filter out HTTP headers if present
            val cleanText = if (textContent.contains("\r\n\r\n")) textContent.substringAfter("\r\n\r\n") else textContent
            processTextJob(cleanText)
            return
        }

        logger("REJECTED: Job from $clientIp is incompatible with Rollo 4x6 thermal printer.")
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

    private fun processPdfJob(pdfBytes: ByteArray) {
        val tempFile = File.createTempFile("net_job_", ".pdf", context.cacheDir)
        FileOutputStream(tempFile).use { it.write(pdfBytes) }
        val uri = Uri.fromFile(tempFile)

        val bitmap = printManager.renderPdfToBitmap(uri)
        tempFile.delete()

        if (bitmap != null) {
            logger("Network PDF converted to 4x6 label. Streaming to Rollo...")
            printManager.printBitmapAsync(bitmap)
        } else {
            logger("ERROR: Failed to render network PDF.")
        }
    }

    private fun processImageJob(imageBytes: ByteArray) {
        val rawBitmap = BitmapFactory.decodeByteArray(imageBytes, 0, imageBytes.size)
        if (rawBitmap == null) {
            logger("ERROR: Failed to decode incoming image.")
            return
        }

        val targetWidth = UsbPrintManager.TARGET_WIDTH
        val targetHeight = UsbPrintManager.TARGET_HEIGHT
        val canvasBitmap = Bitmap.createBitmap(targetWidth, targetHeight, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(canvasBitmap)
        canvas.drawColor(Color.WHITE)

        val scale = Math.min(
            targetWidth.toFloat() / rawBitmap.width,
            targetHeight.toFloat() / rawBitmap.height
        )
        val renderW = rawBitmap.width * scale
        val renderH = rawBitmap.height * scale
        val left = (targetWidth - renderW) / 2f
        val top = (targetHeight - renderH) / 2f

        canvas.drawBitmap(rawBitmap, null, RectF(left, top, left + renderW, top + renderH), null)

        logger("Network Image fitted to 4x6 203DPI canvas. Streaming to Rollo...")
        printManager.printBitmapAsync(canvasBitmap)
    }

    private fun processTextJob(text: String) {
        val targetWidth = UsbPrintManager.TARGET_WIDTH
        val targetHeight = UsbPrintManager.TARGET_HEIGHT
        val canvasBitmap = Bitmap.createBitmap(targetWidth, targetHeight, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(canvasBitmap)
        canvas.drawColor(Color.WHITE)

        val paint = Paint().apply {
            color = Color.BLACK
            textSize = 28f
            isAntiAlias = true
        }

        val lines = text.lines()
        var y = 60f
        for (line in lines) {
            canvas.drawText(line, 40f, y, paint)
            y += 36f
            if (y > targetHeight - 40) break
        }

        logger("Network Text rendered to 4x6 label. Streaming to Rollo...")
        printManager.printBitmapAsync(canvasBitmap)
    }

    private fun isAsciiText(data: ByteArray): Boolean {
        if (data.size > 100000) return false
        var printableCount = 0
        val checkLen = Math.min(data.size, 500)
        for (i in 0 until checkLen) {
            val b = data[i].toInt() and 0xFF
            if (b == 9 || b == 10 || b == 13 || (b in 32..126)) {
                printableCount++
            }
        }
        return (printableCount.toFloat() / checkLen) > 0.85
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
