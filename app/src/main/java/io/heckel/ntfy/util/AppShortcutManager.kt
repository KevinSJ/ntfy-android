package io.heckel.ntfy.util

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import androidx.core.content.pm.ShortcutInfoCompat
import androidx.core.content.pm.ShortcutManagerCompat
import androidx.core.graphics.drawable.IconCompat
import androidx.core.graphics.scale
import io.heckel.ntfy.R
import io.heckel.ntfy.db.Repository
import io.heckel.ntfy.db.Subscription
import io.heckel.ntfy.ui.DetailActivity
import io.heckel.ntfy.ui.MainActivity
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.Locale

/**
 * Manages Android dynamic and pinned App Shortcuts for subscribed ntfy topics.
 *
 * By default, dynamic shortcuts (shown when long-pressing the launcher icon) display
 * the most recently updated topics. Users can also pin specific topics to dynamic
 * shortcuts, or add individual topic shortcuts directly to their home screen.
 */
object AppShortcutManager {
    private const val TAG = "NtfyShortcuts"
    private const val SHORTCUT_ID_PREFIX = "subscription_"
    private const val MAX_SHORTCUT_ICON_SIZE_PX = 192

    private val refreshMutex = Mutex()

    private data class ShortcutSignature(
        val id: String,
        val shortLabel: String,
        val longLabel: String,
        val iconUri: String?
    )

    @Volatile
    private var lastPublishedSignature: List<ShortcutSignature>? = null

    fun shortcutId(subscriptionId: Long): String {
        return "$SHORTCUT_ID_PREFIX$subscriptionId"
    }

    internal fun parseSubscriptionId(shortcutId: String): Long? {
        if (!shortcutId.startsWith(SHORTCUT_ID_PREFIX)) {
            return null
        }
        return shortcutId.removePrefix(SHORTCUT_ID_PREFIX).toLongOrNull()
    }

    fun getMaxDynamicShortcutCount(context: Context): Int {
        return try {
            ShortcutManagerCompat.getMaxShortcutCountPerActivity(context).coerceAtLeast(0)
        } catch (e: Exception) {
            Log.w(TAG, "Unable to query max shortcut count", e)
            0
        }
    }

    fun isPinShortcutSupported(context: Context): Boolean {
        return try {
            ShortcutManagerCompat.isRequestPinShortcutSupported(context)
        } catch (e: Exception) {
            Log.w(TAG, "Unable to check pin shortcut support", e)
            false
        }
    }

    fun shortLabel(appBaseUrl: String?, subscription: Subscription): String {
        val customName = subscription.displayName?.trim()
        if (!customName.isNullOrEmpty()) {
            return customName
        }
        return subscription.topic.ifBlank { displayName(appBaseUrl, subscription) }
    }

    fun longLabel(appBaseUrl: String?, subscription: Subscription): String {
        return displayName(appBaseUrl, subscription)
    }

    /**
     * Selects and orders subscriptions for dynamic app shortcuts.
     *
     * UnifiedPush subscriptions are excluded because they do not have a topic view.
     * Any user-pinned subscription IDs appear first (in pinned order), followed by the
     * most recently updated unpinned subscriptions up to [maxCount]. By default (when
     * [pinnedSubscriptionIds] is empty), this selects the [maxCount] most recently
     * updated topics.
     */
    internal fun selectDynamicShortcutSubscriptions(
        subscriptions: List<Subscription>,
        pinnedSubscriptionIds: List<Long>,
        maxCount: Int,
        appBaseUrl: String?
    ): List<Subscription> {
        if (maxCount <= 0) {
            return emptyList()
        }
        val eligible = subscriptions
            .filter { it.upAppId == null }
            .sortedWith(
                compareByDescending<Subscription> { it.lastActive }
                    .thenBy { displayName(appBaseUrl, it).lowercase(Locale.ROOT) }
                    .thenBy { it.id }
            )
        if (eligible.isEmpty()) {
            return emptyList()
        }
        if (pinnedSubscriptionIds.isEmpty()) {
            return eligible.take(maxCount)
        }
        val byId = eligible.associateBy { it.id }
        val result = ArrayList<Subscription>(minOf(maxCount, eligible.size))
        val includedIds = HashSet<Long>()
        for (pinnedId in pinnedSubscriptionIds) {
            if (result.size >= maxCount) break
            val subscription = byId[pinnedId] ?: continue
            if (includedIds.add(subscription.id)) {
                result.add(subscription)
            }
        }
        for (subscription in eligible) {
            if (result.size >= maxCount) break
            if (includedIds.add(subscription.id)) {
                result.add(subscription)
            }
        }
        return result
    }

