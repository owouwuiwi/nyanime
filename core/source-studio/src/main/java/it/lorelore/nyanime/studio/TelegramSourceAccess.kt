package it.lorelore.nyanime.studio

import it.lorelore.nyanime.source.telegram.TelegramMessagePage
import it.lorelore.nyanime.source.telegram.TelegramSourceDocument
import it.lorelore.nyanime.source.telegram.TelegramSourceMessage
import it.lorelore.nyanime.source.telegram.TelegramSourceReader
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.net.URI
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap

object TelegramPublicChannel {
    fun name(value: String): String {
        val trimmed = value.trim()
        val name =
            if (trimmed.startsWith("https://")) {
                val uri = URI(trimmed)
                require(
                    uri.scheme == "https" &&
                        uri.host == "t.me" &&
                        uri.userInfo == null &&
                        uri.port == -1,
                )
                require(uri.rawQuery == null && uri.rawFragment == null)
                val segments = uri.path.trim('/').split('/')
                when {
                    segments.size == 1 -> segments.single()
                    segments.size == 2 && segments.first() == "s" -> segments.last()
                    else -> error("Choose a public channel username")
                }
            } else {
                trimmed.removePrefix("@")
            }
        require(Regex("[A-Za-z][A-Za-z0-9_]{3,31}").matches(name)) {
            "Choose a public channel username"
        }
        return name.lowercase(Locale.ROOT)
    }

    fun names(values: List<String>): Set<String> {
        require(values.size in 1..16) { "Choose between one and sixteen channels" }
        return values.map(::name).toSet()
    }
}

data class SourceAccessOwner(val packageName: String, val publisher: String, val sourceId: Long) {
    init {
        require(packageName.length in 1..256 && publisher.length in 1..256 && sourceId != 0L)
    }
}

data class SourceAccessState(
    val account: Long?,
    val revision: String,
    val enabled: Boolean,
    val configurable: Boolean = true,
    /** Editing a draft does not revoke an unchanged, active snapshot's reader. */
    val readableRevisions: Set<String> = emptySet(),
    val accountRevision: String = "",
)

data class TelegramPublicChannelState(
    val id: Long,
    val name: String,
    val title: String,
    val publicChannel: Boolean,
    val member: Boolean,
    val pending: Boolean = false,
)

data class TelegramSourceReview(
    val owner: SourceAccessOwner,
    val revision: String,
    val account: Long,
    val channels: List<TelegramPublicChannelState>,
    val accountRevision: String = "",
)

data class TelegramSourceGrant(
    val owner: SourceAccessOwner,
    val account: Long,
    val channels: Map<String, Long>,
    val generation: Long,
    /**
     * An interrupted multi-channel activation keeps its cleanup claims, never reading permission.
     */
    val approved: Boolean = true,
    val accountRevision: String = "",
)

/**
 * Stored separately from grants: ownership survives removal while another source uses the channel.
 */
data class TelegramManagedMembership(val account: Long, val channel: Long)

interface TelegramSourceGrantStore {
    suspend fun grants(): List<TelegramSourceGrant>

    suspend fun save(grant: TelegramSourceGrant)

    suspend fun remove(owner: SourceAccessOwner)

    suspend fun memberships(): Set<TelegramManagedMembership>

    suspend fun remember(membership: TelegramManagedMembership)

    suspend fun forget(membership: TelegramManagedMembership)
}

/** Host implementation only. This interface is never attached to an imported source. */
interface TelegramChannelBackend {
    suspend fun channel(username: String): TelegramPublicChannelState

    suspend fun join(channel: Long)

    suspend fun mute(channel: Long)

    suspend fun leave(channel: Long)

    suspend fun history(channel: Long, before: Long, limit: Int): List<TelegramSourceMessage>

    suspend fun message(channel: Long, message: Long): TelegramSourceMessage

    suspend fun document(channel: Long, message: Long, reference: String): TelegramSourceDocument
}

data class TelegramChannelHistoryPage(val messages: List<TelegramSourceMessage>, val nextBefore: Long?) {
    init {
        require(messages.size <= 100 && (nextBefore == null || nextBefore > 0))
    }
}

/** A host backend can supply the service's explicit pagination token instead of inferring completion. */
interface TelegramChannelPagedBackend : TelegramChannelBackend {
    suspend fun historyPage(channel: Long, before: Long, limit: Int): TelegramChannelHistoryPage
}

data class TelegramSourceActivation(val grant: TelegramSourceGrant, val muteWarnings: Set<Long>)

class TelegramSourceAccessRequired :
    IllegalStateException("Authorize the source's Telegram channels")

