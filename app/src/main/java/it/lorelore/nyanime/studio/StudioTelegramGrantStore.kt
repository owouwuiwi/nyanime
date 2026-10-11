package it.lorelore.nyanime.studio

import android.content.Context
import android.util.AtomicFile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import java.io.File

@Serializable
private data class StoredTelegramGrant(
    val packageName: String,
    val publisher: String,
    val source: Long,
    val account: Long,
    val channels: Map<String, Long>,
    val generation: Long,
    val approved: Boolean,
    val accountRevision: String = "",
) {
    fun grant() =
        TelegramSourceGrant(
            SourceAccessOwner(packageName, publisher, source),
            account,
            channels,
            generation,
            approved,
            accountRevision,
        )
            .also {
                require(account != 0L && generation > 0 && channels.size in 1..16)
                require(
                    channels.all { (name, id) ->
                        TelegramPublicChannel.name(name) == name && id != 0L
                    },
                )
            }
}

@Serializable private data class StoredTelegramMembership(val account: Long, val channel: Long)

@Serializable
private data class StoredTelegramAccess(
    val schema: Int = 1,
    val grants: List<StoredTelegramGrant> = emptyList(),
    val memberships: Set<StoredTelegramMembership> = emptySet(),
)

/** Local authorization state is deliberately excluded from backups, graph exports and Cloud. */
internal data class StudioTelegramGrantSnapshot(val grants: List<TelegramSourceGrant>, val version: Long)

internal class StudioTelegramGrantStore private constructor(context: Context) : TelegramSourceGrantStore {
    private val file = AtomicFile(File(context.noBackupFilesDir, "source-telegram-access-v1.json"))
    private val lock = Mutex()
    private var state: StoredTelegramAccess? = null
    private val version = MutableStateFlow(0L)
    val changes = version.asStateFlow()

    suspend fun snapshot(): StudioTelegramGrantSnapshot = read { current ->
        StudioTelegramGrantSnapshot(current.grants.map(StoredTelegramGrant::grant), version.value)
    }

    private suspend fun <T> read(operation: (StoredTelegramAccess) -> T): T =
        withContext(Dispatchers.IO) {
            lock.withLock { operation(load()) }
        }

    private fun load(): StoredTelegramAccess {
        state?.let {
            return it
        }
        return (
            if (file.baseFile.exists() || File(file.baseFile.path + ".bak").exists()) {
                val bytes = file.openRead().use { SourceBytes.read(it, 512 * 1024) }
                StudioDocuments.json
                    .decodeFromString<StoredTelegramAccess>(bytes.toString(Charsets.UTF_8))
                    .also {
                        require(
                            it.schema == 1 && it.grants.size <= 256 && it.memberships.size <= 4096,
                        )
                        require(
                            it.grants.map { entry -> entry.grant().owner }.distinct().size ==
                                it.grants.size,
                        )
                        require(
                            it.memberships.all { entry ->
                                entry.account != 0L && entry.channel != 0L
                            },
                        )
                    }
            } else {
                StoredTelegramAccess()
            }
            )
            .also { state = it }
    }

    private suspend fun change(operation: (StoredTelegramAccess) -> StoredTelegramAccess) =
        withContext(Dispatchers.IO) {
            lock.withLock {
                val next = operation(load())
                require(next.grants.size <= 256 && next.memberships.size <= 4096)
                val bytes = StudioDocuments.json.encodeToString(next).toByteArray()
                require(bytes.size <= 512 * 1024)
                val output = file.startWrite()
                try {
                    output.write(bytes)
                    file.finishWrite(output)
                    state = next
                    version.value = version.value + 1
                } catch (failure: Exception) {
                    file.failWrite(output)
                    throw failure
                }
            }
        }

    override suspend fun grants() = read { it.grants.map(StoredTelegramGrant::grant) }

    override suspend fun save(grant: TelegramSourceGrant) = change { current ->
        val entry =
            StoredTelegramGrant(
                grant.owner.packageName,
                grant.owner.publisher,
                grant.owner.sourceId,
                grant.account,
                grant.channels,
                grant.generation,
                grant.approved,
                grant.accountRevision,
            )
        current.copy(grants = current.grants.filterNot { it.grant().owner == grant.owner } + entry)
    }

    override suspend fun remove(owner: SourceAccessOwner) = change { current ->
        current.copy(grants = current.grants.filterNot { it.grant().owner == owner })
    }

    override suspend fun memberships() = read { current ->
        current.memberships.map { TelegramManagedMembership(it.account, it.channel) }.toSet()
    }

    override suspend fun remember(membership: TelegramManagedMembership) = change {
        it.copy(
            memberships =
            it.memberships + StoredTelegramMembership(membership.account, membership.channel),
        )
    }

    override suspend fun forget(membership: TelegramManagedMembership) = change {
        it.copy(
            memberships =
            it.memberships - StoredTelegramMembership(membership.account, membership.channel),
        )
    }

    companion object {
        @Volatile private var instance: StudioTelegramGrantStore? = null
        fun get(context: Context) = instance ?: synchronized(this) {
            instance ?: StudioTelegramGrantStore(context.applicationContext).also { instance = it }
        }
    }
}
