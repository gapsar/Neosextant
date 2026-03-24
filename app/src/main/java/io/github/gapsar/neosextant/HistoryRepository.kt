package io.github.gapsar.neosextant

import android.content.Context
import android.util.Log
import org.json.JSONArray
import io.github.gapsar.neosextant.data.AppDatabase
import io.github.gapsar.neosextant.data.PositionEntryEntity

data class PositionEntry(
    val id: Long = System.currentTimeMillis(),
    val timestampStr: String,
    val latitude: Double,
    val longitude: Double,
    val errorEstimateNm: Double?, // Iterative solver shift or LOP error
    val mode: String // "ITERATIVE" or "LOP"
)

class HistoryRepository(private val context: Context) {
    private val PREFS_NAME = "neosextant_history_prefs"
    private val KEY_HISTORY = "position_history"
    private val historyDao = AppDatabase.getDatabase(context).historyDao()

    init {
        migrateLegacyData()
    }

    private fun migrateLegacyData() {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val jsonString = prefs.getString(KEY_HISTORY, null)
        if (jsonString != null) {
            try {
                val jsonArray = JSONArray(jsonString)
                val legacyEntries = mutableListOf<PositionEntryEntity>()
                for (i in 0 until jsonArray.length()) {
                    val obj = jsonArray.getJSONObject(i)
                    val entry = PositionEntryEntity(
                        id = obj.getLong("id"),
                        timestampStr = obj.getString("timestampStr"),
                        latitude = obj.getDouble("latitude"),
                        longitude = obj.getDouble("longitude"),
                        errorEstimateNm = if (obj.has("errorEstimateNm")) obj.getDouble("errorEstimateNm") else null,
                        mode = obj.getString("mode")
                    )
                    legacyEntries.add(entry)
                }
                if (legacyEntries.isNotEmpty()) {
                    historyDao.insertAll(legacyEntries)
                    Log.d("HistoryRepo", "Migrated ${legacyEntries.size} legacy entries to Room")
                }
            } catch (e: Exception) {
                Log.e("HistoryRepo", "Failed to parse legacy history JSON during migration", e)
            } finally {
                prefs.edit().remove(KEY_HISTORY).apply()
            }
        }
    }

    fun saveEntry(entry: PositionEntry) {
        historyDao.insert(PositionEntryEntity.fromPositionEntry(entry))
        Log.d("HistoryRepo", "Saved new position entry.")
    }

    fun getHistory(): List<PositionEntry> {
        return historyDao.getAll().map { it.toPositionEntry() }
    }

    fun deleteEntry(entryId: Long) {
        historyDao.deleteById(entryId)
        Log.d("HistoryRepo", "Deleted entry $entryId")
    }

    fun clearAll() {
        historyDao.deleteAll()
        Log.d("HistoryRepo", "Cleared all history")
    }
}
