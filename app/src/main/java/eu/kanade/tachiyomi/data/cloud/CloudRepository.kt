package eu.kanade.tachiyomi.data.cloud

import android.content.Context
import android.net.Uri
import eu.kanade.tachiyomi.BuildConfig
import eu.kanade.tachiyomi.data.backup.BackupExportStore
import eu.kanade.tachiyomi.data.backup.BackupFileValidator
import eu.kanade.tachiyomi.data.backup.create.BackupCreator
import eu.kanade.tachiyomi.data.backup.create.BackupOptions
import eu.kanade.tachiyomi.data.cloud.sync.CloudSyncBackupProtection
import eu.kanade.tachiyomi.data.telegram.TelegramClient
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import nyanime.telegram.CloudCopy
import nyanime.telegram.CloudManifest
import nyanime.telegram.CloudPage
import nyanime.telegram.CloudPending
import nyanime.telegram.CloudPhase
import nyanime.telegram.CloudProgress
import nyanime.telegram.CloudRetention
import nyanime.telegram.CloudWire
import nyanime.telegram.TelegramException
import nyanime.telegram.TelegramFailure
import nyanime.telegram.flag
import nyanime.telegram.number
import nyanime.telegram.objectOrNull
import nyanime.telegram.tdRequest
import nyanime.telegram.text
import nyanime.telegram.type
import nyanime.telegram.withTelegramTimeout
import java.io.File
import java.security.MessageDigest
import java.util.UUID

data class CloudArchive(val chatId: Long, val title: String)

class CloudRepository private constructor(private val context: Context) {
    val client = TelegramClient.get(context)
    val preferences = CloudPreferences()
    private val storage = CloudStorage(context)
    private val operations = Mutex()
    private val initialized = CompletableDeferred<Unit>()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    val progress = MutableStateFlow(CloudProgress())
    val copies = MutableStateFlow<List<CloudCopy>>(emptyList())
    val archives = MutableStateFlow<List<CloudArchive>>(emptyList())
    private val observedMessages = java.util.concurrent.ConcurrentHashMap<String, JsonObject>()
    val configuredState = MutableStateFlow(false)
    val selectedArchiveState = MutableStateFlow(false)
    private var localDeviceId = ""
    val deviceId: String get() = localDeviceId
    val configured: Boolean get() = configuredState.value
    init {
        scope.launch {
            try {
                localDeviceId = storage.deviceId()
                val account = storage.get("account")?.toLongOrNull()
                if (account != null) {
                    selectedArchiveState.value = storage.get("archive:$account") != null
                    configuredState.value = selectedArchiveState.value && storage.get("setup:$account") == "complete"
                    copies.value = storage.copies(account).sortedByDescending { it.serverDate }
                }
                if (storage.pending() != null) progress.value = CloudProgress(CloudPhase.WAITING)
                initialized.complete(Unit)
            } catch (error: Exception) {
                progress.value = CloudProgress(CloudPhase.FAILED)
                initialized.completeExceptionally(error)
            }
        }
    }

    suspend fun awaitInitialization() {
        initialized.await()
    }

    suspend fun completeSetup(expectedAccount: Long) = withContext(Dispatchers.IO) {
        initialized.await()
        if (expectedAccount <= 0 ||
            storage.get("account") != expectedAccount.toString() ||
            storage.get("archive:$expectedAccount") == null
        ) {
            return@withContext
        }
        storage.put("setup:$expectedAccount", "complete")
        configuredState.value = true
        preferences.introductionComplete().set(true)
        preferences.enabled().set(true)
        CloudBackupWorker.schedule(context)
    }

    suspend fun ready(): Long = withContext(Dispatchers.IO) {
        initialized.await()
        client.connect()
        withTelegramTimeout(45_000) {
            client.state.first { (it.ready && it.account != null) || it.failure != null }.let {
                it.failure?.let { failure -> throw TelegramException(failure) }
                val id = it.account!!.id
                if (storage.get("account") != id.toString()) {
                    storage.put("account", id.toString())
                    selectedArchiveState.value = storage.get("archive:$id") != null
                    configuredState.value = selectedArchiveState.value && storage.get("setup:$id") == "complete"
                    copies.value = storage.copies(id).sortedByDescending { copy -> copy.serverDate }
                }
                id
            }
        }
    }

