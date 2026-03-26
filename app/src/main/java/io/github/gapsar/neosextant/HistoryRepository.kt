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
    val mode: String, // "ITERATIVE" or "LOP"
    val estimatedLatitude: Double = 0.0,
    val estimatedLongitude: Double = 0.0,
    val imagesJson: String? = null
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
                        mode = obj.getString("mode"),
                        estimatedLatitude = 0.0,
                        estimatedLongitude = 0.0,
                        imagesJson = null
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

    companion object {
        fun serializeImages(images: List<io.github.gapsar.neosextant.model.ImageData>): String {
            val jsonArray = JSONArray()
            images.forEach { img ->
                val obj = org.json.JSONObject()
                obj.put("id", img.id)
                obj.put("uri", img.uri.toString())
                obj.put("name", img.name)
                obj.put("timestamp", img.timestamp)
                img.measuredHeight?.let { obj.put("measuredHeight", it) }
                
                val tr = img.tetra3Result
                val trObj = org.json.JSONObject()
                trObj.put("analysisState", tr.analysisState.name)
                trObj.put("solved", tr.solved)
                tr.raDeg?.let { trObj.put("raDeg", it) }
                tr.decDeg?.let { trObj.put("decDeg", it) }
                tr.rollDeg?.let { trObj.put("rollDeg", it) }
                tr.fovDeg?.let { trObj.put("fovDeg", it) }
                tr.errorMessage?.let { trObj.put("errorMessage", it) }
                
                val centroidsArr = JSONArray()
                tr.centroids.forEach { c ->
                    val pairArr = JSONArray()
                    pairArr.put(c.first)
                    pairArr.put(c.second)
                    centroidsArr.put(pairArr)
                }
                trObj.put("centroids", centroidsArr)
                obj.put("tetra3Result", trObj)
                
                img.lopData?.let { ld ->
                    val ldObj = org.json.JSONObject()
                    ld.interceptNm?.let { ldObj.put("interceptNm", it) }
                    ld.azimuthDeg?.let { ldObj.put("azimuthDeg", it) }
                    ld.observedAltitudeDeg?.let { ldObj.put("observedAltitudeDeg", it) }
                    ld.computedAltitudeDeg?.let { ldObj.put("computedAltitudeDeg", it) }
                    ld.errorMessage?.let { ldObj.put("errorMessage", it) }
                    obj.put("lopData", ldObj)
                }
                jsonArray.put(obj)
            }
            return jsonArray.toString()
        }

        fun deserializeImages(jsonString: String?): List<io.github.gapsar.neosextant.model.ImageData> {
            if (jsonString.isNullOrEmpty()) return emptyList()
            val result = mutableListOf<io.github.gapsar.neosextant.model.ImageData>()
            try {
                val array = JSONArray(jsonString)
                for (i in 0 until array.length()) {
                    val obj = array.getJSONObject(i)
                    
                    val trObj = obj.getJSONObject("tetra3Result")
                    val centroidsArr = trObj.optJSONArray("centroids")
                    val centroids = mutableListOf<Pair<Double, Double>>()
                    if (centroidsArr != null) {
                        for (j in 0 until centroidsArr.length()) {
                            val pairArr = centroidsArr.getJSONArray(j)
                            centroids.add(Pair(pairArr.getDouble(0), pairArr.getDouble(1)))
                        }
                    }
                    
                    val tetra3Result = io.github.gapsar.neosextant.model.Tetra3AnalysisResult(
                        analysisState = io.github.gapsar.neosextant.model.AnalysisState.valueOf(trObj.getString("analysisState")),
                        solved = trObj.optBoolean("solved", false),
                        raDeg = if (trObj.has("raDeg")) trObj.getDouble("raDeg") else null,
                        decDeg = if (trObj.has("decDeg")) trObj.getDouble("decDeg") else null,
                        rollDeg = if (trObj.has("rollDeg")) trObj.getDouble("rollDeg") else null,
                        fovDeg = if (trObj.has("fovDeg")) trObj.getDouble("fovDeg") else null,
                        centroids = centroids,
                        errorMessage = if (trObj.has("errorMessage")) trObj.getString("errorMessage") else null
                    )
                    
                    var lopData: io.github.gapsar.neosextant.model.LineOfPositionData? = null
                    if (obj.has("lopData")) {
                        val ldObj = obj.getJSONObject("lopData")
                        lopData = io.github.gapsar.neosextant.model.LineOfPositionData(
                            interceptNm = if (ldObj.has("interceptNm")) ldObj.getDouble("interceptNm") else null,
                            azimuthDeg = if (ldObj.has("azimuthDeg")) ldObj.getDouble("azimuthDeg") else null,
                            observedAltitudeDeg = if (ldObj.has("observedAltitudeDeg")) ldObj.getDouble("observedAltitudeDeg") else null,
                            computedAltitudeDeg = if (ldObj.has("computedAltitudeDeg")) ldObj.getDouble("computedAltitudeDeg") else null,
                            errorMessage = if (ldObj.has("errorMessage")) ldObj.getString("errorMessage") else null
                        )
                    }
                    
                    val img = io.github.gapsar.neosextant.model.ImageData(
                        id = obj.getLong("id"),
                        uri = android.net.Uri.parse(obj.getString("uri")),
                        name = obj.getString("name"),
                        timestamp = obj.getString("timestamp"),
                        measuredHeight = if (obj.has("measuredHeight")) obj.getDouble("measuredHeight") else null,
                        tetra3Result = tetra3Result,
                        lopData = lopData
                    )
                    result.add(img)
                }
            } catch (e: Exception) {
                Log.e("HistoryRepo", "Failed to deserialize images", e)
            }
            return result
        }
    }
}
