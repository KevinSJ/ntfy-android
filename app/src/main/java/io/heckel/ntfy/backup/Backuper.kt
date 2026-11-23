package io.heckel.ntfy.backup

import android.content.Context
import android.net.Uri
import com.google.gson.Gson
import com.google.gson.GsonBuilder
import com.google.gson.annotations.SerializedName
import com.google.gson.stream.JsonReader
import com.google.gson.stream.JsonToken
import com.google.gson.stream.JsonWriter
import io.heckel.ntfy.R
import io.heckel.ntfy.app.Application
import io.heckel.ntfy.db.Repository
import io.heckel.ntfy.firebase.FirebaseMessenger
import io.heckel.ntfy.msg.NotificationService
import io.heckel.ntfy.util.AppShortcutManager
import io.heckel.ntfy.util.CertUtil
import io.heckel.ntfy.util.Log
import io.heckel.ntfy.util.topicUrl
import java.io.BufferedReader
import java.io.BufferedWriter
import java.io.InputStreamReader
import java.io.OutputStreamWriter

class Backuper(val context: Context) {
    private val gson = Gson()
    private val resolver = context.applicationContext.contentResolver
    private val repository = (context.applicationContext as Application).repository
    private val messenger = FirebaseMessenger()
    private val notifier = NotificationService(context)

    suspend fun backup(
        uri: Uri,
        withSettings: Boolean = true,
        withSubscriptions: Boolean = true,
        withUsers: Boolean = true,
        onProgress: ((Int) -> Unit)? = null
    ) {
        Log.d(TAG, "Backing up settings to file $uri")
        val outputStream = resolver.openOutputStream(uri) ?: throw Exception("Cannot open output stream")
        val settings = if (withSettings) createSettings() else null
        val subscriptions = if (withSubscriptions) createSubscriptionList() else null
        val users = if (withUsers) createUserList() else null
        val trustedCertificates = if (withSettings) createTrustedCertificateList() else null
        val clientCertificates = if (withSettings) createClientCertificateList() else null

        outputStream.use { stream ->
            JsonWriter(BufferedWriter(OutputStreamWriter(stream, Charsets.UTF_8), IO_BUFFER_SIZE)).use { writer ->
                writeBackupJson(
                    writer = writer,
                    gson = gson,
                    settings = settings,
                    subscriptions = subscriptions,
                    users = users,
                    trustedCertificates = trustedCertificates,
                    clientCertificates = clientCertificates,
                    writeNotifications = if (withSubscriptions) { emit ->
                        var page = repository.getNotificationsFirstPage(BATCH_SIZE)
                        var totalWritten = 0
                        while (page.isNotEmpty()) {
                            for (n in page) {
                                emit(toBackupNotification(n))
                            }
                            totalWritten += page.size
                            onProgress?.invoke(totalWritten)
                            val last = page.last()
                            page = if (page.size < BATCH_SIZE) {
                                emptyList()
                            } else {
                                repository.getNotificationsAfter(last.id, last.subscriptionId, BATCH_SIZE)
                            }
                        }
                    } else null
                )
            }
        }
        Log.d(TAG, "Backup done")
    }

