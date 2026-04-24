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
import com.google.gson.Gson
import com.ptsl.network_sdk.api.ApiService
import com.ptsl.network_sdk.data_model.NetworkDataRequest
import com.ptsl.network_sdk.data_model.entity.AuthEntity
import com.ptsl.network_sdk.data_model.entity.NetworkDataEntity
import com.ptsl.network_sdk.data_model.logger.EventLogModel
import com.ptsl.network_sdk.data_model.logger.LogDataWrapper
import com.ptsl.network_sdk.db.NetworkDao
import com.ptsl.network_sdk.dl_ul_test.DownloadUploadHelper
import com.ptsl.network_sdk.utils.CommonUtils
import com.ptsl.network_sdk.utils.NetworkEventLogger
import com.ptsl.network_sdk.utils.SdkContainer
import com.ptsl.network_sdk.utils.calculateRttAndLatency
import com.ptsl.network_sdk.utils.prepareDate
import cz.mroczis.netmonster.core.Milliseconds
import cz.mroczis.netmonster.core.factory.NetMonsterFactory
import cz.mroczis.netmonster.core.model.connection.PrimaryConnection
import kotlinx.coroutines.withTimeout
import retrofit2.HttpException
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Locale
import kotlin.math.roundToInt
import kotlin.text.toDouble

/**
 * Worker responsible for periodic background network data collection.
 * Captures signal strength, RTT, and latency, then uploads to the primary backend.
 */
