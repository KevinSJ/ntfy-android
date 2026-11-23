package io.heckel.ntfy.widget

import io.heckel.ntfy.R
import io.heckel.ntfy.db.Attachment
import io.heckel.ntfy.db.Notification
import io.heckel.ntfy.db.Repository
import io.heckel.ntfy.db.Subscription
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Calendar

class WidgetHelperTest {
    private val appBaseUrl = "https://ntfy.sh"

    private fun notif(
        id: String = "n1",
        title: String = "",
        message: String = "",
        priority: Int = 3,
        attachment: Attachment? = null
    ) = Notification(
        id = id,
        subscriptionId = 1L,
        timestamp = 1700000000L,
        sequenceId = id,
        title = title,
        message = message,
        contentType = "",
        encoding = "",
        notificationId = 0,
        priority = priority,
        tags = "",
        click = "",
        icon = null,
        actions = null,
        attachment = attachment,
        deleted = false
    )

    private fun sub(
        id: Long,
        topic: String,
        lastActive: Long = 0L,
        upAppId: String? = null
    ) = Subscription(
        id = id,
        baseUrl = appBaseUrl,
        topic = topic,
        instant = false,
        mutedUntil = 0L,
        minPriority = Repository.MIN_PRIORITY_USE_GLOBAL,
        autoDelete = Repository.AUTO_DELETE_USE_GLOBAL,
        insistent = Repository.INSISTENT_MAX_PRIORITY_USE_GLOBAL,
        lastNotificationId = null,
        icon = null,
        upAppId = upAppId,
        upConnectorToken = if (upAppId != null) "tok_$id" else null,
        displayName = null,
        dedicatedChannels = false,
        totalCount = 0,
        newCount = 0,
        lastActive = lastActive
    )

    @Test
    fun formatBadgeCount_formatsZeroNormalAndOverflow() {
        assertNull(WidgetHelper.formatBadgeCount(-1))
        assertNull(WidgetHelper.formatBadgeCount(0))
        assertEquals("1", WidgetHelper.formatBadgeCount(1))
        assertEquals("42", WidgetHelper.formatBadgeCount(42))
        assertEquals("99", WidgetHelper.formatBadgeCount(99))
        assertEquals("99+", WidgetHelper.formatBadgeCount(100))
        assertEquals("99+", WidgetHelper.formatBadgeCount(5000))
    }

    @Test
    fun priorityIconRes_mapsNonDefaultPrioritiesOnly() {
        assertEquals(R.drawable.ic_priority_1_24dp, WidgetHelper.priorityIconRes(1))
        assertEquals(R.drawable.ic_priority_2_24dp, WidgetHelper.priorityIconRes(2))
        assertNull(WidgetHelper.priorityIconRes(3))
        assertEquals(R.drawable.ic_priority_4_24dp, WidgetHelper.priorityIconRes(4))
        assertEquals(R.drawable.ic_priority_5_24dp, WidgetHelper.priorityIconRes(5))
        assertNull(WidgetHelper.priorityIconRes(0))
    }

    @Test
    fun formatWidgetTimestamp_handlesZeroTodayYesterdayAndOlder() {
        val now = Calendar.getInstance().apply {
            set(2026, Calendar.NOVEMBER, 22, 15, 30, 0)
            set(Calendar.MILLISECOND, 0)
        }
        val todayMorning = (now.clone() as Calendar).apply {
            set(Calendar.HOUR_OF_DAY, 9)
            set(Calendar.MINUTE, 15)
        }
        val yesterday = (now.clone() as Calendar).apply {
            add(Calendar.DAY_OF_YEAR, -1)
        }
        val lastWeek = (now.clone() as Calendar).apply {
            add(Calendar.DAY_OF_YEAR, -7)
        }

        assertEquals("", WidgetHelper.formatWidgetTimestamp(0L, now.timeInMillis, "yesterday"))
        val todayText = WidgetHelper.formatWidgetTimestamp(todayMorning.timeInMillis / 1000L, now.timeInMillis, "yesterday")
        assertTrue(todayText.isNotEmpty())
        assertNotEquals("yesterday", todayText)
        assertEquals("yesterday", WidgetHelper.formatWidgetTimestamp(yesterday.timeInMillis / 1000L, now.timeInMillis, "yesterday"))
        val olderText = WidgetHelper.formatWidgetTimestamp(lastWeek.timeInMillis / 1000L, now.timeInMillis, "yesterday")
        assertTrue(olderText.isNotEmpty())
        assertNotEquals("yesterday", olderText)
        assertNotEquals(todayText, olderText)
    }

    @Test
    fun formatTopicPreview_formatsTitleMessageAttachmentAndFallback() {
        assertEquals("No notifications yet", WidgetHelper.formatTopicPreview(null, "No notifications yet"))
        assertEquals(
            "Deploy: Line 1 Line 2",
            WidgetHelper.formatTopicPreview(notif(title = "Deploy", message = "Line 1\nLine 2"), "Empty")
        )
        assertEquals(
            "Backup done",
            WidgetHelper.formatTopicPreview(notif(message = "Backup done"), "Empty")
        )
        assertEquals(
            "Alert title",
            WidgetHelper.formatTopicPreview(notif(title = "Alert title"), "Empty")
        )
        assertEquals(
            "report.pdf",
            WidgetHelper.formatTopicPreview(
                notif(attachment = Attachment("report.pdf", "application/pdf", 1024L, null, "https://example.com/report.pdf")),
                "Empty"
            )
        )
    }

    @Test
    fun sortSubscriptionsForWidget_excludesUnifiedPushAndOrdersByLastActive() {
        val subs = listOf(
            sub(id = 1, topic = "zeta", lastActive = 0),
            sub(id = 2, topic = "up_channel", lastActive = 9999, upAppId = "org.example.up"),
            sub(id = 3, topic = "alpha", lastActive = 0),
            sub(id = 4, topic = "alerts", lastActive = 500),
            sub(id = 5, topic = "backups", lastActive = 200)
        )

        val sorted = WidgetHelper.sortSubscriptionsForWidget(subs, appBaseUrl)
        assertEquals(listOf(4L, 5L, 3L, 1L), sorted.map { it.id })
    }
}
