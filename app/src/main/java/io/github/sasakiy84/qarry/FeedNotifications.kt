package io.github.sasakiy84.qarry

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.util.Log
import androidx.core.content.edit

enum class FeedNotificationMode(val value: Int, internal val label: String) {
    Default(0, "既定に従う"),
    On(1, "オン"),
    Off(2, "オフ");

    internal fun enabled(defaultEnabled: Boolean): Boolean = when (this) {
        Default -> defaultEnabled
        On -> true
        Off -> false
    }

    internal companion object {
        fun fromValue(value: Int): FeedNotificationMode = entries.firstOrNull { it.value == value } ?: Default
    }
}

internal data class FeedRefreshResult(val applied: Boolean, val newArticles: NewFeedArticles? = null)

internal data class NewFeedArticles(val feedId: Long, val feedTitle: String, val latestTitle: String, val count: Int)

internal class FeedNotifications(private val context: Context) {
    private val preferences = context.getSharedPreferences("feed_notifications", Context.MODE_PRIVATE)
    private val manager = context.getSystemService(NotificationManager::class.java)

    var defaultEnabled: Boolean
        get() = preferences.getBoolean("default_enabled", false)
        set(enabled) { preferences.edit { putBoolean("default_enabled", enabled) } }

    fun hasPermission(): Boolean =
        context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED

    fun canPost(): Boolean = hasPermission() && manager.areNotificationsEnabled() &&
        manager.getNotificationChannel(CHANNEL)?.importance != NotificationManager.IMPORTANCE_NONE

    fun post(articles: NewFeedArticles) {
        if (!canPost()) return
        try {
            manager.createNotificationChannel(NotificationChannel(CHANNEL, "新着記事", NotificationManager.IMPORTANCE_DEFAULT))
            val intent = Intent(context, MainActivity::class.java)
                .setAction(OPEN_FEED)
                .setData(Uri.fromParts(FEED_SCHEME, articles.feedId.toString(), null))
                .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            val pendingIntent = PendingIntent.getActivity(context, 0, intent, PendingIntent.FLAG_IMMUTABLE)
            val notification = Notification.Builder(context, CHANNEL)
                .setSmallIcon(R.drawable.ic_rss)
                .setContentTitle(articles.feedTitle)
                .setContentText(if (articles.count == 1) articles.latestTitle else "新着記事 ${articles.count}件")
                .setContentIntent(pendingIntent)
                .setAutoCancel(true)
                .setVisibility(Notification.VISIBILITY_PRIVATE)
                .build()
            manager.notify(articles.feedId.toString(), NOTIFICATION_ID, notification)
        } catch (_: SecurityException) {
            Log.w("QarryNotifications", "Notification permission changed while posting")
        }
    }

    private companion object {
        const val CHANNEL = "feed_updates"
        const val NOTIFICATION_ID = 1
    }
}

private const val OPEN_FEED = "io.github.sasakiy84.qarry.action.OPEN_NOTIFIED_FEED"
private const val FEED_SCHEME = "qarry-feed"

internal fun Intent.notifiedFeedId(): Long? =
    data?.takeIf { action == OPEN_FEED && it.scheme == FEED_SCHEME }
        ?.schemeSpecificPart?.toLongOrNull()?.takeIf { it > 0 }
