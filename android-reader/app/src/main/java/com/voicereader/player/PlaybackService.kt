package com.voicereader.player

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import com.voicereader.MainActivity
import com.voicereader.R
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

/**
 * Фоновая служба: удерживает озвучивание при выключенном экране
 * и показывает уведомление с кнопками пауза / предложение назад / вперёд.
 */
class PlaybackService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var collector: Job? = null
    private var isForeground = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createChannel()
        // startForegroundService обязывает перейти в foreground сразу, до первой эмиссии состояния.
        ServiceCompat.startForeground(
            this, NOTIFICATION_ID, buildNotification(PlayerEngine.state.value),
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q)
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK else 0
        )
        isForeground = true
        collector = scope.launch {
            PlayerEngine.state.collectLatest { state ->
                if (state.bookId == null) {
                    stopSelf()
                    return@collectLatest
                }
                val notification = buildNotification(state)
                if (state.isPlaying) {
                    if (!isForeground) {
                        ServiceCompat.startForeground(
                            this@PlaybackService, NOTIFICATION_ID, notification,
                            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q)
                                ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK else 0
                        )
                        isForeground = true
                    } else {
                        notificationManager().notify(NOTIFICATION_ID, notification)
                    }
                } else {
                    if (isForeground) {
                        ServiceCompat.stopForeground(this@PlaybackService, ServiceCompat.STOP_FOREGROUND_DETACH)
                        isForeground = false
                    }
                    notificationManager().notify(NOTIFICATION_ID, notification)
                }
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_TOGGLE -> PlayerEngine.toggle()
            ACTION_NEXT -> PlayerEngine.nextSentence()
            ACTION_PREV -> PlayerEngine.prevSentence()
            ACTION_STOP -> {
                PlayerEngine.pause()
                stopSelf()
            }
        }
        // Воскрешать службу без состояния плеера бессмысленно.
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        collector?.cancel()
        scope.cancel()
        notificationManager().cancel(NOTIFICATION_ID)
        super.onDestroy()
    }

    private fun notificationManager() =
        getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

    private fun createChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            "Озвучивание книги",
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = "Управление чтением вслух"
            setShowBadge(false)
        }
        notificationManager().createNotificationChannel(channel)
    }

    private fun buildNotification(state: PlayerState): Notification {
        val open = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val progressText = if (state.sentences.isEmpty()) ""
        else "${state.index + 1} / ${state.sentences.size}"

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(state.title.ifBlank { "Читалка вслух" })
            .setContentText(listOf(state.chapterTitle, progressText).filter { it.isNotBlank() }.joinToString(" · "))
            .setSubText(state.author.takeIf { it.isNotBlank() })
            .setContentIntent(open)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setOngoing(state.isPlaying)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .addAction(R.drawable.ic_notification, "Назад", action(ACTION_PREV))
            .addAction(
                R.drawable.ic_notification,
                if (state.isPlaying) "Пауза" else "Играть",
                action(ACTION_TOGGLE)
            )
            .addAction(R.drawable.ic_notification, "Вперёд", action(ACTION_NEXT))
            .addAction(R.drawable.ic_notification, "Стоп", action(ACTION_STOP))
            .build()
    }

    private fun action(name: String): PendingIntent = PendingIntent.getService(
        this, name.hashCode(),
        Intent(this, PlaybackService::class.java).setAction(name),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
    )

    companion object {
        private const val CHANNEL_ID = "playback"
        private const val NOTIFICATION_ID = 42
        const val ACTION_TOGGLE = "com.voicereader.TOGGLE"
        const val ACTION_NEXT = "com.voicereader.NEXT"
        const val ACTION_PREV = "com.voicereader.PREV"
        const val ACTION_STOP = "com.voicereader.STOP"

        fun start(context: Context) {
            val intent = Intent(context, PlaybackService::class.java)
            context.startForegroundService(intent)
        }
    }
}
