package io.heckel.ntfy.backup

import com.google.gson.Gson
import com.google.gson.stream.JsonReader
import com.google.gson.stream.JsonWriter
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.StringReader
import java.io.StringWriter

class BackuperTest {
    private val gson = Gson()

    private fun sampleSettings() = Settings(
        minPriority = 2,
        autoDownloadMaxSize = 1048576L,
        autoDeleteSeconds = 604800L,
        darkMode = 2,
        dynamicColors = true,
        connectionProtocol = "ws",
        broadcastEnabled = true,
        recordLogs = false,
        defaultBaseUrl = "https://ntfy.example.com",
        mutedUntil = 0L,
        connectionAlertSeconds = 300L,
        lastSharedTopics = listOf("https://ntfy.sh/alerts"),
        pinnedShortcutSubscriptionIds = listOf(10L, 20L)
    )

    private fun sampleSubscription(id: Long = 10L) = Subscription(
        id = id,
        baseUrl = "https://ntfy.sh",
        topic = "alerts_$id",
        instant = true,
        dedicatedChannels = true,
        mutedUntil = 0L,
        minPriority = 1,
        autoDelete = -1L,
        insistent = -1,
        lastNotificationId = "notif_last",
        icon = null,
        upAppId = null,
        upConnectorToken = null,
        displayName = "Alerts $id"
    )

    private fun sampleDbNotification(index: Int, subscriptionId: Long = 10L) = io.heckel.ntfy.db.Notification(
        id = "id_$index",
        subscriptionId = subscriptionId,
        timestamp = 1700000000L + index,
        sequenceId = "seq_$index",
        title = "Title $index",
        message = "Notification body message #$index with some extra text for realism",
        contentType = if (index % 2 == 0) "text/markdown" else "",
        encoding = "",
        notificationId = 999,
        priority = (index % 5) + 1,
        tags = "warning,server",
        click = "https://example.com/$index",
        icon = if (index % 3 == 0) io.heckel.ntfy.db.Icon("https://example.com/icon.png", null) else null,
        actions = if (index % 4 == 0) listOf(
            io.heckel.ntfy.db.Action(
                id = "act_$index",
                action = "view",
                label = "Open",
                clear = true,
                url = "https://example.com",
                method = null,
                headers = null,
                body = null,
                intent = null,
                extras = null,
                value = null,
                progress = null,
                error = null
            )
        ) else null,
        attachment = if (index % 5 == 0) io.heckel.ntfy.db.Attachment(
            name = "file_$index.png",
            type = "image/png",
            size = 12345L,
            expires = 1800000000L,
            url = "https://example.com/file_$index.png",
            contentUri = null,
            progress = -1
        ) else null,
        deleted = index % 7 == 0
    )

