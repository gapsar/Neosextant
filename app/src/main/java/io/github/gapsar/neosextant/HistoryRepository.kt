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
        // S-02: Delete associated image files before removing the DB entry
        try {
            val entity = historyDao.getById(entryId)
            if (entity != null) {
                val images = deserializeImages(entity.imagesJson)
                images.forEach { img ->
                    try {
                        val path = img.uri.path
                        if (path != null) {
                            val file = java.io.File(path)
                            if (file.exists()) {
                                file.delete()
                                Log.d("HistoryRepo", "Deleted image file: ${file.name}")
                            }
                        }
                    } catch (e: Exception) {
                        Log.w("HistoryRepo", "Failed to delete image file", e)
                    }
                }
            }
        } catch (e: Exception) {
            Log.w("HistoryRepo", "Failed to clean up image files for entry $entryId", e)
        }
        historyDao.deleteById(entryId)
        Log.d("HistoryRepo", "Deleted entry $entryId")
    }

    fun clearAll() {
        // S-02: Delete all history image files before clearing DB
        try {
            val historyDir = java.io.File(context.getExternalFilesDir(null), "history_images")
            if (historyDir.exists() && historyDir.isDirectory) {
                historyDir.listFiles()?.forEach { file ->
                    file.delete()
                }
                Log.d("HistoryRepo", "Deleted all history image files")
            }
        } catch (e: Exception) {
            Log.w("HistoryRepo", "Failed to clean up history image directory", e)
        }
        historyDao.deleteAll()
        Log.d("HistoryRepo", "Cleared all history")
    }

    companion object {
        private fun vec3Json(v: SensorCalibrator.Vec3): org.json.JSONObject =
            org.json.JSONObject().apply {
                put("x", v.x.toDouble()); put("y", v.y.toDouble()); put("z", v.z.toDouble())
            }

        private fun vec3From(o: org.json.JSONObject?): SensorCalibrator.Vec3? =
            o?.let {
                SensorCalibrator.Vec3(
                    it.optDouble("x", 0.0).toFloat(),
                    it.optDouble("y", 0.0).toFloat(),
                    it.optDouble("z", 0.0).toFloat()
                )
            }

        private fun recordJson(r: io.github.gapsar.neosextant.model.ShotSensorRecord): org.json.JSONObject =
            org.json.JSONObject().apply {
                r.exposureStartNs?.let { put("exposureStartNs", it) }
                r.exposureEndNs?.let { put("exposureEndNs", it) }
                put("sampleCount", r.sampleCount)
                put("gyroWeight", r.gyroWeight)
                put("rawDirection", vec3Json(r.rawDirection))
                put("rawMagnitude", r.rawMagnitude)
                put("rawDirectionUnweighted", vec3Json(r.rawDirectionUnweighted))
                put("calibratedGravity", vec3Json(r.calibratedGravity))
                put("gravityPath", r.gravityPath)
                r.batteryTempC?.let { put("batteryTempC", it.toDouble()) }
            }

        private fun recordFrom(o: org.json.JSONObject?): io.github.gapsar.neosextant.model.ShotSensorRecord? {
            if (o == null) return null
            return io.github.gapsar.neosextant.model.ShotSensorRecord(
                exposureStartNs = if (o.has("exposureStartNs")) o.getLong("exposureStartNs") else null,
                exposureEndNs = if (o.has("exposureEndNs")) o.getLong("exposureEndNs") else null,
                sampleCount = o.optInt("sampleCount", 0),
                gyroWeight = o.optDouble("gyroWeight", 0.0),
                rawDirection = vec3From(o.optJSONObject("rawDirection")) ?: return null,
                rawMagnitude = o.optDouble("rawMagnitude", 0.0),
                rawDirectionUnweighted = vec3From(o.optJSONObject("rawDirectionUnweighted")) ?: return null,
                calibratedGravity = vec3From(o.optJSONObject("calibratedGravity")) ?: return null,
                gravityPath = o.optString("gravityPath", ""),
                batteryTempC = if (o.has("batteryTempC")) o.getDouble("batteryTempC").toFloat() else null
            )
        }

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
                tr.numMatches?.let { trObj.put("numMatches", it) }
                tr.errorMessage?.let { trObj.put("errorMessage", it) }
                
                val centroidsArr = JSONArray()
                tr.centroids.forEach { c ->
                    val pairArr = JSONArray()
                    pairArr.put(c.first)
                    pairArr.put(c.second)
                    centroidsArr.put(pairArr)
                }
                trObj.put("centroids", centroidsArr)

                val matchedStarsArr = JSONArray()
                tr.matchedStars.forEach { s ->
                    val starObj = org.json.JSONObject()
                    s.name?.let { starObj.put("name", it) }
                    s.constellation?.let { starObj.put("constellation", it) }
                    starObj.put("hipId", s.hipId)
                    starObj.put("y", s.y)
                    starObj.put("x", s.x)
                    s.magnitude?.let { starObj.put("magnitude", it) }
                    matchedStarsArr.put(starObj)
                }
                trObj.put("matchedStars", matchedStarsArr)

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

                // The gravity vector that actually produced the fix. Without it a
                // bad fix cannot be diagnosed after the fact — which orientation
                // the phone thought it was in is the single most useful thing to
                // know, and it was previously thrown away.
                img.gravityVector?.let { obj.put("gravityVector", vec3Json(it)) }
                img.sensorModelJson?.let { obj.put("sensorModel", org.json.JSONObject(it)) }

                // Per-shot burst detail, deliberately WITHOUT centroids or matched
                // stars: those dominate the record size and are not needed for
                // diagnosis. Gravity plus timestamp per shot is what distinguishes
                // the two handheld failure modes — jitter shows up as scatter
                // between these vectors, smooth drift as a monotonic trend, and
                // only the latter escapes the repeatability figure.
                if (img.burstSubResults.isNotEmpty()) {
                    val subsArr = JSONArray()
                    img.burstSubResults.forEach { sub ->
                        val sObj = org.json.JSONObject()
                        sObj.put("timestamp", sub.timestamp)
                        sub.measuredHeight?.let { sObj.put("measuredHeight", it) }
                        sObj.put("gravityVector", vec3Json(sub.gravityVector))
                        val str = sub.tetra3Result
                        sObj.put("solved", str.solved)
                        str.raDeg?.let { sObj.put("raDeg", it) }
                        str.decDeg?.let { sObj.put("decDeg", it) }
                        str.rollDeg?.let { sObj.put("rollDeg", it) }
                        str.fovDeg?.let { sObj.put("fovDeg", it) }
                        str.numMatches?.let { sObj.put("numMatches", it) }
                        sub.sensorRecord?.let { sObj.put("sensorRecord", recordJson(it)) }
                        subsArr.put(sObj)
                    }
                    obj.put("burstSubResults", subsArr)
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

                    val matchedStarsArr = trObj.optJSONArray("matchedStars")
                    val matchedStars = mutableListOf<io.github.gapsar.neosextant.model.MatchedStar>()
                    if (matchedStarsArr != null) {
                        for (j in 0 until matchedStarsArr.length()) {
                            val starObj = matchedStarsArr.getJSONObject(j)
                            matchedStars.add(
                                io.github.gapsar.neosextant.model.MatchedStar(
                                    name = if (starObj.has("name")) starObj.getString("name") else null,
                                    constellation = if (starObj.has("constellation")) starObj.getString("constellation") else null,
                                    hipId = starObj.optInt("hipId", -1),
                                    y = starObj.optDouble("y", 0.0),
                                    x = starObj.optDouble("x", 0.0),
                                    magnitude = if (starObj.has("magnitude")) starObj.getDouble("magnitude") else null
                                )
                            )
                        }
                    }
                    
                    val tetra3Result = io.github.gapsar.neosextant.model.Tetra3AnalysisResult(
                        analysisState = io.github.gapsar.neosextant.model.AnalysisState.valueOf(trObj.getString("analysisState")),
                        solved = trObj.optBoolean("solved", false),
                        raDeg = if (trObj.has("raDeg")) trObj.getDouble("raDeg") else null,
                        decDeg = if (trObj.has("decDeg")) trObj.getDouble("decDeg") else null,
                        rollDeg = if (trObj.has("rollDeg")) trObj.getDouble("rollDeg") else null,
                        fovDeg = if (trObj.has("fovDeg")) trObj.getDouble("fovDeg") else null,
                        numMatches = if (trObj.has("numMatches")) trObj.getInt("numMatches") else null,
                        centroids = centroids,
                        matchedStars = matchedStars,
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
                    
                    // Absent in records written before this was persisted, so
                    // everything here is optional and old rows still load.
                    val subsArr = obj.optJSONArray("burstSubResults")
                    val subs = mutableListOf<io.github.gapsar.neosextant.model.BurstSubResult>()
                    if (subsArr != null) {
                        for (j in 0 until subsArr.length()) {
                            val sObj = subsArr.getJSONObject(j)
                            val g = vec3From(sObj.optJSONObject("gravityVector")) ?: continue
                            subs.add(
                                io.github.gapsar.neosextant.model.BurstSubResult(
                                    path = "",
                                    uri = android.net.Uri.EMPTY,
                                    gravityVector = g,
                                    timestamp = sObj.optString("timestamp", ""),
                                    measuredHeight = if (sObj.has("measuredHeight")) sObj.getDouble("measuredHeight") else null,
                                    tetra3Result = io.github.gapsar.neosextant.model.Tetra3AnalysisResult(
                                        analysisState = if (sObj.optBoolean("solved", false))
                                            io.github.gapsar.neosextant.model.AnalysisState.SUCCESS
                                        else io.github.gapsar.neosextant.model.AnalysisState.FAILURE,
                                        solved = sObj.optBoolean("solved", false),
                                        raDeg = if (sObj.has("raDeg")) sObj.getDouble("raDeg") else null,
                                        decDeg = if (sObj.has("decDeg")) sObj.getDouble("decDeg") else null,
                                        rollDeg = if (sObj.has("rollDeg")) sObj.getDouble("rollDeg") else null,
                                        fovDeg = if (sObj.has("fovDeg")) sObj.getDouble("fovDeg") else null,
                                        numMatches = if (sObj.has("numMatches")) sObj.getInt("numMatches") else null
                                    ),
                                    sensorRecord = recordFrom(sObj.optJSONObject("sensorRecord"))
                                )
                            )
                        }
                    }

                    val img = io.github.gapsar.neosextant.model.ImageData(
                        id = obj.getLong("id"),
                        uri = android.net.Uri.parse(obj.getString("uri")),
                        name = obj.getString("name"),
                        timestamp = obj.getString("timestamp"),
                        measuredHeight = if (obj.has("measuredHeight")) obj.getDouble("measuredHeight") else null,
                        gravityVector = vec3From(obj.optJSONObject("gravityVector")),
                        tetra3Result = tetra3Result,
                        lopData = lopData,
                        burstSubResults = subs,
                        sensorModelJson = obj.optJSONObject("sensorModel")?.toString()
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
