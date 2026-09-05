package com.example.rolloprint

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.fragment.app.DialogFragment
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.button.MaterialButton

class QueueManagerDialogFragment : DialogFragment() {

    private var jobQueueManager: JobQueueManager? = null
    private var onPreviewRequested: ((JobQueueManager.PrintJob) -> Unit)? = null

    companion object {
        fun newInstance(
            queueManager: JobQueueManager,
            onPreview: (JobQueueManager.PrintJob) -> Unit
        ): QueueManagerDialogFragment {
            return QueueManagerDialogFragment().apply {
                jobQueueManager = queueManager
                onPreviewRequested = onPreview
            }
        }
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View? {
        return inflater.inflate(R.layout.dialog_queue_manager, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        val rvQueueJobs = view.findViewById<RecyclerView>(R.id.rvQueueJobs)
        val btnClearAllQueue = view.findViewById<MaterialButton>(R.id.btnClearAllQueue)
        val btnCloseQueue = view.findViewById<MaterialButton>(R.id.btnCloseQueue)

        rvQueueJobs.layoutManager = LinearLayoutManager(context)

        fun refreshAdapter() {
            val jobs = jobQueueManager?.getQueuedJobs() ?: emptyList()
            rvQueueJobs.adapter = QueueAdapter(jobs,
                onPreview = { job ->
                    dismiss()
                    onPreviewRequested?.invoke(job)
                },
                onPrintNow = { job ->
                    jobQueueManager?.printJobManual(job.id)
                    refreshAdapter()
                },
                onDelete = { job ->
                    jobQueueManager?.removeJob(job.id)
                    refreshAdapter()
                }
            )
        }

        refreshAdapter()

        btnClearAllQueue.setOnClickListener {
            jobQueueManager?.clearQueue()
            refreshAdapter()
        }

        btnCloseQueue.setOnClickListener { dismiss() }
    }

    override fun onStart() {
        super.onStart()
        dialog?.window?.apply {
            val width = (resources.displayMetrics.widthPixels * 0.92).toInt()
            setLayout(width, ViewGroup.LayoutParams.WRAP_CONTENT)
        }
    }

    private class QueueAdapter(
        private val jobs: List<JobQueueManager.PrintJob>,
        private val onPreview: (JobQueueManager.PrintJob) -> Unit,
        private val onPrintNow: (JobQueueManager.PrintJob) -> Unit,
        private val onDelete: (JobQueueManager.PrintJob) -> Unit
    ) : RecyclerView.Adapter<QueueAdapter.QueueViewHolder>() {

        class QueueViewHolder(view: View) : RecyclerView.ViewHolder(view) {
            val tvJobName: TextView = view.findViewById(R.id.tvJobName)
            val tvJobStatus: TextView = view.findViewById(R.id.tvJobStatus)
            val btnItemPreview: MaterialButton = view.findViewById(R.id.btnItemPreview)
            val btnItemPrint: MaterialButton = view.findViewById(R.id.btnItemPrint)
            val btnItemDelete: MaterialButton = view.findViewById(R.id.btnItemDelete)
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): QueueViewHolder {
            val view = LayoutInflater.from(parent.context).inflate(R.layout.item_queue_job, parent, false)
            return QueueViewHolder(view)
        }

        override fun onBindViewHolder(holder: QueueViewHolder, position: Int) {
            val job = jobs[position]
            holder.tvJobName.text = "#${job.id} - ${job.name}"
            holder.tvJobStatus.text = "Status: ${job.status.name}"

            holder.btnItemPreview.setOnClickListener { onPreview(job) }
            holder.btnItemPrint.setOnClickListener { onPrintNow(job) }
            holder.btnItemDelete.setOnClickListener { onDelete(job) }
        }

        override fun getItemCount(): Int = jobs.size
    }
}
