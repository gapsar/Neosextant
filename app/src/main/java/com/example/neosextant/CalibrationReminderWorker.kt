package com.example.neosextant

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.work.Worker
import androidx.work.WorkerParameters

class CalibrationReminderWorker(appContext: Context, workerParams: WorkerParameters) :
    Worker(appContext, workerParams) {

    companion object {
        const val CHANNEL_ID = "calibration_reminder"
        const val NOTIFICATION_ID = 1001
    }

    override fun doWork(): Result {
        val calibrator = SensorCalibrator(applicationContext)
        val status = calibrator.checkCalibrationStatus()

        // If calibration is needed (either expired or used too much), send notification
        if (status == SensorCalibrator.CalibrationStatus.NEEDS_CALIBRATION) {
            sendNotification()
        }

        return Result.success()
    }

    @android.annotation.SuppressLint("MissingPermission")
    private fun sendNotification() {
        val notificationManager =
            applicationContext.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

        // Create Channel (Safe to call repeatedly)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val locale = LocaleManager.getLocale(applicationContext)
            val name = S.notifChannelName(locale)
            val descriptionText = S.notifChannelDesc(locale)
            val importance = NotificationManager.IMPORTANCE_DEFAULT
            val channel = NotificationChannel(CHANNEL_ID, name, importance).apply {
                description = descriptionText
            }
            notificationManager.createNotificationChannel(channel)
        }

        // Create Intent to open the app
        val intent = Intent(applicationContext, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
        }
        val pendingIntent: PendingIntent = PendingIntent.getActivity(
            applicationContext, 0, intent, PendingIntent.FLAG_IMMUTABLE
        )

        val locale = LocaleManager.getLocale(applicationContext)
        val builder = NotificationCompat.Builder(applicationContext, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_popup_sync)
            .setContentTitle(S.notifTitle(locale))
            .setContentText(S.notifLongText(locale))
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setContentIntent(pendingIntent)
            .setAutoCancel(true)

        try {
            notificationManager.notify(NOTIFICATION_ID, builder.build())
        } catch (e: SecurityException) {
            // Notification permission might not be granted, fail silently/log
             android.util.Log.e("CalibrationWorker", "Cannot post notification: permission denied")
        }
    }
}