    @Test
    fun roundTrip_preservesAllBackupDataAndBatchesNotifications() = runBlocking {
        val originalSettings = sampleSettings()
        val originalSubs = listOf(sampleSubscription(10L), sampleSubscription(20L))
        val originalNotifications = (1..25).map { sampleDbNotification(it) }
        val originalUsers = listOf(User("https://ntfy.example.com", "alice", "secret"))
        val originalTrusted = listOf(TrustedCertificateBackup("https://ntfy.example.com", "PEMDATA"))
        val originalClient = listOf(ClientCertificateBackup("https://ntfy.example.com", "P12DATA", "pw"))

        val sw = StringWriter()
        JsonWriter(sw).use { writer ->
            Backuper.writeBackupJson(
                writer = writer,
                gson = gson,
                settings = originalSettings,
                subscriptions = originalSubs,
                users = originalUsers,
                trustedCertificates = originalTrusted,
                clientCertificates = originalClient,
                writeNotifications = { emit ->
                    originalNotifications.forEach { emit(Backuper.toBackupNotification(it)) }
                }
            )
        }

        // Verify output is also parseable by BackupFile (100% format compatible)
        val parsedWhole = gson.fromJson(sw.toString(), BackupFile::class.java)
        assertEquals(Backuper.FILE_MAGIC, parsedWhole.magic)
        assertEquals(Backuper.FILE_VERSION, parsedWhole.version)
        assertEquals(25, parsedWhole.notifications?.size)

        var restoredSettings: Settings? = null
        val restoredSubs = mutableListOf<Subscription>()
        val batches = mutableListOf<List<io.heckel.ntfy.db.Notification>>()
        val restoredUsers = mutableListOf<User>()
        val restoredTrusted = mutableListOf<TrustedCertificateBackup>()
        val restoredClient = mutableListOf<ClientCertificateBackup>()
        val progressUpdates = mutableListOf<Int>()

        JsonReader(StringReader(sw.toString())).use { reader ->
            Backuper.readBackupJson(
                reader = reader,
                gson = gson,
                batchSize = 10,
                onSettings = { restoredSettings = it },
                onSubscription = { restoredSubs.add(it) },
                onNotificationsBatch = { batches.add(it) },
                onUser = { restoredUsers.add(it) },
                onTrustedCertificate = { restoredTrusted.add(it) },
                onClientCertificate = { restoredClient.add(it) },
                onProgress = { progressUpdates.add(it) }
            )
        }

        assertEquals(originalSettings, restoredSettings)
        assertEquals(originalSubs, restoredSubs)
        assertEquals(originalUsers, restoredUsers)
        assertEquals(originalTrusted, restoredTrusted)
        assertEquals(originalClient, restoredClient)
        assertEquals(listOf(10, 10, 5), batches.map { it.size })
        assertEquals(listOf(10, 20, 25), progressUpdates)

        val restoredNotifications = batches.flatten()
        assertEquals(originalNotifications.size, restoredNotifications.size)
        for (i in originalNotifications.indices) {
            // notificationId is always reset to 0 on restore
            assertEquals(originalNotifications[i].copy(notificationId = 0), restoredNotifications[i])
        }
    }

    @Test
    fun readBackupJson_handlesSettingsOnlyAndLegacyNullFields() = runBlocking {
        val legacyJson = """
            {
              "magic": "ntfy2586",
              "version": 1,
              "settings": { "minPriority": 3 },
              "subscriptions": null,
              "notifications": [
                {
                  "id": "legacy1",
                  "subscriptionId": 1,
                  "timestamp": 1700000001,
                  "message": "hello",
                  "priority": 3,
                  "deleted": false
                }
              ],
              "users": null,
              "unknownFutureField": { "nested": [1, 2, 3] }
            }
        """.trimIndent()

        var restoredSettings: Settings? = null
        val restoredNotifications = mutableListOf<io.heckel.ntfy.db.Notification>()

        JsonReader(StringReader(legacyJson)).use { reader ->
            Backuper.readBackupJson(
                reader = reader,
                gson = gson,
                onSettings = { restoredSettings = it },
                onSubscription = { fail("Unexpected subscription") },
                onNotificationsBatch = { restoredNotifications.addAll(it) },
                onUser = { fail("Unexpected user") },
                onTrustedCertificate = { fail("Unexpected cert") },
                onClientCertificate = { fail("Unexpected client cert") }
            )
        }

        assertNotNull(restoredSettings)
        assertEquals(3, restoredSettings?.minPriority)
        assertNull(restoredSettings?.pinnedShortcutSubscriptionIds)
        assertEquals(1, restoredNotifications.size)
        val n = restoredNotifications.first()
        assertEquals("legacy1", n.id)
        assertEquals("legacy1", n.sequenceId) // Falls back to id
        assertEquals("", n.title)
        assertEquals("hello", n.message)
        assertEquals("", n.contentType)
        assertEquals("", n.encoding)
        assertEquals("", n.tags)
        assertEquals("", n.click)
    }

