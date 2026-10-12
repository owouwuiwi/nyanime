package eu.kanade.tachiyomi.data.community

import kotlinx.serialization.Serializable
import java.net.URI

@Serializable
enum class ShelfStatus { Watching, Planned, Completed, Paused, Dropped }

@Serializable
enum class WallAccess { Friends, Everyone, Closed }

@Serializable
enum class PresenceAccess { Private, Friends, Public }

/** Public content deliberately has no extension, episode URL, cookies or playback position. */
@Serializable
data class PublicTitle(
    val id: String,
    val title: String,
    val manga: Boolean = false,
    val artwork: String = "",
    val status: ShelfStatus = ShelfStatus.Planned,
) {
    fun valid() = id.length in 1..160 && title.length in 1..240 && safeImage(artwork)
}

@Serializable
data class CommunityProfile(
    val key: String,
    val name: String = "",
    val bio: String = "",
    val avatar: String = "",
    val banner: String = "",
    val accent: Long = 0xFFE50934,
    val favorites: List<PublicTitle> = emptyList(),
    val shelves: List<PublicTitle> = emptyList(),
    val wall: WallAccess = WallAccess.Friends,
) {
    fun valid() = validKey(key) &&
        name.length in 1..40 &&
        bio.length <= 800 &&
        safeImage(avatar) &&
        safeImage(banner) &&
        favorites.size <= 3 &&
        shelves.size <= 250 &&
        favorites.all(PublicTitle::valid) &&
        shelves.all(PublicTitle::valid) &&
        favorites.distinctBy { it.id }.size == favorites.size &&
        shelves.distinctBy { it.id }.size == shelves.size
}

@Serializable
data class SocialPost(
    val text: String = "",
    val image: String = "",
    val title: PublicTitle? = null,
    val spoiler: Boolean = false,
    val sticker: String = "",
) {
    fun valid() = text.length <= 4000 &&
        safeImage(image) &&
        (title?.valid() != false) &&
        sticker in listOf("", "heart", "star", "cat", "popcorn") &&
        (text.isNotBlank() || image.isNotBlank() || title != null || sticker.isNotBlank())
}

@Serializable
data class FriendState(
    val peer: String,
    val outgoing: String = "",
    val incoming: String = "",
    val accepted: Boolean = false,
    val blocked: Boolean = false,
    val revision: Long = 0,
)

@Serializable
data class PrivateGroup(
    val id: String,
    val owner: String,
    val name: String,
    val members: List<String>,
    val revision: Long = 1,
    val image: String = "",
    val closed: Boolean = false,
) {
    fun valid() = id.matches(Regex("[0-9a-f]{32}")) &&
        validKey(owner) &&
        name.length in 1..60 &&
        members.size in (if (closed) 1 else 2)..10 &&
        members.distinct().size == members.size &&
        owner in members &&
        members.all(::validKey) &&
        revision > 0 &&
        safeImage(image)

    fun fingerprint(): String = sha256(communityJson.encodeToString(serializer(), this).toByteArray()).hex()

    fun versionKey(): String = "$id:$revision:${fingerprint()}"
}

@Serializable
data class PrivateAction(
    val version: Int = 1,
    val type: String,
    val request: String = "",
    val peer: String = "",
    val group: PrivateGroup? = null,
    val body: String = "",
    val expires: Long = 0,
    val revision: Long = 0,
)

@Serializable
data class SocialPresence(
    val text: String,
    val expires: Long,
    val activity: SocialActivity? = null,
)

/** Display-only activity: no source, streaming address, database ID or private progress. */
@Serializable
data class SocialActivity(val token: String, val title: String, val item: String, val manga: Boolean = false) {
    fun valid() = token.matches(Regex("[0-9a-f]{32}")) && title.length in 1..240 && item.length <= 240
}

@Serializable
enum class WatchRequestStatus { Pending, Accepted, Declined, Cancelled }