    suspend fun discover(): List<CloudArchive> = withContext(Dispatchers.IO) {
        val account = ready()
        storage.put("account", account.toString())
        copies.value = storage.copies(account).sortedByDescending { it.serverDate }
        val result = mutableListOf<CloudArchive>()
        for (list in listOf("chatListMain", "chatListArchive")) {
            while (true) {
                try {
                    client.request(
                        tdRequest(
                            "loadChats",
                            "chat_list" to tdRequest(list),
                            "limit" to JsonPrimitive(100),
                        ),
                    )
                } catch (
                    error: TelegramException,
                ) {
                    if (error.code == 404) break else throw error
                }
            }
            val ids =
                client.request(
                    tdRequest("getChats", "chat_list" to tdRequest(list), "limit" to JsonPrimitive(100_000)),
                )["chat_ids"] as? JsonArray
                    ?: continue
            for (id in ids) {
                val chat = client.request(tdRequest("getChat", "chat_id" to id))
                val type = chat.objectOrNull("type") ?: continue
                if (type.type() != "chatTypeSupergroup" || !type.flag("is_channel")) continue
                val group = client.request(
                    tdRequest(
                        "getSupergroup",
                        "supergroup_id" to JsonPrimitive(type.number("supergroup_id")),
                    ),
                )
                if (group.objectOrNull("status")?.type() != "chatMemberStatusCreator") continue
                val info = client.request(
                    tdRequest(
                        "getSupergroupFullInfo",
                        "supergroup_id" to JsonPrimitive(type.number("supergroup_id")),
                    ),
                )
                if (info.text("description") == CloudManifest.ARCHIVE_MARKER) {
                    verifyArchive(chat.number("id"))
                    result += CloudArchive(chat.number("id"), chat.text("title"))
                }
            }
        }
        archives.value = result.distinctBy { it.chatId }
        archives.value
    }

    suspend fun selectArchive(chatId: Long) = withContext(Dispatchers.IO) {
        val account = ready()
        verifyArchive(chatId)
        storage.put("account", account.toString())
        storage.put("archive:$account", chatId.toString())
        selectedArchiveState.value = true
        refresh()
    }

    suspend fun createArchive() = withContext(Dispatchers.IO) {
        val found = discover()
        check(found.isEmpty()) { "Select an existing archive" }
        val chat = client.request(
            buildJsonObject {
                put("@type", "createNewSupergroupChat")
                put("title", "Nyanime Backup")
                put("is_channel", true)
                put("is_forum", false)
                put("description", CloudManifest.ARCHIVE_MARKER)
                put("message_auto_delete_time", 0)
            },
        )
        selectArchive(chat.number("id"))
    }

    private suspend fun verifyArchive(chatId: Long) {
        val chat = client.request(tdRequest("getChat", "chat_id" to JsonPrimitive(chatId)))
        val type = chat.objectOrNull("type") ?: throw TelegramException(TelegramFailure.ARCHIVE_UNSAFE)
        if (type.type() != "chatTypeSupergroup" || !type.flag("is_channel")) {
            throw TelegramException(TelegramFailure.ARCHIVE_UNSAFE)
        }
        val group = client.request(
            tdRequest(
                "getSupergroup",
                "supergroup_id" to JsonPrimitive(type.number("supergroup_id")),
            ),
        )
        val info = client.request(
            tdRequest(
                "getSupergroupFullInfo",
                "supergroup_id" to JsonPrimitive(type.number("supergroup_id")),
            ),
        )
        if (!CloudWire.privateArchive(group, info)) throw TelegramException(TelegramFailure.ARCHIVE_UNSAFE)
    }

    private suspend fun archive(account: Long): Long = storage.get("archive:$account")?.toLongOrNull()
        ?: throw TelegramException(TelegramFailure.REQUEST)

