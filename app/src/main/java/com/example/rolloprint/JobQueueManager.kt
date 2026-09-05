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

    var onQueueJobsChanged: ((List<PrintJob>) -> Unit)? = null

    private fun notifyQueueChanged() {
        val jobs = queue.toList()
        onQueueChanged(jobs.size)
        onQueueJobsChanged?.invoke(jobs)
    }

    fun addJob(bitmap: Bitmap, name: String) {
        val jobId = (1000..9999).random()
        val job = PrintJob(jobId, bitmap, name, JobStatus.PENDING)
        queue.add(job)
        logger("[QUEUE] Job '$name' (#$jobId) added to queue. Total in queue: ${queue.size}")
        notifyQueueChanged()
        processNextJob()
    }

    private fun processNextJob() {
        synchronized(this) {
            if (isProcessing) return
            val nextJob = queue.find { it.status == JobStatus.PENDING } ?: run {
                notifyQueueChanged()
                return
            }
            isProcessing = true
            nextJob.status = JobStatus.PRINTING
            notifyQueueChanged()

            logger("[QUEUE] Printing job '${nextJob.name}' (#${nextJob.id})...")

            printManager.printBitmapAsync(nextJob.bitmap) { success ->
                synchronized(this@JobQueueManager) {
                    isProcessing = false
                    if (success) {
                        nextJob.status = JobStatus.COMPLETED
                        queue.remove(nextJob)
                        notifyQueueChanged()
                        logger("[QUEUE] Completed job #${nextJob.id}. Remaining in queue: ${queue.size}")
                        if (queue.isNotEmpty()) {
                            processNextJob()
                        }
                    } else {
                        nextJob.status = JobStatus.HELD
                        notifyQueueChanged()
                        logger("[QUEUE] Job #${nextJob.id} HELD (Hardware error / Out of paper). Remaining in queue: ${queue.size}")
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
                notifyQueueChanged()
                processNextJob()
            }
        }
    }

    fun removeJob(jobId: Int) {
        synchronized(this) {
            queue.removeAll { it.id == jobId }
            notifyQueueChanged()
            logger("[QUEUE] Removed job #$jobId from queue. Remaining: ${queue.size}")
        }
    }

    fun clearQueue() {
        synchronized(this) {
            val count = queue.size
            queue.clear()
            notifyQueueChanged()
            logger("[QUEUE] Cleared $count queued job(s) from print queue.")
        }
    }

    fun getQueuedJobs(): List<PrintJob> = queue.toList()

    fun getQueueSize(): Int = queue.size
}