@Serializable
data class SocialWatchRequest(
    val id: String,
    val requester: String,
    val host: String,
    val activity: SocialActivity,
    val expires: Long,
    val status: WatchRequestStatus = WatchRequestStatus.Pending,
    val invite: String = "",
) {
    fun valid(now: Long) = id.matches(Regex("[0-9a-f]{32}")) &&
        validKey(requester) &&
        validKey(host) &&
        requester != host &&
        activity.valid() &&
        !activity.manga &&
        expires in (now + 1)..(now + 180_000)

    fun pending(now: Long) = status == WatchRequestStatus.Pending && expires > now

    fun acceptsResponse(next: SocialWatchRequest, author: String, now: Long): Boolean =
        pending(now) &&
            next.copy(status = status, invite = invite) == this &&
            when (next.status) {
                WatchRequestStatus.Accepted ->
                    author == host &&
                        runCatching {
                            eu.kanade.tachiyomi.data.watch.WatchInvite.parse(next.invite, now)
                        }.isSuccess
                WatchRequestStatus.Declined -> author == host && next.invite.isEmpty()
                WatchRequestStatus.Cancelled -> author == requester && next.invite.isEmpty()
                WatchRequestStatus.Pending -> false
            }
}

@Serializable
data class SyncReference(
    val manga: Boolean = false,
    val source: Long,
    val titleUrl: String,
    val itemUrl: String = "",
) {
    fun key(): String = sha256(communityJson.encodeToString(serializer(), this).toByteArray()).hex()
    fun valid() = titleUrl.length in 1..2048 && itemUrl.length <= 2048
}

/** Hybrid revision orders causally observed edits; device breaks truly concurrent ties. */
@Serializable
data class SyncRevision(val millis: Long, val counter: Int, val device: String) : Comparable<SyncRevision> {
    override fun compareTo(
        other: SyncRevision,
    ): Int = compareValuesBy(this, other, { it.millis }, { it.counter }, { it.device })
    fun next(now: Long, localDevice: String): SyncRevision =
        if (now > millis) SyncRevision(now, 0, localDevice) else copy(counter = counter + 1, device = localDevice)
}

@Serializable
enum class SyncField { Library, Progress, Seen, Bookmark, History }

@Serializable
data class SyncCategory(val id: String, val name: String)

@Serializable
data class SyncRecord(
    val ref: SyncReference,
    val revision: SyncRevision,
    val title: String = "",
    val item: String = "",
    val artwork: String = "",
    val favorite: Boolean = false,
    val seen: Boolean = false,
    val bookmark: Boolean = false,
    val position: Long = 0,
    val duration: Long = 0,
    val number: Double = -1.0,
    val history: Long = 0,
    val categories: List<SyncCategory> = emptyList(),
    val deleted: Boolean = false,
    val edits: Set<SyncField> = SyncField.entries.toSet(),
    val clocks: Map<SyncField, SyncRevision> = emptyMap(),
) {
    fun valid(now: Long) = ref.valid() &&
        title.length <= 240 &&
        item.length <= 240 &&
        position in 0..604_800_000 &&
        duration in 0..604_800_000 &&
        number.isFinite() &&
        categories.size <= 100 &&
        categories.all { it.name.length <= 128 && it.id.length == 32 } &&
        revision.millis in 0..(now + 300_000) &&
        revision.counter >= 0 &&
        revision.device.length == 32 &&
        clocks.values.all { it.millis in 0..(now + 300_000) && it.counter >= 0 && it.device.length == 32 }
}

