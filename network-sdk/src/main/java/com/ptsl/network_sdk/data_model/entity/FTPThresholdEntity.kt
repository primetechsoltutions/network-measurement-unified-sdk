package com.ptsl.network_sdk.data_model.entity

import androidx.annotation.Keep
import androidx.room.Entity
import androidx.room.PrimaryKey
import com.google.gson.annotations.SerializedName

@Entity
@Keep
data class FTPThresholdEntity(
    @PrimaryKey
    var id: Int = 1, // Single row for caching
    @SerializedName("rsrpThreshold") var rsrpThreshold: Int = 105,
    @SerializedName("dlSpeedThreshold") var dlSpeedThreshold: Double = 5000.0,
    @SerializedName("ulSpeedThreshold") var ulSpeedThreshold: Double = 5000.0,
    @SerializedName("nbhDlThroughputThreshold") var nbhDlThroughputThreshold: Double = 5.0,
    @SerializedName("lastUpdated") var lastUpdated: Long = 0L
)
