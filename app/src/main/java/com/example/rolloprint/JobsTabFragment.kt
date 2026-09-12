package com.example.rolloprint

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageButton
import android.widget.TextView
import androidx.fragment.app.Fragment
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.button.MaterialButton

class JobsTabFragment : Fragment() {

    private var jobQueueManager: JobQueueManager? = null

    fun setJobQueueManager(manager: JobQueueManager) {
        this.jobQueueManager = manager
        if (view != null) {
            refreshAdapter(manager.getQueuedJobs())
        }
    }

    private var isOldestFirst = true
    private lateinit var rvQueueJobs: RecyclerView
    private lateinit var tvEmptyQueuePlaceholder: TextView
    private lateinit var btnToggleSortOrder: ImageButton

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        val view = inflater.inflate(R.layout.fragment_jobs_tab, container, false)

        rvQueueJobs = view.findViewById(R.id.rvQueueJobs)
        tvEmptyQueuePlaceholder = view.findViewById(R.id.tvEmptyQueuePlaceholder)
        btnToggleSortOrder = view.findViewById(R.id.btnToggleSortOrder)
        val btnPrintAllQueue = view.findViewById<MaterialButton>(R.id.btnPrintAllQueue)
        val btnClearAllQueue = view.findViewById<MaterialButton>(R.id.btnClearAllQueue)

        rvQueueJobs.layoutManager = LinearLayoutManager(requireContext())

        jobQueueManager?.onQueueJobsChanged = { jobs ->
            activity?.runOnUiThread {
                refreshAdapter(jobs)
            }
        }

        btnToggleSortOrder.setOnClickListener {
            isOldestFirst = !isOldestFirst
            if (isOldestFirst) {
                btnToggleSortOrder.setImageResource(R.drawable.ic_arrow_upward)
                btnToggleSortOrder.contentDescription = getString(R.string.sort_oldest_first)
            } else {
                btnToggleSortOrder.setImageResource(R.drawable.ic_arrow_downward)
                btnToggleSortOrder.contentDescription = getString(R.string.sort_newest_first)
            }
            jobQueueManager?.let { refreshAdapter(it.getQueuedJobs()) }
        }

        btnPrintAllQueue.setOnClickListener {
            jobQueueManager?.printAllJobs()
        }

        btnClearAllQueue.setOnClickListener {
            jobQueueManager?.clearQueue()
        }

        jobQueueManager?.let { refreshAdapter(it.getQueuedJobs()) }

        return view
    }

    private fun refreshAdapter(jobs: List<JobQueueManager.PrintJob>) {
        val displayJobs = if (isOldestFirst) jobs else jobs.reversed()
        if (displayJobs.isEmpty()) {
            rvQueueJobs.visibility = View.GONE
            tvEmptyQueuePlaceholder.visibility = View.VISIBLE
        } else {
            rvQueueJobs.visibility = View.VISIBLE
            tvEmptyQueuePlaceholder.visibility = View.GONE
            rvQueueJobs.adapter = QueueAdapter(
                displayJobs,
                onPreview = { job ->
                    // Handle Preview
                },
                onPrintNow = { job ->
                    jobQueueManager?.printJobManual(job.id)
                },
                onDelete = { job ->
                    jobQueueManager?.removeJob(job.id)
                }
            )
        }
    }

    override fun onDestroyView() {
        jobQueueManager?.onQueueJobsChanged = null
        super.onDestroyView()
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