    suspend fun restore(uri: Uri, onProgress: ((Int) -> Unit)? = null) {
        Log.d(TAG, "Restoring settings from file $uri")
        val inputStream = resolver.openInputStream(uri) ?: throw Exception("Cannot open input stream")
        var restoredSettings: Settings? = null
        val restoredSubscriptions = mutableListOf<io.heckel.ntfy.db.Subscription>()

        inputStream.use { stream ->
            JsonReader(BufferedReader(InputStreamReader(stream, Charsets.UTF_8), IO_BUFFER_SIZE)).use { reader ->
                repository.withTransaction {
                    readBackupJson(
                        reader = reader,
                        gson = gson,
                        batchSize = BATCH_SIZE,
                        onSettings = { settings ->
                            restoredSettings = settings
                        },
                        onSubscription = { s ->
                            try {
                                val subscription = io.heckel.ntfy.db.Subscription(
                                    id = s.id,
                                    baseUrl = s.baseUrl,
                                    topic = s.topic,
                                    instant = s.instant,
                                    dedicatedChannels = s.dedicatedChannels,
                                    mutedUntil = s.mutedUntil,
                                    minPriority = s.minPriority ?: Repository.MIN_PRIORITY_USE_GLOBAL,
                                    autoDelete = s.autoDelete ?: Repository.AUTO_DELETE_USE_GLOBAL,
                                    insistent = s.insistent ?: Repository.INSISTENT_MAX_PRIORITY_USE_GLOBAL,
                                    lastNotificationId = s.lastNotificationId,
                                    icon = s.icon,
                                    upAppId = s.upAppId,
                                    upConnectorToken = s.upConnectorToken,
                                    displayName = s.displayName,
                                )
                                repository.addSubscription(subscription)
                                restoredSubscriptions.add(subscription)
                            } catch (e: Exception) {
                                Log.w(TAG, "Unable to restore subscription ${s.id} (${topicUrl(s.baseUrl, s.topic)}): ${e.message}. Ignoring.", e)
                            }
                        },
                        onNotificationsBatch = { batch ->
                            repository.addNotifications(batch)
                        },
                        onUser = { u ->
                            try {
                                repository.addUser(io.heckel.ntfy.db.User(
                                    baseUrl = u.baseUrl,
                                    username = u.username,
                                    password = u.password
                                ))
                            } catch (e: Exception) {
                                Log.w(TAG, "Unable to restore user ${u.baseUrl} / ${u.username}: ${e.message}. Ignoring.", e)
                            }
                        },
                        onTrustedCertificate = { c ->
                            try {
                                CertUtil.parsePemCertificate(c.pem) // Validate the certificate
                                repository.addTrustedCertificate(c.baseUrl, c.pem)
                            } catch (e: Exception) {
                                Log.w(TAG, "Unable to restore trusted certificate for ${c.baseUrl}: ${e.message}. Ignoring.", e)
                            }
                        },
                        onClientCertificate = { c ->
                            try {
                                repository.addClientCertificate(c.baseUrl, c.p12Base64, c.password)
                            } catch (e: Exception) {
                                Log.w(TAG, "Unable to restore client certificate for ${c.baseUrl}: ${e.message}. Ignoring.", e)
                            }
                        },
                        onNotificationError = { id, e ->
                            Log.w(TAG, "Unable to restore notification $id: ${e.message}. Ignoring.", e)
                        },
                        onProgress = onProgress
                    )
                }
            }
        }

        // Apply non-database side effects only after the database transaction has committed
        applySettings(restoredSettings)
        val appBaseUrl = context.getString(R.string.app_base_url)
        restoredSubscriptions.forEach { subscription ->
            if (subscription.baseUrl == appBaseUrl) {
                messenger.subscribe(subscription.topic)
            }
            if (subscription.dedicatedChannels) {
                notifier.createSubscriptionNotificationChannels(subscription)
                // TODO Backup/restore individual notification channel settings
            }
        }
        AppShortcutManager.refreshDynamicShortcuts(context, repository, forceUpdate = true)
        Log.d(TAG, "Restoring done")
    }

    fun settingsAsString(): String {
        val gson = GsonBuilder().setPrettyPrinting().create()
        return gson.toJson(createSettings())
    }

    private fun applySettings(settings: Settings?) {
        if (settings == null) {
            return
        }
        if (settings.minPriority != null) {
            repository.setMinPriority(settings.minPriority)
        }
        if (settings.autoDownloadMaxSize != null) {
            repository.setAutoDownloadMaxSize(settings.autoDownloadMaxSize)
        }
        if (settings.autoDeleteSeconds != null) {
            repository.setAutoDeleteSeconds(settings.autoDeleteSeconds)
        }
        if (settings.darkMode != null) {
            repository.setDarkMode(settings.darkMode)
        }
        if (settings.dynamicColors != null) {
            repository.setDynamicColorsEnabled(settings.dynamicColors)
        }
        if (settings.connectionProtocol != null) {
            repository.setConnectionProtocol(settings.connectionProtocol)
        }
        if (settings.broadcastEnabled != null) {
            repository.setBroadcastEnabled(settings.broadcastEnabled)
        }
        if (settings.recordLogs != null) {
            repository.setRecordLogsEnabled(settings.recordLogs)
        }
        if (settings.defaultBaseUrl != null) {
            repository.setDefaultBaseUrl(settings.defaultBaseUrl)
        }
        if (settings.mutedUntil != null) {
            repository.setGlobalMutedUntil(settings.mutedUntil)
        }
        if (settings.connectionAlertSeconds != null) {
            repository.setConnectionAlertSeconds(settings.connectionAlertSeconds)
        }
        if (settings.lastSharedTopics != null) {
            settings.lastSharedTopics.forEach { repository.addLastShareTopic(it) }
        }
        if (settings.pinnedShortcutSubscriptionIds != null) {
            repository.setPinnedShortcutSubscriptionIds(settings.pinnedShortcutSubscriptionIds)
        }
    }

