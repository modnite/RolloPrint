package com.example.rolloprint

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ScrollView
import android.widget.TextView
import androidx.fragment.app.Fragment
import com.google.android.material.button.MaterialButton
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class LogTabFragment : Fragment() {

    private lateinit var tvLog: TextView
    private lateinit var scrollViewLog: ScrollView

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        val view = inflater.inflate(R.layout.fragment_log_tab, container, false)
        tvLog = view.findViewById(R.id.tvLog)
        scrollViewLog = view.findViewById(R.id.scrollViewLog)

        val btnDumpLogs = view.findViewById<MaterialButton>(R.id.btnDumpLogs)
        btnDumpLogs.setOnClickListener {
            (activity as? MainActivity)?.dumpActivityLogsToEtherpad()
        }

        return view
    }

    fun log(text: String) {
        activity?.runOnUiThread {
            val timestamp = SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date())
            tvLog.append("[$timestamp] $text\n")
            scrollViewLog.post { scrollViewLog.fullScroll(ScrollView.FOCUS_DOWN) }
        }
    }

    fun getLogText(): String {
        return tvLog.text.toString()
    }
}