    private fun parseCopy(message: JsonObject, account: Long, chat: Long): CloudCopy? {
        return CloudWire.copy(message, account, chat)
    }

    suspend fun page(cursor: Long = 0): CloudPage = withContext(Dispatchers.IO) {
        val account = ready()
        val chat = archive(account)
        val response = client.request(
            buildJsonObject {
                put("@type", "getChatHistory")
                put("chat_id", chat)
                put("from_message_id", cursor)
                put("offset", 0)
                put("limit", 50)
                put("only_local", false)
            },
        )
        val messages = (response["messages"] as? JsonArray).orEmpty().map { it.jsonObject }
        val next = messages.minOfOrNull { it.number("id") } ?: 0
        messages.forEach { message ->
            CloudWire.manifest(message, account, chat)?.let {
                observedMessages[it.backupId] =
                    message
            }
        }
        val values = messages.mapNotNull { parseCopy(it, account, chat) }.distinctBy { it.manifest.backupId }
        values.forEach(storage::cache)
        copies.value = (if (cursor == 0L) values else copies.value.filter { it.accountId == account } + values)
            .distinctBy { it.manifest.backupId }.sortedByDescending { it.serverDate }
        CloudPage(values, next, messages.isEmpty() || next == cursor)
    }

    suspend fun refresh() = withContext(Dispatchers.IO) {
        page()
    }

    /** TDLib may return a short first page containing only service posts. Do not mistake it for an empty archive. */
    suspend fun restoreSuggestions(): CloudPage = withContext(Dispatchers.IO) {
        var cursor = 0L
        while (true) {
            val result = page(cursor)
            if (copies.value.isNotEmpty() || result.complete) return@withContext result
            cursor = result.cursor
        }
        error("Unreachable")
    }

    private suspend fun allCopies(): List<CloudCopy> {
        val result = mutableListOf<CloudCopy>()
        observedMessages.clear()
        var cursor = 0L
        while (true) {
            val next = page(cursor)
            result += next.copies
            if (next.complete) break
            cursor = next.cursor
        }
        return result.distinctBy { it.manifest.backupId }
    }

    suspend fun backup(automatic: Boolean): CloudPhase = operations.withLock {
        withContext(Dispatchers.IO) {
            val account = ready()
            val chat = archive(account)
            verifyArchive(chat)
            var pending = storage.pending()
            if (pending != null && (pending.accountId != account || pending.chatId != chat)) {
                throw TelegramException(TelegramFailure.REQUEST)
            }
            if (pending == null) {
                progress.value = CloudProgress(CloudPhase.PREPARING)
                val id = UUID.randomUUID().toString()
                val file = File(context.noBackupFilesDir, "cloud/outbox/$id.nyabk").apply {
                    parentFile!!.mkdirs()
                    createNewFile()
                }
                try {
                    BackupCreator(context, false).backup(Uri.fromFile(file), BackupOptions.complete())
                    val contentHash = sha256(file)
                    if (automatic && contentHash == storage.get("hash:$account:$deviceId")) {
                        file.delete()
                        progress.value = CloudProgress(CloudPhase.UNCHANGED)
                        return@withContext CloudPhase.UNCHANGED
                    }
                    val protection = CloudSyncBackupProtection(context).protect(file, account, id)
                    val hash = if (protection != null) sha256(file) else contentHash
                    val manifest =
                        CloudManifest(
                            version = if (protection != null) 2 else 1,
                            backupId = id,
                            deviceId = deviceId,
                            deviceName = preferences.deviceName().get().take(80),
                            sha256 = hash,
                            bytes = file.length(),
                            appVersion = BuildConfig.VERSION_NAME,
                            createdAt = System.currentTimeMillis(),
                            protectionSpace = protection?.first,
                            protectionEpoch = protection?.second,
                        )
                    require(manifest.valid())
                    pending = CloudPending(manifest, account, chat, file.path, contentHash = contentHash)
                    storage.pending(pending!!)
                } catch (error: Exception) {
                    file.delete()
                    throw error
                }
            }
            val operation = pending!!
            val file = privateFile("outbox", operation.manifest.backupId)
            check(file.canonicalPath == File(operation.path).canonicalPath)
            // An uncertain acknowledgement is reconciled before any new send.
            val existing = allCopies().firstOrNull { it.manifest.backupId == operation.manifest.backupId }
            val confirmed = existing ?: upload(operation, file)
            progress.value = CloudProgress(CloudPhase.CHECKING)
            check(confirmed.manifest == operation.manifest)
            storage.confirm(confirmed)
            val previous = copies.value.filterNot { it.manifest.backupId == confirmed.manifest.backupId }
            copies.value = (previous + confirmed).sortedByDescending { it.serverDate }
            file.delete()
            progress.value = CloudProgress(CloudPhase.SAVED, 1f)
            // A cleanup failure cannot turn a confirmed backup into a failed upload.
            try {
                val history = allCopies()
                val days = preferences.maxAgeDays().get().coerceIn(0, 3650)
                CloudRetention.obsolete(
                    history,
                    if (days >
                        0
                    ) {
                        Int.MAX_VALUE
                    } else {
                        preferences.keepCopies().get().coerceIn(1, 100)
                    },
                    days,
                    confirmed.serverDate,
                ).forEach { deleteInternal(it) }
            } catch (
                cancel: CancellationException,
            ) {
                throw cancel
            } catch (_: Exception) {
                storage.put("cleanup_pending", "true")
            }
            CloudPhase.SAVED
        }
    }

