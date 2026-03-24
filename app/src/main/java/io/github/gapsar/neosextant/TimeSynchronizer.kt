package io.github.gapsar.neosextant

import android.content.Context
import android.location.LocationManager
import android.os.SystemClock
import android.util.Log
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.util.Date

/**
 * Opportunistically fetches an accurate time signal from a reliable network source (NTP)
 * or GPS to calculate the offset between the phone's internal clock and true absolute time.
 * This offset is saved persistently so that true time can be approximated even when offline
 * during celestial navigation.
 * Accuracy: Sub-100ms with NTP, sub-1s with GPS.
 */
object TimeSynchronizer {
    private const val PREFS_NAME = "NeosextantTime"
    private const val KEY_OFFSET = "time_offset_millis"
    private var timeOffsetMillis: Long = 0L

    fun sync(context: Context) {
        // Load the persistently stored offset first so we have *something* immediately
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        timeOffsetMillis = prefs.getLong(KEY_OFFSET, 0L)
        Log.d("TimeSynchronizer", "Initialized with stored offset: ${timeOffsetMillis}ms")

        CoroutineScope(Dispatchers.IO).launch {
            // First, try to sync using NTP (highly accurate, sub-100ms)
            val ntpOffset = trySyncNTP()
            if (ntpOffset != null) {
                timeOffsetMillis = ntpOffset
                prefs.edit().putLong(KEY_OFFSET, timeOffsetMillis).apply()
                Log.d("TimeSynchronizer", "Successfully synced true time via NTP. Offset updated to: ${timeOffsetMillis}ms")
                return@launch
            }

            // If NTP fails (e.g. no internet), try checking GPS time if permission is available
            val gpsOffset = trySyncGPS(context)
            if (gpsOffset != null) {
                timeOffsetMillis = gpsOffset
                prefs.edit().putLong(KEY_OFFSET, timeOffsetMillis).apply()
                Log.d("TimeSynchronizer", "Successfully synced true time via GPS. Offset updated to: ${timeOffsetMillis}ms")
                return@launch
            }

            Log.d("TimeSynchronizer", "Failed to sync via NTP and GPS. Keeping existing offset of ${timeOffsetMillis}ms")
        }
    }

    private fun trySyncNTP(): Long? {
        return try {
            val ntpHost = "time.google.com"
            val address = InetAddress.getByName(ntpHost)
            val socket = DatagramSocket()
            socket.soTimeout = 3000

            val buffer = ByteArray(48)
            buffer[0] = 0x1B // NTP Version 3, Mode 3 (Client)
            val requestTime = SystemClock.elapsedRealtime()
            val requestPacket = DatagramPacket(buffer, buffer.size, address, 123)
            socket.send(requestPacket)

            val responsePacket = DatagramPacket(buffer, buffer.size)
            socket.receive(responsePacket)
            val responseTime = SystemClock.elapsedRealtime()

            val transmitTimestamp = readTimeStamp(buffer, 40)

            val roundTripTime = responseTime - requestTime
            // Transmit timestamp from server is the server time when it sent the packet.
            // Server time exactly at message receipt on client is approximately ServerTransmitTime + RoundTripTime/2
            val serverTimeAtReceipt = transmitTimestamp + roundTripTime / 2

            // Calculate what System.currentTimeMillis() was when we received the packet
            val deviceTimeAtReceipt = System.currentTimeMillis()

            val offset = serverTimeAtReceipt - deviceTimeAtReceipt
            socket.close()
            offset
        } catch (e: Exception) {
            Log.d("TimeSynchronizer", "NTP Sync Exception: ${e.message}")
            null
        }
    }

    @android.annotation.SuppressLint("MissingPermission")
    private fun trySyncGPS(context: Context): Long? {
        return try {
            if (ContextCompat.checkSelfPermission(context, android.Manifest.permission.ACCESS_FINE_LOCATION) == android.content.pm.PackageManager.PERMISSION_GRANTED) {
                val locationManager = context.getSystemService(Context.LOCATION_SERVICE) as LocationManager
                val provider = LocationManager.GPS_PROVIDER
                val lastKnownLocation = locationManager.getLastKnownLocation(provider)
                if (lastKnownLocation != null) {
                    // location.time is the UTC time of the fix
                    // location.elapsedRealtimeNanos is when the fix was delivered
                    val fixTimeUtc = lastKnownLocation.time
                    val fixAgeMillis = (SystemClock.elapsedRealtimeNanos() - lastKnownLocation.elapsedRealtimeNanos) / 1000000L

                    if (fixAgeMillis < 60000) { // Only use if fix is relatively recent (e.g. < 1 minute old)
                        val assumedCurrentGpsTime = fixTimeUtc + fixAgeMillis
                        return assumedCurrentGpsTime - System.currentTimeMillis()
                    }
                }
            }
            null
        } catch (e: Exception) {
            Log.d("TimeSynchronizer", "GPS Sync Exception: ${e.message}")
            null
        }
    }

    private fun readTimeStamp(buffer: ByteArray, offset: Int): Long {
        val seconds = read32(buffer, offset)
        val fraction = read32(buffer, offset + 4)
        // NTP timestamp is seconds since Jan 1, 1900.
        // Unix time is seconds since Jan 1, 1970.
        // The difference is 2208988800 seconds.
        return ((seconds - 2208988800L) * 1000L) + ((fraction * 1000L) / 0x100000000L)
    }

    private fun read32(buffer: ByteArray, offset: Int): Long {
        val b0 = buffer[offset].toLong() and 0xFF
        val b1 = buffer[offset + 1].toLong() and 0xFF
        val b2 = buffer[offset + 2].toLong() and 0xFF
        val b3 = buffer[offset + 3].toLong() and 0xFF
        return (b0 shl 24) or (b1 shl 16) or (b2 shl 8) or b3
    }

    /**
     * Returns a Date object representing the highly accurate True Time
     * by applying the mathematically calculated offset to the phone's internal clock.
     */
    fun getTrueTime(): Date {
        return Date(System.currentTimeMillis() + timeOffsetMillis)
    }
}
