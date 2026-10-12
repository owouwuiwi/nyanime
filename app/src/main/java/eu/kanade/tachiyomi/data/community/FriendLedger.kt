package eu.kanade.tachiyomi.data.community

import kotlinx.serialization.Serializable

/** Each author owns its own revision. A remote clock cannot overwrite our cancellation or block. */
@Serializable
internal data class FriendLedger(
    val own: PrivateAction? = null,
    val peer: PrivateAction? = null,
    val ownRequests: Set<String> = emptySet(),
    val peerRequests: Set<String> = emptySet(),
    val dismissedPeerRequests: Set<String> = emptySet(),
) {
    fun apply(action: PrivateAction, self: Boolean): FriendLedger {
        if (action.type !in listOf("friend.request", "friend.accept", "friend.remove") ||
            action.revision <= 0
        ) {
            return this
        }
        if (action.type != "friend.remove" && action.request.length !in 1..80) return this
        val previous = if (self) own else peer
        val latest =
            previous == null ||
                action.revision > previous.revision ||
                action.revision == previous.revision &&
                action.request > previous.request
        val request = if (action.type == "friend.request") setOf(action.request) else emptySet()
        return if (self) {
            copy(
                own = if (latest) action else own,
                ownRequests = (ownRequests + request).takeLastSet(128),
                dismissedPeerRequests = if (action.type == "friend.remove") {
                    (
                        dismissedPeerRequests +
                            action.request +
                            listOfNotNull(peer?.takeIf { it.type == "friend.request" }?.request)
                        ).takeLastSet(128)
                } else {
                    dismissedPeerRequests
                },
            )
        } else {
            copy(peer = if (latest) action else peer, peerRequests = (peerRequests + request).takeLastSet(128))
        }
    }
    fun state(key: String, blocked: Boolean): FriendState {
        val removed = own?.type == "friend.remove" || peer?.type == "friend.remove"
        val ownAccepted = own?.type == "friend.accept" &&
            own.request in peerRequests &&
            (peer?.type == "friend.accept" || peer?.request == own.request)
        val peerAccepted = peer?.type == "friend.accept" &&
            peer.request in ownRequests &&
            (own?.type == "friend.accept" || own?.request == peer.request)
        val accepted = !blocked && !removed && (ownAccepted || peerAccepted)
        return FriendState(
            key,
            outgoing = own?.takeIf { it.type == "friend.request" && !accepted }?.request.orEmpty(),
            incoming = peer?.takeIf {
                it.type == "friend.request" &&
                    !accepted &&
                    !blocked &&
                    it.request !in dismissedPeerRequests
            }?.request.orEmpty(),
            accepted = accepted,
            blocked = blocked,
        )
    }
    private fun Set<String>.takeLastSet(size: Int) = sorted().takeLast(size).toSet()
}
