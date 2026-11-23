package io.heckel.ntfy.widget

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.os.Build
import androidx.core.graphics.scale
import io.heckel.ntfy.R
import io.heckel.ntfy.db.Notification
import io.heckel.ntfy.db.Subscription
import io.heckel.ntfy.ui.DetailActivity
import io.heckel.ntfy.ui.MainActivity
import io.heckel.ntfy.util.PRIORITY_HIGH
import io.heckel.ntfy.util.PRIORITY_LOW
import io.heckel.ntfy.util.PRIORITY_MAX
import io.heckel.ntfy.util.PRIORITY_MIN
import io.heckel.ntfy.util.displayName
import io.heckel.ntfy.util.formatMessage
import io.heckel.ntfy.util.formatTitle
import java.text.DateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

internal object WidgetHelper {
    const val MAX_WIDGET_ITEMS = 25
    private const val MAX_WIDGET_ICON_SIZE_PX = 96

    fun formatBadgeCount(count: Int): String? {
        return when {
            count <= 0 -> null
            count <= 99 -> count.toString()
            else -> "99+"
        }
    }

    fun priorityIconRes(priority: Int): Int? {
        return when (priority) {
            PRIORITY_MIN -> R.drawable.ic_priority_1_24dp
            PRIORITY_LOW -> R.drawable.ic_priority_2_24dp
            PRIORITY_HIGH -> R.drawable.ic_priority_4_24dp
            PRIORITY_MAX -> R.drawable.ic_priority_5_24dp
            else -> null
        }
    }

    fun formatWidgetTimestamp(
        timestampSecs: Long,
        nowMillis: Long = System.currentTimeMillis(),
        yesterdayLabel: String
    ): String {
        if (timestampSecs <= 0L) {
            return ""
        }
        val eventCal = Calendar.getInstance().apply { timeInMillis = timestampSecs * 1000L }
        val nowCal = Calendar.getInstance().apply { timeInMillis = nowMillis }
        val sameYear = eventCal.get(Calendar.YEAR) == nowCal.get(Calendar.YEAR)
        val sameDay = sameYear && eventCal.get(Calendar.DAY_OF_YEAR) == nowCal.get(Calendar.DAY_OF_YEAR)
        if (sameDay) {
            return DateFormat.getTimeInstance(DateFormat.SHORT).format(Date(timestampSecs * 1000L))
        }
        val yesterdayCal = (nowCal.clone() as Calendar).apply { add(Calendar.DAY_OF_YEAR, -1) }
        val isYesterday = eventCal.get(Calendar.YEAR) == yesterdayCal.get(Calendar.YEAR) &&
            eventCal.get(Calendar.DAY_OF_YEAR) == yesterdayCal.get(Calendar.DAY_OF_YEAR)
        if (isYesterday) {
            return yesterdayLabel
        }
        return DateFormat.getDateInstance(DateFormat.SHORT).format(Date(timestampSecs * 1000L))
    }

    fun formatNotificationTitle(notification: Notification): String {
        return if (notification.title.isNotEmpty()) {
            formatTitle(notification)
        } else {
            ""
        }
    }

    fun formatNotificationMessage(notification: Notification): String {
        return formatMessage(notification)
    }

    fun formatTopicPreview(latestNotification: Notification?, emptyText: String): String {
        if (latestNotification == null) {
            return emptyText
        }
        val title = formatNotificationTitle(latestNotification).trim()
        val message = formatNotificationMessage(latestNotification).replace('\n', ' ').trim()
        return when {
            title.isNotEmpty() && message.isNotEmpty() -> "$title: $message"
            message.isNotEmpty() -> message
            title.isNotEmpty() -> title
            latestNotification.attachment != null -> latestNotification.attachment.name
            else -> emptyText
        }
    }

    fun sortSubscriptionsForWidget(
        subscriptions: List<Subscription>,
        appBaseUrl: String?
    ): List<Subscription> {
        return subscriptions
            .filter { it.upAppId == null }
            .sortedWith(
                compareByDescending<Subscription> { it.lastActive }
                    .thenBy { displayName(appBaseUrl, it).lowercase(Locale.ROOT) }
                    .thenBy { it.id }
            )
    }

    fun scaleWidgetIcon(bitmap: Bitmap): Bitmap {
        val maxDimension = maxOf(bitmap.width, bitmap.height)
        if (maxDimension <= MAX_WIDGET_ICON_SIZE_PX || maxDimension <= 0) {
            return bitmap
        }
        val scaleFactor = MAX_WIDGET_ICON_SIZE_PX.toFloat() / maxDimension.toFloat()
        val targetWidth = (bitmap.width * scaleFactor).toInt().coerceAtLeast(1)
        val targetHeight = (bitmap.height * scaleFactor).toInt().coerceAtLeast(1)
        return bitmap.scale(targetWidth, targetHeight)
    }

    fun buildMainPendingIntent(context: Context, requestCode: Int): PendingIntent {
        val intent = Intent(context, MainActivity::class.java).apply {
            action = Intent.ACTION_MAIN
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        return PendingIntent.getActivity(
            context,
            requestCode,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    fun buildDetailPendingIntent(context: Context, requestCode: Int, subscriptionId: Long): PendingIntent {
        val mainIntent = Intent(context, MainActivity::class.java).apply {
            action = Intent.ACTION_MAIN
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
        }
        val detailIntent = Intent(context, DetailActivity::class.java).apply {
            action = DetailActivity.ACTION_OPEN_SHORTCUT
            flags = Intent.FLAG_ACTIVITY_CLEAR_TOP
            putExtra(MainActivity.EXTRA_SUBSCRIPTION_ID, subscriptionId)
        }
        return PendingIntent.getActivities(
            context,
            requestCode,
            arrayOf(mainIntent, detailIntent),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    /**
     * Mutable explicit-intent PendingIntent template for widget ListView items.
     * Fill-in intents from each row supply [MainActivity.EXTRA_SUBSCRIPTION_ID] to the
     * top [DetailActivity] intent while keeping [MainActivity] cleanly on the back stack.
     */
    fun buildDetailTemplatePendingIntent(context: Context, requestCode: Int): PendingIntent {
        val mainIntent = Intent(context, MainActivity::class.java).apply {
            action = Intent.ACTION_MAIN
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
        }
        val detailIntent = Intent(context, DetailActivity::class.java).apply {
            action = DetailActivity.ACTION_OPEN_SHORTCUT
            flags = Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE
        } else {
            PendingIntent.FLAG_UPDATE_CURRENT
        }
        return PendingIntent.getActivities(
            context,
            requestCode,
            arrayOf(mainIntent, detailIntent),
            flags
        )
    }

    fun buildDetailFillInIntent(subscriptionId: Long): Intent {
        return Intent().apply {
            putExtra(MainActivity.EXTRA_SUBSCRIPTION_ID, subscriptionId)
        }
    }
}