    private suspend fun upload(operation: CloudPending, file: File): CloudCopy {
        check(file.isFile && file.length() == operation.manifest.bytes && sha256(file) == operation.manifest.sha256)
        progress.value = CloudProgress(CloudPhase.UPLOADING, 0f)
        var message = observedMessages[operation.manifest.backupId] ?: if (operation.messageId != 0L) {
            try {
                client.request(
                    tdRequest(
                        "getMessage",
                        "chat_id" to JsonPrimitive(operation.chatId),
                        "message_id" to JsonPrimitive(operation.messageId),
                    ),
                )
            } catch (
                error: TelegramException,
            ) {
                if (error.code == 404) null else throw error
            }
        } else {
            null
        }
        if (message?.objectOrNull("sending_state")?.type() == "messageSendingStateFailed") {
            client.request(
                tdRequest(
                    "deleteMessages",
                    "chat_id" to JsonPrimitive(operation.chatId),
                    "message_ids" to JsonArray(listOf(JsonPrimitive(message!!.number("id")))),
                    "revoke" to JsonPrimitive(true),
                ),
            )
            message = null
        }
        if (message == null) {
            // A lost send reply is resolved on the server, not just from the local TDLib history cache.
            if (operation.sending) {
                val response = client.request(
                    tdRequest(
                        "searchChatMessages",
                        "chat_id" to JsonPrimitive(operation.chatId),
                        "query" to JsonPrimitive(operation.manifest.backupId),
                        "from_message_id" to JsonPrimitive(0),
                        "offset" to JsonPrimitive(0),
                        "limit" to JsonPrimitive(100),
                    ),
                )
                (response["messages"] as? JsonArray).orEmpty().mapNotNull {
                    parseCopy(it.jsonObject, operation.accountId, operation.chatId)
                }
                    .firstOrNull {
                        it.manifest.backupId == operation.manifest.backupId &&
                            it.manifest.sha256 == operation.manifest.sha256
                    }
                    ?.let { return it }
            }
            storage.pending(operation.copy(sending = true))
            message = client.request(
                buildJsonObject {
                    put("@type", "sendMessage")
                    put("chat_id", operation.chatId)
                    put(
                        "input_message_content",
                        tdRequest(
                            "inputMessageDocument",
                            "document" to tdRequest(
                                "inputDocument",
                                "document" to tdRequest("inputFileLocal", "path" to JsonPrimitive(file.path)),
                                "disable_content_type_detection" to JsonPrimitive(true),
                            ),
                            "caption" to
                                tdRequest("formattedText", "text" to JsonPrimitive(operation.manifest.caption())),
                        ),
                    )
                },
            )
            storage.pending(operation.copy(messageId = message.number("id"), sending = true))
        }
        val initialId = message.number("id")
        return withTimeout(30 * 60_000L) {
            while (true) {
                client.receipts.value[initialId]?.let { receipt ->
                    if (receipt.type() == "updateMessageSendFailed") {
                        val error = receipt.objectOrNull("error")
                        throw error?.let(nyanime.telegram.TelegramErrors::parse)
                            ?: TelegramException(TelegramFailure.REQUEST)
                    }
                    message = receipt.objectOrNull("message") ?: message
                }
                parseCopy(message!!, operation.accountId, operation.chatId)?.let { return@withTimeout it }
                val fileId =
                    message!!.objectOrNull("content")?.objectOrNull("document")?.objectOrNull("document")?.number("id")
                        ?: 0
                client.files.value[fileId]?.objectOrNull("remote")?.number("uploaded_size")?.let { uploaded ->
                    progress.value =
                        CloudProgress(
                            CloudPhase.UPLOADING,
                            (uploaded.toDouble() / operation.manifest.bytes).toFloat().coerceIn(0f, 0.99f),
                        )
                }
                delay(1000)
                try {
                    message =
                        client.request(
                            tdRequest(
                                "getMessage",
                                "chat_id" to JsonPrimitive(operation.chatId),
                                "message_id" to JsonPrimitive(message!!.number("id")),
                            ),
                        )
                } catch (error: TelegramException) {
                    if (error.code != 404) throw error
                    val match = allCopies().firstOrNull { it.manifest.backupId == operation.manifest.backupId }
                    if (match != null) return@withTimeout match
                }
            }
            error("Unreachable")
        }
    }

