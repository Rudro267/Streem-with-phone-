package com.example.service

import android.Manifest
import android.app.Activity
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.example.MainActivity
import com.example.R

class StudioStreamService : Service() {

    companion object {
        const val ACTION_START_HOST = "com.example.action.START_HOST"
        const val ACTION_START_DIRECTOR = "com.example.action.START_DIRECTOR"
        const val ACTION_STOP_SESSION = "com.example.action.STOP_SESSION"
        const val ACTION_TOGGLE_REMOTE_LOCK = "com.example.action.TOGGLE_REMOTE_LOCK"
        const val ACTION_REFRESH_NOTIFICATION = "com.example.action.REFRESH_NOTIFICATION"
        const val EXTRA_RESULT_CODE = "extra_result_code"
        const val EXTRA_PROJECTION_DATA = "extra_projection_data"

        private const val CHANNEL_ID = "tallylink_live_channel"
        private const val NOTIFICATION_ID = 4901
    }

    private lateinit var sessionBus: StudioSessionBus

    override fun onCreate() {
        super.onCreate()
        sessionBus = StudioSessionBus.getInstance(applicationContext)
        ensureNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP_SESSION -> {
                sessionBus.stopSession()
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
                return START_NOT_STICKY
            }
            ACTION_TOGGLE_REMOTE_LOCK -> {
                sessionBus.setRemoteControlEnabled(!sessionBus.isRemoteControlEnabled.value)
                val nm = getSystemService(NotificationManager::class.java)
                nm?.notify(NOTIFICATION_ID, buildNotification(isHost = true))
                return START_NOT_STICKY
            }
            ACTION_REFRESH_NOTIFICATION -> {
                val nm = getSystemService(NotificationManager::class.java)
                nm?.notify(
                    NOTIFICATION_ID,
                    buildNotification(isHost = sessionBus.role.value == com.example.model.DeviceRole.HOST)
                )
                return START_NOT_STICKY
            }
            ACTION_START_HOST -> {
                val resultCode = intent.getIntExtra(EXTRA_RESULT_CODE, Activity.RESULT_CANCELED)
                val data: Intent? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    intent.getParcelableExtra(EXTRA_PROJECTION_DATA, Intent::class.java)
                } else {
                    @Suppress("DEPRECATION")
                    intent.getParcelableExtra(EXTRA_PROJECTION_DATA)
                }
                if (resultCode != Activity.RESULT_OK || data == null) {
                    stopSelf()
                    return START_NOT_STICKY
                }

                val hasMic = ContextCompat.checkSelfPermission(
                    this,
                    Manifest.permission.RECORD_AUDIO
                ) == PackageManager.PERMISSION_GRANTED

                val fgsType = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                    var mask = ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION or
                        ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE
                    if (hasMic) {
                        mask = mask or ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
                    }
                    mask
                } else 0

                ServiceCompat.startForeground(
                    this,
                    NOTIFICATION_ID,
                    buildNotification(isHost = true),
                    fgsType
                )

                val mpm = getSystemService(MediaProjectionManager::class.java)
                val projection = mpm?.getMediaProjection(resultCode, data)
                if (projection != null) {
                    sessionBus.startHostSession(projection)
                } else {
                    sessionBus.appendLog("FGS", "Failed to obtain MediaProjection", true)
                    stopForeground(STOP_FOREGROUND_REMOVE)
                    stopSelf()
                }
            }
            ACTION_START_DIRECTOR -> {
                val fgsType = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE
                } else 0

                ServiceCompat.startForeground(
                    this,
                    NOTIFICATION_ID,
                    buildNotification(isHost = false),
                    fgsType
                )
                sessionBus.startDirectorSession()
            }
        }
        return START_NOT_STICKY
    }

    private fun ensureNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val nm = getSystemService(NotificationManager::class.java)
            val channel = NotificationChannel(
                CHANNEL_ID,
                getString(R.string.notification_channel_name),
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = getString(R.string.notification_channel_desc)
            }
            nm?.createNotificationChannel(channel)
        }
    }

    private fun buildNotification(isHost: Boolean): Notification {
        val openIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val openPending = PendingIntent.getActivity(
            this,
            0,
            openIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val stopIntent = Intent(this, StudioStreamService::class.java).apply {
            action = ACTION_STOP_SESSION
        }
        val stopPending = PendingIntent.getService(
            this,
            1,
            stopIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val title = getString(if (isHost) R.string.notification_host_title else R.string.notification_director_title)
        val text = getString(if (isHost) R.string.notification_host_text else R.string.notification_director_text)

        val builder = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.presence_video_online)
            .setContentTitle(title)
            .setContentText(text)
            .setOngoing(true)
            .setContentIntent(openPending)
            .addAction(0, getString(R.string.notification_action_stop), stopPending)

        if (isHost) {
            val toggleIntent = Intent(this, StudioStreamService::class.java).apply {
                action = ACTION_TOGGLE_REMOTE_LOCK
            }
            val togglePending = PendingIntent.getService(
                this,
                2,
                toggleIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            val lockLabel = getString(
                if (sessionBus.isRemoteControlEnabled.value) {
                    R.string.notification_action_lock_touch
                } else {
                    R.string.notification_action_unlock_touch
                }
            )
            builder.addAction(0, lockLabel, togglePending)
        }

        return builder.build()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
