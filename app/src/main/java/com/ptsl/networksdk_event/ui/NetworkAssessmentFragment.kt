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
import android.widget.CheckBox
import android.widget.ImageButton
import android.widget.LinearLayout
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
    
    // Selective Result Views
    private lateinit var rawJsonCard: View
    private lateinit var rawSelectiveTextView: TextView
    private lateinit var selectiveResultsContainer: LinearLayout

    // Validation Checkboxes
    private lateinit var cbMobileData: CheckBox
    private lateinit var cbBlSim: CheckBox
    private lateinit var cbPermissions: CheckBox
    private lateinit var cbBlPresence: CheckBox

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        val view = inflater.inflate(R.layout.fragment_network_assessment, container, false)
        
        // Initialize a new uploader instance for this fragment
        networkDataUploader = NetworkDataUploader()
        networkDataUploader.init(this, "MyBL")


        
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

        view.findViewById<Button>(R.id.event_3).setOnClickListener {
            startMeasurement(
                UploadType.SelectiveNetworkDataCapture, 
                "Selective Capture",
                checkMobileData = cbMobileData.isChecked,
                checkBlSim = cbBlSim.isChecked,
                checkPermissions = cbPermissions.isChecked,
                checkBlSimPresence = cbBlPresence.isChecked
            )
        }

        // Initialize selective views
        rawJsonCard = view.findViewById(R.id.rawJsonCard)
        rawSelectiveTextView = view.findViewById(R.id.rawSelectiveTextView)
        selectiveResultsContainer = view.findViewById(R.id.selectiveResultsContainer)

        // Initialize checkboxes
        cbMobileData = view.findViewById(R.id.cbMobileData)
        cbBlSim = view.findViewById(R.id.cbBlSim)
        cbPermissions = view.findViewById(R.id.cbPermissions)
        cbBlPresence = view.findViewById(R.id.cbBlPresence)

        return view
    }

    private fun startMeasurement(
        uploadType: UploadType, 
        eventName: String,
        checkMobileData: Boolean = false,
        checkBlSim: Boolean = false,
        checkPermissions: Boolean = false,
        checkBlSimPresence: Boolean = false
    ) {
        val currentDate = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.getDefault())
            .format(System.currentTimeMillis())

        loadingOverlay.visibility = View.VISIBLE
        resultTextView.text = "Starting $eventName. Please wait...\n"
        rawJsonCard.visibility = View.GONE
        selectiveResultsContainer.visibility = View.GONE
        selectiveResultsContainer.removeAllViews()

        networkDataUploader.startUploading(
            "MYBL-1000111",
            "1.1.0-demo",
            currentDate,
            eventName,
            uploadType = uploadType,
            checkMobileData = checkMobileData,
            checkBlSim = checkBlSim,
            checkPermissions = checkPermissions,
            checkBlSimPresence = checkBlSimPresence
        ) { success, status ->
            // Print the full response to Logcat for debugging
            Log.d("SDK_RESONSE", "Status: $success, Message: ${status.message}")

            // CRITICAL: We use view lifecycle check before updating UI
            if (isAdded) {
                activity?.runOnUiThread {
                    loadingOverlay.visibility = View.GONE
                    if (success) {
                        resultTextView.text = "Success for $eventName:\n${status.message}"
                        if (uploadType == UploadType.SelectiveNetworkDataCapture) {
                            displaySelectiveResult(status.message ?: "")
                        } else {
                            displayChart(status.message ?: "")
                        }
                    } else {
                        resultTextView.text = "Failed for $eventName:\n${status.message}"
                    }
                }
            }
        }
    }

    private fun displaySelectiveResult(jsonResponse: String) {
        if (!isAdded) return
        try {
            val jsonObject = JSONObject(jsonResponse)
            
            // Show Raw JSON
            rawJsonCard.visibility = View.VISIBLE
            rawSelectiveTextView.text = jsonObject.toString(2)

            val dataArray = jsonObject.optJSONArray("data")
            if (dataArray != null && dataArray.length() > 0) {
                selectiveResultsContainer.visibility = View.VISIBLE
                selectiveResultsContainer.removeAllViews()

                for (i in 0 until dataArray.length()) {
                    val item = dataArray.getJSONObject(i)
                    addSelectiveCard(item, i + 1)
                }
            }
        } catch (e: Exception) {
            Log.e("DemoApp", "Error parsing selective result: ${e.message}")
        }
    }

    private fun addSelectiveCard(item: JSONObject, index: Int) {
        val context = context ?: return
        val cardView = LayoutInflater.from(context).inflate(R.layout.item_selective_channel, selectiveResultsContainer, false)
        
        // Find Views
        val techType = cardView.findViewById<TextView>(R.id.techType)
        val operatorMccMnc = cardView.findViewById<TextView>(R.id.operatorMccMnc)
        val timestamp = cardView.findViewById<TextView>(R.id.timestamp)
        
        val rsrp = cardView.findViewById<TextView>(R.id.rsrp)
        val rsrq = cardView.findViewById<TextView>(R.id.rsrq)
        val snr = cardView.findViewById<TextView>(R.id.snr)
        val rssi = cardView.findViewById<TextView>(R.id.rssi)
        
        val cidEnb = cardView.findViewById<TextView>(R.id.cidEnb)
        val tacPci = cardView.findViewById<TextView>(R.id.tacPci)
        val bandArfcn = cardView.findViewById<TextView>(R.id.bandArfcn)
        val taBw = cardView.findViewById<TextView>(R.id.taBw)
        
        val rttLatency = cardView.findViewById<TextView>(R.id.rttLatency)
        val speeds = cardView.findViewById<TextView>(R.id.speeds)
        
        val deviceInfo = cardView.findViewById<TextView>(R.id.deviceInfo)
        val location = cardView.findViewById<TextView>(R.id.location)
        val bottomInfo = cardView.findViewById<TextView>(R.id.bottomInfo)

        // Populate Data
        val type = item.optString("type", "N/A")
        techType.text = type
        operatorMccMnc.text = "MCC: ${item.optString("mcc", "000")} / MNC: ${item.optString("mnc", "00")}"
        
        // Extract time from timestamp if possible
        val fullTime = item.optString("time", "00:00:00")
        timestamp.text = if (fullTime.contains("T")) fullTime.substringAfter("T") else fullTime

        // Signal
        rsrp.text = "RSRP: ${item.optString("rsrp", "N/A")} dBm"
        rsrq.text = "RSRQ: ${item.optString("rsrq", "N/A")} dB"
        snr.text = "SNR: ${item.optString("snr", "N/A")} dB"
        rssi.text = "RSSI: ${item.optString("rssi", "N/A")} dBm"

        // Cell & Band
        cidEnb.text = "CID: ${item.optString("cid", "N/A")} / eNB: ${item.optString("enb", "N/A")}"
        tacPci.text = "TAC: ${item.optString("tac", "N/A")} / PCI: ${item.optString("pci", "N/A")}"
        bandArfcn.text = "Band: ${item.optString("band", "N/A")} / ARFCN: ${item.optString("arfcn", "N/A")}"
        taBw.text = "TA: ${item.optString("ta", "N/A")} / BW: ${item.optString("bw", "N/A")}"

        // Performance
        rttLatency.text = "RTT: ${item.optString("rtt", "0")}ms / Lat: ${item.optString("latency", "0")}ms"
        speeds.text = "DL: ${item.optString("dlspeed", "0")} / UL: ${item.optString("ulspeed", "0")} Mbps"

        // Device & Loc
        deviceInfo.text = "Slot ${item.optString("usedSimSlot", "N/A")} / OS ${item.optString("deviceOsVersion", "N/A")}"
        location.text = "${item.optString("latitude", "0.0")}, ${item.optString("longitude", "0.0")}"
        
        bottomInfo.text = "MSISDN: ${item.optString("msisdn", "N/A")} | Event: ${item.optString("integratedAppEventName", "N/A")}"

        selectiveResultsContainer.addView(cardView)
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