    suspend fun download(copy: CloudCopy): File = operations.withLock {
        withContext(Dispatchers.IO) {
            try {
                require(copy.accountId == ready() && copy.chatId == archive(copy.accountId))
                verifyArchive(copy.chatId)
                progress.value = CloudProgress(CloudPhase.DOWNLOADING, 0f)
                val file = client.request(
                    buildJsonObject {
                        put("@type", "downloadFile")
                        put("file_id", copy.fileId)
                        put("priority", 16)
                        put("synchronous", false)
                    },
                )
                var current = file
                withTimeout(30 * 60_000L) {
                    while (current.objectOrNull("local")?.flag("is_downloading_completed") != true) {
                        delay(500)
                        current = client.request(tdRequest("getFile", "file_id" to JsonPrimitive(copy.fileId)))
                        val size = current.objectOrNull("local")?.number("downloaded_size") ?: 0
                        progress.value =
                            CloudProgress(
                                CloudPhase.DOWNLOADING,
                                (size.toDouble() / copy.manifest.bytes).toFloat().coerceIn(0f, 0.99f),
                            )
                    }
                }
                val downloaded = File(current.objectOrNull("local")!!.text("path"))
                val telegramRoot = File(context.noBackupFilesDir, "telegram").canonicalPath + File.separator
                check(downloaded.canonicalPath.startsWith(telegramRoot))
                check(downloaded.length() == copy.manifest.bytes && sha256(downloaded) == copy.manifest.sha256)
                val target = privateFile("restore", copy.manifest.backupId)
                try {
                    if (copy.manifest.protected) {
                        CloudSyncBackupProtection(context).unprotect(downloaded, target, copy.accountId, copy.manifest)
                    } else {
                        downloaded.copyTo(target, overwrite = true)
                    }
                    BackupFileValidator(context).validate(Uri.fromFile(target))
                } catch (error: Exception) {
                    target.delete()
                    throw error
                }
                progress.value = CloudProgress(CloudPhase.IDLE)
                target
            } catch (_: TimeoutCancellationException) {
                progress.value = CloudProgress(CloudPhase.FAILED)
                throw TelegramException(TelegramFailure.DISCONNECTED, code = 408)
            } catch (cancel: CancellationException) {
                progress.value = CloudProgress()
                throw cancel
            } catch (error: Exception) {
                progress.value = CloudProgress(CloudPhase.FAILED)
                throw error
            }
        }
    }

