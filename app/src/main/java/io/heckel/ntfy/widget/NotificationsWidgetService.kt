package io.heckel.ntfy.widget

import android.appwidget.AppWidgetManager
import android.content.Context
import android.content.Intent
import android.os.Binder
import android.view.View
import android.widget.RemoteViews
import android.widget.RemoteViewsService
import io.heckel.ntfy.R
import io.heckel.ntfy.db.Notification
import io.heckel.ntfy.db.Repository
import io.heckel.ntfy.db.Subscription
import io.heckel.ntfy.util.displayName
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking

/**
 * RemoteViewsService supplying recent notifications to [NotificationsWidgetProvider].
 */
class NotificationsWidgetService : RemoteViewsService() {
    override fun onGetViewFactory(intent: Intent): RemoteViewsFactory {
        val appWidgetId = intent.getIntExtra(
            AppWidgetManager.EXTRA_APPWIDGET_ID,
            AppWidgetManager.INVALID_APPWIDGET_ID
        )
        return NotificationsRemoteViewsFactory(applicationContext, appWidgetId)
    }

    private class NotificationsRemoteViewsFactory(
        private val context: Context,
        private val appWidgetId: Int
    ) : RemoteViewsFactory {
        private val repository = Repository.getInstance(context)
        private val appBaseUrl = context.getString(R.string.app_base_url)
        private val yesterdayLabel = context.getString(R.string.main_item_date_yesterday)

        private var items: List<Notification> = emptyList()
        private var subscriptionsById: Map<Long, Subscription> = emptyMap()
        private var showTopicName: Boolean = true

        override fun onCreate() {
            // Data loaded in onDataSetChanged()
        }

        override fun onDataSetChanged() {
            val identityToken = Binder.clearCallingIdentity()
            try {
                runBlocking(Dispatchers.IO) {
                    val configuredSubId = repository.getWidgetSubscriptionId(appWidgetId)
                    val subscriptions = repository.getSubscriptions().filter { it.upAppId == null }
                    subscriptionsById = subscriptions.associateBy { it.id }
                    showTopicName = configuredSubId == Repository.WIDGET_SUBSCRIPTION_ALL
                    items = if (showTopicName) {
                        repository.getRecentNotificationsAll(WidgetHelper.MAX_WIDGET_ITEMS)
                    } else if (subscriptionsById.containsKey(configuredSubId)) {
                        repository.getRecentNotifications(configuredSubId, WidgetHelper.MAX_WIDGET_ITEMS)
                    } else {
                        emptyList()
                    }
                }
            } finally {
                Binder.restoreCallingIdentity(identityToken)
            }
        }

        override fun onDestroy() {
            items = emptyList()
            subscriptionsById = emptyMap()
        }

        override fun getCount(): Int = items.size

        override fun getViewAt(position: Int): RemoteViews {
            val views = RemoteViews(context.packageName, R.layout.widget_notification_item)
            val notification = items.getOrNull(position) ?: return views

            // Topic name (only in All Topics mode)
            if (showTopicName) {
                val subscription = subscriptionsById[notification.subscriptionId]
                if (subscription != null) {
                    views.setTextViewText(R.id.widget_item_topic, displayName(appBaseUrl, subscription))
                    views.setViewVisibility(R.id.widget_item_topic, View.VISIBLE)
                } else {
                    views.setViewVisibility(R.id.widget_item_topic, View.GONE)
                }
            } else {
                views.setViewVisibility(R.id.widget_item_topic, View.GONE)
            }

            // Timestamp
            val timeText = WidgetHelper.formatWidgetTimestamp(
                timestampSecs = notification.timestamp,
                yesterdayLabel = yesterdayLabel
            )
            views.setTextViewText(R.id.widget_item_time, timeText)

            // Priority icon
            val priorityIcon = WidgetHelper.priorityIconRes(notification.priority)
            if (priorityIcon != null) {
                views.setImageViewResource(R.id.widget_item_priority, priorityIcon)
                views.setViewVisibility(R.id.widget_item_priority, View.VISIBLE)
            } else {
                views.setViewVisibility(R.id.widget_item_priority, View.GONE)
            }

            // Unread indicator dot
            views.setViewVisibility(
                R.id.widget_item_unread_dot,
                if (notification.notificationId != 0) View.VISIBLE else View.GONE
            )

            // Title
            val titleText = WidgetHelper.formatNotificationTitle(notification)
            if (titleText.isNotEmpty()) {
                views.setTextViewText(R.id.widget_item_title, titleText)
                views.setViewVisibility(R.id.widget_item_title, View.VISIBLE)
            } else {
                views.setViewVisibility(R.id.widget_item_title, View.GONE)
            }

            // Message
            val messageText = WidgetHelper.formatNotificationMessage(notification)
            if (messageText.isNotEmpty()) {
                views.setTextViewText(R.id.widget_item_message, messageText)
                views.setViewVisibility(R.id.widget_item_message, View.VISIBLE)
            } else {
                views.setViewVisibility(R.id.widget_item_message, View.GONE)
            }

            // Attachment indicator
            val attachment = notification.attachment
            if (attachment != null) {
                views.setTextViewText(R.id.widget_item_attachment, "\uD83D\uDCCE ${attachment.name}")
                views.setViewVisibility(R.id.widget_item_attachment, View.VISIBLE)
            } else {
                views.setViewVisibility(R.id.widget_item_attachment, View.GONE)
            }

            // Click opens this notification's topic in DetailActivity
            views.setOnClickFillInIntent(
                R.id.widget_notification_item_root,
                WidgetHelper.buildDetailFillInIntent(notification.subscriptionId)
            )
            return views
        }

        override fun getLoadingView(): RemoteViews? = null

        override fun getViewTypeCount(): Int = 1

        override fun getItemId(position: Int): Long {
            val item = items.getOrNull(position) ?: return position.toLong()
            return (item.subscriptionId * 31L) + item.id.hashCode().toLong()
        }

        override fun hasStableIds(): Boolean = true
    }
}
