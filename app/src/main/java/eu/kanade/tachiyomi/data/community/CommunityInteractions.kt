package eu.kanade.tachiyomi.data.community

/** Public-screen actions, independent of identity storage and network lifecycle. */
interface CommunityInteractions {
    fun requestFriend(peer: String)
    fun react(item: CommunityItem)
    fun moderate(item: CommunityItem, pin: Boolean = false)
    fun removeFriend(peer: String, block: Boolean = false)
}
