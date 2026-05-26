package dev.androidagent

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationCompat
import dev.androidagent.chat.ChatState
import dev.androidagent.chat.ChatUnreadReply
import dev.androidagent.chat.latestUnreadSessionKey
import dev.androidagent.overlay.ClientBrandPresentation

internal class ChatNotificationController(
    private val context: Context,
    private val brandPresentationFor: (ChatState, String?) -> ClientBrandPresentation
) {
    private var notifiedReplySessions = emptySet<String>()

    private val notificationManager: NotificationManager
        get() = context.getSystemService(NotificationManager::class.java)

    fun createChannels() {
        if (Build.VERSION.SDK_INT >= 26) {
            notificationManager.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "Agent chat", NotificationManager.IMPORTANCE_LOW)
            )
            notificationManager.createNotificationChannel(
                NotificationChannel(REPLY_CHANNEL_ID, "Chat replies", NotificationManager.IMPORTANCE_DEFAULT).apply {
                    description = "Per-session reply notifications from the selected chat client"
                }
            )
        }
    }

    fun updateForeground(state: ChatState) {
        notificationManager.notify(
            NOTIFICATION_ID,
            foregroundNotification(state)
        )
    }

    fun foregroundNotification(state: ChatState): Notification {
        val flags = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        val stopPendingIntent = PendingIntent.getService(
            context,
            REQUEST_STOP_TURN,
            Intent(context, AgentForegroundService::class.java).setAction(ACTION_STOP_TURN),
            flags
        )
        val latestUnreadSessionKey = state.latestUnreadSessionKey()
        val openIntent = Intent(context, AgentForegroundService::class.java)
            .setAction(if (latestUnreadSessionKey != null) ACTION_OPEN_CHAT_SESSION else ACTION_OPEN_CHAT)
            .putExtra(EXTRA_PANEL_PRESENTATION, PANEL_PRESENTATION_AUTO)
        latestUnreadSessionKey?.let { openIntent.putExtra(EXTRA_SESSION_KEY, it) }
        val openPendingIntent = PendingIntent.getService(context, REQUEST_OPEN_CHAT, openIntent, flags)
        return NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification_bubble)
            .setColor(0xFF245BFF.toInt())
            .setContentTitle(FOREGROUND_TITLE)
            .setContentText(FOREGROUND_TEXT)
            .setStyle(NotificationCompat.BigTextStyle().bigText(FOREGROUND_TEXT))
            .setContentIntent(openPendingIntent)
            .setOnlyAlertOnce(true)
            .setOngoing(true)
            .setSilent(true)
            .setShowWhen(false)
            .setLocalOnly(true)
            .setPriority(NotificationCompat.PRIORITY_MIN)
            .addAction(R.drawable.ic_close, "Stop Turn", stopPendingIntent)
            .build()
    }

    fun syncReplies(state: ChatState) {
        val nextSessions = state.unreadReplies.keys
        for (sessionKey in notifiedReplySessions - nextSessions) {
            notificationManager.cancel(replyNotificationId(sessionKey))
        }
        for ((sessionKey, unread) in state.unreadReplies) {
            runCatching {
                notificationManager.notify(replyNotificationId(sessionKey), replyNotification(state, sessionKey, unread))
            }.onFailure { error ->
                Log.w(TAG, "Failed to post reply notification for $sessionKey", error)
            }
        }
        notifiedReplySessions = nextSessions
    }

    fun cancelReply(sessionKey: String) {
        notificationManager.cancel(replyNotificationId(sessionKey))
        notifiedReplySessions = notifiedReplySessions - sessionKey
    }

    fun cancelAllReplies(state: ChatState) {
        (notifiedReplySessions + state.unreadReplies.keys).forEach { sessionKey ->
            notificationManager.cancel(replyNotificationId(sessionKey))
        }
        notifiedReplySessions = emptySet()
    }

    private fun replyNotification(state: ChatState, sessionKey: String, unread: ChatUnreadReply): Notification {
        val flags = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        val openIntent = Intent(context, AgentForegroundService::class.java)
            .setAction(ACTION_OPEN_CHAT_SESSION)
            .putExtra(EXTRA_SESSION_KEY, sessionKey)
            .putExtra(EXTRA_PANEL_PRESENTATION, PANEL_PRESENTATION_AUTO)
        val contentIntent = PendingIntent.getService(context, replyNotificationId(sessionKey), openIntent, flags)
        val deleteIntent = Intent(context, AgentForegroundService::class.java)
            .setAction(ACTION_DISMISS_CHAT_SESSION_NOTIFICATION)
            .putExtra(EXTRA_SESSION_KEY, sessionKey)
        val deletePendingIntent = PendingIntent.getService(
            context,
            replyNotificationId(sessionKey) + REQUEST_DISMISS_REPLY_OFFSET,
            deleteIntent,
            flags
        )
        val label = unread.displayNameFor(sessionKey)
        val count = unread.count
        val copy = brandPresentationFor(state, sessionKey).copy
        val text = unread.latestPreview
            ?: if (unread.latestStatus == "failed") copy.failedReplyFallback() else "Tap to view the reply."
        return NotificationCompat.Builder(context, REPLY_CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification_bubble)
            .setColor(0xFF245BFF.toInt())
            .setContentTitle(if (count > 1) "$count unread ${copy.name} replies in $label" else copy.repliedIn(label))
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setContentIntent(contentIntent)
            .setDeleteIntent(deletePendingIntent)
            .setCategory(NotificationCompat.CATEGORY_MESSAGE)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setNumber(count)
            .setAutoCancel(true)
            .build()
    }

    private fun replyNotificationId(sessionKey: String): Int {
        return REPLY_NOTIFICATION_ID_BASE + (sessionKey.hashCode() and 0x0FFFFFFF)
    }

    companion object {
        private const val TAG = "ChatNotifications"
        const val ACTION_STOP_TURN = "dev.openclawagent.action.STOP_TURN"
        const val ACTION_OPEN_CHAT = "dev.openclawagent.action.OPEN_CHAT"
        const val ACTION_OPEN_CHAT_SESSION = "dev.openclawagent.action.OPEN_CHAT_SESSION"
        const val ACTION_DISMISS_CHAT_SESSION_NOTIFICATION = "dev.openclawagent.action.DISMISS_CHAT_SESSION_NOTIFICATION"
        const val EXTRA_PANEL_PRESENTATION = "panelPresentation"
        const val EXTRA_SESSION_KEY = "sessionKey"
        const val PANEL_PRESENTATION_AUTO = "auto"
        const val NOTIFICATION_ID = 1
        const val CHANNEL_ID = "open-claw-agent"
        private const val REPLY_CHANNEL_ID = "open-claw-agent-replies"
        private const val FOREGROUND_TITLE = "Android Agent active"
        private const val FOREGROUND_TEXT = "Floating chat bubble is running"
        private const val REQUEST_STOP_TURN = 0
        private const val REQUEST_OPEN_CHAT = 2
        private const val REQUEST_DISMISS_REPLY_OFFSET = 500_000
        private const val REPLY_NOTIFICATION_ID_BASE = 10_000
    }
}