    fun buildShortcutInfo(context: Context, subscription: Subscription, rank: Int = 0): ShortcutInfoCompat {
        val appBaseUrl = context.getString(R.string.app_base_url)
        val shortName = shortLabel(appBaseUrl, subscription)
        val longName = longLabel(appBaseUrl, subscription)
        val icon = buildShortcutIcon(context, subscription)
        val intents = buildShortcutIntents(context, subscription, appBaseUrl)
        return ShortcutInfoCompat.Builder(context, shortcutId(subscription.id))
            .setShortLabel(shortName)
            .setLongLabel(longName)
            .setIcon(icon)
            .setRank(rank)
            .setIntents(intents)
            .build()
    }

    fun requestPinShortcut(context: Context, subscription: Subscription): Boolean {
        if (subscription.upAppId != null) {
            return false
        }
        return try {
            if (!ShortcutManagerCompat.isRequestPinShortcutSupported(context)) {
                return false
            }
            val shortcutInfo = buildShortcutInfo(context, subscription)
            ShortcutManagerCompat.requestPinShortcut(context, shortcutInfo, null)
        } catch (e: Exception) {
            Log.w(TAG, "Unable to request pinned shortcut for subscription ${subscription.id}", e)
            false
        }
    }

    fun createShortcutResultIntent(context: Context, subscription: Subscription): Intent {
        val shortcutInfo = buildShortcutInfo(context, subscription)
        return ShortcutManagerCompat.createShortcutResultIntent(context, shortcutInfo)
    }

    fun reportShortcutUsed(context: Context, subscriptionId: Long) {
        try {
            ShortcutManagerCompat.reportShortcutUsed(context, shortcutId(subscriptionId))
        } catch (e: Exception) {
            Log.w(TAG, "Unable to report shortcut used for subscription $subscriptionId", e)
        }
    }

    fun disableSubscriptionShortcut(context: Context, subscriptionId: Long) {
        try {
            val appContext = context.applicationContext
            ShortcutManagerCompat.disableShortcuts(
                appContext,
                listOf(shortcutId(subscriptionId)),
                appContext.getString(R.string.shortcut_disabled_message)
            )
        } catch (e: Exception) {
            Log.w(TAG, "Unable to disable shortcut for subscription $subscriptionId", e)
        }
    }

    suspend fun refreshDynamicShortcuts(
        context: Context,
        repository: Repository = Repository.getInstance(context),
        forceUpdate: Boolean = false
    ) {
        refreshMutex.withLock {
            try {
                val appContext = context.applicationContext
                val appBaseUrl = appContext.getString(R.string.app_base_url)
                val maxCount = getMaxDynamicShortcutCount(appContext)
                val subscriptions = repository.getSubscriptions()
                val pinnedIds = repository.getPinnedShortcutSubscriptionIds()

                // Prune pinned IDs that no longer reference a valid non-UnifiedPush subscription
                val validIds = subscriptions.asSequence().filter { it.upAppId == null }.map { it.id }.toSet()
                val cleanedPinnedIds = pinnedIds.filter { it in validIds }
                if (cleanedPinnedIds.size != pinnedIds.size) {
                    repository.setPinnedShortcutSubscriptionIds(cleanedPinnedIds)
                }

                val selected = selectDynamicShortcutSubscriptions(
                    subscriptions = subscriptions,
                    pinnedSubscriptionIds = cleanedPinnedIds,
                    maxCount = maxCount,
                    appBaseUrl = appBaseUrl
                )
                val targetSignature = selected.map { subscription ->
                    ShortcutSignature(
                        id = shortcutId(subscription.id),
                        shortLabel = shortLabel(appBaseUrl, subscription),
                        longLabel = longLabel(appBaseUrl, subscription),
                        iconUri = subscription.icon
                    )
                }
                if (!forceUpdate && targetSignature == lastPublishedSignature) {
                    val currentIds = ShortcutManagerCompat.getDynamicShortcuts(appContext).map { it.id }
                    if (currentIds == targetSignature.map { it.id }) {
                        return
                    }
                }

                val shortcuts = selected.mapIndexed { index, subscription ->
                    buildShortcutInfo(appContext, subscription, rank = index)
                }
                ShortcutManagerCompat.setDynamicShortcuts(appContext, shortcuts)
                lastPublishedSignature = targetSignature
                Log.d(TAG, "Updated ${shortcuts.size} dynamic shortcut(s): ${shortcuts.map { it.id }}")
            } catch (e: Exception) {
                Log.w(TAG, "Unable to refresh dynamic shortcuts", e)
            }
        }
    }

