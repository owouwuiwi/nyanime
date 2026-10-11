package eu.kanade.tachiyomi.cloud

import android.content.Context
import android.content.ContextWrapper
import android.database.DatabaseErrorHandler
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import eu.kanade.tachiyomi.BuildConfig
import it.lorelore.nyanime.tracking.PendingTrackingEdit
import it.lorelore.nyanime.tracking.PersonalTrackingStore
import it.lorelore.nyanime.tracking.TrackingConfirmation
import it.lorelore.nyanime.tracking.TrackingDelivery
import it.lorelore.nyanime.tracking.TrackingEditWorker
import it.lorelore.nyanime.tracking.TrackingField
import it.lorelore.nyanime.tracking.TrackingLegacyMigration
import it.lorelore.nyanime.tracking.TrackingMedium
import it.lorelore.nyanime.tracking.TrackingPatch
import it.lorelore.nyanime.tracking.TrackingQueueException
import it.lorelore.nyanime.tracking.TrackingQueueReconciliation
import it.lorelore.nyanime.tracking.TrackingSubject
import it.lorelore.nyanime.tracking.TrackingValues
import it.lorelore.nyanime.tracking.TrackingWorkerProbeRuntime
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.io.File
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger

/** Actual Android SQLite and SharedPreferences, isolated from every real tracker and app database. */
object TrackingQueueDeviceProbe {
    var stage: String = "setup"
        private set
    fun run(app: Context): Map<String, Any> {
        check(BuildConfig.ISOLATED_CLOUD_PROBE)
        TrackingWorkerProbeRuntime.cleanInterruptedFixtures(app)
        val nonce = UUID.randomUUID().toString()
        val root = File(app.cacheDir, "tracking-queue-probe-$nonce")
        check(root.canonicalFile.parentFile == app.cacheDir.canonicalFile)
        check(root.mkdirs())
        val context = object : ContextWrapper(app) {
            override fun getApplicationContext(): Context = this
            override fun getDatabasePath(name: String): File = File(root, name)
            override fun getSharedPreferences(name: String, mode: Int) = app.getSharedPreferences("$nonce-$name", mode)
            override fun openOrCreateDatabase(name: String, mode: Int, factory: SQLiteDatabase.CursorFactory?) =
                SQLiteDatabase.openDatabase(
                    getDatabasePath(name).absolutePath,
                    factory,
                    SQLiteDatabase.CREATE_IF_NECESSARY,
                )
            override fun openOrCreateDatabase(
                name: String,
                mode: Int,
                factory: SQLiteDatabase.CursorFactory?,
                handler: DatabaseErrorHandler?,
            ) =
                SQLiteDatabase.openDatabase(
                    getDatabasePath(name).absolutePath,
                    factory,
                    SQLiteDatabase.CREATE_IF_NECESSARY,
                    handler,
                )
        }
        var store = PersonalTrackingStore(context)
        val began = android.os.SystemClock.elapsedRealtime()
        val subject = TrackingSubject(TrackingMedium.MANGA, 5001, "/fixture/work")
        val baseline = TrackingValues(progress = 1.5, score = 7.0)
        fun edit(
            value: Double,
            revision: Long,
            field: TrackingField = TrackingField.PROGRESS,
        ): PendingTrackingEdit = PendingTrackingEdit(
            subject, 21, 31, 9999, 77, "fixture-account",
            TrackingPatch(
                if (field ==
                    TrackingField.SCORE
                ) {
                    baseline.copy(score = value)
                } else {
                    baseline.copy(progress = value)
                },
                setOf(field),
            ),
            revision, accountEpoch = "fixture-session", base = baseline,
        )
        try {
            stage = "probe-network-guard"
            val guarded = it.lorelore.nyanime.tracking.TrackingRequestGuard.client(okhttp3.OkHttpClient(), 9999)
            val blockedRequest = runCatching {
                guarded.newCall(okhttp3.Request.Builder().url("http://127.0.0.1:9/fixture").build()).execute().close()
            }.exceptionOrNull()
            check(blockedRequest is java.net.ProtocolException && blockedRequest.message == "Isolated tracking probe")

            stage = "unsent-undo"
            val first = store.enqueue(edit(2.5, 1000), baseline)
            check(store.undo(first.receiptId, 1001))
            check(store.pending().isEmpty())

            stage = "inflight-undo"
            val inflight = store.enqueue(edit(2.5, 2000), baseline)
            val claimed = store.claim(inflight, 2001)!!
            val newer = store.enqueue(edit(3.5, 2002), baseline.copy(progress = 2.5))
            store.complete(claimed, TrackingConfirmation(baseline, baseline.copy(progress = 2.5)))
            check(store.pending(newer.key)!!.patch.values.progress == 3.5)
            val next = store.claim(store.pending(newer.key)!!, 2003)!!
            store.complete(next, TrackingConfirmation(baseline.copy(progress = 2.5), baseline.copy(progress = 3.5)))
            check(store.pending().isEmpty())
            check(store.undo(newer.receiptId, 2004))
            val inverse = store.pending(newer.key)!!
            check(inverse.patch.values.progress == 2.5 && inverse.expectedRemote!!.progress == 3.5)
            check(TrackingQueueReconciliation.desired(inverse, baseline.copy(progress = 3.5, score = 9.0)).score == 9.0)
            check(
                runCatching {
                    TrackingQueueReconciliation.desired(inverse, baseline.copy(progress = 9.5))
                }.exceptionOrNull() is TrackingQueueException,
            )
            check(store.discard(inverse))

            stage = "revision-ownership"
            val scoreA = store.enqueue(edit(8.0, 3000, TrackingField.SCORE), baseline)
            store.enqueue(edit(9.0, 3001, TrackingField.SCORE), baseline.copy(score = 8.0))
            store.enqueue(edit(8.0, 3002, TrackingField.SCORE), baseline.copy(score = 9.0))
            check(!store.undo(scoreA.receiptId, 3003))
            check(store.pending(scoreA.key)!!.patch.values.score == 8.0)
            check(store.discard(store.pending(scoreA.key)!!))

            stage = "interrupted-recovery"
            val uncertain = store.enqueue(edit(10.5, 4000), baseline)
            store.claim(uncertain, 4001)!!
            close(store)
            store = PersonalTrackingStore(context)
            store.recoverInterrupted()
            check(store.pending(uncertain.key)!!.delivery == TrackingDelivery.UNCERTAIN)
            check(store.flight(uncertain.key)!!.patch == uncertain.patch)
            val resumed = store.claim(store.pending(uncertain.key)!!, 4002)!!
            check(resumed.attempts == 2)
            store.complete(
                resumed,
                TrackingConfirmation(baseline.copy(progress = 10.5), baseline.copy(progress = 10.5)),
            )
            check(store.pending().isEmpty())
            check(!store.receipt(uncertain.receiptId)!!.noEffect)

            stage = "repeated-interruption-limit"
            val interrupted = store.enqueue(edit(11.5, 4100), baseline)
            repeat(it.lorelore.nyanime.tracking.TrackingQueuePolicy.MAX_ATTEMPTS) { index ->
                check(store.claim(store.pending(interrupted.key)!!, 4101L + index) != null)
                store.recoverInterrupted()
            }
            val held = store.pending(interrupted.key)!!
            check(held.delivery == TrackingDelivery.BLOCKED)
            check(held.problem == it.lorelore.nyanime.tracking.TrackingQueueProblem.EXHAUSTED)
            check(held.patch.values.progress == 11.5)
            check(store.claim(held, Long.MAX_VALUE) == null)
            check(store.discard(held))

            stage = "manual-retry-retains-uncertain-automatic-undo"
            val lostReply = store.enqueue(
                edit(12.5, 4200).copy(
                    origin = it.lorelore.nyanime.tracking.TrackingEditOrigin.AUTOMATIC,
                    automaticProgress = true,
                ),
                baseline,
            )
            val lostFlight = store.claim(lostReply, 4201)!!
            store.noteAttemptBase(lostFlight, baseline)
            store.failed(lostFlight, it.lorelore.nyanime.tracking.TrackingQueueProblem.NETWORK, 4202)
            check(store.retry(lostReply.key, lostReply.revision))
            val retried = store.claim(store.pending(lostReply.key)!!, 4203)!!
            check(retried.attempts == 1 && retried.attemptBase == baseline)
            val alreadyApplied = baseline.copy(progress = 12.5)
            store.complete(retried, TrackingConfirmation(alreadyApplied, alreadyApplied))
            check(!store.receipt(lostReply.receiptId)!!.noEffect)
            check(store.undo(lostReply.receiptId, 4204))
            val lostInverse = store.pending(lostReply.key)!!
            check(lostInverse.patch.values.progress == baseline.progress)
            check(store.discard(lostInverse))

            stage = "already-current-before-first-request-stays-no-effect-after-retry"
            val externalValue = baseline.copy(progress = 13.5)
            val currentElsewhere = store.enqueue(edit(13.5, 4300), baseline)
            val currentFlight = store.claim(currentElsewhere, 4301)!!
            store.noteAttemptBase(currentFlight, externalValue)
            store.failed(currentFlight, it.lorelore.nyanime.tracking.TrackingQueueProblem.NETWORK, 4302)
            val waitingCurrent = store.pending(currentElsewhere.key)!!
            val automaticRetry = store.claim(waitingCurrent, waitingCurrent.notBefore)!!
            check(automaticRetry.attempts == 2)
            store.complete(automaticRetry, TrackingConfirmation(externalValue, externalValue))
            check(store.receipt(currentElsewhere.receiptId)!!.noEffect)
            check(!store.undo(currentElsewhere.receiptId, 4400))

            // The trigger fails after metadata and owner writes, before the queued row is accepted.
            stage = "transaction-rollback"
            val db = SQLiteDatabase.openDatabase(
                context.getDatabasePath("personal-tracking.sqlite").absolutePath,
                null,
                0,
            )
            db.execSQL(
                "CREATE TRIGGER fixture_reject_receipt BEFORE INSERT ON receipts BEGIN SELECT RAISE(ABORT, 'fixture'); END",
            )
            val receiptsBefore = store.recent()
            check(runCatching { store.enqueue(edit(20.5, 5000), baseline) }.isFailure)
            check(store.pending().isEmpty())
            check(store.recent() == receiptsBefore)
            db.execSQL("DROP TRIGGER fixture_reject_receipt")
            db.close()

            stage = "automatic-effects-ownership"
            val scoreEdit = store.enqueue(edit(8.0, 6000, TrackingField.SCORE), baseline)
            val automatic = store.enqueue(
                edit(9.5, 6001).copy(
                    origin = it.lorelore.nyanime.tracking.TrackingEditOrigin.AUTOMATIC,
                    automaticProgress = true,
                ),
                baseline.copy(score = 8.0),
            )
            val automaticFlight = store.claim(automatic, 6002)!!
            val remoteBefore = baseline.copy(progress = 8.5)
            val remoteAfter = remoteBefore.copy(progress = 9.5, score = 8.0, status = 2)
            store.complete(automaticFlight, TrackingConfirmation(remoteBefore, remoteAfter))
            check(store.owners(automatic)[TrackingField.SCORE] == scoreEdit.revision)
            check(store.receipt(automatic.receiptId)!!.before.progress == 8.5)
            check(store.undo(automatic.receiptId, 6003))
            val automaticInverse = store.pending(automatic.key)!!
            check(TrackingField.SCORE !in automaticInverse.patch.fields)
            check(automaticInverse.patch.values.progress == 8.5)
            check(store.discard(automaticInverse))

            stage = "automatic-first-score-last-ownership"
            val autoFirst = store.enqueue(
                edit(6.5, 6050).copy(
                    origin = it.lorelore.nyanime.tracking.TrackingEditOrigin.AUTOMATIC,
                    automaticProgress = true,
                ),
                baseline,
            )
            val scoreLast = store.enqueue(edit(8.0, 6051, TrackingField.SCORE), baseline.copy(progress = 6.5))
            val scoreLastFlight = store.claim(scoreLast, 6052)!!
            val withEffects = baseline.copy(progress = 6.5, score = 8.0, status = 2, startDate = 1000)
            store.complete(scoreLastFlight, TrackingConfirmation(baseline, withEffects))
            check(store.owners(autoFirst)[TrackingField.STATUS] == autoFirst.revision)
            check(store.owners(autoFirst)[TrackingField.START] == autoFirst.revision)
            check(store.receipt(scoreLast.receiptId)!!.edit.patch.fields == setOf(TrackingField.SCORE))
            check(store.undo(scoreLast.receiptId, 6053))
            val scoreOnlyInverse = store.pending(scoreLast.key)!!
            check(scoreOnlyInverse.patch.fields == setOf(TrackingField.SCORE))
            check(store.discard(scoreOnlyInverse))
            check(store.undo(autoFirst.receiptId, 6054))
            val autoFieldsInverse = store.pending(autoFirst.key)!!
            check(TrackingField.STATUS in autoFieldsInverse.patch.fields)
            check(TrackingField.PROGRESS in autoFieldsInverse.patch.fields)
            check(TrackingField.SCORE !in autoFieldsInverse.patch.fields)
            check(store.discard(autoFieldsInverse))

            stage = "per-receipt-no-effect"
            val mixedScore = store.enqueue(edit(8.0, 6100, TrackingField.SCORE), baseline)
            val currentProgress = store.enqueue(
                edit(9.5, 6101).copy(
                    origin = it.lorelore.nyanime.tracking.TrackingEditOrigin.AUTOMATIC,
                    automaticProgress = true,
                ),
                baseline.copy(score = 8.0),
            )
            val mixedFlight = store.claim(currentProgress, 6102)!!
            store.complete(
                mixedFlight,
                TrackingConfirmation(
                    baseline.copy(progress = 9.5),
                    baseline.copy(progress = 9.5, score = 8.0),
                ),
            )
            check(store.receipt(currentProgress.receiptId)!!.noEffect)
            check(!store.undo(currentProgress.receiptId, 6103))
            check(!store.receipt(mixedScore.receiptId)!!.noEffect)

            stage = "sqlite-v1-upgrade"
            val oldEdit = edit(24.5, 6200).copy(accountEpoch = "", base = null)
            val v1 = SQLiteDatabase.openOrCreateDatabase(context.getDatabasePath("v1.sqlite"), null)
            v1.execSQL("CREATE TABLE personal (key TEXT NOT NULL PRIMARY KEY, data TEXT NOT NULL)")
            v1.execSQL("CREATE TABLE pending (key TEXT NOT NULL PRIMARY KEY, data TEXT NOT NULL)")
            v1.execSQL(
                "INSERT INTO pending (key, data) VALUES (?, ?)",
                arrayOf(
                    oldEdit.key,
                    kotlinx.serialization.json.Json.encodeToString(PendingTrackingEdit.serializer(), oldEdit),
                ),
            )
            v1.version = 1
            v1.close()
            val upgraded = PersonalTrackingStore(context, "v1.sqlite")
            try {
                check(upgraded.pending().single() == oldEdit)
                check(upgraded.legacy().isEmpty())
                check(upgraded.recent().isEmpty())
            } finally {
                close(upgraded)
            }

            stage = "service-delay-and-other-tracker"
            val now = java.util.concurrent.atomic.AtomicLong(System.currentTimeMillis())
            val limited = store.enqueue(edit(11.5, now.get()), baseline)
            val other = store.enqueue(edit(12.5, now.incrementAndGet()).copy(trackId = 32, trackerId = 10000), baseline)
            val gateway = it.lorelore.nyanime.tracking.TrackingRemoteGateway(app, store, now::get)
            var limitedCalls = 0
            var otherCalls = 0
            runBlocking {
                gateway.drain { requested ->
                    if (requested.trackerId == limited.trackerId) {
                        limitedCalls++
                        throw it.lorelore.nyanime.tracking.TrackingQueueHttpFailure(429, 120_000)
                    }
                    otherCalls++
                    TrackingConfirmation(baseline, TrackingQueueReconciliation.desired(requested, baseline))
                }
                check(store.pending(other.key) == null && otherCalls == 1 && limitedCalls == 1)
                check(store.pending(limited.key)!!.notBefore > now.get())
                gateway.drain { error("Cooldown must suppress this request") }
                now.addAndGet(120_001)
                gateway.drain { requested ->
                    limitedCalls++
                    TrackingConfirmation(baseline, TrackingQueueReconciliation.desired(requested, baseline))
                }
            }
            check(limitedCalls == 2 && store.pending().isEmpty())

            stage = "blocked-history-cannot-starve-new-edit"
            val blocked = (0 until 50).map { index ->
                store.enqueue(
                    edit(15.5, now.incrementAndGet()).copy(
                        trackId = 50_000L + index,
                        delivery = TrackingDelivery.BLOCKED,
                        problem = it.lorelore.nyanime.tracking.TrackingQueueProblem.AUTHENTICATION,
                    ),
                    baseline,
                )
            }
            val fresh = store.enqueue(edit(16.5, now.incrementAndGet()), baseline)
            var freshCalls = 0
            runBlocking {
                gateway.drain { requested ->
                    check(requested.key == fresh.key)
                    freshCalls++
                    TrackingConfirmation(baseline, TrackingQueueReconciliation.desired(requested, baseline))
                }
            }
            check(freshCalls == 1 && store.pending(fresh.key) == null)
            check(store.pending().size == blocked.size)
            blocked.forEach { check(store.discard(it)) }

            stage = "legacy-migration"
            val preferences = context.getSharedPreferences("tracking_queue", Context.MODE_PRIVATE)
            check(preferences.edit().putFloat("606", 7.5f).commit())
            TrackingLegacyMigration.migrate(context, store, allowIsolated = true)
            check(store.legacy().single().progress == 7.5)
            check(store.pending().isEmpty())
            val legacy = store.legacy().single()
            store.discardLegacy(legacy.id)
            // Simulate the old preference surviving a kill after the atomic import.
            check(preferences.edit().putFloat("606", 7.5f).commit())
            TrackingLegacyMigration.migrate(context, store, allowIsolated = true)
            check(store.legacy().isEmpty())
            check(store.pending().isEmpty())

            stage = "uncertain-before-write-undo"
            val prior = store.enqueue(edit(10.5, 7000), baseline)
            val later = store.enqueue(edit(12.5, 7001), baseline.copy(progress = 10.5))
            val pendingLater = store.claim(later, 7002)!!
            store.noteAttemptBase(pendingLater, baseline.copy(score = 9.0))
            store.failed(pendingLater, it.lorelore.nyanime.tracking.TrackingQueueProblem.NETWORK, 7003)
            check(store.undo(later.receiptId, 7004))
            val correction = store.pending(prior.key)!!
            val corrected = TrackingQueueReconciliation.desired(correction, baseline.copy(score = 9.0))
            check(corrected.progress == 10.5 && corrected.score == 9.0)
            check(store.discard(correction))

            stage = "real-work-manager"
            val clock = java.util.concurrent.atomic.AtomicLong(
                maxOf(
                    System.currentTimeMillis(),
                    store.cooldown(9999) + 1,
                ),
            )
            val entered = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            val calls = AtomicInteger()
            val concurrency = AtomicInteger()
            val maximum = AtomicInteger()
            var remote = baseline
            val runtime = TrackingWorkerProbeRuntime(nonce, store, clock::get) { queued ->
                val active = concurrency.incrementAndGet()
                maximum.accumulateAndGet(active) { one, two -> maxOf(one, two) }
                try {
                    store.noteAttemptBase(queued, remote)
                    if (calls.incrementAndGet() == 1) {
                        entered.complete(Unit)
                        release.await()
                    }
                    val beforeRemote = remote
                    remote = TrackingQueueReconciliation.desired(queued, remote)
                    TrackingConfirmation(beforeRemote, remote)
                } finally {
                    concurrency.decrementAndGet()
                }
            }
            TrackingWorkerProbeRuntime.current = runtime
            store.enqueue(edit(4.5, clock.get()), baseline)
            TrackingEditWorker.schedule(app, immediately = true)
            runBlocking { withTimeout(30_000) { entered.await() } }
            store.enqueue(edit(9.5, clock.incrementAndGet()), baseline.copy(progress = 4.5))
            repeat(20) { TrackingEditWorker.schedule(app, immediately = true) }
            check(TrackingWorkerProbeRuntime.unfinished(app, nonce) <= 2)
            release.complete(Unit)
            runBlocking {
                withTimeout(30_000) {
                    while (store.pending().isNotEmpty() || runtime.active.get() != 0) delay(100)
                }
            }
            check(remote.progress == 9.5 && calls.get() == 2 && maximum.get() == 1)
            TrackingWorkerProbeRuntime.current = null
            TrackingWorkerProbeRuntime.cancel(app, nonce)

            return mapOf(
                "unsentUndoRemoved" to true, "inflightUndoPreservedNewerWrite" to true,
                "sameValueAbaProtected" to true, "durableUncertainRecovery" to true,
                "sqliteRollback" to true, "legacyReviewWithoutReplay" to true,
                "workManagerSerializedSends" to true, "boundedRapidTapJobs" to true,
                "uncertainBeforeWriteUndo" to true, "realTrackerRequests" to 0, "implicitEffectsOwnership" to true,
                "perReceiptNoEffect" to true, "v1UpgradePreserved" to true, "serviceCooldownIsolation" to true,
                "elapsedMillis" to android.os.SystemClock.elapsedRealtime() - began,
            )
        } finally {
            val activeRuntime = TrackingWorkerProbeRuntime.current
            TrackingWorkerProbeRuntime.current = null
            TrackingWorkerProbeRuntime.cancel(app, nonce)
            runBlocking { withTimeout(15_000) { while ((activeRuntime?.active?.get() ?: 0) > 0) delay(100) } }
            close(store)
            context.getSharedPreferences("tracking_queue", Context.MODE_PRIVATE).edit().clear().commit()
            check(
                root.canonicalFile.parentFile == app.cacheDir.canonicalFile &&
                    root.name == "tracking-queue-probe-$nonce",
            )
            app.deleteSharedPreferences("$nonce-tracking_queue")
            check(root.deleteRecursively())
        }
    }

    private fun close(store: PersonalTrackingStore) {
        val field = PersonalTrackingStore::class.java.getDeclaredField("database")
        field.isAccessible = true
        (field.get(store) as SQLiteOpenHelper).close()
    }
}
