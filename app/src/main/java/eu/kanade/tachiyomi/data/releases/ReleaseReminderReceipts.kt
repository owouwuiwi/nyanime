package eu.kanade.tachiyomi.data.releases

import tachiyomi.data.handlers.anime.AnimeDatabaseHandler
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get

/** Delivery acknowledgements are local to this installation, independent of schedule providers. */
class ReleaseReminderReceipts(private val db: AnimeDatabaseHandler = Injekt.get()) {
    suspend fun delivered(): Set<Pair<String, String>> = db.awaitList {
        reminderReceiptQueries.getReceipts { key, kind -> key to kind }
    }.toSet()

    suspend fun record(reminder: ReleaseReminder, now: Long) = db.await(inTransaction = true) {
        reminder.keys.forEach {
            reminderReceiptQueries.recordReceipt(it, reminder.kind.name, now)
            reminderReceiptQueries.recordReceipt(it, reminder.timeReceipt, now)
        }
        reminderReceiptQueries.pruneReceipts(now - 365 * ReleasePolicy.DAY)
    }
}
