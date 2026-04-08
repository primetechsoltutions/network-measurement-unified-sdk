package com.ptsl.networksdk_event.ui

import android.graphics.Color
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.ImageButton
import android.widget.TextView
import androidx.fragment.app.Fragment
import com.github.mikephil.charting.charts.HorizontalBarChart
import com.github.mikephil.charting.charts.LineChart
import com.github.mikephil.charting.components.XAxis
import com.github.mikephil.charting.data.BarData
import com.github.mikephil.charting.data.BarDataSet
import com.github.mikephil.charting.data.BarEntry
import com.github.mikephil.charting.data.Entry
import com.github.mikephil.charting.data.LineData
import com.github.mikephil.charting.data.LineDataSet
import com.github.mikephil.charting.formatter.IndexAxisValueFormatter
import com.github.mikephil.charting.utils.ColorTemplate
import com.ptsl.network_sdk.NetworkDataUploader
import com.ptsl.network_sdk.UploadType
import com.ptsl.networksdk_event.MainActivity
import com.ptsl.networksdk_event.R
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.*

class NetworkAssessmentFragment : Fragment() {

    private lateinit var networkDataUploader: NetworkDataUploader
    private lateinit var resultTextView: TextView
    private lateinit var loadingOverlay: View
    private lateinit var signalChart: HorizontalBarChart
    private lateinit var speedChart: LineChart

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        val view = inflater.inflate(R.layout.fragment_network_assessment, container, false)
        
        // Use the uploader initialized in MainActivity
        networkDataUploader = (activity as MainActivity).networkDataUploader
        
        resultTextView = view.findViewById(R.id.resultTextView)
        loadingOverlay = view.findViewById(R.id.loadingOverlay)
        signalChart = view.findViewById(R.id.signalChart)
        speedChart = view.findViewById(R.id.speedChart)

        view.findViewById<ImageButton>(R.id.back_btn).setOnClickListener {
            parentFragmentManager.popBackStack()
        }

        view.findViewById<Button>(R.id.event_1).setOnClickListener {
            startMeasurement(UploadType.NetworkDataCapture, "Standard Capture")
        }

        view.findViewById<Button>(R.id.event_2).setOnClickListener {
            startMeasurement(UploadType.FTPNetworkDataCapture, "FTP Test")
        }

        return view
    }

    private fun startMeasurement(uploadType: UploadType, eventName: String) {
        val currentDate = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.getDefault())
            .format(System.currentTimeMillis())

        loadingOverlay.visibility = View.VISIBLE
        resultTextView.text = "Starting $eventName. Please wait...\n"

        networkDataUploader.startUploading(
            "MYBL-1000111",
            "1.1.0-demo",
            currentDate,
            eventName,
            uploadType = uploadType
        ) { success, status ->
            // Print the full response to Logcat for debugging
            Log.d("SDK_RESONSE", "Status: $success, Message: ${status.message}")

            // CRITICAL: We use view lifecycle check before updating UI
            if (isAdded) {
                activity?.runOnUiThread {
                    loadingOverlay.visibility = View.GONE
                    if (success) {
                        resultTextView.text = "Success for $eventName:\n${status.message}"
                        displayChart(status.message ?: "")
                    } else {
                        resultTextView.text = "Failed for $eventName:\n${status.message}"
                    }
                }
            } else {
                Log.w("DemoApp", "Fragment detached but SDK callback received. Stability confirmed.")
            }
        }
    }

    private fun displayChart(jsonResponse: String) {
        if (!isAdded) return
        try {
            val jsonObject = JSONObject(jsonResponse)
            val testResults = jsonObject.optJSONArray("testResults") ?: return
            
            val entries = ArrayList<BarEntry>()
            for (i in 0 until testResults.length()) {
                val item = testResults.getJSONObject(i)
                val value = item.optDouble("value", 0.0).toFloat()
                entries.add(BarEntry(i.toFloat(), value))
            }

            val dataSet = BarDataSet(entries, "Network Metrics")
            dataSet.colors = ColorTemplate.MATERIAL_COLORS.toList()
            val barData = BarData(dataSet)
            signalChart.data = barData
            signalChart.invalidate()
        } catch (e: Exception) {
            Log.e("DemoApp", "Error parsing chart data: ${e.message}")
        }
    }
}