    private fun createSettings(): Settings {
        return Settings(
            minPriority = repository.getMinPriority(),
            autoDownloadMaxSize = repository.getAutoDownloadMaxSize(),
            autoDeleteSeconds = repository.getAutoDeleteSeconds(),
            darkMode = repository.getDarkMode(),
            dynamicColors = repository.getDynamicColorsEnabled(),
            connectionProtocol = repository.getConnectionProtocol(),
            broadcastEnabled = repository.getBroadcastEnabled(),
            recordLogs = repository.getRecordLogs(),
            defaultBaseUrl = repository.getDefaultBaseUrl() ?: "",
            mutedUntil = repository.getGlobalMutedUntil(),
            connectionAlertSeconds = repository.getConnectionAlertSeconds(),
            lastSharedTopics = repository.getLastShareTopics(),
            pinnedShortcutSubscriptionIds = repository.getPinnedShortcutSubscriptionIds()
        )
    }

    private suspend fun createSubscriptionList(): List<Subscription> {
        return repository.getSubscriptions().map { s ->
            Subscription(
                id = s.id,
                baseUrl = s.baseUrl,
                topic = s.topic,
                instant = s.instant,
                dedicatedChannels = s.dedicatedChannels,
                mutedUntil = s.mutedUntil,
                minPriority = s.minPriority,
                autoDelete = s.autoDelete,
                insistent = s.insistent,
                lastNotificationId = s.lastNotificationId,
                icon = s.icon,
                upAppId = s.upAppId,
                upConnectorToken = s.upConnectorToken,
                displayName = s.displayName
            )
        }
    }

    private suspend fun createUserList(): List<User> {
        return repository.getUsers().map { u ->
            User(
                baseUrl = u.baseUrl,
                username = u.username,
                password = u.password
            )
        }
    }

    private suspend fun createTrustedCertificateList(): List<TrustedCertificateBackup> {
        return repository.getTrustedCertificates().map { trustedCert ->
            TrustedCertificateBackup(
                baseUrl = trustedCert.baseUrl,
                pem = trustedCert.pem
            )
        }
    }

    private suspend fun createClientCertificateList(): List<ClientCertificateBackup> {
        return repository.getClientCertificates().map { clientCert ->
            ClientCertificateBackup(
                baseUrl = clientCert.baseUrl,
                p12Base64 = clientCert.p12Base64,
                password = clientCert.password
            )
        }
    }