    suspend fun refreshPinnedShortcuts(
        context: Context,
        repository: Repository = Repository.getInstance(context)
    ) {
        try {
            val appContext = context.applicationContext
            val pinnedShortcuts = ShortcutManagerCompat.getShortcuts(
                appContext,
                ShortcutManagerCompat.FLAG_MATCH_PINNED
            )
            if (pinnedShortcuts.isEmpty()) {
                return
            }
            val subscriptionsById = repository.getSubscriptions()
                .filter { it.upAppId == null }
                .associateBy { it.id }
            val toUpdate = mutableListOf<ShortcutInfoCompat>()
            val toDisable = mutableListOf<String>()
            for (shortcut in pinnedShortcuts) {
                val subscriptionId = parseSubscriptionId(shortcut.id) ?: continue
                val subscription = subscriptionsById[subscriptionId]
                if (subscription != null) {
                    toUpdate.add(buildShortcutInfo(appContext, subscription))
                } else {
                    toDisable.add(shortcut.id)
                }
            }
            if (toUpdate.isNotEmpty()) {
                ShortcutManagerCompat.updateShortcuts(appContext, toUpdate)
                Log.d(TAG, "Updated ${toUpdate.size} pinned shortcut(s)")
            }
            if (toDisable.isNotEmpty()) {
                ShortcutManagerCompat.disableShortcuts(
                    appContext,
                    toDisable,
                    appContext.getString(R.string.shortcut_disabled_message)
                )
                Log.d(TAG, "Disabled ${toDisable.size} stale pinned shortcut(s)")
            }
        } catch (e: Exception) {
            Log.w(TAG, "Unable to refresh pinned shortcuts", e)
        }
    }

    suspend fun onSubscriptionUpdated(
        context: Context,
        subscription: Subscription,
        repository: Repository = Repository.getInstance(context)
    ) {
        if (subscription.upAppId != null) {
            return
        }
        try {
            val appContext = context.applicationContext
            val shortcutInfo = buildShortcutInfo(appContext, subscription)
            ShortcutManagerCompat.updateShortcuts(appContext, listOf(shortcutInfo))
        } catch (e: Exception) {
            Log.w(TAG, "Unable to update shortcut for subscription ${subscription.id}", e)
        }
        refreshDynamicShortcuts(context, repository, forceUpdate = true)
    }

    suspend fun onSubscriptionRemoved(
        context: Context,
        subscriptionId: Long,
        repository: Repository = Repository.getInstance(context)
    ) {
        repository.setSubscriptionPinnedToShortcuts(subscriptionId, pinned = false)
        disableSubscriptionShortcut(context, subscriptionId)
        refreshDynamicShortcuts(context, repository, forceUpdate = true)
    }

    private fun buildShortcutIntents(
        context: Context,
        subscription: Subscription,
        appBaseUrl: String
    ): Array<Intent> {
        // Clear any existing task stack before recreating [MainActivity, DetailActivity]
        // so reopening a shortcut after minimizing never stacks duplicate activities.
        val mainIntent = Intent(context, MainActivity::class.java).apply {
            action = Intent.ACTION_MAIN
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
        }
        val detailIntent = Intent(context, DetailActivity::class.java).apply {
            action = DetailActivity.ACTION_OPEN_SHORTCUT
            flags = Intent.FLAG_ACTIVITY_CLEAR_TOP
            putExtra(MainActivity.EXTRA_SUBSCRIPTION_ID, subscription.id)
            putExtra(MainActivity.EXTRA_SUBSCRIPTION_BASE_URL, subscription.baseUrl)
            putExtra(MainActivity.EXTRA_SUBSCRIPTION_TOPIC, subscription.topic)
            putExtra(MainActivity.EXTRA_SUBSCRIPTION_DISPLAY_NAME, displayName(appBaseUrl, subscription))
            putExtra(MainActivity.EXTRA_SUBSCRIPTION_INSTANT, subscription.instant)
            putExtra(MainActivity.EXTRA_SUBSCRIPTION_MUTED_UNTIL, subscription.mutedUntil)
        }
        return arrayOf(mainIntent, detailIntent)
    }

    private fun buildShortcutIcon(context: Context, subscription: Subscription): IconCompat {
        val iconUri = subscription.icon
        if (iconUri != null) {
            val bitmap = iconUri.readBitmapFromUriOrNull(context)
            if (bitmap != null) {
                return IconCompat.createWithBitmap(scaleBitmapForShortcut(bitmap))
            }
        }
        return IconCompat.createWithResource(context, R.drawable.ic_shortcut_topic)
    }

    private fun scaleBitmapForShortcut(bitmap: Bitmap): Bitmap {
        val maxDimension = maxOf(bitmap.width, bitmap.height)
        if (maxDimension <= MAX_SHORTCUT_ICON_SIZE_PX || maxDimension <= 0) {
            return bitmap
        }
        val scaleFactor = MAX_SHORTCUT_ICON_SIZE_PX.toFloat() / maxDimension.toFloat()
        val targetWidth = (bitmap.width * scaleFactor).toInt().coerceAtLeast(1)
        val targetHeight = (bitmap.height * scaleFactor).toInt().coerceAtLeast(1)
        return bitmap.scale(targetWidth, targetHeight)
    }
}