    suspend fun export(copy: CloudCopy): Uri = withContext(Dispatchers.IO) {
        val file = download(copy)
        val uri = BackupExportStore.createPending(context)
        try {
            context.contentResolver.openOutputStream(uri)!!.use { output ->
                file.inputStream().use { it.copyTo(output) }
            }
            BackupExportStore.publish(context, uri)
            uri
        } catch (error: Exception) {
            BackupExportStore.delete(context, uri)
            throw error
        }
    }

    suspend fun delete(copy: CloudCopy) = operations.withLock { withContext(Dispatchers.IO) { deleteInternal(copy) } }
    private suspend fun deleteInternal(copy: CloudCopy) {
        require(copy.accountId == ready() && copy.chatId == archive(copy.accountId))
        verifyArchive(copy.chatId)
        client.request(
            tdRequest(
                "deleteMessages",
                "chat_id" to JsonPrimitive(copy.chatId),
                "message_ids" to JsonArray(listOf(JsonPrimitive(copy.messageId))),
                "revoke" to JsonPrimitive(true),
            ),
        )
        storage.deleted(copy)
        copies.value = copies.value.filterNot { it.manifest.backupId == copy.manifest.backupId }
    }

    suspend fun pin(copy: CloudCopy) = operations.withLock {
        withContext(Dispatchers.IO) {
            require(copy.accountId == ready() && copy.chatId == archive(copy.accountId))
            verifyArchive(copy.chatId)
            val request = tdRequest(
                if (copy.pinned) "unpinChatMessage" else "pinChatMessage",
                "chat_id" to JsonPrimitive(copy.chatId),
                "message_id" to JsonPrimitive(copy.messageId),
                "disable_notification" to JsonPrimitive(true),
            )
            client.request(request)
            val changed = copy.copy(pinned = !copy.pinned)
            storage.cache(changed)
            copies.value = copies.value.map { if (it.manifest.backupId == copy.manifest.backupId) changed else it }
        }
    }

    suspend fun disconnect() = withContext(Dispatchers.IO) {
        preferences.enabled().set(false)
        CloudBackupWorker.schedule(context)
        androidx.work.WorkManager.getInstance(context).cancelUniqueWork(CloudBackupWorker.MANUAL).result.get()
        androidx.work.WorkManager.getInstance(context).cancelUniqueWork(CloudBackupWorker.RETRY_MANUAL).result.get()
        operations.withLock {
            client.logout()
            val root = context.noBackupFilesDir.canonicalFile
            val cloud = File(root, "cloud").canonicalFile
            check(cloud.parentFile == root)
            if (cloud.exists()) check(cloud.deleteRecursively())
            storage.clearAccount()
            configuredState.value = false
            selectedArchiveState.value = false
            copies.value = emptyList()
            progress.value = CloudProgress()
        }
    }

    private fun privateFile(folder: String, id: String): File {
        require(folder in listOf("outbox", "restore") && UUID.fromString(id).toString() == id)
        return File(context.noBackupFilesDir, "cloud/$folder/$id.nyabk").apply { parentFile!!.mkdirs() }
    }

    companion object {
        @Volatile private var instance: CloudRepository? = null
        fun get(context: Context): CloudRepository = instance ?: synchronized(this) {
            instance ?: CloudRepository(context.applicationContext).also { instance = it }
        }
        internal fun sha256(file: File): String {
            val digest = MessageDigest.getInstance("SHA-256")
            file.inputStream().buffered().use { stream ->
                val buffer = ByteArray(64 * 1024)
                while (true) {
                    val count = stream.read(buffer)
                    if (count < 0) break
                    digest.update(buffer, 0, count)
                }
            }
            return digest.digest().joinToString("") { "%02x".format(it) }
        }
    }
}