    companion object {
        internal const val FILE_MAGIC = "ntfy2586"
        internal const val FILE_VERSION = 1
        internal const val BATCH_SIZE = 1000
        private const val IO_BUFFER_SIZE = 64 * 1024
        private const val TAG = "NtfyExporter"

        internal fun toBackupNotification(n: io.heckel.ntfy.db.Notification): Notification {
            val actions = n.actions?.map { a ->
                Action(
                    id = a.id,
                    action = a.action,
                    label = a.label,
                    clear = a.clear,
                    url = a.url,
                    method = a.method,
                    headers = a.headers,
                    body = a.body,
                    intent = a.intent,
                    extras = a.extras,
                    value = a.value,
                    progress = a.progress,
                    error = a.error
                )
            }
            val attachment = if (n.attachment != null) {
                Attachment(
                    name = n.attachment.name,
                    type = n.attachment.type,
                    size = n.attachment.size,
                    expires = n.attachment.expires,
                    url = n.attachment.url,
                    contentUri = n.attachment.contentUri,
                    progress = n.attachment.progress,
                )
            } else {
                null
            }
            val icon = if (n.icon != null && n.icon.hasValidUrl()) {
                Icon(
                    url = n.icon.url,
                    contentUri = n.icon.contentUri,
                )
            } else {
                null
            }
            return Notification(
                id = n.id,
                subscriptionId = n.subscriptionId,
                timestamp = n.timestamp,
                sequenceId = n.sequenceId,
                title = n.title,
                message = n.message,
                contentType = n.contentType,
                encoding = n.encoding,
                priority = n.priority,
                tags = n.tags,
                click = n.click,
                icon = icon,
                actions = actions,
                attachment = attachment,
                deleted = n.deleted
            )
        }

        internal fun toDbNotification(n: Notification): io.heckel.ntfy.db.Notification {
            val id = n.id ?: throw IllegalArgumentException("Notification ID is null")
            val actions = n.actions?.map { a ->
                io.heckel.ntfy.db.Action(
                    id = a.id,
                    action = a.action,
                    label = a.label,
                    clear = a.clear,
                    url = a.url,
                    method = a.method,
                    headers = a.headers,
                    body = a.body,
                    intent = a.intent,
                    extras = a.extras,
                    value = a.value,
                    progress = a.progress,
                    error = a.error
                )
            }
            val attachment = if (n.attachment != null) {
                io.heckel.ntfy.db.Attachment(
                    name = n.attachment.name,
                    type = n.attachment.type,
                    size = n.attachment.size,
                    expires = n.attachment.expires,
                    url = n.attachment.url,
                    contentUri = n.attachment.contentUri,
                    progress = n.attachment.progress,
                )
            } else {
                null
            }
            val icon = if (n.icon != null && !n.icon.url.isNullOrEmpty()) {
                io.heckel.ntfy.db.Icon(
                    url = n.icon.url,
                    contentUri = n.icon.contentUri,
                )
            } else {
                null
            }
            return io.heckel.ntfy.db.Notification(
                id = id,
                subscriptionId = n.subscriptionId,
                timestamp = n.timestamp,
                sequenceId = n.sequenceId?.takeIf { it.isNotEmpty() } ?: id,
                title = n.title ?: "",
                message = n.message ?: "",
                contentType = n.contentType ?: "",
                encoding = n.encoding ?: "",
                notificationId = 0,
                priority = n.priority,
                tags = n.tags ?: "",
                click = n.click ?: "",
                icon = icon,
                actions = actions,
                attachment = attachment,
                deleted = n.deleted
            )
        }

        internal suspend fun writeBackupJson(
            writer: JsonWriter,
            gson: Gson,
            settings: Settings?,
            subscriptions: List<Subscription>?,
            users: List<User>?,
            trustedCertificates: List<TrustedCertificateBackup>?,
            clientCertificates: List<ClientCertificateBackup>?,
            writeNotifications: (suspend (emit: (Notification) -> Unit) -> Unit)?
        ) {
            writer.beginObject()
            writer.name("magic").value(FILE_MAGIC)
            writer.name("version").value(FILE_VERSION)
            if (settings != null) {
                writer.name("settings")
                gson.getAdapter(Settings::class.java).write(writer, settings)
            }
            if (subscriptions != null) {
                val adapter = gson.getAdapter(Subscription::class.java)
                writer.name("subscriptions")
                writer.beginArray()
                for (subscription in subscriptions) {
                    adapter.write(writer, subscription)
                }
                writer.endArray()
            }
            if (writeNotifications != null) {
                val adapter = gson.getAdapter(Notification::class.java)
                writer.name("notifications")
                writer.beginArray()
                writeNotifications { notification ->
                    adapter.write(writer, notification)
                }
                writer.endArray()
            }
            if (users != null) {
                val adapter = gson.getAdapter(User::class.java)
                writer.name("users")
                writer.beginArray()
                for (user in users) {
                    adapter.write(writer, user)
                }
                writer.endArray()
            }
            if (trustedCertificates != null) {
                val adapter = gson.getAdapter(TrustedCertificateBackup::class.java)
                writer.name("trustedCertificates")
                writer.beginArray()
                for (cert in trustedCertificates) {
                    adapter.write(writer, cert)
                }
                writer.endArray()
            }
            if (clientCertificates != null) {
                val adapter = gson.getAdapter(ClientCertificateBackup::class.java)
                writer.name("clientCertificates")
                writer.beginArray()
                for (cert in clientCertificates) {
                    adapter.write(writer, cert)
                }
                writer.endArray()
            }
            writer.endObject()
            writer.flush()
        }

        internal suspend fun readBackupJson(
            reader: JsonReader,
            gson: Gson,
            batchSize: Int = BATCH_SIZE,
            onSettings: (Settings) -> Unit,
            onSubscription: suspend (Subscription) -> Unit,
            onNotificationsBatch: suspend (List<io.heckel.ntfy.db.Notification>) -> Unit,
            onUser: suspend (User) -> Unit,
            onTrustedCertificate: suspend (TrustedCertificateBackup) -> Unit,
            onClientCertificate: suspend (ClientCertificateBackup) -> Unit,
            onNotificationError: (String?, Exception) -> Unit = { _, _ -> },
            onProgress: ((Int) -> Unit)? = null
        ) {
            if (reader.peek() != JsonToken.BEGIN_OBJECT) {
                throw InvalidBackupFileException()
            }
            var magic: String? = null
            var restoredNotificationCount = 0

            val settingsAdapter = gson.getAdapter(Settings::class.java)
            val subscriptionAdapter = gson.getAdapter(Subscription::class.java)
            val notificationAdapter = gson.getAdapter(Notification::class.java)
            val userAdapter = gson.getAdapter(User::class.java)
            val trustedCertAdapter = gson.getAdapter(TrustedCertificateBackup::class.java)
            val clientCertAdapter = gson.getAdapter(ClientCertificateBackup::class.java)

            reader.beginObject()
            while (reader.hasNext()) {
                when (reader.nextName()) {
                    "magic" -> {
                        if (reader.peek() == JsonToken.NULL) {
                            reader.nextNull()
                            throw InvalidBackupFileException()
                        }
                        magic = reader.nextString()
                        if (magic != FILE_MAGIC) {
                            throw InvalidBackupFileException()
                        }
                    }
                    "version" -> reader.skipValue()
                    "settings" -> {
                        if (reader.peek() == JsonToken.NULL) {
                            reader.nextNull()
                        } else {
                            settingsAdapter.read(reader)?.let(onSettings)
                        }
                    }
                    "subscriptions" -> {
                        readJsonArray(reader) {
                            subscriptionAdapter.read(reader)?.let { onSubscription(it) }
                        }
                    }
                    "notifications" -> {
                        val batch = ArrayList<io.heckel.ntfy.db.Notification>(batchSize)
                        readJsonArray(reader) {
                            val parsed = notificationAdapter.read(reader)
                            if (parsed != null) {
                                val dbNotification = try {
                                    toDbNotification(parsed)
                                } catch (e: Exception) {
                                    onNotificationError(parsed.id, e)
                                    null
                                }
                                if (dbNotification != null) {
                                    batch.add(dbNotification)
                                    if (batch.size >= batchSize) {
                                        onNotificationsBatch(batch.toList())
                                        restoredNotificationCount += batch.size
                                        batch.clear()
                                        onProgress?.invoke(restoredNotificationCount)
                                    }
                                }
                            }
                        }
                        if (batch.isNotEmpty()) {
                            onNotificationsBatch(batch.toList())
                            restoredNotificationCount += batch.size
                            batch.clear()
                            onProgress?.invoke(restoredNotificationCount)
                        }
                    }
                    "users" -> {
                        readJsonArray(reader) {
                            userAdapter.read(reader)?.let { onUser(it) }
                        }
                    }
                    "trustedCertificates" -> {
                        readJsonArray(reader) {
                            trustedCertAdapter.read(reader)?.let { onTrustedCertificate(it) }
                        }
                    }
                    "clientCertificates" -> {
                        readJsonArray(reader) {
                            clientCertAdapter.read(reader)?.let { onClientCertificate(it) }
                        }
                    }
                    else -> reader.skipValue()
                }
            }
            reader.endObject()

            if (magic != FILE_MAGIC) {
                throw InvalidBackupFileException()
            }
        }

        private inline fun readJsonArray(reader: JsonReader, onElement: () -> Unit) {
            if (reader.peek() == JsonToken.NULL) {
                reader.nextNull()
                return
            }
            reader.beginArray()
            while (reader.hasNext()) {
                onElement()
            }
            reader.endArray()
        }
    }
}

