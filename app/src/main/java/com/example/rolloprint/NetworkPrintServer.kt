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
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
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
        val PORTS = intArrayOf(9100, 515)
        const val SERVICE_TYPE_PDL = "_pdl-datastream._tcp."
        const val SERVICE_NAME = "Rollo Printer"
    }

    fun start() {
        if (isRunning) return
        isRunning = true
        val ipAddress = getLocalIpAddress()
        logger("[NETWORK] Print Server active on $ipAddress (Ports: 9100, 515)")
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
                                logger("[NETWORK] Server Port $port Error: ${e.message}")
                            }
                        }
                    }
                } catch (e: Exception) {
                    if (isRunning) {
                        logger("[NETWORK] Failed to bind Port $port: ${e.message}")
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
        logger("[NETWORK] Print Server Stopped.")
        onStatusChanged(false, null)
    }

    private fun registerNsdService() {
        try {
            nsdManager = context.getSystemService(Context.NSD_SERVICE) as NsdManager
            
            // Register as standard Generic Plug-and-Play JetDirect Network Printer (no driver prompt on clients)
            val serviceInfo = NsdServiceInfo().apply {
                serviceName = SERVICE_NAME
                serviceType = SERVICE_TYPE_PDL
                port = 9100
                setAttribute("txtvers", "1")
                setAttribute("qtotal", "1")
                setAttribute("pdl", "application/pdf,image/png,image/jpeg")
                setAttribute("rp", "raw")
                setAttribute("ty", "Generic Label Printer")
                setAttribute("product", "(Generic Label Printer)")
                setAttribute("note", "RolloPrint Network Server")
                setAttribute("Color", "F")
                setAttribute("Duplex", "F")
                setAttribute("papercustom", "4x6in,101.6x152.4mm")
            }

            registrationListener = object : NsdManager.RegistrationListener {
                override fun onServiceRegistered(serviceInfo: NsdServiceInfo) {
                    logger("[NETWORK] Plug-and-Play Printer (mDNS) Registered: ${serviceInfo.serviceName}")
                }
                override fun onRegistrationFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {
                    logger("[NETWORK] mDNS Registration Warning: $errorCode")
                }
                override fun onServiceUnregistered(serviceInfo: NsdServiceInfo) {}
                override fun onUnregistrationFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {}
            }

            nsdManager?.registerService(serviceInfo, NsdManager.PROTOCOL_DNS_SD, registrationListener)
        } catch (e: Exception) {
            logger("[NETWORK] mDNS Setup Notice: ${e.message}")
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
        logger("[NETWORK] Incoming print job from $clientIp on port $port")
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
                            break
                        }
                    }
                } catch (_: SocketTimeoutException) {
                    // Socket timeout triggers when client finishes streaming payload
                }

                try {
                    socket.close()
                } catch (_: Exception) {}

                val jobData = baos.toByteArray()
                if (jobData.isEmpty()) {
                    logger("[NETWORK] Received empty job payload from $clientIp")
                    return@execute
                }

                logger("[NETWORK] Ingested network job (${jobData.size} bytes) from $clientIp. Converting to local 4x6 PDF...")
                convertAndPrintNetworkJob(jobData)
            } catch (e: Exception) {
                logger("[NETWORK] Error processing network job from $clientIp: ${e.message}")
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

    /**
     * Converts ANY network job payload into an actual 4x6 PDF file on server cache storage,
     * then ingests it directly into the working local PDF print pipeline (skipping preview).
     */
    private fun convertAndPrintNetworkJob(data: ByteArray) {
        val pdfFile = File(context.cacheDir, "net_pdf_${System.currentTimeMillis()}.pdf")

        try {
            // 1. Check if incoming payload contains PDF (%PDF-)
            val pdfHeader = "%PDF-".toByteArray()
            val pdfStart = findByteSequence(data, pdfHeader)
            if (pdfStart != -1) {
                val pdfBytes = data.copyOfRange(pdfStart, data.size)
                FileOutputStream(pdfFile).use { it.write(pdfBytes) }
                logger("[NETWORK] Extracted PDF document (${pdfBytes.size} bytes). Ingesting into local PDF engine...")
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
                    logger("[NETWORK] Extracted Image document. Converting to 4x6 PDF...")
                    createPdfFromBitmap(bitmap, pdfFile)
                    ingestPdfToLocalPrint(pdfFile)
                    return
                }
            }

            // 3. Fallback: PostScript or Text payload -> Convert to 4x6 PDF
            logger("[NETWORK] Converting Text/PostScript stream to 4x6 PDF...")
            val textContent = String(data, Charsets.UTF_8)
            createPdfFromText(textContent, pdfFile)
            ingestPdfToLocalPrint(pdfFile)

        } catch (e: Exception) {
            logger("[NETWORK] ERROR converting network job to PDF: ${e.message}")
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

        val lines = mutableListOf<String>()
        if (textContent.contains("%!PS-Adobe")) {
            val regex = Regex("\\((.*?)\\)")
            val matches = regex.findAll(textContent)
            for (match in matches) {
                val str = match.groupValues[1].trim()
                if (str.length > 2 && !str.startsWith("00") && !str.contains("Font") && !str.contains("Encoding")) {
                    lines.add(str)
                }
            }
            if (lines.isEmpty()) {
                lines.addAll(textContent.lines().filter { !it.startsWith("%") && it.trim().isNotEmpty() })
            }
        } else {
            lines.addAll(textContent.lines())
        }

        var y = 60f
        for (line in lines) {
            if (line.isBlank()) continue
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
            logger("[NETWORK] PDF ingested successfully into local print pipeline (skipping preview).")
            printManager.printBitmapAsync(bitmap)
        } else {
            logger("[NETWORK] ERROR: Local PDF rendering engine failed for network job.")
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
