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
import com.ptsl.network_sdk.network_data_worker.FTPNetworkDataWorker
import com.ptsl.network_sdk.network_data_worker.NetworkDataWorker
import com.ptsl.network_sdk.utils.CheckPermissionHandler
import com.ptsl.network_sdk.utils.SdkContainer
import java.lang.ref.WeakReference
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
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
    val isInitialized: Boolean get() = SdkContainer.isInitialized() && this::checkPermissionHandler.isInitialized && this::context.isInitialized

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
        try {
            SdkContainer.init(this.context)
            Log.i(TAG, "SDK Initialized for $appName via ${owner::class.java.simpleName}")
        } catch (e: Exception) {
            Log.e(TAG, "Critical failure during SDK Initialization: ${e.message}")
        }
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
        if (!isInitialized) {
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
                  val isPermissionsGranted =
                      checkPermissionHandler.isAllPermissionsGrantedExcludingGps()

                  val errorMessage = when {
                      !isPermissionsGranted -> "Required permissions (Location or Phone State) are missing."
                      !isGpsEnabled -> "GPS is disabled. Please enable GPS to proceed."
                      else -> "Required permissions are missing."
                  }

                  val error = NetworkDataResponse(
                      status = "Failed",
                      testResult = "Failed",
                      statusCode = 400,
                      message = errorMessage
                  )
                  callback(false, createSuccessStatus(error))
                  return@requestPermission
              }


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

                          if (!isLifecycleOwnerValid()) return@launch
                          callback(
                              true, createSuccessStatus(
                                  NetworkDataResponse(
                                      message = "SDK Task enqueued"
                                  )
                              )
                          )
                      }
                  }

                  UploadType.FTPNetworkDataCapture -> {
                      SdkContainer.coroutineScope.launch {
                          val auth = createAuthEntity()
                          SdkContainer.dao.insertAuthData(auth)

                          val workId = enqueueNetworkDataWork(
                              auth,
                              msisdn,
                              integratedAppVersion,
                              sdkInitiateTimeStamp,
                              integratedAppEventName,
                              userLatitude,
                              userLongitude,
                              uploadType
                          )

                          try {
                              val result = withTimeoutOrNull(60_000) {
                                  WorkManager.getInstance(context).getWorkInfoByIdFlow(workId)
                                      .filter { it?.state?.isFinished == true }.first()
                              }

                              if (!isLifecycleOwnerValid()) return@launch

                              if (result != null) {
                                  val response = result.outputData.getString("hostAppResponse")

                                  val networkDataResponse = try {
                                      response?.let {
                                          Gson().fromJson(it, NetworkDataResponse::class.java)
                                      }
                                  } catch (e: Exception) {
                                      Log.e(TAG, "Failed to parse worker JSON response: ${e.message}")
                                      null
                                  } ?: NetworkDataResponse(
                                      status = "Failed",
                                      testResult = "Failed",
                                      statusCode = 400,
                                      message = "FTP assessment failed."
                                  )
                                  callback(true, createSuccessStatus(networkDataResponse))

                              } else {
                                  // Timeout
                                  Log.w(
                                      TAG,
                                      "FTP assessment timed out after 60s. Cancelling work: $workId"
                                  )
                                  WorkManager.getInstance(context).cancelWorkById(workId)
                                  callback(
                                      false, createSuccessStatus(
                                          NetworkDataResponse(
                                              status = "Failed",
                                              statusCode = 408,
                                              message = "FTP assessment timed out."
                                          )
                                      )
                                  )
                              }

                          } catch (e: Exception) {
                              Log.e(TAG, "Error during FTP observation", e)
                              if (!isLifecycleOwnerValid()) return@launch
                              callback(
                                  false, createSuccessStatus(
                                      NetworkDataResponse(
                                          status = "Failed",
                                          statusCode = 500,
                                          message = "Error during FTP assessment: ${e.message}"
                                      )
                                  )
                              )


                          }
                      }
                  }
              }
          }
      }catch (e: Exception){
          Log.e(TAG, "Error starting upload process", e)
          callback(
              false, createSuccessStatus(
                  NetworkDataResponse(
                      status = "Failed",
                      statusCode = 500,
                      message = "Error starting upload: ${e.message}"
                  )
              )
          )
      }
    }

    private fun isLifecycleOwnerValid(): Boolean {
        val activity = activityRef?.get()
        if (activity == null || activity.isFinishing || activity.isDestroyed) {
            Log.w(TAG, "Host Activity is no longer valid. Skipping callback.")
            return false
        }

        val owner = lifecycleOwnerRef?.get()
        if (owner is Fragment) {
            if (!owner.isAdded || owner.isDetached || owner.viewLifecycleOwnerLiveData.value == null) {
                Log.w(TAG, "Host Fragment is no longer valid. Skipping callback.")
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
        type: UploadType,
    ): java.util.UUID {

        val inputData = workDataOf(
            "msisdn" to msisdn,
            "integratedAppVersion" to integratedAppVersion,
            "sdkInitiateTimeStamp" to sdkInitiateTimeStamp,
            "integratedAppEventName" to integratedAppEventName,
            "sdkVersion" to authEntity.sdkVersion,
            "userLatitude" to userLatitude,
            "userLongitude" to userLongitude
        )

        val workRequest = when (type) {
            UploadType.NetworkDataCapture -> OneTimeWorkRequestBuilder<NetworkDataWorker>().setInputData(inputData).build()

            UploadType.FTPNetworkDataCapture -> OneTimeWorkRequestBuilder<FTPNetworkDataWorker>().setInputData(inputData).build()
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
    FTPNetworkDataCapture
}