/** Consent belongs to account, publisher and source. Membership actions are host UI operations. */
class TelegramSourceAccess(
    private val store: TelegramSourceGrantStore,
    private val backend: TelegramChannelBackend,
    private val current: suspend (SourceAccessOwner) -> SourceAccessState,
) {
    private val changes = Mutex()
    private val active = ConcurrentHashMap<SourceAccessOwner, MutableSet<Job>>()

    suspend fun review(owner: SourceAccessOwner, requested: List<String>): TelegramSourceReview {
        val state = current(owner)
        val account = state.account ?: throw TelegramSourceAccessRequired()
        require(state.configurable) { "Source is unavailable" }
        val channels =
            TelegramPublicChannel.names(requested).map { name ->
                backend.channel(name).also {
                    require(
                        it.publicChannel &&
                            it.id != 0L &&
                            TelegramPublicChannel.name(it.name) == name,
                    ) {
                        "Only public broadcast channels are supported"
                    }
                }
            }
        require(current(owner) == state) { "Source or Telegram account changed" }
        return TelegramSourceReview(owner, state.revision, account, channels, state.accountRevision)
    }

    suspend fun activate(
        review: TelegramSourceReview,
        muteNewChannels: Boolean,
    ): TelegramSourceActivation = changes.withLock {
        require(
            review.channels.size in 1..16 &&
                review.channels.map { it.id }.distinct().size == review.channels.size,
        )
        val state = current(review.owner)
        require(
            state.configurable &&
                state.account == review.account &&
                state.accountRevision == review.accountRevision &&
                state.revision == review.revision,
        ) {
            "Review the source again after an account or source change"
        }
        val old = store.grants().singleOrNull { it.owner == review.owner }
        val pending =
            TelegramSourceGrant(
                review.owner,
                review.account,
                review.channels.associate { TelegramPublicChannel.name(it.name) to it.id },
                (old?.generation ?: 0L) + 1L,
                approved = false,
                accountRevision = review.accountRevision,
            )
        cancelReaders(review.owner)
        // A renewal must not temporarily revoke the still-published source. The host
        // reconciles grant changes immediately, so replacing its approved grant with
        // an unapproved one would disable it and invalidate this activation's CAS.
        // Keep only the existing account/epoch's approval until the replacement is
        // complete; new channels gain no access before the final approved save.
        val renewing = old?.approved == true &&
            old.account == review.account &&
            old.accountRevision == review.accountRevision
        if (!renewing) store.save(pending)
        val channels = linkedMapOf<String, Long>()
        val warnings = mutableSetOf<Long>()
        for (reviewed in review.channels) {
            require(current(review.owner) == state) { "Source or Telegram account changed" }
            val username = TelegramPublicChannel.name(reviewed.name)
            var channel = backend.channel(username)
            require(channel.publicChannel && channel.id == reviewed.id) {
                "The reviewed channel identity changed"
            }
            if (!channel.member) {
                check(!channel.pending) { "The channel join request is still pending" }
                backend.join(channel.id)
                require(current(review.owner) == state) { "Source or Telegram account changed" }
                channel = backend.channel(username)
                require(channel.publicChannel && channel.id == reviewed.id && channel.member) {
                    "Channel membership has not been confirmed"
                }
                store.remember(TelegramManagedMembership(review.account, channel.id))
                if (muteNewChannels) {
                    try {
                        backend.mute(channel.id)
                    } catch (cancel: kotlinx.coroutines.CancellationException) {
                        throw cancel
                    } catch (_: Exception) {
                        warnings += channel.id
                    }
                }
            }
            channels[username] = channel.id
        }
        require(current(review.owner) == state) { "Source or Telegram account changed" }
        val grant = pending.copy(channels = channels, approved = true)
        cancelReaders(review.owner)
        store.save(grant)
        TelegramSourceActivation(grant, warnings)
    }

    /**
     * Returns only app-managed channels which no other source currently uses. Never leaves
     * automatically.
     */
    suspend fun revoke(owner: SourceAccessOwner): Set<TelegramManagedMembership> =
        changes.withLock {
            val old = store.grants().singleOrNull { it.owner == owner }
            cancelReaders(owner)
            store.remove(owner)
            if (old == null) return@withLock emptySet()
            val used =
                store
                    .grants()
                    .filter { it.account == old.account }
                    .flatMap { it.channels.values }
                    .toSet()
            store
                .memberships()
                .filter {
                    it.account == old.account &&
                        it.channel in old.channels.values &&
                        it.channel !in used
                }
                .toSet()
        }

    suspend fun leave(owner: SourceAccessOwner, membership: TelegramManagedMembership) =
        changes.withLock {
            require(current(owner).account == membership.account) { "Telegram account changed" }
            require(membership in store.memberships()) { "This channel was not joined by Nyanime" }
            require(
                store.grants().none {
                    it.account == membership.account && membership.channel in it.channels.values
                },
            ) {
                "Another source still uses this channel"
            }
            backend.leave(membership.channel)
            store.forget(membership)
        }

    /** Logging out revokes readers and grants without changing Telegram memberships. */
    suspend fun revokeAccount(account: Long) = changes.withLock {
        store
            .grants()
            .filter { it.account == account }
            .forEach {
                cancelReaders(it.owner)
                store.remove(it.owner)
            }
    }

    /** Ends a host preview lease without granting or changing persistent membership. */
    fun stopReads(owner: SourceAccessOwner) = cancelReaders(owner)

    fun stopAllReads() = active.keys.toList().forEach(::cancelReaders)

    /** Revalidates a cached preview without downloading messages or joining any channel. */
    suspend fun checkAccess(owner: SourceAccessOwner, revision: String, channels: List<String>) {
        allowed(owner, revision, TelegramPublicChannel.names(channels))
    }

    fun reader(
        owner: SourceAccessOwner,
        revision: String,
        requested: List<String>,
    ): TelegramSourceReader {
        val names = TelegramPublicChannel.names(requested)
        return object : TelegramSourceReader {
            override suspend fun history(
                channel: String,
                cursor: String?,
                limit: Int,
            ): TelegramMessagePage =
                read(owner, revision, names) { grant ->
                    require(limit in 1..100)
                    val id = requireChannel(grant, channel)
                    val before =
                        if (cursor == null) {
                            0L
                        } else {
                            val parts = cursor.split(':')
                            require(
                                parts.size == 3 &&
                                    parts[0] == "history" &&
                                    parts[1].toLongOrNull() == id,
                            ) {
                                "History cursor belongs to another channel"
                            }
                            requireNotNull(parts[2].toLongOrNull()).also { require(it > 0) }
                        }
                    val page = (backend as? TelegramChannelPagedBackend)?.historyPage(id, before, limit)
                    val messages = page?.messages ?: backend.history(id, before, limit)
                    require(
                        messages.size <= limit &&
                            messages.all {
                                it.channelId == id && (before == 0L || it.messageId <= before)
                            },
                    )
                    val ordered =
                        messages.distinctBy { it.messageId }.sortedByDescending { it.messageId }
                    val next = if (page != null) {
                        page.nextBefore
                    } else {
                        ordered.minOfOrNull { it.messageId }?.takeIf { before == 0L || it < before }
                    }
                    require(next == null || before == 0L || next < before) { "History cursor did not advance" }
                    TelegramMessagePage(ordered, next?.let { "history:$id:$it" })
                }

            override suspend fun message(channel: String, messageId: Long): TelegramSourceMessage =
                read(owner, revision, names) { grant ->
                    require(messageId > 0L)
                    val id = requireChannel(grant, channel)
                    backend.message(id, messageId).also {
                        require(it.channelId == id && it.messageId == messageId) {
                            "Message identity changed"
                        }
                    }
                }

            override suspend fun document(reference: String): TelegramSourceDocument =
                read(owner, revision, names) { grant ->
                    val parts = reference.split(':')
                    require(
                        parts.size == 4 &&
                            parts[0] == "document" &&
                            Regex("[a-f0-9]{64}").matches(parts[3]),
                    ) {
                        "Invalid attachment reference"
                    }
                    val id = requireNotNull(parts[1].toLongOrNull())
                    val message = requireNotNull(parts[2].toLongOrNull()).also { require(it > 0L) }
                    require(id in grant.channels.values) {
                        "This channel is outside the source grant"
                    }
                    requireChannel(grant, grant.channels.entries.first { it.value == id }.key)
                    backend.document(id, message, reference).also {
                        require(it.reference == reference) { "Attachment identity changed" }
                    }
                }
        }
    }

    private suspend fun requireChannel(grant: TelegramSourceGrant, raw: String): Long {
        val name = TelegramPublicChannel.name(raw)
        val id = grant.channels[name] ?: throw TelegramSourceAccessRequired()
        val channel = backend.channel(name)
        if (channel.id != id || !channel.publicChannel || !channel.member) {
            throw TelegramSourceAccessRequired()
        }
        return id
    }

    private suspend fun allowed(
        owner: SourceAccessOwner,
        revision: String,
        names: Set<String>,
    ): TelegramSourceGrant {
        val state = current(owner)
        val grant =
            store.grants().singleOrNull { it.owner == owner }
                ?: throw TelegramSourceAccessRequired()
        if (
            !grant.approved ||
            !state.enabled ||
            revision !in state.readableRevisions.ifEmpty { setOf(state.revision) } ||
            state.account != grant.account ||
            state.accountRevision != grant.accountRevision ||
            grant.channels.keys != names
        ) {
            throw TelegramSourceAccessRequired()
        }
        return grant
    }

    private suspend fun <T> read(
        owner: SourceAccessOwner,
        revision: String,
        names: Set<String>,
        operation: suspend (TelegramSourceGrant) -> T,
    ): T {
        val grant = allowed(owner, revision, names)
        val job = Job(currentCoroutineContext()[Job])
        active.computeIfAbsent(owner) { ConcurrentHashMap.newKeySet() }.add(job)
        try {
            return withContext(job) {
                check(allowed(owner, revision, names) == grant) { "Source access changed" }
                operation(grant).also {
                    currentCoroutineContext().ensureActive()
                    check(allowed(owner, revision, names) == grant) { "Source access changed" }
                }
            }
        } finally {
            active[owner]?.remove(job)
            job.complete()
        }
    }

    private fun cancelReaders(owner: SourceAccessOwner) {
        active.remove(owner)?.forEach { it.cancel() }
    }
}