    @Test
    fun readBackupJson_rejectsInvalidOrMissingMagicImmediately() = runBlocking {
        val badMagicJson = """
            {
              "magic": "wrong",
              "notifications": [ { "id": "n1", "subscriptionId": 1, "timestamp": 1, "priority": 3, "deleted": false } ]
            }
        """.trimIndent()

        var notificationsCalled = false
        try {
            JsonReader(StringReader(badMagicJson)).use { reader ->
                Backuper.readBackupJson(
                    reader = reader,
                    gson = gson,
                    onSettings = {},
                    onSubscription = {},
                    onNotificationsBatch = { notificationsCalled = true },
                    onUser = {},
                    onTrustedCertificate = {},
                    onClientCertificate = {}
                )
            }
            fail("Expected InvalidBackupFileException")
        } catch (_: InvalidBackupFileException) {
            assertTrue(!notificationsCalled)
        }

        val missingMagicJson = """{ "version": 1, "settings": { "minPriority": 1 } }"""
        try {
            JsonReader(StringReader(missingMagicJson)).use { reader ->
                Backuper.readBackupJson(
                    reader = reader,
                    gson = gson,
                    onSettings = {},
                    onSubscription = {},
                    onNotificationsBatch = {},
                    onUser = {},
                    onTrustedCertificate = {},
                    onClientCertificate = {}
                )
            }
            fail("Expected InvalidBackupFileException")
        } catch (_: InvalidBackupFileException) {
            // Expected
        }
    }

    @Test
    fun readBackupJson_skipsInvalidNotificationEntryAndContinues() = runBlocking {
        val jsonWithCorruptEntry = """
            {
              "magic": "ntfy2586",
              "version": 1,
              "notifications": [
                { "id": "ok1", "subscriptionId": 1, "timestamp": 100, "message": "first", "priority": 3, "deleted": false },
                { "id": null, "subscriptionId": 1, "timestamp": 101, "message": "corrupt", "priority": 3, "deleted": false },
                { "id": "ok2", "subscriptionId": 1, "timestamp": 102, "message": "second", "priority": 3, "deleted": false }
              ]
            }
        """.trimIndent()

        val restored = mutableListOf<io.heckel.ntfy.db.Notification>()
        var errorCount = 0

        JsonReader(StringReader(jsonWithCorruptEntry)).use { reader ->
            Backuper.readBackupJson(
                reader = reader,
                gson = gson,
                onSettings = {},
                onSubscription = {},
                onNotificationsBatch = { restored.addAll(it) },
                onUser = {},
                onTrustedCertificate = {},
                onClientCertificate = {},
                onNotificationError = { _, _ -> errorCount++ }
            )
        }

        assertEquals(1, errorCount)
        assertEquals(listOf("ok1", "ok2"), restored.map { it.id })
    }

    @Test
    fun streamingPerformance_handlesLarge50kNotificationBackupQuickly() = runBlocking {
        val totalCount = 50_000
        val sw = StringWriter(16 * 1024 * 1024)
        JsonWriter(sw).use { writer ->
            Backuper.writeBackupJson(
                writer = writer,
                gson = gson,
                settings = sampleSettings(),
                subscriptions = listOf(sampleSubscription(10L)),
                users = emptyList(),
                trustedCertificates = emptyList(),
                clientCertificates = emptyList(),
                writeNotifications = { emit ->
                    for (i in 1..totalCount) {
                        emit(Backuper.toBackupNotification(sampleDbNotification(i)))
                    }
                }
            )
        }

        val jsonText = sw.toString()
        assertTrue("Expected realistic large backup (>10MB), got ${jsonText.length} chars", jsonText.length > 10_000_000)

        var batchCount = 0
        var restoredCount = 0
        val startMs = System.currentTimeMillis()
        JsonReader(StringReader(jsonText)).use { reader ->
            Backuper.readBackupJson(
                reader = reader,
                gson = gson,
                batchSize = Backuper.BATCH_SIZE,
                onSettings = {},
                onSubscription = {},
                onNotificationsBatch = { batch ->
                    batchCount++
                    restoredCount += batch.size
                },
                onUser = {},
                onTrustedCertificate = {},
                onClientCertificate = {}
            )
        }
        val elapsedMs = System.currentTimeMillis() - startMs

        assertEquals(totalCount, restoredCount)
        assertEquals(totalCount / Backuper.BATCH_SIZE, batchCount)
        assertTrue("Expected streaming 50k notifications to finish quickly, took ${elapsedMs}ms", elapsedMs < 15_000)
    }
}
