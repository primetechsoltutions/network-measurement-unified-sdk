package com.ptsl.network_sdk.api

import com.ptsl.network_sdk.data_model.BaseResponse
import com.ptsl.network_sdk.data_model.FTPCellInfoGetDataRequest
import com.ptsl.network_sdk.data_model.FTPNetworkDataRequest
import com.ptsl.network_sdk.data_model.NetworkDataRequest
import com.ptsl.network_sdk.data_model.entity.AssessmentDataResponseEntity
import com.ptsl.network_sdk.data_model.entity.AuthEntity
import com.ptsl.network_sdk.data_model.entity.FTPCellInfoGetResponse
import com.ptsl.network_sdk.data_model.entity.FTPThresholdEntity
import com.ptsl.network_sdk.data_model.logger.LogDataWrapper
import okhttp3.RequestBody
import okhttp3.ResponseBody
import retrofit2.Response
import retrofit2.http.Body
import retrofit2.http.GET
import retrofit2.http.POST
import retrofit2.http.Query

interface ApiService {

    @POST("v903/UnifiedNetworkSDK/save-network-event-sdk-data")
    suspend fun postNetworkData(@Body request: NetworkDataRequest): BaseResponse<Any>

    @POST("v903/blWifiDeviceNetworkAssessments/save-network-assessment-data") // Assuming same endpoint or similar for FTP
    suspend fun postFTPNetworkData(@Body request: FTPNetworkDataRequest): BaseResponse<AssessmentDataResponseEntity>

    @POST("v903/UnifiedNetworkSDK/save-network-sdk-logs")
    suspend fun postNetworkDataLogs(@Body request: LogDataWrapper): BaseResponse<Any>

    @GET("NetworkMesurment/GetBandwithFile")
    suspend fun getBandwidthFile(@Query("Size") networkType: String?): Response<ResponseBody>

    @POST("NetworkMesurment/SaveBandwithFile")
    suspend fun saveBandwidthFile(@Body body: RequestBody): Response<Unit>

    @POST("v903/blWifiDeviceNetworkAssessments/cell-info-by-node-sector")
    suspend fun postFTPCellInfo(@Body request: FTPCellInfoGetDataRequest): BaseResponse<List<FTPCellInfoGetResponse>>

    @POST("v903/blWifiDeviceNetworkAssessments/get-ftp-thresholds")
    suspend fun getFTPThresholds(@Body request: AuthEntity): BaseResponse<FTPThresholdEntity>
}