/** Progress is independent of completion and bookmarks; rewatching may move it backwards. */
internal object SyncMerge {
    fun merge(previous: SyncRecord?, next: SyncRecord): SyncRecord {
        val incomingClocks = next.clocks.ifEmpty { next.edits.associateWith { next.revision } }
        if (previous == null) return next.copy(clocks = incomingClocks)
        require(previous.ref == next.ref)
        val oldClocks = previous.clocks.ifEmpty { previous.edits.associateWith { previous.revision } }
        fun newer(field: SyncField) =
            incomingClocks[field]?.let { oldClocks[field] == null || it > oldClocks.getValue(field) } == true
        val progress = newer(SyncField.Progress)
        val library = newer(SyncField.Library)
        val clocks = oldClocks.toMutableMap().apply {
            incomingClocks.forEach { (field, clock) -> if (newer(field)) put(field, clock) }
        }
        return previous.copy(
            revision = maxOf(previous.revision, next.revision),
            title = if (next.revision > previous.revision && next.title.isNotEmpty()) next.title else previous.title,
            item = if (next.revision > previous.revision && next.item.isNotEmpty()) next.item else previous.item,
            artwork = if (next.revision > previous.revision &&
                next.artwork.isNotEmpty()
            ) {
                next.artwork
            } else {
                previous.artwork
            },
            number = if (next.revision > previous.revision && next.number >= 0) next.number else previous.number,
            favorite = if (library) next.favorite else previous.favorite,
            categories = if (library) next.categories else previous.categories,
            deleted = if (library) next.deleted else previous.deleted,
            seen = if (newer(SyncField.Seen)) next.seen else previous.seen,
            bookmark = if (newer(SyncField.Bookmark)) next.bookmark else previous.bookmark,
            history = if (newer(SyncField.History)) next.history else previous.history,
            position = if (progress) next.position else previous.position,
            duration = if (progress) next.duration else previous.duration,
            edits = clocks.keys, clocks = clocks,
        )
    }
}

@Serializable
data class CommunityItem(
    val id: String,
    val author: String,
    val at: Long,
    val post: SocialPost,
    val wall: String = "",
    val reply: String = "",
    val likes: Set<String> = emptySet(),
    val pinned: Boolean = false,
)

@Serializable
data class ChatItem(
    val id: String,
    val author: String,
    val conversation: String,
    val text: String,
    val at: Long,
    val invite: String = "",
    val inviteExpires: Long = 0,
    val watchRequest: String = "",
)

data class CommunityState(
    val recent: List<SyncRecord> = emptyList(),
    val lastReceipt: Long = 0,
    val ready: Boolean = false,
    val loading: Boolean = false,
    val publishing: Boolean = false,
    val me: CommunityProfile? = null,
    val profiles: Map<String, CommunityProfile> = emptyMap(),
    val friends: List<FriendState> = emptyList(),
    val posts: List<CommunityItem> = emptyList(),
    val chats: List<ChatItem> = emptyList(),
    val groups: List<PrivateGroup> = emptyList(),
    val groupInvites: List<PrivateGroup> = emptyList(),
    val presence: Map<String, SocialPresence> = emptyMap(),
    val presenceAccess: PresenceAccess = PresenceAccess.Private,
    val pending: Int = 0,
    val replicating: Int = 0,
    val relayIssue: String? = null,
    val recovering: Int = 0,
    val connected: Int = 0,
    val error: String? = null,
    val background: Boolean = false,
    val syncEnabled: Boolean = true,
    val library: List<SyncRecord> = emptyList(),
    val unresolved: List<SyncRecord> = emptyList(),
    val muted: Set<String> = emptySet(),
    val profileDraft: CommunityProfile? = null,
    val postDraft: SocialPost? = null,
    val watchRequests: Map<String, SocialWatchRequest> = emptyMap(),
) {
    fun deliveryLabel(): String = when {
        publishing -> "Preparo le immagini…"
        !syncEnabled && pending > 0 -> "Sincronizzazione personale in pausa"
        connected == 0 -> "Offline · dati salvati sul dispositivo"
        recovering > 0 -> "Recupero · $recovering aggiornamenti"
        pending > replicating -> "${pending - replicating} aggiornamenti da inviare"
        replicating > 0 -> "Dati inviati · seconda copia in attesa"
        else -> "Connesso"
    }

    fun profile(key: String) = profiles[key] ?: CommunityProfile(key, key.take(8))
    fun isFriend(key: String) = friends.any { it.peer == key && it.accepted && !it.blocked }
}

internal fun safeImage(value: String): Boolean = value.isEmpty() ||
    runCatching {
        val uri = URI(value)
        uri.scheme == "https" &&
            uri.rawUserInfo == null &&
            uri.host != null &&
            value.length <= 2048 &&
            uri.port in listOf(-1, 443)
    }.getOrDefault(false)
