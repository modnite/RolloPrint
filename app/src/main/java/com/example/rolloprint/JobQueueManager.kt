package com.example.rolloprint

import android.graphics.Bitmap
import java.util.concurrent.ConcurrentLinkedQueue

enum class JobStatus {
    PENDING,
    HELD,
    PRINTING,
    COMPLETED,
    FAILED
}

class JobQueueManager(
    private val printManager: UsbPrintManager,
    private val logger: (String) -> Unit,
    private val onQueueChanged: (Int) -> Unit
) {
    data class PrintJob(
        val id: Int,
        val bitmap: Bitmap,
        val name: String,
        var status: JobStatus = JobStatus.PENDING
    )

    private val queue = ConcurrentLinkedQueue<PrintJob>()
    @Volatile
    private var isProcessing = false

    fun addJob(bitmap: Bitmap, name: String) {
        val jobId = (1000..9999).random()
        val job = PrintJob(jobId, bitmap, name, JobStatus.PENDING)
        queue.add(job)
        val currentSize = queue.size
        logger("[QUEUE] Job '$name' (#$jobId) added to queue. Total in queue: $currentSize")
        onQueueChanged(currentSize)
        processNextJob()
    }

    private fun processNextJob() {
        synchronized(this) {
            if (isProcessing) return
            val nextJob = queue.find { it.status == JobStatus.PENDING } ?: run {
                onQueueChanged(queue.count { it.status != JobStatus.COMPLETED && it.status != JobStatus.FAILED })
                return
            }
            isProcessing = true
            nextJob.status = JobStatus.PRINTING

            logger("[QUEUE] Printing job '${nextJob.name}' (#${nextJob.id})...")

            printManager.printBitmapAsync(nextJob.bitmap) { success ->
                synchronized(this@JobQueueManager) {
                    isProcessing = false
                    if (success) {
                        nextJob.status = JobStatus.COMPLETED
                        queue.remove(nextJob)
                        val remaining = queue.size
                        onQueueChanged(remaining)
                        logger("[QUEUE] Completed job #${nextJob.id}. Remaining in queue: $remaining")
                        if (remaining > 0) {
                            processNextJob()
                        }
                    } else {
                        nextJob.status = JobStatus.HELD
                        val remaining = queue.size
                        onQueueChanged(remaining)
                        logger("[QUEUE] Job #${nextJob.id} HELD (Hardware error / Out of paper). Remaining in queue: $remaining")
                    }
                }
            }
        }
    }

    fun printJobManual(jobId: Int) {
        synchronized(this) {
            val job = queue.find { it.id == jobId }
            if (job != null) {
                job.status = JobStatus.PENDING
                logger("[QUEUE] User manually triggered job #${job.id} ('${job.name}')...")
                processNextJob()
            }
        }
    }

    fun removeJob(jobId: Int) {
        synchronized(this) {
            queue.removeAll { it.id == jobId }
            val remaining = queue.size
            onQueueChanged(remaining)
            logger("[QUEUE] Removed job #$jobId from queue. Remaining: $remaining")
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

    fun getQueuedJobs(): List<PrintJob> = queue.toList()

    fun getQueueSize(): Int = queue.size
}
