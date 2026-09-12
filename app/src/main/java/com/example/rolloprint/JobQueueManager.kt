package com.example.rolloprint

import android.graphics.Bitmap
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicInteger

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

    companion object {
        private val globalJobIdCounter = AtomicInteger(101)

        @JvmStatic
        fun getNextJobId(): Int = globalJobIdCounter.getAndIncrement()
    }

    private fun notifyQueueChanged() {
        val jobs = queue.toList()
        onQueueChanged(jobs.size)
        onQueueJobsChanged?.invoke(jobs)
    }

    @JvmOverloads
    fun addJob(bitmap: Bitmap, name: String, forceHold: Boolean = false, assignedJobId: Int = getNextJobId()) {
        val initialStatus = if (forceHold) JobStatus.HELD else JobStatus.PENDING
        val job = PrintJob(assignedJobId, bitmap, name, initialStatus)
        queue.add(job)
        val modeStr = if (forceHold) "HELD (manual hold enabled)" else "PENDING"
        logger("[QUEUE] Job '$name' (#$assignedJobId) added to queue [$modeStr]. Total in queue: ${queue.size}")
        notifyQueueChanged()
        if (!forceHold) {
            processNextJob()
        }
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

    fun printAllJobs() {
        synchronized(this) {
            queue.forEach { job ->
                if (job.status == JobStatus.HELD || job.status == JobStatus.FAILED) {
                    job.status = JobStatus.PENDING
                }
            }
            logger("[QUEUE] User triggered 'Print all' for ${queue.size} queued job(s)...")
            notifyQueueChanged()
            processNextJob()
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
