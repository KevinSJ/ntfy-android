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
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * Home screen widget displaying an overview of all subscribed topics ordered by
 * most recently updated, with latest message previews and unread badges.
 */
class TopicsWidgetProvider : AppWidgetProvider() {

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
            val pendingResult = goAsync()
            val appContext = context.applicationContext
            val scope = (appContext as? Application)?.ioScope ?: CoroutineScope(Dispatchers.IO)
            scope.launch {
                try {
                    AppWidgetUpdater.pollAndRefresh(appContext, Repository.WIDGET_SUBSCRIPTION_ALL)
                } finally {
                    pendingResult.finish()
                }
            }
        }
    }

    companion object {
        private const val TAG = "NtfyTopicsWidget"
        const val ACTION_REFRESH = "io.heckel.ntfy.WIDGET_TOPICS_REFRESH"
        private const val REQUEST_CODE_HEADER_OFFSET = 400_000
        private const val REQUEST_CODE_REFRESH_OFFSET = 500_000
        private const val REQUEST_CODE_TEMPLATE_OFFSET = 600_000

        suspend fun updateWidgets(
            context: Context,
            appWidgetManager: AppWidgetManager,
            appWidgetIds: IntArray
        ) {
            val repository = Repository.getInstance(context)
            val subscriptions = repository.getSubscriptions().filter { it.upAppId == null }
            val totalUnread = subscriptions.sumOf { it.newCount }

            for (appWidgetId in appWidgetIds) {
                try {
                    val views = RemoteViews(context.packageName, R.layout.widget_topics)

                    // Header badge
                    val badgeText = WidgetHelper.formatBadgeCount(totalUnread)
                    if (badgeText != null) {
                        views.setTextViewText(R.id.widget_header_badge, badgeText)
                        views.setViewVisibility(R.id.widget_header_badge, View.VISIBLE)
                    } else {
                        views.setViewVisibility(R.id.widget_header_badge, View.GONE)
                    }

                    // Header opens MainActivity
                    views.setOnClickPendingIntent(
                        R.id.widget_header,
                        WidgetHelper.buildMainPendingIntent(context, REQUEST_CODE_HEADER_OFFSET + appWidgetId)
                    )

                    // Refresh button polls all topics
                    val refreshIntent = Intent(context, TopicsWidgetProvider::class.java).apply {
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

                    // Collection ListView adapter
                    val serviceIntent = Intent(context, TopicsWidgetService::class.java).apply {
                        putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, appWidgetId)
                        data = toUri(Intent.URI_INTENT_SCHEME).toUri()
                    }
                    @Suppress("DEPRECATION")
                    views.setRemoteAdapter(R.id.widget_topics_list, serviceIntent)
                    views.setEmptyView(R.id.widget_topics_list, R.id.widget_topics_empty)
                    views.setPendingIntentTemplate(
                        R.id.widget_topics_list,
                        WidgetHelper.buildDetailTemplatePendingIntent(context, REQUEST_CODE_TEMPLATE_OFFSET + appWidgetId)
                    )

                    appWidgetManager.updateAppWidget(appWidgetId, views)
                    @Suppress("DEPRECATION")
                    appWidgetManager.notifyAppWidgetViewDataChanged(appWidgetId, R.id.widget_topics_list)
                } catch (e: Exception) {
                    Log.w(TAG, "Unable to update topics widget $appWidgetId", e)
                }
            }
        }
    }
}
