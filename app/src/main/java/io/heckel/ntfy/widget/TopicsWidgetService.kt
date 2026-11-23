package io.heckel.ntfy.widget

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
import io.heckel.ntfy.util.readBitmapFromUriOrNull
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking

/**
 * RemoteViewsService supplying subscribed topics (ordered by most recently updated)
 * to [TopicsWidgetProvider].
 */
class TopicsWidgetService : RemoteViewsService() {
    override fun onGetViewFactory(intent: Intent): RemoteViewsFactory {
        return TopicsRemoteViewsFactory(applicationContext)
    }

    private class TopicsRemoteViewsFactory(
        private val context: Context
    ) : RemoteViewsFactory {
        private val repository = Repository.getInstance(context)
        private val appBaseUrl = context.getString(R.string.app_base_url)
        private val yesterdayLabel = context.getString(R.string.main_item_date_yesterday)
        private val noMessagesText = context.getString(R.string.widget_topics_no_messages)

        private var subscriptions: List<Subscription> = emptyList()
        private var latestBySubId: Map<Long, Notification> = emptyMap()

        override fun onCreate() {
            // Data loaded in onDataSetChanged()
        }

        override fun onDataSetChanged() {
            val identityToken = Binder.clearCallingIdentity()
            try {
                runBlocking(Dispatchers.IO) {
                    subscriptions = WidgetHelper.sortSubscriptionsForWidget(
                        subscriptions = repository.getSubscriptions(),
                        appBaseUrl = appBaseUrl
                    )
                    latestBySubId = repository.getLatestNotificationsBySubscription()
                }
            } finally {
                Binder.restoreCallingIdentity(identityToken)
            }
        }

        override fun onDestroy() {
            subscriptions = emptyList()
            latestBySubId = emptyMap()
        }

        override fun getCount(): Int = subscriptions.size

        override fun getViewAt(position: Int): RemoteViews {
            val views = RemoteViews(context.packageName, R.layout.widget_topic_item)
            val subscription = subscriptions.getOrNull(position) ?: return views

            // Topic icon
            val iconBitmap = subscription.icon?.readBitmapFromUriOrNull(context)
            if (iconBitmap != null) {
                views.setImageViewBitmap(R.id.widget_topic_icon, WidgetHelper.scaleWidgetIcon(iconBitmap))
            } else {
                views.setImageViewResource(R.id.widget_topic_icon, R.drawable.ic_widget_ntfy)
            }

            // Name & latest message preview
            views.setTextViewText(R.id.widget_topic_name, displayName(appBaseUrl, subscription))
            val preview = WidgetHelper.formatTopicPreview(latestBySubId[subscription.id], noMessagesText)
            views.setTextViewText(R.id.widget_topic_subtitle, preview)

            // Timestamp
            val timeText = WidgetHelper.formatWidgetTimestamp(
                timestampSecs = subscription.lastActive,
                yesterdayLabel = yesterdayLabel
            )
            views.setTextViewText(R.id.widget_topic_time, timeText)
            views.setViewVisibility(R.id.widget_topic_time, if (timeText.isNotEmpty()) View.VISIBLE else View.GONE)

            // Unread badge
            val badgeText = WidgetHelper.formatBadgeCount(subscription.newCount)
            if (badgeText != null) {
                views.setTextViewText(R.id.widget_topic_badge, badgeText)
                views.setViewVisibility(R.id.widget_topic_badge, View.VISIBLE)
            } else {
                views.setViewVisibility(R.id.widget_topic_badge, View.GONE)
            }

            // Click opens this topic directly in DetailActivity
            views.setOnClickFillInIntent(
                R.id.widget_topic_item_root,
                WidgetHelper.buildDetailFillInIntent(subscription.id)
            )
            return views
        }

        override fun getLoadingView(): RemoteViews? = null

        override fun getViewTypeCount(): Int = 1

        override fun getItemId(position: Int): Long {
            return subscriptions.getOrNull(position)?.id ?: position.toLong()
        }

        override fun hasStableIds(): Boolean = true
    }
}
