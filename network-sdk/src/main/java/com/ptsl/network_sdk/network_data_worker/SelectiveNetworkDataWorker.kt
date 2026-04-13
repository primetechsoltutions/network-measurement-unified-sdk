package com.ptsl.network_sdk.network_data_worker

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Build
import android.telephony.SubscriptionManager
import android.util.Log
import androidx.core.app.ActivityCompat
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.google.gson.Gson
import com.ptsl.network_sdk.api.ApiService
import com.ptsl.network_sdk.data_model.entity.AuthEntity
import com.ptsl.network_sdk.data_model.entity.NetworkDataEntity
import com.ptsl.network_sdk.data_model.logger.EventLogModel
import com.ptsl.network_sdk.data_model.logger.LogDataWrapper
import com.ptsl.network_sdk.db.NetworkDao
import com.ptsl.network_sdk.dl_ul_test.DownloadUploadHelper
import com.ptsl.network_sdk.utils.CommonUtils
import com.ptsl.network_sdk.utils.NetworkEventLogger
import com.ptsl.network_sdk.utils.calculateRttAndLatency
import com.ptsl.network_sdk.utils.prepareDate
import cz.mroczis.netmonster.core.factory.NetMonsterFactory
import cz.mroczis.netmonster.core.model.connection.PrimaryConnection
import java.util.*

/**
 * Worker responsible for a one-time "Selective" network data collection.
 * Captures signal strength, RTT, and latency, and returns the data directly to the host app
 * via WorkManager's output data. It performs event logging but skips standard data upload.
 */
