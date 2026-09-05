package com.example.rolloprint

import android.graphics.Bitmap
import java.util.concurrent.ConcurrentLinkedQueue

class JobQueueManager(
    private val printManager: UsbPrintManager,
    private val logger: (String) -> Unit,
    private val onQueueChanged: (Int) -> Unit
) {
    data class PrintJob(val id: Int, val bitmap: Bitmap, val name: String)

    private val queue = ConcurrentLinkedQueue<PrintJob>()
    @Volatile
    private var isProcessing = false

    fun addJob(bitmap: Bitmap, name: String) {
        val jobId = (1000..9999).random()
        val job = PrintJob(jobId, bitmap, name)
        queue.add(job)
        val currentSize = queue.size
        logger("[QUEUE] Job '$name' (#$jobId) added to queue. Total in queue: $currentSize")
        onQueueChanged(currentSize)
        processNextJob()
    }

    private fun processNextJob() {
        synchronized(this) {
            if (isProcessing) return
            val nextJob = queue.peek() ?: run {
                onQueueChanged(0)
                return
            }
            isProcessing = true

            logger("[QUEUE] Printing job '${nextJob.name}' (#${nextJob.id})...")

            printManager.printBitmapAsync(nextJob.bitmap) { success ->
                synchronized(this@JobQueueManager) {
                    queue.poll() // Remove completed or failed job
                    isProcessing = false
                    val remaining = queue.size
                    onQueueChanged(remaining)
                    if (success) {
                        logger("[QUEUE] Completed job #${nextJob.id}. Remaining: $remaining")
                    } else {
                        logger("[QUEUE] Job #${nextJob.id} finished/paused. Remaining in queue: $remaining")
                    }
                    if (remaining > 0) {
                        processNextJob()
                    }
                }
            }
        }
    }

    fun clearQueue() {
        synchronized(this) {
            val count = queue.size
            queue.clear()
            onQueueChanged(0)
            logger("[QUEUE] Cleared $count queued job(s) from print queue.")
        }
    }

    fun getQueueSize(): Int = queue.size
}
