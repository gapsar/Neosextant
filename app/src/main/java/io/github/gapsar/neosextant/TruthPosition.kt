package io.github.gapsar.neosextant

import android.content.Context
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone
import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * A position the user knows to be true (the ship's GPS, a chart, a landmark),
 * and the UTC time it was read. It is only used to measure the error of a
 * fix, never as an input to the fix itself.
 */
data class TruthPosition(val latitude: Double, val longitude: Double, val timeUtc: String)

object TruthPositions {
    private const val PREFS = "neosextant_prefs"
    private const val KEY_LAT = "reference_latitude"
    private const val KEY_LON = "reference_longitude"
    private const val KEY_TIME = "reference_time_utc"
    private const val EARTH_RADIUS_NM = 3440.065

    /** Same format and clock as the shot timestamps, so the two compare directly. */
    fun utcFormat() = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS", Locale.US).apply {
        timeZone = TimeZone.getTimeZone("UTC")
        isLenient = false
    }

    fun nowUtc(): String = utcFormat().format(TimeSynchronizer.getTrueTime())

    /** The reference position set in Settings, copied into each new fix. */
    fun loadReference(context: Context): TruthPosition? {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val time = prefs.getString(KEY_TIME, null) ?: return null
        if (!prefs.contains(KEY_LAT) || !prefs.contains(KEY_LON)) return null
        return TruthPosition(
            java.lang.Double.longBitsToDouble(prefs.getLong(KEY_LAT, 0L)),
            java.lang.Double.longBitsToDouble(prefs.getLong(KEY_LON, 0L)),
            time
        )
    }

    fun saveReference(context: Context, position: TruthPosition) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putLong(KEY_LAT, java.lang.Double.doubleToRawLongBits(position.latitude))
            .putLong(KEY_LON, java.lang.Double.doubleToRawLongBits(position.longitude))
            .putString(KEY_TIME, position.timeUtc)
            .apply()
    }

    fun clearReference(context: Context) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .remove(KEY_LAT).remove(KEY_LON).remove(KEY_TIME)
            .apply()
    }

    /**
     * Parses a latitude or a longitude typed as decimal degrees ("47.2057",
     * "-3.0867") or as degrees and minutes, as a ship's GPS shows them
     * ("47 12.345 N", "47°12.345'N", "003 05 12 W"). A comma is read as a
     * decimal point. Returns null if the text is not a valid coordinate.
     */
    fun parseCoordinate(text: String, isLatitude: Boolean): Double? {
        var t = text.trim().uppercase(Locale.US).replace(',', '.')
        if (t.isEmpty()) return null
        val positive = if (isLatitude) 'N' else 'E'
        val negative = if (isLatitude) 'S' else 'W'
        val letters = t.filter { it.isLetter() }
        var sign = 1.0
        when (letters) {
            "" -> {}
            positive.toString() -> {}
            negative.toString() -> sign = -1.0
            else -> return null
        }
        t = t.filter { !it.isLetter() }
        if (t.trim().startsWith("-")) {
            if (letters.isNotEmpty()) return null
            sign = -1.0
            t = t.trim().removePrefix("-")
        }
        val parts = t.split(Regex("[^0-9.]+")).filter { it.isNotEmpty() }
        val numbers = parts.map { it.toDoubleOrNull() ?: return null }
        val value = when (numbers.size) {
            1 -> numbers[0]
            2 -> if (numbers[1] < 60.0) numbers[0] + numbers[1] / 60.0 else return null
            3 -> if (numbers[1] < 60.0 && numbers[2] < 60.0)
                numbers[0] + numbers[1] / 60.0 + numbers[2] / 3600.0 else return null
            else -> return null
        }
        val limit = if (isLatitude) 90.0 else 180.0
        return if (value <= limit) sign * value else null
    }

    /** Great-circle distance in nautical miles. */
    fun distanceNm(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val p1 = Math.toRadians(lat1)
        val p2 = Math.toRadians(lat2)
        val dp = p2 - p1
        val dl = Math.toRadians(lon2 - lon1)
        val h = sin(dp / 2) * sin(dp / 2) + cos(p1) * cos(p2) * sin(dl / 2) * sin(dl / 2)
        return 2 * EARTH_RADIUS_NM * asin(sqrt(h.coerceIn(0.0, 1.0)))
    }

    /** Seconds from [fromUtc] to [toUtc], or null if either does not parse. */
    fun secondsBetween(fromUtc: String, toUtc: String): Double? {
        val f = utcFormat()
        return try {
            (f.parse(toUtc)!!.time - f.parse(fromUtc)!!.time) / 1000.0
        } catch (_: Exception) { null }
    }
}
