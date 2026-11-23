package io.heckel.ntfy.widget

import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.content.Context
import io.heckel.ntfy.app.Application
import io.heckel.ntfy.db.Repository
import io.heckel.ntfy.msg.ApiService
import io.heckel.ntfy.msg.NotificationDispatcher
import io.heckel.ntfy.msg.Poller
import io.heckel.ntfy.util.AppShortcutManager
import io.heckel.ntfy.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * Coordinates refreshing all active ntfy home screen widgets when subscriptions
 * or notifications change, or when the user taps a widget's refresh button.
 */
object AppWidgetUpdater {
    private const val TAG = "NtfyWidgetUpdater"

    fun updateAll(context: Context) {
        val appContext = context.applicationContext
        val scope = (appContext as? Application)?.ioScope ?: CoroutineScope(Dispatchers.IO)
        scope.launch {
            updateAllNow(appContext)
        }
    }

    suspend fun updateAllNow(context: Context) {
        try {
            val appContext = context.applicationContext
            val manager = AppWidgetManager.getInstance(appContext)

            val notifIds = manager.getAppWidgetIds(ComponentName(appContext, NotificationsWidgetProvider::class.java))
            if (notifIds.isNotEmpty()) {
                NotificationsWidgetProvider.updateWidgets(appContext, manager, notifIds)
            }

            val topicIds = manager.getAppWidgetIds(ComponentName(appContext, TopicsWidgetProvider::class.java))
            if (topicIds.isNotEmpty()) {
                TopicsWidgetProvider.updateWidgets(appContext, manager, topicIds)
            }
        } catch (e: Exception) {
            Log.w(TAG, "Unable to update app widgets", e)
        }
    }

    suspend fun pollAndRefresh(
        context: Context,
        subscriptionId: Long = Repository.WIDGET_SUBSCRIPTION_ALL
    ) {
        val appContext = context.applicationContext
        val repository = Repository.getInstance(appContext)
        val api = ApiService(appContext)
        val poller = Poller(api, repository)
        val dispatcher = NotificationDispatcher(appContext, repository)

        try {
            val subscriptions = if (subscriptionId == Repository.WIDGET_SUBSCRIPTION_ALL) {
                repository.getSubscriptions().filter { it.upAppId == null }
            } else {
                listOfNotNull(repository.getSubscription(subscriptionId)?.takeIf { it.upAppId == null })
            }
            for (subscription in subscriptions) {
                try {
                    val newNotifications = poller.poll(subscription)
                    newNotifications.forEach { notification ->
                        dispatcher.dispatch(subscription, notification)
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "Widget poll failed for subscription ${subscription.id}: ${e.message}")
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Widget poll failed", e)
        } finally {
            updateAllNow(appContext)
            AppShortcutManager.refreshDynamicShortcuts(appContext, repository)
        }
    }
}
