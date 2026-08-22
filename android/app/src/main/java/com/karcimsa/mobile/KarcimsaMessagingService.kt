package com.karcimsa.mobile

import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage
import kotlin.random.Random

class KarcimsaMessagingService : FirebaseMessagingService() {

    override fun onMessageReceived(message: RemoteMessage) {
        super.onMessageReceived(message)

        val title = message.notification?.title
            ?: message.data["title"]
            ?: "KARÇİMSA Araç Bildirimi"

        val body = message.notification?.body
            ?: message.data["body"]
            ?: "Yeni operasyon bildirimi var."

        val eventType = message.data["event_type"].orEmpty()
        val plate = message.data["plate"].orEmpty()
        val startTime = message.data["start_time"].orEmpty()

        showNotification(title, body, eventType, plate, startTime)
    }

    private fun showNotification(
        title: String,
        body: String,
        eventType: String,
        plate: String,
        startTime: String
    ) {
        val requestCode = Random.nextInt(10000, 99999)

        val openAppIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
            putExtra(MainActivity.EXTRA_EVENT_TYPE, eventType)
            putExtra(MainActivity.EXTRA_PLATE, plate)
            putExtra(MainActivity.EXTRA_START_TIME, startTime)
        }

        val pendingIntent = PendingIntent.getActivity(
            this,
            requestCode,
            openAppIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val notification = NotificationCompat.Builder(
            this,
            MainActivity.VEHICLE_CHANNEL_ID
        )
            .setSmallIcon(android.R.drawable.stat_notify_more)
            .setContentTitle(title)
            .setContentText(body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .setContentIntent(pendingIntent)
            .build()

        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.notify(requestCode, notification)
    }
}
