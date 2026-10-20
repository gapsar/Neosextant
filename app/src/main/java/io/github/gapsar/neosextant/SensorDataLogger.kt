package io.github.gapsar.neosextant

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.os.SystemClock
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import java.io.BufferedWriter
import java.io.File
import java.io.FileWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class SensorDataLogger(private val context: Context) {
    private var isRecording = false
    private var currentFile: File? = null
    // Fresh channel per recording; closed on stop so the consumer drains
    // every buffered line into the current file before finishing.
    private var dataChannel: Channel<String>? = null
    private var loggingJob: Job? = null
    private val scope = CoroutineScope(Dispatchers.IO)

    fun startRecording(): File? {
        if (isRecording) return currentFile

        val logDir = File(context.getExternalFilesDir(null), "sensor_logs")
        if (!logDir.exists()) {
            logDir.mkdirs()
        }

        val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        currentFile = File(logDir, "sensor_log_$timestamp.csv")

        try {
            val writer = FileWriter(currentFile, true)
            // Write CSV Header
            writer.append("Timestamp,Accel_X,Accel_Y,Accel_Z,Gyro_X,Gyro_Y,Gyro_Z,Kalman_Cal_X,Kalman_Cal_Y,Kalman_Cal_Z,Kalman_Raw_X,Kalman_Raw_Y,Kalman_Raw_Z,Android_Grav_X,Android_Grav_Y,Android_Grav_Z,Kalman_Wave_Corrected_X,Kalman_Wave_Corrected_Y,Kalman_Wave_Corrected_Z,Android_Wave_Corrected_X,Android_Wave_Corrected_Y,Android_Wave_Corrected_Z,Event_Time_ns,Gyro_Event_Time_ns,Battery_Temp_C\n")
            writer.flush()
            writer.close()

            val channel = Channel<String>(capacity = Channel.UNLIMITED)
            dataChannel = channel
            isRecording = true

            // Start consuming the channel
            loggingJob = scope.launch {
                val fw = BufferedWriter(FileWriter(currentFile, true))
                try {
                    for (line in channel) {
                        fw.append(line)
                        fw.append("\n")
                    }
                } catch (e: Exception) {
                    Log.e("SensorDataLogger", "Error writing to CSV", e)
                } finally {
                    try {
                        fw.flush()
                        fw.close()
                    } catch (e: Exception) {
                        Log.e("SensorDataLogger", "Error closing CSV writer", e)
                    }
                }
            }
        } catch (e: Exception) {
            Log.e("SensorDataLogger", "Failed to create log file", e)
            return null
        }
        return currentFile
    }

    fun stopRecording() {
        if (!isRecording) return
        isRecording = false
        // Closing (not cancelling) lets the consumer loop finish draining
        // buffered lines before the writer is flushed and closed.
        dataChannel?.close()
        dataChannel = null
        loggingJob = null
    }

    fun isRecording() = isRecording

    // The battery temperature changes slowly and its broadcast is not free:
    // read it at most every BATTERY_READ_INTERVAL_MS.
    private var batteryTempC: Float? = null
    private var batteryReadMs: Long? = null

    /**
     * One row per accelerometer event. [timestamp] is the wall clock at the
     * callback; [eventTimeNs] is the accelerometer event time and
     * [gyroEventTimeNs] the time of the last gyro sample, both on the
     * elapsedRealtime clock, the same clock as the camera SENSOR_TIMESTAMP.
     */
    fun logData(
        timestamp: Long,
        eventTimeNs: Long,
        gyroEventTimeNs: Long,
        rawAccel: FloatArray,
        rawGyro: FloatArray,
        kalmanCal: FloatArray,
        kalmanRaw: FloatArray,
        androidGrav: FloatArray,
        kalmanWaveCorrected: FloatArray,
        androidWaveCorrected: FloatArray
    ) {
        if (!isRecording) return

        val line = buildString {
            append(timestamp).append(",")
            append(rawAccel[0]).append(",").append(rawAccel[1]).append(",").append(rawAccel[2]).append(",")
            append(rawGyro[0]).append(",").append(rawGyro[1]).append(",").append(rawGyro[2]).append(",")
            append(kalmanCal[0]).append(",").append(kalmanCal[1]).append(",").append(kalmanCal[2]).append(",")
            append(kalmanRaw[0]).append(",").append(kalmanRaw[1]).append(",").append(kalmanRaw[2]).append(",")
            append(androidGrav[0]).append(",").append(androidGrav[1]).append(",").append(androidGrav[2]).append(",")
            append(kalmanWaveCorrected[0]).append(",").append(kalmanWaveCorrected[1]).append(",").append(kalmanWaveCorrected[2]).append(",")
            append(androidWaveCorrected[0]).append(",").append(androidWaveCorrected[1]).append(",").append(androidWaveCorrected[2]).append(",")
            append(eventTimeNs).append(",").append(gyroEventTimeNs).append(",")
            append(currentBatteryTempC() ?: "")
        }
        dataChannel?.trySend(line)
    }

    private fun currentBatteryTempC(): Float? {
        val now = SystemClock.elapsedRealtime()
        val last = batteryReadMs
        if (last == null || now - last >= BATTERY_READ_INTERVAL_MS) {
            batteryReadMs = now
            batteryTempC = readBatteryTemperatureC(context)
        }
        return batteryTempC
    }

    companion object {
        private const val BATTERY_READ_INTERVAL_MS = 10_000L

        /**
         * Battery temperature in deg C, a stand-in for the IMU temperature
         * (Android rarely exposes that one). Null if the system does not give it.
         */
        fun readBatteryTemperatureC(context: Context): Float? {
            val intent: Intent = context.registerReceiver(
                null, IntentFilter(Intent.ACTION_BATTERY_CHANGED)) ?: return null
            val tenths = intent.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, Int.MIN_VALUE)
            return if (tenths == Int.MIN_VALUE) null else tenths / 10f
        }
    }
}
