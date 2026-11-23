package io.heckel.ntfy.util

import io.heckel.ntfy.db.Repository
import io.heckel.ntfy.db.Subscription
import org.junit.Assert.assertEquals
import org.junit.Test

class AppShortcutManagerTest {
    private val appBaseUrl = "https://ntfy.sh"

    private fun sub(
        id: Long,
        topic: String,
        baseUrl: String = appBaseUrl,
        lastActive: Long = 0L,
        displayName: String? = null,
        upAppId: String? = null
    ): Subscription {
        return Subscription(
            id = id,
            baseUrl = baseUrl,
            topic = topic,
            instant = false,
            mutedUntil = 0L,
            minPriority = Repository.MIN_PRIORITY_USE_GLOBAL,
            autoDelete = Repository.AUTO_DELETE_USE_GLOBAL,
            insistent = Repository.INSISTENT_MAX_PRIORITY_USE_GLOBAL,
            lastNotificationId = null,
            icon = null,
            upAppId = upAppId,
            upConnectorToken = if (upAppId != null) "token_$id" else null,
            displayName = displayName,
            dedicatedChannels = false,
            totalCount = 0,
            newCount = 0,
            lastActive = lastActive
        )
    }

    @Test
    fun selectDynamicShortcuts_defaultsToMostRecentlyUpdatedChannels() {
        val subscriptions = listOf(
            sub(id = 1, topic = "oldest", lastActive = 100),
            sub(id = 2, topic = "newest", lastActive = 500),
            sub(id = 3, topic = "middle", lastActive = 300),
            sub(id = 4, topic = "second_newest", lastActive = 400),
            sub(id = 5, topic = "never_updated", lastActive = 0)
        )

        val selected = AppShortcutManager.selectDynamicShortcutSubscriptions(
            subscriptions = subscriptions,
            pinnedSubscriptionIds = emptyList(),
            maxCount = 4,
            appBaseUrl = appBaseUrl
        )

        assertEquals(listOf(2L, 4L, 3L, 1L), selected.map { it.id })
    }

    @Test
    fun selectDynamicShortcuts_excludesUnifiedPushSubscriptions() {
        val subscriptions = listOf(
            sub(id = 1, topic = "up_topic", lastActive = 9999, upAppId = "org.example.app"),
            sub(id = 2, topic = "alerts", lastActive = 200),
            sub(id = 3, topic = "backups", lastActive = 100)
        )

        val selected = AppShortcutManager.selectDynamicShortcutSubscriptions(
            subscriptions = subscriptions,
            pinnedSubscriptionIds = listOf(1L),
            maxCount = 4,
            appBaseUrl = appBaseUrl
        )

        assertEquals(listOf(2L, 3L), selected.map { it.id })
    }

    @Test
    fun selectDynamicShortcuts_prioritizesPinnedThenFillsWithLastUpdated() {
        val subscriptions = listOf(
            sub(id = 1, topic = "alpha", lastActive = 100),
            sub(id = 2, topic = "beta", lastActive = 500),
            sub(id = 3, topic = "gamma", lastActive = 300),
            sub(id = 4, topic = "delta", lastActive = 400),
            sub(id = 5, topic = "epsilon", lastActive = 50)
        )

        val selected = AppShortcutManager.selectDynamicShortcutSubscriptions(
            subscriptions = subscriptions,
            pinnedSubscriptionIds = listOf(5L, 1L),
            maxCount = 4,
            appBaseUrl = appBaseUrl
        )

        // Pinned 5 and 1 come first in pinned order, then most recently updated 2 and 4 fill remaining slots
        assertEquals(listOf(5L, 1L, 2L, 4L), selected.map { it.id })
    }

    @Test
    fun selectDynamicShortcuts_ignoresMissingAndDuplicatePinnedIds() {
        val subscriptions = listOf(
            sub(id = 1, topic = "one", lastActive = 100),
            sub(id = 2, topic = "two", lastActive = 200)
        )

        val selected = AppShortcutManager.selectDynamicShortcutSubscriptions(
            subscriptions = subscriptions,
            pinnedSubscriptionIds = listOf(999L, 1L, 1L),
            maxCount = 4,
            appBaseUrl = appBaseUrl
        )

        assertEquals(listOf(1L, 2L), selected.map { it.id })
    }

    @Test
    fun selectDynamicShortcuts_respectsZeroOrSmallMaxCount() {
        val subscriptions = listOf(
            sub(id = 1, topic = "one", lastActive = 100),
            sub(id = 2, topic = "two", lastActive = 200)
        )

        assertEquals(
            emptyList<Long>(),
            AppShortcutManager.selectDynamicShortcutSubscriptions(subscriptions, emptyList(), 0, appBaseUrl).map { it.id }
        )
        assertEquals(
            listOf(2L),
            AppShortcutManager.selectDynamicShortcutSubscriptions(subscriptions, emptyList(), 1, appBaseUrl).map { it.id }
        )
    }

    @Test
    fun selectDynamicShortcuts_tieBreaksDeterministicallyByDisplayName() {
        val subscriptions = listOf(
            sub(id = 1, topic = "zeta", lastActive = 0),
            sub(id = 2, topic = "alpha", lastActive = 0),
            sub(id = 3, topic = "beta", lastActive = 0)
        )

        val selected = AppShortcutManager.selectDynamicShortcutSubscriptions(
            subscriptions = subscriptions,
            pinnedSubscriptionIds = emptyList(),
            maxCount = 4,
            appBaseUrl = appBaseUrl
        )

        assertEquals(listOf(2L, 3L, 1L), selected.map { it.id })
    }

    @Test
    fun labels_useCustomDisplayNameOrConciseTopicAndFullLongLabel() {
        val defaultServerSub = sub(id = 1, topic = "alerts")
        assertEquals("alerts", AppShortcutManager.shortLabel(appBaseUrl, defaultServerSub))
        assertEquals("alerts", AppShortcutManager.longLabel(appBaseUrl, defaultServerSub))

        val customServerSub = sub(id = 2, topic = "backups", baseUrl = "https://ntfy.example.com")
        assertEquals("backups", AppShortcutManager.shortLabel(appBaseUrl, customServerSub))
        assertEquals("ntfy.example.com/backups", AppShortcutManager.longLabel(appBaseUrl, customServerSub))

        val renamedSub = sub(id = 3, topic = "backups", baseUrl = "https://ntfy.example.com", displayName = "Server Backups")
        assertEquals("Server Backups", AppShortcutManager.shortLabel(appBaseUrl, renamedSub))
        assertEquals("Server Backups", AppShortcutManager.longLabel(appBaseUrl, renamedSub))
    }

    @Test
    fun shortcutId_isDeterministicAndRoundTrips() {
        val id = AppShortcutManager.shortcutId(42L)
        assertEquals("subscription_42", id)
        assertEquals(42L, AppShortcutManager.parseSubscriptionId(id))
        assertEquals(null, AppShortcutManager.parseSubscriptionId("other_42"))
        assertEquals(null, AppShortcutManager.parseSubscriptionId("subscription_notanumber"))
    }
}
