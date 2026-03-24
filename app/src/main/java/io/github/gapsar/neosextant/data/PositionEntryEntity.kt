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
    val mode: String
) {
    fun toPositionEntry(): PositionEntry {
        return PositionEntry(
            id = id,
            timestampStr = timestampStr,
            latitude = latitude,
            longitude = longitude,
            errorEstimateNm = errorEstimateNm,
            mode = mode
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
                mode = entry.mode
            )
        }
    }
}
