package org.fivesevenfive.wearvian.companion.wear

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import com.google.android.gms.wearable.MessageEvent
import com.google.android.gms.wearable.WearableListenerService
import org.fivesevenfive.wearvian.companion.R
import org.fivesevenfive.wearvian.companion.ui.MainActivity

/**
 * Receives the watch's `/wearvian/enroll/request` in the background, stashes it
 * in [PendingEnrollment], and notifies the user to open the app and complete the
 * Rivian login. The interactive cloud login + EnrollPhone happens in the UI; the
 * result is sent back via [WearTransport].
 */
class WearEnrollmentListenerService : WearableListenerService() {

    override fun onMessageReceived(event: MessageEvent) {
        if (event.path != EnrollmentContract.PATH_REQUEST) return
        val request = runCatching { EnrollmentContract.Request.parse(event.data) }.getOrNull() ?: return
        PendingEnrollment.submit(request, event.sourceNodeId)
        notifyUser()
    }

    private fun notifyUser() {
        val nm = getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL, "Watch enrollment", NotificationManager.IMPORTANCE_HIGH),
            )
        }
        val pi = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val n = NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle("Enroll your watch as a Rivian key")
            .setContentText("Tap to sign in to Rivian and finish enrollment.")
            .setContentIntent(pi)
            .setAutoCancel(true)
            .build()
        nm.notify(NOTIF_ID, n)
    }

    private companion object {
        const val CHANNEL = "wearvian_enrollment"
        const val NOTIF_ID = 1001
    }
}
