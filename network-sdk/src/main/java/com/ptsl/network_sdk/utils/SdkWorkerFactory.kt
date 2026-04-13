package com.ptsl.network_sdk.utils

import android.content.Context
import androidx.work.ListenableWorker
import androidx.work.WorkerFactory
import androidx.work.WorkerParameters
import com.ptsl.network_sdk.network_data_worker.FTPNetworkDataWorker
import com.ptsl.network_sdk.network_data_worker.NetworkDataWorker
import com.ptsl.network_sdk.network_data_worker.SelectiveNetworkDataWorker

internal class SdkWorkerFactory : WorkerFactory(){
    override fun createWorker(
        appContext: Context,
        workerClassName: String,
        workerParameters: WorkerParameters
    ): ListenableWorker? {
        // Guard against WorkManager triggering workers before SdkContainer is initialized
        // (e.g., cold start from SystemJobService). If not initialized, return null to let
        // the default WorkerFactory handle it or skip gracefully.
        return try {
            when (workerClassName){
                NetworkDataWorker::class.java.name->
                    NetworkDataWorker(
                        appContext,
                        workerParameters,
                        SdkContainer.apiService,
                        SdkContainer.downloadUploadHelper,
                        SdkContainer.dao
                    )
                FTPNetworkDataWorker::class.java.name ->
                    FTPNetworkDataWorker(
                        appContext,
                        workerParameters,
                        SdkContainer.apiService,
                        SdkContainer.downloadUploadHelper,
                        SdkContainer.dao
                    )
                SelectiveNetworkDataWorker::class.java.name ->
                    SelectiveNetworkDataWorker(
                        appContext,
                        workerParameters,
                        SdkContainer.apiService,
                        SdkContainer.downloadUploadHelper,
                        SdkContainer.dao
                    )
                else -> null
            }
        } catch (e: UninitializedPropertyAccessException) {
            android.util.Log.e("SdkWorkerFactory", "SdkContainer not initialized. Cannot create worker: $workerClassName")
            null
        }
    }
}