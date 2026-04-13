package com.ptsl.network_sdk

import android.content.Context
import android.util.Log
import androidx.appcompat.app.AppCompatActivity
import androidx.work.Constraints
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.workDataOf
import androidx.fragment.app.Fragment
import androidx.lifecycle.LifecycleOwner
import com.ptsl.network_sdk.data_model.UploadStatus
import com.ptsl.network_sdk.data_model.entity.AuthEntity
import com.ptsl.network_sdk.network_data_worker.FTPNetworkDataWorker
import com.ptsl.network_sdk.network_data_worker.NetworkDataWorker
import com.ptsl.network_sdk.network_data_worker.SelectiveNetworkDataWorker
import com.ptsl.network_sdk.utils.CheckPermissionHandler
import com.ptsl.network_sdk.utils.NetworkSdk
import com.ptsl.network_sdk.utils.SdkContainer
import java.lang.ref.WeakReference
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.launch

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

    /** Initializes the SDK with the host activity and application name. */
    fun init(activity: AppCompatActivity, applicationName: String) {
        this.activityRef = WeakReference(activity)
        this.lifecycleOwnerRef = WeakReference(activity)
        this.checkPermissionHandler = CheckPermissionHandler(activity)
        this.context = activity.applicationContext
        this.applicationName = applicationName
        NetworkSdk.init(activity.applicationContext)
        Log.i(TAG, "SDK Initialized for $applicationName")
    }

    /** Initializes the SDK with the host fragment and application name. */
    fun init(fragment: Fragment, applicationName: String) {
        val activity = fragment.requireActivity() as? AppCompatActivity
            ?: throw IllegalStateException("Fragment must be attached to an AppCompatActivity")
        this.activityRef = WeakReference(activity)
        this.lifecycleOwnerRef = WeakReference(fragment)
        this.checkPermissionHandler = CheckPermissionHandler(activity)
        this.context = activity.applicationContext
        this.applicationName = applicationName
        NetworkSdk.init(activity.applicationContext)
        Log.i(TAG, "SDK Initialized for $applicationName from Fragment")
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
        checkMobileData: Boolean = false,
        checkBlSim: Boolean = false,
        checkPermissions: Boolean = false,
        checkBlSimPresence: Boolean = false,
        callback: (Boolean, UploadStatus) -> Unit
    ) {
        if (!this::checkPermissionHandler.isInitialized) {
            Log.e(TAG, "SDK not initialized. Call init() first.")
            callback(false, UploadStatus(message = "SDK not initialized"))
            return
        }

        val ignoreGpsLimit = uploadType == UploadType.FTPNetworkDataCapture || uploadType == UploadType.SelectiveNetworkDataCapture
        requestPermission(ignoreGpsLimit) { isGranted ->
            if (!isGranted && uploadType == UploadType.FTPNetworkDataCapture) {
                Log.w(TAG, "Permissions not granted for measurement capture.")

                val isGpsEnabled = checkPermissionHandler.isGpsEnabled()
                val isPermissionsGranted =
                    checkPermissionHandler.isAllPermissionsGrantedExcludingGps()

                val errorMessage =
                    when {
                        !isPermissionsGranted ->
                            "Required permissions (Location or Phone State) are missing."
                        !isGpsEnabled -> "GPS is disabled. Please enable GPS to proceed."
                        else -> "Required permissions are missing."
                    }
                val jsonError =
                    """{"status":"Failed","testResult":"Failed","statusCode":400,"message":"$errorMessage"}"""
                callback(false, createSuccessStatus(isGranted, jsonError))
                return@requestPermission
            }

            // For NetworkDataCapture, we proceed even if permissions are missing or GPS is
            // disabled.

            when (uploadType) {
                UploadType.NetworkDataCapture -> {
                    SdkContainer.coroutineScope.launch {
                        val auth = createAuthEntity()
                        SdkContainer.dao.insertAuthData(auth)

                        enqueueNetworkDataWork(
                            auth,
                            msisdn,
                            integratedAppVersion,
                            sdkInitiateTimeStamp,
                            integratedAppEventName,
                            userLatitude,
                            userLongitude,
                            uploadType
                        )

                        callback(true, createSuccessStatus(isGranted))
                    }
                }
                UploadType.FTPNetworkDataCapture -> {
                    SdkContainer.coroutineScope.launch {
                        val auth = createAuthEntity()
                        SdkContainer.dao.insertAuthData(auth)

                        val workId =
                            enqueueNetworkDataWork(
                                auth,
                                msisdn,
                                integratedAppVersion,
                                sdkInitiateTimeStamp,
                                integratedAppEventName,
                                userLatitude,
                                userLongitude,
                                uploadType
                            )

                        // Observe work result to return qualitative assessment to host app
                        val activity = activityRef?.get()
                        if (activity != null) {
                            activity.runOnUiThread {
                                val liveData =
                                    WorkManager.getInstance(context)
                                        .getWorkInfoByIdLiveData(workId)
                                val isCallbackCalled = AtomicBoolean(false)

                                Log.e("Owner Type", "Owner is ${lifecycleOwnerRef?.get()?.javaClass?.simpleName}")

                                val observer = object : androidx.lifecycle.Observer<androidx.work.WorkInfo?> {
                                    override fun onChanged(value: androidx.work.WorkInfo?) {
                                        val owner = lifecycleOwnerRef?.get() ?: run {
                                            liveData.removeObserver(this)
                                            return
                                        }

                                        // Safety check: close observer and return if host is no longer valid
                                        if (owner is Fragment && !owner.isAdded) {
                                            liveData.removeObserver(this)
                                            return
                                        }
                                        if (owner is AppCompatActivity && (owner.isFinishing || owner.isDestroyed)) {
                                            liveData.removeObserver(this)
                                            return
                                        }

                                        if (value != null && value.state.isFinished && isCallbackCalled.compareAndSet(false, true)) {
                                            val response = value.outputData.getString("hostAppResponse")
                                                ?: if (uploadType == UploadType.FTPNetworkDataCapture) "FTP assessment completed." else "Selective capture completed."
                                            callback(true, createSuccessStatus(isGranted, response))
                                            liveData.removeObserver(this)
                                        }
                                    }
                                }

                                val owner = lifecycleOwnerRef?.get() ?: activity
                                liveData.observe(owner, observer)
                            }
                        } else {
                            callback(
                                true,
                                createSuccessStatus(
                                    isGranted,
                                    "Work enqueued (Activity detached)"
                                )
                            )
                        }
                    }
                }

                UploadType.SelectiveNetworkDataCapture -> {
                    SdkContainer.coroutineScope.launch {
                        val auth = createAuthEntity()
                        SdkContainer.dao.insertAuthData(auth)

                        val workId = enqueueNetworkDataWork(
                            auth, msisdn, integratedAppVersion, sdkInitiateTimeStamp,
                            integratedAppEventName, userLatitude, userLongitude, uploadType,
                            checkMobileData, checkBlSim, checkPermissions, checkBlSimPresence
                        )
                        // Observe work result to return qualitative assessment to host app
                        val activity = activityRef?.get()
                        if (activity != null) {
                            activity.runOnUiThread {
                                val liveData =
                                    WorkManager.getInstance(context)
                                        .getWorkInfoByIdLiveData(workId)
                                val isCallbackCalled = AtomicBoolean(false)

                                Log.e("Owner Type", "Owner is ${lifecycleOwnerRef?.get()?.javaClass?.simpleName}")

                                val observer = object : androidx.lifecycle.Observer<androidx.work.WorkInfo?> {
                                    override fun onChanged(value: androidx.work.WorkInfo?) {
                                        val owner = lifecycleOwnerRef?.get() ?: run {
                                            liveData.removeObserver(this)
                                            return
                                        }

                                        // Safety check: close observer and return if host is no longer valid
                                        if (owner is Fragment && !owner.isAdded) {
                                            liveData.removeObserver(this)
                                            return
                                        }
                                        if (owner is AppCompatActivity && (owner.isFinishing || owner.isDestroyed)) {
                                            liveData.removeObserver(this)
                                            return
                                        }

                                        if (value != null && value.state.isFinished && isCallbackCalled.compareAndSet(false, true)) {
                                            val response = value.outputData.getString("hostAppResponse")
                                                ?: if (uploadType == UploadType.FTPNetworkDataCapture) "FTP assessment completed." else "Selective capture completed."
                                            callback(true, createSuccessStatus(isGranted, response))
                                            liveData.removeObserver(this)
                                        }
                                    }
                                }

                                val owner = lifecycleOwnerRef?.get() ?: activity
                                liveData.observe(owner, observer)
                            }
                        } else {
                            callback(
                                true,
                                createSuccessStatus(
                                    isGranted,
                                    "Work enqueued (Activity detached)"
                                )
                            )
                        }
                    }
                }
            }
        }
    }

    private fun createAuthEntity() =
        AuthEntity(
            sdkVersion = BuildConfig.SdkVersion,
            isSdkInitialized = this::checkPermissionHandler.isInitialized,
            isLocationEnabled =
                checkPermissionHandler.isLocationPermissionGranted() &&
                        checkPermissionHandler.isGpsEnabled(),
            isPhoneStateEnabled = checkPermissionHandler.isPhoneStatePermissionGranted(),
            hostAppName = applicationName
        )

    private fun createSuccessStatus(
        isGranted: Boolean,
        message: String = "SDK Task enqueued"
    ): UploadStatus {
        return UploadStatus(
            isSdkInit = this::checkPermissionHandler.isInitialized,
            isLocationEnabled = checkPermissionHandler.isLocationPermissionGranted(),
            isPhoneStateGranted = checkPermissionHandler.isPhoneStatePermissionGranted(),
            dataSaved = true,
            message = message
        )
    }

    /** Internal helper to request necessary permissions. */
    private fun requestPermission(ignoreGpsLimit: Boolean = false, callback: (Boolean) -> Unit) {
        if (this::checkPermissionHandler.isInitialized) {
            if (checkPermissionHandler.isPermissionGranted()) {
                callback(true)
            } else {
                checkPermissionHandler.requestPermission(
                    ignoreGpsLimit = ignoreGpsLimit,
                    callback = callback
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
        type: UploadType,
        checkMobileData: Boolean = false,
        checkBlSim: Boolean = false,
        checkPermissions: Boolean = false,
        checkBlSimPresence: Boolean = false
    ): java.util.UUID {

        val inputData =
            workDataOf(
                "msisdn" to msisdn,
                "integratedAppVersion" to integratedAppVersion,
                "sdkInitiateTimeStamp" to sdkInitiateTimeStamp,
                "integratedAppEventName" to integratedAppEventName,
                "sdkVersion" to authEntity.sdkVersion,
                "userLatitude" to userLatitude,
                "userLongitude" to userLongitude,
                "checkMobileData" to checkMobileData,
                "checkBlSim" to checkBlSim,
                "checkPermissions" to checkPermissions,
                "checkBlSimPresence" to checkBlSimPresence
            )

        val constraints =
            Constraints.Builder()
                .setRequiredNetworkType(
                    if (type == UploadType.NetworkDataCapture) NetworkType.CONNECTED
                    else NetworkType.NOT_REQUIRED
                )
                .build()

        val workRequest =
            when (type) {
                UploadType.NetworkDataCapture ->
                    OneTimeWorkRequestBuilder<NetworkDataWorker>()
                        .setConstraints(constraints)
                        .setInputData(inputData)
                        .build()
                UploadType.FTPNetworkDataCapture ->
                    OneTimeWorkRequestBuilder<FTPNetworkDataWorker>()
                        .setConstraints(constraints)
                        .setInputData(inputData)
                        .build()
                UploadType.SelectiveNetworkDataCapture ->
                    OneTimeWorkRequestBuilder<SelectiveNetworkDataWorker>()
                        .setConstraints(constraints)
                        .setInputData(inputData)
                        .build()
            }

        WorkManager.getInstance(context).enqueue(workRequest)
        Log.d(TAG, "Enqueued ${type.name} with ID: ${workRequest.id}")
        return workRequest.id
    }
}

/** Defines the available measurement types. */
enum class UploadType {
    /** Standard periodic network measurement. */
    NetworkDataCapture,
    /** Comprehensive diagnostic capture with FTP and Cell Info. */
    FTPNetworkDataCapture,
    /** One-time capture that returns raw data directly to the host app. */
    SelectiveNetworkDataCapture
}