class NetworkDataWorker(
    appContext: Context,
    workerParams: WorkerParameters,
) : CoroutineWorker(appContext, workerParams) {

    private val apiService: ApiService by lazy { SdkContainer.init(applicationContext); SdkContainer.apiService }
    private val downloader: DownloadUploadHelper by lazy { SdkContainer.init(applicationContext); SdkContainer.downloadUploadHelper }
    private val databaseDao: NetworkDao by lazy { SdkContainer.init(applicationContext); SdkContainer.dao }
    private val TAG = "NetworkDataWorker"

    override suspend fun doWork(): Result {
        val msisdn = inputData.getString("msisdn") ?: ""
        val integratedAppVersion = inputData.getString("integratedAppVersion") ?: ""
        val sdkInitiateTimeStamp = inputData.getString("sdkInitiateTimeStamp") ?: ""
        val integratedAppEventName = inputData.getString("integratedAppEventName") ?: ""
        val userLatitude = inputData.getDouble("userLatitude", 0.0)
        val userLongitude = inputData.getDouble("userLongitude", 0.0)

        val authEntity = getAuth()
        val newDataList: MutableList<NetworkDataEntity> = mutableListOf()

        return try {
            withTimeout(60_000L) {
            // 1. Fetch current location
            val locationPair = if (CommonUtils.isGpsEnabled(applicationContext)) {
                LocationHelper.getCurrentLocation(applicationContext)
            } else {
                Pair(0.0, 0.0)
            }

            // 2. Capture network data (signal, RTT, Latency)
            val dataList = getReqData(locationPair).toMutableList()
            for (data in dataList) {
                data.integratedAppEventName = integratedAppEventName
                data.msisdn = msisdn
                data.sdkInitiateTimeStamp = sdkInitiateTimeStamp
                data.userLatitude = userLatitude
                data.userLongitude = userLongitude
                data.integratedAppVersion = integratedAppVersion
                newDataList.add(data)
            }

            // 3. Merge with any cached data from previous failed attempts
            val localData = databaseDao.getNetworkData()
            val mergeData: List<NetworkDataEntity> = newDataList + (localData ?: emptyList())

            // 4. Send network data to backend
            apiService.postNetworkData(
                NetworkDataRequest(authEntity, mergeData)
            )
            Log.i(TAG, "✅ Network data uploaded successfully")

            clearNetworkDataCache()
            Result.success()

        }
        }
        catch (e: Exception) {
            var statusCode = 0
            val errorMessage = when (e) {
                is HttpException -> {
                    statusCode = e.code()
                    val errorBody = try {
                        e.response()?.errorBody()?.string()
                    } catch (_: Exception) {
                        null
                    }
                    "HTTP error: ${e.code()} ${e.message}${if (errorBody != null) " | Body: $errorBody" else ""}"
                }
                is IOException -> "Network error: ${e.message}"
                else -> "Unexpected error: ${e.message}"
            }

            Log.w(TAG, "⚠️ Upload failed, caching data locally: $errorMessage")
            insertNetworkDataInDb(newDataList)

            // Log the failure to the events backend
            val failedRequest = try {
                Gson().toJson(NetworkDataRequest(authEntity, newDataList))
            } catch (ex: Exception) {
                "Serialization failed: ${ex.message}"
            }

            val eventLogModel = NetworkEventLogger.createNetworkRequestFailedLog(
                authEntity.hostAppName, eventName = integratedAppEventName, errorMessage=errorMessage, stackTrace = failedRequest, statusCode = statusCode
            ).apply {
                this.msisdn = msisdn
                this.integratedAppVersion = integratedAppVersion
                this.sdkInitiateTimeStamp = sdkInitiateTimeStamp
                this.integratedAppEventName = integratedAppEventName
                this.userLatitude = userLatitude
                this.userLongitude = userLongitude
            }
            preparedLogEventData(authEntity, eventLogModel)
            
            Result.failure()
        }
    }

    private suspend fun getAuth(): AuthEntity = databaseDao.getPersistentAuth() ?: AuthEntity()

    /**
     * Captures core network metrics and cell info.
     */
    private suspend fun getReqData(
        locationPair: Pair<Double, Double>,
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

            // Fallback if no cell info is available
            if (cells.isNullOrEmpty()) {
                val auth = getAuth()
                val eventLogModel = NetworkEventLogger.createPermissionMissingLog(
                    auth.hostAppName,
                    inputData.getString("integratedAppEventName") ?: "",
                    permissionException?.message ?: "N/A",
                    permissionException?.stackTraceToString()
                ).apply {
                    this.msisdn = inputData.getString("msisdn") ?: ""
                    this.integratedAppVersion = inputData.getString("integratedAppVersion") ?: ""
                    this.sdkInitiateTimeStamp = inputData.getString("sdkInitiateTimeStamp") ?: ""
                    this.integratedAppEventName = inputData.getString("integratedAppEventName") ?: ""
                    this.userLatitude = inputData.getDouble("userLatitude", 0.0)
                    this.userLongitude = inputData.getDouble("userLongitude", 0.0)
                }
                preparedLogEventData(
                    auth,
                    eventLogModel

                )

                dataList.add(
                    NetworkDataEntity(
                        lattitude = 0.0,
                        longitude = 0.0,
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

            // Process connection-primary cells
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

            val eventLogModel =   NetworkEventLogger.createNetworkDataFetchFailedLog(
                getAuth().hostAppName, inputData.getString("integratedAppEventName") ?: "",
                e.message ?: "N/A", e.stackTraceToString()
            ).apply {
                this.msisdn = inputData.getString("msisdn") ?: ""
                this.integratedAppVersion = inputData.getString("integratedAppVersion") ?: ""
                this.sdkInitiateTimeStamp = inputData.getString("sdkInitiateTimeStamp") ?: ""
                this.integratedAppEventName = inputData.getString("integratedAppEventName") ?: ""
                this.userLatitude = inputData.getDouble("userLatitude", 0.0)
                this.userLongitude = inputData.getDouble("userLongitude", 0.0)
            }
            preparedLogEventData(
                getAuth(),
                eventLogModel
            )

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
                clearNetworkDataCacheLog()
            }
        } catch (e: Exception) {
            insertNetworkDataLogInDb(eventLogModel)
        }
    }

    private suspend fun insertNetworkDataInDb(newDataList: List<NetworkDataEntity>) {
        try {
            if (newDataList.isNotEmpty()) {
                databaseDao.insertNetworkData(newDataList)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error caching network data", e)
        }
    }

    private suspend fun clearNetworkDataCache() {
        databaseDao.deleteNetworkData()
    }

    private suspend fun clearNetworkDataCacheLog() {
        databaseDao.deleteNetworkDataLogEvent()
    }

    private suspend fun insertNetworkDataLogInDb(eventLogModel: EventLogModel) {
        try {
            databaseDao.insertNetworkDataLogIntoDB(eventLogModel)
        } catch (_: Exception) { }
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