package nyanime.telegram.sync

data class SyncArchiveDescriptor(val space: String, val rootMessage: Long) {
    fun encode(): String {
        requireSyncId(space)
        require(rootMessage >= 0) // Zero is an explicitly unfinished, resumable setup.
        return "${SyncEnvelope.ARCHIVE_MARKER}\n$space\n$rootMessage"
    }

    companion object {
        fun parse(description: String): SyncArchiveDescriptor? = runCatching {
            val lines = description.lines()
            require(lines.size == 3 && lines[0] == SyncEnvelope.ARCHIVE_MARKER)
            SyncArchiveDescriptor(lines[1], lines[2].toLong()).also { it.encode() }
        }.getOrNull()
    }
}
