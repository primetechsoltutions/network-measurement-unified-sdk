package com.ptsl.network_sdk

import android.content.Context
import android.util.Log
import androidx.appcompat.app.AppCompatActivity
import androidx.fragment.app.Fragment
import androidx.lifecycle.LifecycleOwner
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.workDataOf
import com.google.gson.Gson
import com.ptsl.network_sdk.data_model.NetworkDataResponse
import com.ptsl.network_sdk.data_model.UploadStatus
import com.ptsl.network_sdk.data_model.entity.AuthEntity
import com.ptsl.network_sdk.network_data_worker.FTPAssessmentExecutionInput
import com.ptsl.network_sdk.network_data_worker.FTPAssessmentExecutor
import com.ptsl.network_sdk.network_data_worker.NetworkDataWorker
import com.ptsl.network_sdk.utils.CheckPermissionHandler
import com.ptsl.network_sdk.utils.SdkContainer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.lang.ref.WeakReference

/**
 * Main entry point for the Network Measurement SDK. Handles initialization, permission requests,
 * and enqueueing measurement tasks.
 */
class NetworkDataUploader {
    private var activityRef: WeakReference<AppCompatActivity>? = null
    private var lifecycleOwnerRef: WeakReference<LifecycleOwner>? = null
    private lateinit var checkPermissionHandler: CheckPermissionHandler
    private lateinit var context: Context
    private lateinit var applicationName: String

    private val TAG = "NetworkDataUploader"

    fun init(activity: AppCompatActivity, applicationName: String) {
        setup(activity, activity, CheckPermissionHandler(activity), applicationName)
    }

    fun init(fragment: Fragment, applicationName: String) {
        val activity = fragment.activity as? AppCompatActivity ?: return
        setup(activity, fragment, CheckPermissionHandler(fragment), applicationName)
    }

    private fun setup(
        activity: AppCompatActivity,
        owner: LifecycleOwner,
        permissionHandler: CheckPermissionHandler,
        appName: String
    ) {
        this.activityRef = WeakReference(activity)
        this.lifecycleOwnerRef = WeakReference(owner)
        this.checkPermissionHandler = permissionHandler
        this.context = activity.applicationContext
        this.applicationName = appName
        SdkContainer.init(this.context)
        Log.i(TAG, "SDK Initialized for $appName via ${owner::class.java.simpleName}")
    }