data class BackupFile(
    val magic: String,
    val version: Int,
    val settings: Settings?,
    val subscriptions: List<Subscription>?,
    val notifications: List<Notification>?,
    val users: List<User>?,
    val trustedCertificates: List<TrustedCertificateBackup>? = null,
    val clientCertificates: List<ClientCertificateBackup>? = null
)

data class Settings(
    val minPriority: Int?,
    val autoDownloadMaxSize: Long?,
    val autoDeleteSeconds: Long?,
    val darkMode: Int?,
    val dynamicColors: Boolean?,
    val connectionProtocol: String?,
    val broadcastEnabled: Boolean?,
    val recordLogs: Boolean?,
    val defaultBaseUrl: String?,
    val mutedUntil: Long?,
    val connectionAlertSeconds: Long?,
    val lastSharedTopics: List<String>?,
    val pinnedShortcutSubscriptionIds: List<Long>? = null
)

data class Subscription(
    val id: Long,
    val baseUrl: String,
    val topic: String,
    val instant: Boolean,
    val dedicatedChannels: Boolean,
    val mutedUntil: Long,
    val minPriority: Int?,
    val autoDelete: Long?,
    val insistent: Int?,
    val lastNotificationId: String?,
    val icon: String?,
    val upAppId: String?,
    val upConnectorToken: String?,
    val displayName: String?
)