class SelectiveNetworkDataWorker(
    appContext: Context,
    workerParams: WorkerParameters,
    private val apiService: ApiService,
    private val downloader: DownloadUploadHelper,
    private val databaseDao: NetworkDao,
) : CoroutineWorker(appContext, workerParams) {

    private val TAG = "SelectiveNetworkWorker"

    override suspend fun doWork(): Result {
        val msisdn = inputData.getString("msisdn") ?: ""
        val integratedAppVersion = inputData.getString("integratedAppVersion") ?: ""
        val sdkInitiateTimeStamp = inputData.getString("sdkInitiateTimeStamp") ?: ""
        val integratedAppEventName = inputData.getString("integratedAppEventName") ?: ""
        val userLatitude = inputData.getDouble("userLatitude", 0.0)
        val userLongitude = inputData.getDouble("userLongitude", 0.0)

        val authEntity = getAuth()
        val capturedDataList: MutableList<NetworkDataEntity> = mutableListOf()

        return try {
            // 1. Initial Validations
            val preFlightError = performPreFlightChecks()
            if (preFlightError != null) {
                val (msg, code, status) = preFlightError
                Log.w(TAG, "Pre-flight check failed: $msg ($code)")
                return returnResultToHost("Failed", status, msg, null)
            }
            // 2. Fetch current location
            val locationPair = if (CommonUtils.isGpsEnabled(applicationContext)) {
                LocationHelper.getCurrentLocation(applicationContext)
            } else {
                Pair(0.0, 0.0)
            }

            // 3. Capture network data (signal, RTT, Latency)
            val dataList = getReqData(locationPair, integratedAppEventName, msisdn, integratedAppVersion, sdkInitiateTimeStamp, userLatitude, userLongitude).toMutableList()
            for (data in dataList) {
                data.integratedAppEventName = integratedAppEventName
                data.msisdn = msisdn
                data.sdkInitiateTimeStamp = sdkInitiateTimeStamp
                data.userLatitude = userLatitude
                data.userLongitude = userLongitude
                data.integratedAppVersion = integratedAppVersion
                capturedDataList.add(data)
            }

            Log.i(TAG, "✅ Selective network data captured and returning to host")
            returnResultToHost("Success", 200, "Selective capture completed successfully.", capturedDataList)

        } catch (e: Exception) {
            val errorMessage = "Selective capture error: ${e.message}"
            Log.e(TAG, errorMessage)

            val eventLogModel = NetworkEventLogger.createNetworkDataFetchFailedLog(
                authEntity.hostAppName, integratedAppEventName,
                errorMessage, e.stackTraceToString()
            ).apply {
                this.msisdn = msisdn
                this.integratedAppVersion = integratedAppVersion
                this.sdkInitiateTimeStamp = sdkInitiateTimeStamp
                this.integratedAppEventName = integratedAppEventName
                this.userLatitude = userLatitude
                this.userLongitude = userLongitude
            }
            preparedLogEventData(authEntity, eventLogModel)

            returnResultToHost("Failed", 400, "Selective capture failed due to an internal error.", null)
        }
    }

    private fun performPreFlightChecks(): Triple<String, String, Int>? {
        val checkMobileData = inputData.getBoolean("checkMobileData", false)
        val checkBlSim = inputData.getBoolean("checkBlSim", false)
        val checkPermissions = inputData.getBoolean("checkPermissions", false)
        val checkBlSimPresence = inputData.getBoolean("checkBlSimPresence", false)

        // 1. Permission & GPS Check
        if (checkPermissions) {
            val hasPerms = hasRequiredPermissions()
            val isGpsEnabled = CommonUtils.isGpsEnabled(applicationContext)

            if (!hasPerms) {
                return Triple("Required permissions (Location or Phone State) are missing.", "PERMISSION_DENIED", 400)
            }
            if (!isGpsEnabled) {
                return Triple("GPS is disabled. Please enable GPS to proceed.", "GPS_DISABLED", 400)
            }
        }

        // 2. Mobile Data Connection Check
        if (checkMobileData && !isMobileNetworkConnected(applicationContext)) {
            return Triple("Mobile data connection is required for this capture. Please enable mobile data.", "MOBILE_DATA_REQUIRED", 400)
        }

        // 3. Banglalink Active Data Check (Mobile Data must be connected with BL SIM)
        if (checkBlSim && !isBanglalinkDataEnabled()) {
            return Triple("Banglalink mobile data must be enabled and active for this capture.", "BL_DATA_REQUIRED", 400)
        }

        // 4. Banglalink SIM Presence Check
        if (checkBlSimPresence && !hasBanglalinkSimPresent()) {
            return Triple("A Banglalink SIM card is required for this capture.", "BL_SIM_REQUIRED", 400)
        }

        return null
    }

    private fun hasRequiredPermissions(): Boolean {
        val locationGranted = ActivityCompat.checkSelfPermission(
            applicationContext, Manifest.permission.ACCESS_FINE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED ||
                ActivityCompat.checkSelfPermission(
                    applicationContext, Manifest.permission.ACCESS_COARSE_LOCATION
                ) == PackageManager.PERMISSION_GRANTED

        val phoneStateGranted = ActivityCompat.checkSelfPermission(
            applicationContext, Manifest.permission.READ_PHONE_STATE
        ) == PackageManager.PERMISSION_GRANTED

        return locationGranted && phoneStateGranted
    }

    private fun isBanglalinkDataEnabled(): Boolean {
        return try {
            if (ActivityCompat.checkSelfPermission(applicationContext, Manifest.permission.READ_PHONE_STATE) != PackageManager.PERMISSION_GRANTED) {
                return false
            }
            val sm = SubscriptionManager.from(applicationContext)
            val defaultDataId = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                SubscriptionManager.getDefaultDataSubscriptionId()
            } else {
                -1
            }

            if (defaultDataId != -1) {
                val info = sm.getActiveSubscriptionInfo(defaultDataId)
                info?.mnc?.toString()?.removePrefix("0") == "3"
            } else {
                sm.activeSubscriptionInfoList?.firstOrNull()?.mnc?.toString()?.removePrefix("0") == "3"
            }
        } catch (e: Exception) {
            false
        }
    }

    private fun hasBanglalinkSimPresent(): Boolean {
        return try {
            if (ActivityCompat.checkSelfPermission(applicationContext, Manifest.permission.READ_PHONE_STATE) != PackageManager.PERMISSION_GRANTED) {
                return false
            }
            val sm = SubscriptionManager.from(applicationContext)
            sm.activeSubscriptionInfoList?.any { it.mnc.toString().removePrefix("0") == "3" } ?: false
        } catch (e: Exception) {
            false
        }
    }

    private fun returnResultToHost(
        status: String,
        statusCode: Int,
        message: String,
        data: Any?
    ): Result {
        val responseMap = mapOf(
            "status" to status,
            "statusCode" to statusCode,
            "message" to message,
            "data" to data
        )

        val responseJson = Gson().toJson(responseMap)
        Log.i(TAG, "Selective capture response: $status, Code: $statusCode")
        return Result.success(workDataOf("hostAppResponse" to responseJson))
    }

    private suspend fun getAuth(): AuthEntity = databaseDao.getPersistentAuth() ?: AuthEntity()

    private suspend fun getReqData(
        locationPair: Pair<Double, Double>,
        integratedAppEventName: String,
        msisdn: String,
        integratedAppVersion: String,
        sdkInitiateTimeStamp: String,
        userLatitude: Double,
        userLongitude: Double
    ): ArrayList<NetworkDataEntity> {

        val dataList = arrayListOf<NetworkDataEntity>()

        return try {
            val testUrl = "https://crsrcgz.banglalink.net"
            val isMobileConnected = isMobileNetworkConnected(applicationContext)
            val activeNetworkMnc = if (isMobileConnected) getActiveNetworkMNC() else "-1"

            val metrics = calculateRttAndLatency(
                hasMobileInternet = true,
                testUrl = testUrl
            )

            Log.d(TAG, "Metrics captured: RTT=${metrics.rtt}ms, Latency=${metrics.latency}ms")

            var permissionException: Exception? = null

            val cells = try {
                val hasPermission = ActivityCompat.checkSelfPermission(
                    applicationContext, Manifest.permission.ACCESS_FINE_LOCATION
                ) == PackageManager.PERMISSION_GRANTED ||
                        ActivityCompat.checkSelfPermission(
                            applicationContext, Manifest.permission.ACCESS_COARSE_LOCATION
                        ) == PackageManager.PERMISSION_GRANTED

                if (hasPermission) {
                    NetMonsterFactory.get(applicationContext).getCells()
                } else {
                    permissionException = SecurityException("Missing location permission")
                    null
                }
            } catch (e: Exception) {
                permissionException = e
                null
            }

            if (cells.isNullOrEmpty()) {
                val auth = getAuth()
                val eventLogModel = NetworkEventLogger.createPermissionMissingLog(
                    auth.hostAppName,
                    integratedAppEventName,
                    permissionException?.message ?: "N/A",
                    permissionException?.stackTraceToString()
                ).apply {
                    this.msisdn = msisdn
                    this.integratedAppVersion = integratedAppVersion
                    this.sdkInitiateTimeStamp = sdkInitiateTimeStamp
                    this.integratedAppEventName = integratedAppEventName
                    this.userLatitude = userLatitude
                    this.userLongitude = userLongitude
                }
                preparedLogEventData(auth, eventLogModel)

                dataList.add(
                    NetworkDataEntity(
                        lattitude = locationPair.first,
                        longitude = locationPair.second,
                        data = if (isMobileConnected) "Mobile" else "Wifi",
                        usedSimSlot = getSimCount(),
                        rtt = CommonUtils.round2(metrics.rtt),
                        latency = CommonUtils.round2(metrics.latency),
                        time = CommonUtils.getCurrentDateTime(),
                        date = CommonUtils.getCurrentDate(),
                        deviceModel = Build.MODEL,
                        deviceManufacture = Build.MANUFACTURER,
                        deviceOsVersion = Build.VERSION.SDK_INT.toString()
                    )
                )
                return dataList
            }

            cells.forEach { cell ->
                if (cell.connectionStatus is PrimaryConnection) {
                    dataList.add(
                        cell.prepareDate(
                            locationPair, downloader, isMobileConnected, activeNetworkMnc, getSimCount(),
                            rtt = CommonUtils.round2(metrics.rtt),
                            latency = CommonUtils.round2(metrics.latency),
                            applicationContext
                        )
                    )
                }
            }
            dataList

        } catch (e: Exception) {
            val eventLogModel = NetworkEventLogger.createNetworkDataFetchFailedLog(
                getAuth().hostAppName, integratedAppEventName,
                e.message ?: "N/A", e.stackTraceToString()
            ).apply {
                this.msisdn = msisdn
                this.integratedAppVersion = integratedAppVersion
                this.sdkInitiateTimeStamp = sdkInitiateTimeStamp
                this.integratedAppEventName = integratedAppEventName
                this.userLatitude = userLatitude
                this.userLongitude = userLongitude
            }
            preparedLogEventData(getAuth(), eventLogModel)
            dataList
        }
    }

    private fun isMobileNetworkConnected(context: Context): Boolean {
        return try {
            val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
            val network = cm.activeNetwork ?: return false
            val caps = cm.getNetworkCapabilities(network) ?: return false
            caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR)
        } catch (e: Exception) {
            false
        }
    }

    private fun getActiveNetworkMNC(): String {
        var mnc = "-1"
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                val sm = SubscriptionManager.from(applicationContext)
                val dataSubId = SubscriptionManager.getDefaultDataSubscriptionId()
                if (dataSubId != SubscriptionManager.INVALID_SUBSCRIPTION_ID) {
                    val si = sm.getActiveSubscriptionInfo(dataSubId)
                    mnc = si?.mnc?.toString() ?: "-1"
                }
            }
        } catch (_: Exception) { }
        return "0${mnc}"
    }

    private suspend fun preparedLogEventData(auth: AuthEntity, eventLogModel: EventLogModel) {
        try {
            val logsToSend = mutableListOf<EventLogModel>()
            val cachedCount = databaseDao.getNetworkDataLogEventCount()
            if (cachedCount > 0) {
                logsToSend.addAll(databaseDao.getNetworkDataLogEvent())
            }
            logsToSend.add(eventLogModel)

            apiService.postNetworkDataLogs(LogDataWrapper(auth, ArrayList(logsToSend)))

            if (cachedCount > 0) {
                databaseDao.deleteNetworkDataLogEvent()
            }
        } catch (e: Exception) {
            databaseDao.insertNetworkDataLogIntoDB(eventLogModel)
        }
    }

    private fun getSimCount(): Int {
        return try {
            val sm = SubscriptionManager.from(applicationContext)
            sm.activeSubscriptionInfoList?.size ?: 0
        } catch (e: Exception) {
            0
        }
    }
}