    /**
     * Starts the data collection and upload process.
     * @param msisdn User mobile number
     * @param integratedAppVersion Version of the host app
     * @param sdkInitiateTimeStamp Format: yyyy-MM-dd'T'HH:mm:ss
     * @param integratedAppEventName Unique name for the event
     * @param uploadType Type of capture (Standard or FTP)
     * @param callback Result callback returning success status and details
     */
    fun startUploading(
        msisdn: String,
        integratedAppVersion: String,
        sdkInitiateTimeStamp: String,
        integratedAppEventName: String,
        userLatitude: Double = 0.0,
        userLongitude: Double = 0.0,
        uploadType: UploadType,
        callback: (Boolean, UploadStatus) -> Unit
    ) {
        if (!this::checkPermissionHandler.isInitialized || !SdkContainer.isInitialized()) {
            Log.e(TAG, "SDK not initialized. Call init() first.")
            callback(
                false, UploadStatus(
                    isSdkInit = false, response = Gson().toJson(
                        NetworkDataResponse(
                            status = "Failed", statusCode = 400, message = "SDK not initialized"
                        )
                    )
                )
            )
            return
        }

        try {
            val ignoreGpsLimit = uploadType == UploadType.FTPNetworkDataCapture
            requestPermission(ignoreGpsLimit) { isGranted ->
                if (!isGranted && uploadType == UploadType.FTPNetworkDataCapture) {
                    Log.w(TAG, "Permissions not granted for measurement capture.")

                    val isGpsEnabled = checkPermissionHandler.isGpsEnabled()
                    val isLocationPermissionsGranted =
                        checkPermissionHandler.isLocationPermissionGranted()
                    val isPhoneStatePermissionsGranted =
                        checkPermissionHandler.isPhoneStatePermissionGranted()

                    val errorMessage = when {
                        !isLocationPermissionsGranted -> "To continue the network assessment, please allow Location permission and enable Precise Location if available."
                        !isPhoneStatePermissionsGranted -> "To continue the network assessment, please allow Phone State permission."
                        !isGpsEnabled -> "To continue network assessment, please enable GPS/location services."
                        else -> "Required permissions are missing."
                    }

                    val error = NetworkDataResponse(
                        status = "Failed",
                        testResult = "Failed",
                        statusCode = 400,
                        message = errorMessage
                    )
                    dispatchCallback(callback, false, createSuccessStatus(error))
                    return@requestPermission
                }


                when (uploadType) {
                    UploadType.NetworkDataCapture -> {
                        SdkContainer.coroutineScope?.launch {
                            val auth = createAuthEntity()
                            SdkContainer.dao?.insertAuthData(auth)

                            enqueueNetworkDataWork(
                                auth,
                                msisdn,
                                integratedAppVersion,
                                sdkInitiateTimeStamp,
                                integratedAppEventName,
                                userLatitude,
                                userLongitude
                            )

                            dispatchCallback(
                                callback, true, createSuccessStatus(
                                    NetworkDataResponse(
                                        message = "SDK Task enqueued"
                                    )
                                )
                            )
                        }
                    }

                    UploadType.FTPNetworkDataCapture -> {
                        SdkContainer.coroutineScope?.launch {
                            val auth = createAuthEntity()
                            SdkContainer.dao?.insertAuthData(auth)

                            val ftpResponse = withTimeoutOrNull(60_000L) {
                                SdkContainer.apiService?.let { apiService ->
                                    SdkContainer.downloadUploadHelper?.let { downloader ->
                                        SdkContainer.dao?.let { dao ->
                                            FTPAssessmentExecutor(
                                                context, apiService, downloader, dao
                                            )
                                        }
                                    }
                                }?.execute(
                                    FTPAssessmentExecutionInput(
                                        msisdn,
                                        integratedAppVersion,
                                        sdkInitiateTimeStamp,
                                        integratedAppEventName,
                                        userLatitude,
                                        userLongitude
                                    )
                                )
                            }
                            if (ftpResponse != null) {
                                dispatchCallback(
                                    callback,
                                    ftpResponse.status.equals("Success", ignoreCase = true),
                                    createSuccessStatus(ftpResponse)
                                )
                            } else {
                                dispatchCallback(
                                    callback, false, createSuccessStatus(
                                        NetworkDataResponse(
                                            status = "Failed",
                                            statusCode = 400,
                                            message = "Network assessment couldn’t be completed due to a processing timeout, please try again."
                                        )
                                    )
                                )
                            }

                        }
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error starting upload process ${e.message}")
            dispatchCallback(
                callback, false, createSuccessStatus(
                    NetworkDataResponse(
                        status = "Failed",
                        statusCode = 400,
                        message = "Error starting upload process"
                    )
                )
            )
        }
    }


    private fun dispatchCallback(
        callback: (Boolean, UploadStatus) -> Unit, success: Boolean, status: UploadStatus
    ) {
        SdkContainer.coroutineScope?.launch {
            withContext(Dispatchers.Main) {
                if (!isLifecycleOwnerValid()) return@withContext
                callback(success, status)
            }
        }
    }

    private fun isLifecycleOwnerValid(): Boolean {
        val activity = activityRef?.get()
        if (activity == null || activity.isFinishing || activity.isDestroyed) {
            Log.w(TAG, "Host Activity is no longer valid. Skipping callback.")
            return false
        }

        val owner = lifecycleOwnerRef?.get()
        if (owner == null) {
            Log.w(TAG, "LifecycleOwner reference lost. Skipping callback.")
            return false
        }

        if (owner is Fragment) {
            if (!owner.isAdded || owner.isDetached || owner.viewLifecycleOwnerLiveData.value == null) {
                Log.w(
                    TAG,
                    "Host Fragment is no longer valid (detached or removed). Skipping callback."
                )
                return false
            }
        }
        return true
    }

    private fun createAuthEntity() = AuthEntity(
        sdkVersion = BuildConfig.SdkVersion,
        isSdkInitialized = this::checkPermissionHandler.isInitialized,
        isLocationEnabled = checkPermissionHandler.isLocationPermissionGranted() && checkPermissionHandler.isGpsEnabled(),
        isPhoneStateEnabled = checkPermissionHandler.isPhoneStatePermissionGranted(),
        hostAppName = applicationName
    )

    private fun createSuccessStatus(
        networkDataResponse: NetworkDataResponse = NetworkDataResponse(
            status = "Failed", statusCode = 400, message = ""
        )
    ): UploadStatus {
        return UploadStatus(
            isSdkInit = this::checkPermissionHandler.isInitialized,
            isLocationEnabled = checkPermissionHandler.isLocationPermissionGranted(),
            isPhoneStateGranted = checkPermissionHandler.isPhoneStatePermissionGranted(),
            response = Gson().toJson(networkDataResponse)
        )
    }

    /** Internal helper to request necessary permissions. */
    private fun requestPermission(ignoreGpsLimit: Boolean = false, callback: (Boolean) -> Unit) {
        if (this::checkPermissionHandler.isInitialized) {
            if (checkPermissionHandler.isPermissionGranted()) {
                callback(true)
            } else {
                checkPermissionHandler.requestPermission(
                    ignoreGpsLimit = ignoreGpsLimit, callback = callback
                )
            }
        } else {
            callback(false)
        }
    }

    private fun enqueueNetworkDataWork(
        authEntity: AuthEntity,
        msisdn: String,
        integratedAppVersion: String,
        sdkInitiateTimeStamp: String,
        integratedAppEventName: String,
        userLatitude: Double,
        userLongitude: Double,
    ): java.util.UUID {

        try {
            val inputData = workDataOf(
                "msisdn" to msisdn,
                "integratedAppVersion" to integratedAppVersion,
                "sdkInitiateTimeStamp" to sdkInitiateTimeStamp,
                "integratedAppEventName" to integratedAppEventName,
                "sdkVersion" to authEntity.sdkVersion,
                "userLatitude" to userLatitude,
                "userLongitude" to userLongitude
            )

            val workRequest =
                OneTimeWorkRequestBuilder<NetworkDataWorker>().setInputData(inputData).build()

            WorkManager.getInstance(context).enqueue(workRequest)
            Log.d(TAG, "Enqueued ${UploadType.NetworkDataCapture.name} with ID: ${workRequest.id}")
            return workRequest.id
        } catch (e: Exception) {
            Log.e(TAG, "Failed to enqueue work: ${e.message}")
            return java.util.UUID(0, 0)
        }
    }
}

/** Defines the available measurement types. */
enum class UploadType {
    /** Standard periodic network measurement. */
    NetworkDataCapture,

    /** Comprehensive diagnostic capture with FTP and Cell Info. */
    FTPNetworkDataCapture
}