data class Notification(
    val id: String?,
    val subscriptionId: Long,
    val timestamp: Long,
    @SerializedName("sequence_id") val sequenceId: String?, // Sequence ID for updating notifications
    val title: String?,
    val message: String?,
    val contentType: String?, // "" or "text/markdown" (empty assumes "text/plain")
    val encoding: String?, // "base64" or ""
    val priority: Int, // 1=min, 3=default, 5=max
    val tags: String?,
    val click: String?, // URL/intent to open on notification click
    val icon: Icon?,
    val actions: List<Action>?,
    val attachment: Attachment?,
    val deleted: Boolean
)

data class Action(
    val id: String, // Synthetic ID to identify result, and easily pass via Broadcast and WorkManager
    val action: String, // "view", "http", "broadcast", or "copy"
    val label: String,
    val clear: Boolean?, // clear notification after successful execution
    val url: String?, // used in "view" and "http" actions
    val method: String?, // used in "http" action
    val headers: Map<String,String>?, // used in "http" action
    val body: String?, // used in "http" action
    val intent: String?, // used in "broadcast" action
    val extras: Map<String,String>?, // used in "broadcast" action
    val value: String? = null, // used in "copy" action
    val progress: Int?, // used to indicate progress in popup
    val error: String? // used to indicate errors in popup
)

data class Attachment(
    val name: String, // Filename
    val type: String?, // MIME type
    val size: Long?, // Size in bytes
    val expires: Long?, // Unix timestamp
    val url: String, // URL (mandatory, see ntfy server)
    val contentUri: String?, // After it's downloaded, the content:// location
    val progress: Int, // Progress during download, -1 if not downloaded
)

data class Icon(
    val url: String?, // URL (nullable to handle corrupt backup files)
    val contentUri: String?, // After it's downloaded, the content:// location
)

data class User(
    val baseUrl: String,
    val username: String,
    val password: String
)

data class TrustedCertificateBackup(
    val baseUrl: String,
    val pem: String
)

data class ClientCertificateBackup(
    val baseUrl: String,
    val p12Base64: String,
    val password: String
)

class InvalidBackupFileException : Exception("Invalid backup file format")
