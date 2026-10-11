package eu.kanade.tachiyomi.data.reading

import kotlinx.serialization.Serializable

/** Local navigation checkpoint, scoped to a room without persisting its invitation secret. */
@Serializable
internal data class ReadingReturnCheckpoint(val room: String, val bookmark: ReadingBookmark) {
    fun valid(): Boolean = room.matches(Regex("[0-9a-f]{64}")) && bookmark.valid()
}

/** Recovery stays dormant until the user explicitly rejoins the room that owns the checkpoint. */
internal class ReadingReturnPoint(saved: ReadingReturnCheckpoint? = null) {
    private var room: String? = null
    var checkpoint: ReadingReturnCheckpoint? = saved?.takeIf { it.valid() }
        private set

    val bookmark: ReadingBookmark?
        get() = checkpoint?.takeIf { it.room == room }?.bookmark

    fun roomChanged(room: String?) {
        this.room = room?.takeIf { it.matches(Regex("[0-9a-f]{64}")) }
        if (this.room != null && checkpoint?.room != this.room) clear()
    }

    fun remember(bookmark: ReadingBookmark?) {
        val room = room ?: return
        if (this.bookmark == null && bookmark?.valid() == true) {
            checkpoint = ReadingReturnCheckpoint(room, bookmark)
        }
    }

    fun clear() {
        checkpoint = null
    }
}
