package eu.kanade.tachiyomi.data.backup.create

/** Called only after the replacement has been written and decoded successfully. */
object BackupRetention {
    fun <T> obsolete(filesNewestFirst: List<T>, verified: T, keep: Int = 4): List<T> {
        require(keep > 0)
        return filesNewestFirst.filter { it != verified }.drop(keep - 1)
    }

    fun shouldNotify(failures: Int): Boolean = failures == 1 || failures % 3 == 0
}
