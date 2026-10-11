package nyanime.telegram.sync

data class SyncArchiveDescriptor(val space: String, val rootMessage: Long, val maintenanceVersion: Int = 0) {
    fun encode(): String {
        requireSyncId(space)
        require(rootMessage >= 0) // Zero is an explicitly unfinished, resumable setup.
        require(maintenanceVersion in 0..1)
        require(maintenanceVersion == 0 || rootMessage > 0)
        return "${SyncEnvelope.ARCHIVE_MARKER}\n$space\n$rootMessage" +
            if (maintenanceVersion > 0) "\nmaintenance=$maintenanceVersion" else ""
    }

    companion object {
        fun parse(description: String): SyncArchiveDescriptor? = runCatching {
            val lines = description.lines()
            require(lines.size in 3..4 && lines[0] == SyncEnvelope.ARCHIVE_MARKER)
            val maintenance = if (lines.size == 4) {
                require(lines[3] == "maintenance=1")
                1
            } else {
                0
            }
            SyncArchiveDescriptor(lines[1], lines[2].toLong(), maintenance).also { it.encode() }
        }.getOrNull()
    }
}
