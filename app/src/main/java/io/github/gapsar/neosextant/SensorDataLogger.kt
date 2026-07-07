package io.github.gapsar.neosextant

import android.content.Context
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
            writer.append("Timestamp,Accel_X,Accel_Y,Accel_Z,Gyro_X,Gyro_Y,Gyro_Z,Kalman_Cal_X,Kalman_Cal_Y,Kalman_Cal_Z,Kalman_Raw_X,Kalman_Raw_Y,Kalman_Raw_Z,Android_Grav_X,Android_Grav_Y,Android_Grav_Z,Kalman_Wave_Corrected_X,Kalman_Wave_Corrected_Y,Kalman_Wave_Corrected_Z,Android_Wave_Corrected_X,Android_Wave_Corrected_Y,Android_Wave_Corrected_Z\n")
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

    fun logData(
        timestamp: Long,
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
            append(androidWaveCorrected[0]).append(",").append(androidWaveCorrected[1]).append(",").append(androidWaveCorrected[2])
        }
        dataChannel?.trySend(line)
    }
}
