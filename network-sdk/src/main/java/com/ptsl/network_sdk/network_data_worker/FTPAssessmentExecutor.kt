package com.ptsl.network_sdk.network_data_worker

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Build
import android.telephony.SubscriptionManager
import android.telephony.TelephonyManager
import android.util.Log
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.ptsl.network_sdk.api.ApiService
import com.ptsl.network_sdk.data_model.AssessmentResult
import com.ptsl.network_sdk.data_model.CellMetadata
import com.ptsl.network_sdk.data_model.FTPCellInfoGetDataRequest
import com.ptsl.network_sdk.data_model.FTPNetworkDataRequest
import com.ptsl.network_sdk.data_model.NetworkDataResponse
import com.ptsl.network_sdk.data_model.NetworkMetrics
import com.ptsl.network_sdk.data_model.SpeedMetrics
import com.ptsl.network_sdk.data_model.UserMetadata
import com.ptsl.network_sdk.data_model.entity.AuthEntity
import com.ptsl.network_sdk.data_model.entity.FTPCellInfoGetRequest
import com.ptsl.network_sdk.data_model.entity.FTPNetworkDataEntity
import com.ptsl.network_sdk.data_model.entity.FTPThresholdEntity
import com.ptsl.network_sdk.data_model.logger.EventLogModel
import com.ptsl.network_sdk.data_model.logger.LogDataWrapper
import com.ptsl.network_sdk.db.NetworkDao
import com.ptsl.network_sdk.dl_ul_test.DownloadUploadHelper
import com.ptsl.network_sdk.utils.CommonUtils
import com.ptsl.network_sdk.utils.NetworkEventLogger
import com.ptsl.network_sdk.utils.prepareFTPData
import cz.mroczis.netmonster.core.factory.NetMonsterFactory
import cz.mroczis.netmonster.core.model.connection.PrimaryConnection
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withTimeout
import retrofit2.HttpException
import java.io.IOException

data class FTPAssessmentExecutionInput(
    val msisdn: String,
    val integratedAppVersion: String,
    val sdkInitiateTimeStamp: String,
    val integratedAppEventName: String,
    val userLatitude: Double,
    val userLongitude: Double
)

