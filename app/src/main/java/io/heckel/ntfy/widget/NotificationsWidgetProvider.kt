package io.heckel.ntfy.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.Context
import android.content.Intent
import android.view.View
import android.widget.RemoteViews
import androidx.core.net.toUri
import io.heckel.ntfy.R
import io.heckel.ntfy.app.Application
import io.heckel.ntfy.db.Repository
import io.heckel.ntfy.util.Log
import io.heckel.ntfy.util.displayName
import io.heckel.ntfy.util.readBitmapFromUriOrNull
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * Home screen widget displaying a live scrollable feed of recent notifications,
 * either for all subscribed topics or for a specific configured topic.
 */
class NotificationsWidgetProvider : AppWidgetProvider() {

    override fun onUpdate(context: Context, appWidgetManager: AppWidgetManager, appWidgetIds: IntArray) {
        Log.init(context)
        val pendingResult = goAsync()
        val appContext = context.applicationContext
        val scope = (appContext as? Application)?.ioScope ?: CoroutineScope(Dispatchers.IO)
        scope.launch {
            try {
                updateWidgets(appContext, appWidgetManager, appWidgetIds)
            } finally {
                pendingResult.finish()
            }
        }
    }

    override fun onReceive(context: Context, intent: Intent) {
        super.onReceive(context, intent)
        if (intent.action == ACTION_REFRESH) {
            Log.init(context)
            val appWidgetId = intent.getIntExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, AppWidgetManager.INVALID_APPWIDGET_ID)
            val pendingResult = goAsync()
            val appContext = context.applicationContext
            val scope = (appContext as? Application)?.ioScope ?: CoroutineScope(Dispatchers.IO)
            scope.launch {
                try {
                    val repository = Repository.getInstance(appContext)
                    val subscriptionId = if (appWidgetId != AppWidgetManager.INVALID_APPWIDGET_ID) {
                        repository.getWidgetSubscriptionId(appWidgetId)
                    } else {
                        Repository.WIDGET_SUBSCRIPTION_ALL
                    }
                    AppWidgetUpdater.pollAndRefresh(appContext, subscriptionId)
                } finally {
                    pendingResult.finish()
                }
            }
        }
    }

    override fun onDeleted(context: Context, appWidgetIds: IntArray) {
        val repository = Repository.getInstance(context.applicationContext)
        appWidgetIds.forEach { appWidgetId ->
            repository.removeWidgetConfig(appWidgetId)
        }
    }

    companion object {
        private const val TAG = "NtfyNotifWidget"
        const val ACTION_REFRESH = "io.heckel.ntfy.WIDGET_NOTIFICATIONS_REFRESH"
        private const val REQUEST_CODE_HEADER_OFFSET = 100_000
        private const val REQUEST_CODE_REFRESH_OFFSET = 200_000
        private const val REQUEST_CODE_TEMPLATE_OFFSET = 300_000

        suspend fun updateWidgets(
            context: Context,
            appWidgetManager: AppWidgetManager,
            appWidgetIds: IntArray
        ) {
            val repository = Repository.getInstance(context)
            val appBaseUrl = context.getString(R.string.app_base_url)
            val subscriptions = repository.getSubscriptions().filter { it.upAppId == null }
            val subscriptionsById = subscriptions.associateBy { it.id }

            for (appWidgetId in appWidgetIds) {
                try {
                    val configuredSubId = repository.getWidgetSubscriptionId(appWidgetId)
                    val views = RemoteViews(context.packageName, R.layout.widget_notifications)

                    if (configuredSubId == Repository.WIDGET_SUBSCRIPTION_ALL) {
                        val totalUnread = subscriptions.sumOf { it.newCount }
                        views.setImageViewResource(R.id.widget_header_icon, R.drawable.ic_widget_ntfy)
                        views.setTextViewText(R.id.widget_header_title, context.getString(R.string.widget_notifications_all_topics))
                        bindBadge(views, totalUnread)
                        views.setTextViewText(R.id.widget_notifications_empty_text, context.getString(R.string.widget_notifications_empty))
                        views.setOnClickPendingIntent(
                            R.id.widget_header,
                            WidgetHelper.buildMainPendingIntent(context, REQUEST_CODE_HEADER_OFFSET + appWidgetId)
                        )
                    } else {
                        val subscription = subscriptionsById[configuredSubId]
                        if (subscription != null) {
                            val iconBitmap = subscription.icon?.readBitmapFromUriOrNull(context)
                            if (iconBitmap != null) {
                                views.setImageViewBitmap(R.id.widget_header_icon, WidgetHelper.scaleWidgetIcon(iconBitmap))
                            } else {
                                views.setImageViewResource(R.id.widget_header_icon, R.drawable.ic_widget_ntfy)
                            }
                            views.setTextViewText(R.id.widget_header_title, displayName(appBaseUrl, subscription))
                            bindBadge(views, subscription.newCount)
                            views.setTextViewText(R.id.widget_notifications_empty_text, context.getString(R.string.widget_notifications_empty))
                            views.setOnClickPendingIntent(
                                R.id.widget_header,
                                WidgetHelper.buildDetailPendingIntent(context, REQUEST_CODE_HEADER_OFFSET + appWidgetId, subscription.id)
                            )
                        } else {
                            views.setImageViewResource(R.id.widget_header_icon, R.drawable.ic_widget_ntfy)
                            views.setTextViewText(R.id.widget_header_title, context.getString(R.string.app_name))
                            bindBadge(views, 0)
                            views.setTextViewText(R.id.widget_notifications_empty_text, context.getString(R.string.widget_notifications_topic_removed))
                            val reconfigureIntent = Intent(context, NotificationsWidgetConfigureActivity::class.java).apply {
                                putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, appWidgetId)
                                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
                            }
                            val reconfigurePendingIntent = PendingIntent.getActivity(
                                context,
                                REQUEST_CODE_HEADER_OFFSET + appWidgetId,
                                reconfigureIntent,
                                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                            )
                            views.setOnClickPendingIntent(R.id.widget_header, reconfigurePendingIntent)
                        }
                    }

                    // Refresh button click
                    val refreshIntent = Intent(context, NotificationsWidgetProvider::class.java).apply {
                        action = ACTION_REFRESH
                        putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, appWidgetId)
                    }
                    val refreshPendingIntent = PendingIntent.getBroadcast(
                        context,
                        REQUEST_CODE_REFRESH_OFFSET + appWidgetId,
                        refreshIntent,
                        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                    )
                    views.setOnClickPendingIntent(R.id.widget_refresh_button, refreshPendingIntent)

                    // Collection ListView adapter (unique data URI per widget ID so each has its own factory)
                    val serviceIntent = Intent(context, NotificationsWidgetService::class.java).apply {
                        putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, appWidgetId)
                        data = toUri(Intent.URI_INTENT_SCHEME).toUri()
                    }
                    @Suppress("DEPRECATION")
                    views.setRemoteAdapter(R.id.widget_notifications_list, serviceIntent)
                    views.setEmptyView(R.id.widget_notifications_list, R.id.widget_notifications_empty)
                    views.setPendingIntentTemplate(
                        R.id.widget_notifications_list,
                        WidgetHelper.buildDetailTemplatePendingIntent(context, REQUEST_CODE_TEMPLATE_OFFSET + appWidgetId)
                    )

                    appWidgetManager.updateAppWidget(appWidgetId, views)
                    @Suppress("DEPRECATION")
                    appWidgetManager.notifyAppWidgetViewDataChanged(appWidgetId, R.id.widget_notifications_list)
                } catch (e: Exception) {
                    Log.w(TAG, "Unable to update notifications widget $appWidgetId", e)
                }
            }
        }

        private fun bindBadge(views: RemoteViews, unreadCount: Int) {
            val badgeText = WidgetHelper.formatBadgeCount(unreadCount)
            if (badgeText != null) {
                views.setTextViewText(R.id.widget_header_badge, badgeText)
                views.setViewVisibility(R.id.widget_header_badge, View.VISIBLE)
            } else {
                views.setViewVisibility(R.id.widget_header_badge, View.GONE)
            }
        }
    }
}
