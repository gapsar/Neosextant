package com.example.basic_neosextant.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

@Dao
interface HistoryDao {
    @Query("SELECT * FROM position_history ORDER BY id DESC")
    fun getAll(): List<PositionEntryEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun insert(entry: PositionEntryEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun insertAll(entries: List<PositionEntryEntity>)

    @Query("DELETE FROM position_history WHERE id = :entryId")
    fun deleteById(entryId: Long)

    @Query("DELETE FROM position_history")
    fun deleteAll()
}