internal class FTPAssessmentExecutor(
    private val appContext: Context,
    private val apiService: ApiService,
    private val downloader: DownloadUploadHelper,
    private val databaseDao: NetworkDao
) {
    private val TAG = "FTPNetworkDataWorker"


    suspend fun execute(input: FTPAssessmentExecutionInput): NetworkDataResponse {
        return try {
            val preFlightError = performPreFlightChecks()
            if (preFlightError != null) {
                val (msg, code, status) = preFlightError
                logValidationFailure(input, msg, code)
                return response(
                    status = "Failed", testResult = "Failed", statusCode = status, message = msg
                )
            }

            updateThresholdsIfNeeded()

            val authEntity = getAuth()
            val locationPair = LocationHelper.getCurrentLocation(appContext)
            val ftpData = getCapturedNetworkData(locationPair)

            if (ftpData.technologyType == "NON_4G_IGNORED") {
                val msg = "FWA Capture ignored: Not on Banglalink 4G network"
                Log.w(TAG, msg)
                logValidationFailure(input, msg, "FTP_CAPTURE_NOT_4G")
                return response(
                    status = "Failed",
                    testResult = "Failed",
                    statusCode = 400,
                    message = "To continue network assessment, please ensure your Banglalink 4G SIM and mobile data are active."
                )
            }

            if (ftpData.technologyType == "SKIP_MNC_MISMATCH") {
                val msg = "FWA Capture ignored: MNC Mismatch (Not Banglalink)"
                Log.w(TAG, msg)
                logValidationFailure(input, msg, "FTP_CAPTURE_MNC_MISMATCH")
                return response(
                    status = "Failed",
                    testResult = "Failed",
                    statusCode = 400,
                    message = "To continue network assessment, please ensure your Banglalink 4G SIM and mobile data are active."
                )
            }

            enrichDataWithInput(ftpData, input)
            enrichDataWithCellInfo(ftpData, authEntity)

            val backendResponse = apiService.postFTPNetworkData(
                FTPNetworkDataRequest(auth = authEntity, data = ftpData)
            )


            val thresholds = databaseDao.getFTPThresholds() ?: FTPThresholdEntity()
            val isRsrpPass = Math.abs(ftpData.rsrp) <= thresholds.rsrpThreshold
            val isDlSpeedPass = ftpData.dlSpeed > thresholds.dlSpeedThreshold
            val isNbhDlThroughputPass =
                ftpData.nbhDlThroughputMbps > thresholds.nbhDlThroughputThreshold

            val isPass = isRsrpPass && isDlSpeedPass && isNbhDlThroughputPass

            val status = if (isPass) "Success" else "Failed"
            val testResult = if (isPass) "Pass" else "Failed"
            val statusCode = if (isPass) 200 else 400
            val message =
                if (isPass) "Your network assessment successful." else "Your network assessment failed."

            val dataResult = AssessmentResult(
                assessmentId = backendResponse.data?.assessmentId ?: 0,
                networkData = NetworkMetrics(
                    RSRP = ftpData.rsrp, SNR = ftpData.snr, RSRQ = ftpData.rsrq
                ),
                cellInfo = CellMetadata(
                    cellName = ftpData.cellName,
                    eNodeBName = ftpData.eNodeBName,
                    nbhDlThroughputMbps = ftpData.nbhDlThroughputMbps,
                    nbhTrafficGB = ftpData.nbhTrafficGb
                ),
                speedPair = SpeedMetrics(
                    ulSpeedKbps = ftpData.ulSpeed, dlSpeedKbps = ftpData.dlSpeed
                ),
                userInfo = UserMetadata(
                    deviceManufacture = ftpData.deviceManufacture,
                    deviceModel = ftpData.deviceModel,
                    deviceOsVersion = ftpData.deviceOsVersion,
                    latitude = ftpData.latitude,
                    longitude = ftpData.longitude,
                    msisdn = ftpData.msisdn,
                )
            )
            response(
                status = status,
                testResult = testResult,
                statusCode = statusCode,
                message = message,
                data = dataResult
            )
        } catch (e: TimeoutCancellationException) {
            Log.e(TAG, "Global timeout during assessment ${e.message}")
            try {
                withTimeout(10000) {
                    logError(input, e, "FTP_CAPTURE_TIMEOUT")
                }
            } catch (_: Exception) {
            }
            response(
                status = "Failed",
                testResult = "Failed",
                statusCode = 408,
                message = "Network assessment couldn’t be completed due to a processing timeout, please try again."
            )
        } catch (e: IOException) {
            Log.e(TAG, "Network error during assessment ${e.message}")
            logError(input, e, "FTP_CAPTURE_NETWORK_ERROR")
            response(
                status = "Failed",
                testResult = "Failed",
                statusCode = 400,
                message = "Network assessment couldn’t be completed due to a processing timeout, please try again."
            )
        } catch (e: Exception) {
            Log.e(TAG, "Unexpected execution error ${e.message}")
            try {
                withTimeout(10000) {
                    logError(input, e, "FTP_CAPTURE_EXECUTION_ERROR")
                }
            } catch (_: Exception) {
            }
            response(
                status = "Failed",
                testResult = "Failed",
                statusCode = 400,
                message = "Network assessment failed due to a technical or processing error, please enable all required permissions and try again."
            )
        }
    }

    private fun response(
        status: String,
        testResult: String,
        statusCode: Int,
        message: String,
        data: AssessmentResult? = null
    ): NetworkDataResponse {
        return NetworkDataResponse(
            status = status,
            testResult = testResult,
            statusCode = statusCode,
            message = message,
            data = data
        )
    }


    private fun performPreFlightChecks(): Triple<String, String, Int>? {
        // Location Permission Check
        if (!hasRequiredPermissions()) {
            Log.w(TAG, "Missing location permissions for FTP Capture")
            return Triple("To continue the network assessment, please allow Location permission and enable Precise Location if available.", "FTP_PERMISSION_DENIED", 400)
        }

        // Phone State Permission Check
        if (!isPhoneStatePermissionGranted()) {
            Log.w(TAG, "Missing Phone State permissions for FTP Capture")
            return Triple("To continue the network assessment, please allow Phone State permission.", "FTP_PHONESTATE_PERMISSION_DENIED", 400)
        }

        // GPS Enable Check
        if (!CommonUtils.isGpsEnabled(appContext)) {
            Log.w(TAG, "GPS is disabled for FTP Capture")
            return Triple("To continue network assessment, please enable GPS/location services.", "FTP_GPS_DISABLED", 400)
        }

        // Internet Connectivity Check
        if (!isInternetAvailable()) {
            Log.w(TAG, "No internet access for diagnostic capture")
            return Triple(
                "To continue network assessment, please enable and use Banglalink 4G internet.",
                "FTP_INTERNET_UNAVAILABLE",
                400
            )
        }

        // Wi-Fi Check (Strictly forbidden for FTP Capture)
        if (isWifiConnected()) {
            Log.w(TAG, "Wi-Fi is connected, rejecting FTP Capture")
            return Triple(
                "To continue network assessment, please turn off Wi-Fi and use Banglalink 4G internet.",
                "FTP_WIFI_CONNECTED",
                400
            )
        }

        // Mobile Data Connection Check
        if (!isMobileNetworkConnected()) {
            Log.w(TAG, "Mobile data not connected for diagnostic capture")
            return Triple(
                "To continue network assessment, please ensure your Banglalink 4G SIM and mobile data are active.",
                "FTP_MOBILE_DATA_REQUIRED",
                400
            )
        }

        // 4G/LTE Check
        if (!is4GConnected()) {
            Log.w(TAG, "Network is not 4G/LTE, rejecting FTP Capture")
            return Triple(
                "To continue network assessment, please switch to the Banglalink 4G network.",
                "FTP_4G_REQUIRED",
                400
            )
        }

        // Banglalink SIM and Mobile Data Check
        if (!isBanglalinkDataEnabled()) {
            Log.w(TAG, "Banglalink data not active or SIM mismatch")
            return Triple(
                "To continue network assessment, please switch to the Banglalink 4G network and use Banglalink 4G internet.",
                "FTP_BANGLALINK_DATA_UNAVAILABLE",
                400
            )
        }

        return null
    }

    private fun hasRequiredPermissions(): Boolean {
        return ActivityCompat.checkSelfPermission(
            appContext, Manifest.permission.ACCESS_FINE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED && ActivityCompat.checkSelfPermission(
            appContext, Manifest.permission.ACCESS_COARSE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED
    }

    private fun isInternetAvailable(): Boolean {
        return try {
            val cm =
                appContext.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                val network = cm?.activeNetwork ?: return false
                val caps = cm.getNetworkCapabilities(network) ?: return false
                caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) && caps.hasCapability(
                    NetworkCapabilities.NET_CAPABILITY_VALIDATED
                )
            } else {
                @Suppress("DEPRECATION") val activeNetworkInfo =
                    cm?.activeNetworkInfo ?: return false
                activeNetworkInfo.isConnected
            }
        } catch (e: Exception) {
            false
        }
    }

    private fun isBanglalinkDataEnabled(): Boolean {
        return try {
            val sm = SubscriptionManager.from(appContext)
            if (ActivityCompat.checkSelfPermission(
                    appContext, Manifest.permission.READ_PHONE_STATE
                ) != PackageManager.PERMISSION_GRANTED
            ) {
                return false
            }

            val subscriptions = sm.activeSubscriptionInfoList
            if (subscriptions.isNullOrEmpty()) return false

            val hasBLSim = subscriptions.any { it.mnc.toString().removePrefix("0") == "3" }
            if (!hasBLSim) return false

            val defaultDataId = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                SubscriptionManager.getDefaultDataSubscriptionId()
            } else {
                -1
            }

            if (defaultDataId != -1) {
                val info = sm.getActiveSubscriptionInfo(defaultDataId)
                info?.mnc?.toString()?.removePrefix("0") == "3"
            } else {
                subscriptions.firstOrNull()?.mnc?.toString()?.removePrefix("0") == "3"
            }
        } catch (e: Exception) {
            false
        }
    }

    private suspend fun getAuth(): AuthEntity = databaseDao.getPersistentAuth() ?: AuthEntity()

    private fun enrichDataWithInput(
        ftpData: FTPNetworkDataEntity, input: FTPAssessmentExecutionInput
    ) {
        ftpData.apply {
            msisdn = input.msisdn
            integratedAppVersion = input.integratedAppVersion
            sdkInitiateTimeStamp = input.sdkInitiateTimeStamp
            integratedAppEventName = input.integratedAppEventName
            userLatitude = input.userLatitude
            userLongitude = input.userLongitude
        }
    }

    private suspend fun enrichDataWithCellInfo(ftpData: FTPNetworkDataEntity, auth: AuthEntity) {
        if (ftpData.cid == 0 || ftpData.enb == 0) return

        try {
            val request = FTPCellInfoGetDataRequest(
                auth = auth, data = FTPCellInfoGetRequest(eNB = ftpData.enb, cID = ftpData.cid)
            )
            val response = apiService.postFTPCellInfo(request)
            if (response.statusCode == 200 && !response.data.isNullOrEmpty()) {
                val cellInfo = response.data!!.first()
                ftpData.apply {
                    eNodeBName = cellInfo.eNodeBName
                    cellName = cellInfo.cellName
                    eNodeBId = cellInfo.eNodeBId
                    sector = cellInfo.sector
                    nbhDlThroughputMbps = cellInfo.nbhDlThroughputMbps
                    nbhTrafficGb = cellInfo.nbhTrafficGb
                }
                Log.d(TAG, "Cell Info Enriched: ${cellInfo.cellName}")
            }
        } catch (e: Exception) {
            Log.w(TAG, "Cell Enrichment failed: ${e.message}")
        }
    }

    private suspend fun getCapturedNetworkData(locationPair: Pair<Double, Double>): FTPNetworkDataEntity {
        val isMobileConnected = isMobileNetworkConnected()
        val activeMnc = if (isMobileConnected) getActiveNetworkMNC() else "-1"

        Log.d(
            TAG,
            "getCapturedNetworkData: activeMnc=$activeMnc, isMobileConnected=$isMobileConnected"
        )

        // 1. Initial MNC Check (Active Subscription)
        val activeMncClean = activeMnc.removePrefix("0")
        if (isMobileConnected && activeMncClean != "3") {
            Log.w(TAG, "Active MNC mismatch: expected 3, got $activeMnc")
            return FTPNetworkDataEntity().apply { technologyType = "SKIP_MNC_MISMATCH" }
        }

        val cells = try {
            if (hasRequiredPermissions()) NetMonsterFactory.get(appContext).getCells()
            else null
        } catch (e: Exception) {
            Log.e(TAG, "Error fetching cells: ${e.message}")
            null
        }

        if (cells.isNullOrEmpty()) {
            Log.w(TAG, "No cells detected by NetMonster")
            return FTPNetworkDataEntity()
        }

        var foundNon4gBanglalink = false

        // 2. Iterate through all detected cells to find a Primary Banglalink 4G Cell
        for (cell in cells) {
            if (cell.connectionStatus is PrimaryConnection) {
                val cellMnc = cell.network?.mnc?.toString()?.removePrefix("0") ?: ""
                val isBanglalink = cellMnc == "3"
                val isLte = cell is cz.mroczis.netmonster.core.model.cell.CellLte

                Log.d(
                    TAG,
                    "Inspecting Primary Cell: type=${cell.javaClass.simpleName}, mnc=$cellMnc, isLte=$isLte, isBL=$isBanglalink"
                )

                if (isBanglalink) {
                    if (isLte) {
                        Log.i(TAG, "✅ Found valid Banglalink 4G Primary Cell")
                        return cell.prepareFTPData(
                            locationPair, downloader, isMobileConnected, activeMnc,
                        )
                    } else {
                        Log.d(
                            TAG,
                            "Found Banglalink Primary cell but it is NOT 4G (Technology: ${cell.javaClass.simpleName})"
                        )
                        foundNon4gBanglalink = true
                    }
                }
            }
        }

        return if (foundNon4gBanglalink) {
            Log.w(TAG, "❌ No Banglalink 4G cell found, only lower technologies detected")
            FTPNetworkDataEntity().apply { technologyType = "NON_4G_IGNORED" }
        } else {
            Log.w(TAG, "❌ No Banglalink primary connection detected in cell list")
            FTPNetworkDataEntity()
        }
    }

    private suspend fun updateThresholdsIfNeeded() {
        try {
            val cached = databaseDao.getFTPThresholds()
            val shouldFetch = cached == null || (CommonUtils.getCurrentDate() != cached.lastUpdated)

            if (shouldFetch) {
                val response = apiService.getFTPThresholds(getAuth())
                if (response.statusCode == 200 && response.data != null) {
                    val newThresholds = response.data?.apply {
                        lastUpdated = CommonUtils.getCurrentDate()
                        id = 1
                    }
                    databaseDao.insertFTPThresholds(newThresholds ?: FTPThresholdEntity())
                    Log.i(TAG, "Thresholds sync successful")
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Threshold Update failed: ${e.message}")
        }
    }

    private suspend fun logValidationFailure(
        input: FTPAssessmentExecutionInput, message: String, errorCode: String
    ) {
        val auth = getAuth()
        val eventName = input.integratedAppEventName

        val eventLogModel = NetworkEventLogger.createNetworkRequestFailedLog(
            auth.hostAppName, eventName, message, "Validation Logic Rejection: $errorCode", 400
        ).apply {
            msisdn = input.msisdn
            integratedAppVersion = input.integratedAppVersion
            sdkInitiateTimeStamp = input.sdkInitiateTimeStamp
            integratedAppEventName = eventName
            userLatitude = input.userLatitude
            userLongitude = input.userLongitude
        }

        preparedLogEventData(auth, eventLogModel)
    }

    private suspend fun logError(
        input: FTPAssessmentExecutionInput, e: Exception, eventName: String
    ) {
        val auth = getAuth()
        var statusCode = 0
        val errorMessage = when (e) {
            is HttpException -> {
                statusCode = e.code()
                val errorBody = try {
                    e.response()?.errorBody()?.string()
                } catch (_: Exception) {
                    null
                }
                "HTTP error: ${e.message}${if (errorBody != null) " | Body: $errorBody" else ""}"
            }

            is IOException -> "Network error: ${e.message}"
            else -> "Unexpected error: ${e.message}"
        }

        val eventLogModel = NetworkEventLogger.createNetworkRequestFailedLog(
            auth.hostAppName, eventName, errorMessage, e.stackTraceToString(), statusCode
        ).apply {
            msisdn = input.msisdn
            integratedAppVersion = input.integratedAppVersion
            sdkInitiateTimeStamp = input.sdkInitiateTimeStamp
            integratedAppEventName = eventName
            userLatitude = input.userLatitude
            userLongitude = input.userLongitude
        }

        preparedLogEventData(auth, eventLogModel)
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

    private fun isMobileNetworkConnected(): Boolean {
        return try {
            val cm =
                appContext.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
            val network = cm?.activeNetwork ?: return false
            val caps = cm.getNetworkCapabilities(network) ?: return false
            caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR)
        } catch (e: Exception) {
            false
        }
    }

    private fun isWifiConnected(): Boolean {
        return try {
            val cm =
                appContext.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
            val network = cm?.activeNetwork ?: return false
            val caps = cm.getNetworkCapabilities(network) ?: return false
            caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)
        } catch (e: Exception) {
            false
        }
    }

    private fun is4GConnected(): Boolean {
        return try {
            val telephonyManager =
                appContext.getSystemService(Context.TELEPHONY_SERVICE) as? TelephonyManager
            val networkType = if (ActivityCompat.checkSelfPermission(
                    appContext, Manifest.permission.READ_PHONE_STATE
                ) == PackageManager.PERMISSION_GRANTED
            ) {
                telephonyManager?.dataNetworkType
            } else {
                TelephonyManager.NETWORK_TYPE_UNKNOWN
            }
            networkType == TelephonyManager.NETWORK_TYPE_LTE
        } catch (_: Exception) {
            false
        }
    }

    private fun getActiveNetworkMNC(): String {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                val sm = SubscriptionManager.from(appContext)
                val dataSubId = SubscriptionManager.getDefaultDataSubscriptionId()
                if (dataSubId != SubscriptionManager.INVALID_SUBSCRIPTION_ID) {
                    val si = sm.getActiveSubscriptionInfo(dataSubId)
                    return "0${si?.mnc ?: -1}"
                }
            }
        } catch (_: Exception) {
        }
        return "0-1"
    }
   private fun isPhoneStatePermissionGranted(): Boolean {
        return ContextCompat.checkSelfPermission(
            appContext, Manifest.permission.READ_PHONE_STATE
        ) == PackageManager.PERMISSION_GRANTED
    }
}

