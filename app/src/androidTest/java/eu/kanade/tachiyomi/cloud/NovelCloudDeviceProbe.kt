package eu.kanade.tachiyomi.cloud

import android.content.Context
import eu.kanade.tachiyomi.BuildConfig
import eu.kanade.tachiyomi.data.cloud.sync.CloudSyncPreferences
import eu.kanade.tachiyomi.data.cloud.sync.CloudSyncStore
import eu.kanade.tachiyomi.data.cloud.sync.NovelCloudRecords
import eu.kanade.tachiyomi.data.cloud.sync.NovelCloudSyncPort
import it.lorelore.nyanime.novel.NovelAnnotation
import it.lorelore.nyanime.novel.NovelAnnotationKind
import it.lorelore.nyanime.novel.NovelAppearance
import it.lorelore.nyanime.novel.NovelBook
import it.lorelore.nyanime.novel.NovelFormat
import it.lorelore.nyanime.novel.NovelIdentity
import it.lorelore.nyanime.novel.NovelPosition
import it.lorelore.nyanime.novel.NovelStore
import it.lorelore.nyanime.novel.NovelTheme
import nyanime.telegram.sync.SyncBatch
import nyanime.telegram.sync.SyncChange
import nyanime.telegram.sync.SyncCheckpoints
import nyanime.telegram.sync.syncJson
import tachiyomi.core.common.preference.AndroidPreferenceStore
import java.io.File
import java.util.UUID

