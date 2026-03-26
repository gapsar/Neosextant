package io.github.gapsar.neosextant.data

import androidx.room.Entity
import androidx.room.PrimaryKey
import io.github.gapsar.neosextant.PositionEntry

@Entity(tableName = "position_history")
data class PositionEntryEntity(
    @PrimaryKey
    val id: Long,
    val timestampStr: String,
    val latitude: Double,
    val longitude: Double,
    val errorEstimateNm: Double?,
    val mode: String,
    val estimatedLatitude: Double = 0.0,
    val estimatedLongitude: Double = 0.0,
    val imagesJson: String? = null
) {
    fun toPositionEntry(): PositionEntry {
        return PositionEntry(
            id = id,
            timestampStr = timestampStr,
            latitude = latitude,
            longitude = longitude,
            errorEstimateNm = errorEstimateNm,
            mode = mode,
            estimatedLatitude = estimatedLatitude,
            estimatedLongitude = estimatedLongitude,
            imagesJson = imagesJson
        )
    }

    companion object {
        fun fromPositionEntry(entry: PositionEntry): PositionEntryEntity {
            return PositionEntryEntity(
                id = entry.id,
                timestampStr = entry.timestampStr,
                latitude = entry.latitude,
                longitude = entry.longitude,
                errorEstimateNm = entry.errorEstimateNm,
                mode = entry.mode,
                estimatedLatitude = entry.estimatedLatitude,
                estimatedLongitude = entry.estimatedLongitude,
                imagesJson = entry.imagesJson
            )
        }
    }
}