/** Two independent SQLite installations. Never creates a Telegram client, vault or remote chat. */
internal object NovelCloudDeviceProbe {
    suspend fun run(context: Context): String {
        check(BuildConfig.ISOLATED_CLOUD_PROBE) { "Requires the isolated private build" }
        val nonce = UUID.randomUUID().toString()
        val names = listOf("novel-qa-a-$nonce.db", "novel-qa-b-$nonce.db")
        val localA = NovelStore(context, names[0])
        val localB = NovelStore(context, names[1])
        val space = UUID.randomUUID().toString()
        val accountA = Long.MAX_VALUE - 211
        val accountB = Long.MAX_VALUE - 212
        val cloudA = CloudSyncStore(context, accountA, space)
        val cloudB = CloudSyncStore(context, accountB, space)
        val prefsName = "novel-qa-$nonce"
        val shared = context.getSharedPreferences(prefsName, 0)
        val preferences = CloudSyncPreferences(AndroidPreferenceStore(context, shared))
        val deviceA = UUID.randomUUID().toString()
        val deviceB = UUID.randomUUID().toString()
        cloudA.bindDevice(deviceA)
        cloudB.bindDevice(deviceB)
        var stamp = 10_000L
        val portA =
            NovelCloudSyncPort(context, cloudA, preferences, { ++stamp }, { localA }, { true })
        val portB =
            NovelCloudSyncPort(context, cloudB, preferences, { ++stamp }, { localB }, { true })
        var message = 1L
        val passed = mutableListOf<String>()
        suspend fun transfer(from: CloudSyncStore, to: CloudSyncStore, port: NovelCloudSyncPort) {
            val changes = mutableListOf<SyncChange>()
            from.snapshot { changes += it }
            for ((device, values) in changes.groupBy { it.dot.device }) {
                values.chunked(100).forEach { chunk ->
                    val batch = SyncBatch(
                        id = UUID.randomUUID().toString(),
                        space = space,
                        device = device,
                        changes = chunk,
                    )
                    to.receive(
                        batch,
                        SyncCheckpoints.hash(syncJson.encodeToString(batch)),
                        message++,
                    )
                }
            }
            repeat(8) {
                val pending = to.projections()
                if (pending.isEmpty()) return
                var applied = 0
                for (projection in pending) if (port.apply(projection)) applied++
                if (applied == 0) return@repeat
            }
            check(to.projectionCount() == 0L) { "Projection did not converge" }
        }
        try {
            portA.configure(true, false, emptySet())
            portB.configure(true, false, emptySet())
            val fingerprint = "a".repeat(64)
            val book =
                NovelBook(
                    NovelIdentity.local(fingerprint),
                    "Fictional novel",
                    format = NovelFormat.EPUB,
                    contentVersion = fingerprint,
                    importedAt = 1,
                    modifiedAt = 1,
                )
            localA.put(book)
            val point =
                NovelPosition(
                    book.id,
                    fingerprint,
                    """{"href":"chapter.xhtml","locations":{"progression":0.25}}""",
                    2,
                    0.25,
                )
            localA.savePosition(point)
            val note =
                NovelAnnotation(
                    bookId = book.id,
                    contentVersion = fingerprint,
                    locatorJson = point.locatorJson,
                    kind = NovelAnnotationKind.NOTE,
                    quote = "Fictional passage",
                    note = "Private fixture",
                    createdAt = 3,
                    modifiedAt = 3,
                )
            localA.putAnnotation(note)
            val appearance = NovelAppearance(theme = NovelTheme.PAPER)
            localA.saveAppearance(book.id, appearance)
            localA.saveAppearance(null, appearance)
            check(portA.capture() == 5)
            transfer(cloudA, cloudB, portB)
            check(localB.get(book.id) == book)
            check(localB.position(book.id) == point)
            check(localB.annotations(book.id).single() == note)
            check(localB.appearance(book.id) == appearance)
            check(localB.appearance(null) == appearance)
            check(portB.capture() == 0 && cloudB.pendingCount() == 0L)
            passed += "book-position-note-appearance-roundtrip-no-echo"

            localB.putAnnotation(note.copy(deleted = true, modifiedAt = 4))
            localB.saveAppearance(book.id, null)
            check(portB.capture() == 2)
            transfer(cloudB, cloudA, portA)
            check(localA.annotations(book.id).isEmpty())
            check(localA.annotations(book.id, true).single().deleted)
            check(localA.appearance(book.id) == null)
            check(portA.capture() == 0)
            passed += "deletion-and-inheritance-tombstones"

            repeat(100) { n ->
                localA.savePosition(point.copy(updatedAt = 100L + n, totalProgression = n / 100.0))
            }
            check(portA.pendingCount() == 1L)
            check(portA.capture() == 1)
            transfer(cloudA, cloudB, portB)
            check(localB.position(book.id)?.totalProgression == 0.99)
            check(portB.capture() == 0)
            passed += "latest-per-entity-journal"

            portA.configure(true, true, emptySet())
            localA.setFavorite(book.id, false)
            check(portA.capture() == 0 && portA.pendingCount() == 0L)
            portA.configure(true, false, emptySet())
            check(portA.capture() == 1)
            passed += "incognito-suspends-without-losing-pending"

            preferences.sharedReader().set(false)
            localA.saveAppearance(null, NovelAppearance(theme = NovelTheme.DARK))
            check(portA.capture() == 0)
            preferences.sharedReader().set(true)
            // Match the production privacy-policy change; it makes previously withheld rows
            // eligible.
            cloudA.state("exclusions", "reader-enabled")
            check(portA.capture() == 1)
            passed += "reader-preference-policy"

            val old = requireNotNull(localB.position(book.id))
            localB.savePosition(old.copy(updatedAt = 900, totalProgression = 0.55))
            localA.savePosition(point.copy(updatedAt = 901, totalProgression = 0.75))
            portA.capture()
            transfer(cloudA, cloudB, portB)
            val reference =
                NovelCloudRecords.ref(requireNotNull(localB.record("position", book.id)), book)
            check(
                requireNotNull(cloudB.record(reference))
                    .fields
                    .getValue("document")
                    .versions
                    .size == 2,
            )
            check(localB.position(book.id) != null)
            passed += "offline-edits-retain-concurrent-revisions"

            val portable = localB.archive().encode()
            check(
                "originalFile" !in portable &&
                    "publicationFile" !in portable &&
                    "coverFile" !in portable,
            )
            check("cloud-sync-" !in portable)
            passed += "no-local-paths-or-transport-secrets"
            return "PASS " + passed.size + ": " + passed.joinToString()
        } finally {
            localA.close()
            localB.close()
            cloudA.close()
            cloudB.close()
            names.forEach(context::deleteDatabase)
            listOf(accountA, accountB).forEach { account ->
                val file = File(context.noBackupFilesDir, "cloud-sync-$account-$space.sqlite")
                listOf("", "-wal", "-shm", "-journal").forEach { suffix ->
                    val owned = File(file.path + suffix)
                    check(!owned.exists() || owned.delete()) {
                        "Cannot remove isolated Cloud fixture"
                    }
                }
            }
            check(context.deleteSharedPreferences(prefsName))
        }
    }
}
