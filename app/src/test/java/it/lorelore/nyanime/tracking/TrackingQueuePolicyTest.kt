package it.lorelore.nyanime.tracking

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class TrackingQueuePolicyTest {
    private val before = TrackingValues(progress = 8.5, score = 7.0, notes = "Private local fixture")

    @Test fun `automatic progress cannot cross a url based association or a new account session`() {
        val incoming = edit().copy(
            origin = TrackingEditOrigin.AUTOMATIC,
            automaticProgress = true,
            remoteId = 0,
            remoteUrl = "/fictional/remote-one",
        )
        assertTrue(TrackingQueuePolicy.acceptsAutomatic(null, incoming))
        assertTrue(TrackingQueuePolicy.acceptsAutomatic(incoming, incoming))
        assertFalse(TrackingQueuePolicy.acceptsAutomatic(incoming.copy(remoteUrl = "/fictional/remote-two"), incoming))
        assertFalse(TrackingQueuePolicy.acceptsAutomatic(incoming.copy(accountEpoch = "session-two"), incoming))
    }

    @Test fun `manual progress and status retain precedence while an independent score can coalesce`() {
        val incoming = edit().copy(origin = TrackingEditOrigin.AUTOMATIC, automaticProgress = true)
        assertFalse(TrackingQueuePolicy.acceptsAutomatic(edit(), incoming))
        val score = edit(TrackingPatch(before.copy(score = 9.0), setOf(TrackingField.SCORE)))
        assertTrue(TrackingQueuePolicy.acceptsAutomatic(score, incoming))
        val withStatus = incoming.copy(
            patch = incoming.patch.copy(fields = setOf(TrackingField.PROGRESS, TrackingField.STATUS)),
        )
        assertFalse(TrackingQueuePolicy.acceptsAutomatic(withStatus, incoming))
    }

    @Test fun `provider transitions respect manual status and date edits in a coalesced request`() {
        val automatic = edit().copy(origin = TrackingEditOrigin.AUTOMATIC, automaticProgress = true)
        assertTrue(TrackingQueuePolicy.automaticTransitions(automatic))
        val score = automatic.copy(
            patch = automatic.patch.copy(fields = setOf(TrackingField.PROGRESS, TrackingField.SCORE)),
        )
        assertTrue(TrackingQueuePolicy.automaticTransitions(score))
        for (field in listOf(TrackingField.STATUS, TrackingField.START, TrackingField.FINISH)) {
            assertFalse(
                TrackingQueuePolicy.automaticTransitions(
                    automatic.copy(
                        patch = automatic.patch.copy(fields = setOf(TrackingField.PROGRESS, field)),
                    ),
                ),
            )
        }
    }

    @Test fun `verified normalization of our own flight does not reject a newer manual choice`() {
        val flight = edit(TrackingPatch(before.copy(progress = 12.5), setOf(TrackingField.PROGRESS)))
        val newer = TrackingQueuePolicy.prepare(
            edit(TrackingPatch(before.copy(progress = 13.0), setOf(TrackingField.PROGRESS))),
            before,
            flight,
        )
        val normalized = before.copy(progress = 12.0)
        val rebased = newer.rebaseAfterConfirmation(flight, TrackingConfirmation(before, normalized))
        assertEquals(13.0, TrackingQueueReconciliation.desired(rebased, normalized).progress)
        assertThrows(TrackingQueueException::class.java) {
            TrackingQueueReconciliation.desired(rebased, normalized.copy(progress = 15.0))
        }
    }

    @Test fun `a manual status accepts our verified automatic transition but keeps unrelated remote conflicts`() {
        val flight = edit(TrackingPatch(before.copy(progress = 9.5), setOf(TrackingField.PROGRESS))).copy(
            origin = TrackingEditOrigin.AUTOMATIC,
            automaticProgress = true,
        )
        val request = TrackingQueuePolicy.prepare(
            edit(TrackingPatch(before.copy(status = 3, score = 8.0), setOf(TrackingField.STATUS, TrackingField.SCORE))),
            before,
            flight,
        )
        val automaticAfter = before.copy(progress = 9.5, status = 1)
        val rebased = request.rebaseAfterConfirmation(flight, TrackingConfirmation(before, automaticAfter))
        val desired = TrackingQueueReconciliation.desired(rebased, automaticAfter)
        assertEquals(3L, desired.status)
        assertEquals(9.5, desired.progress)
        assertThrows(TrackingQueueException::class.java) {
            TrackingQueueReconciliation.desired(rebased, automaticAfter.copy(score = 9.0))
        }
        val foreignBefore = before.copy(status = 2)
        val foreignAfter = foreignBefore.copy(progress = 9.5)
        val held = request.rebaseAfterConfirmation(flight, TrackingConfirmation(foreignBefore, foreignAfter))
        assertThrows(TrackingQueueException::class.java) {
            TrackingQueueReconciliation.desired(held, foreignAfter)
        }
    }

    @Test fun `uncertain undo also accepts the verified baseline when the write never applied`() {
        val inverse = edit(TrackingPatch(before.copy(progress = 10.5), setOf(TrackingField.PROGRESS))).copy(
            expectedRemote = before.copy(progress = 12.5),
            alternativeExpectedRemote = before.copy(progress = 1.5),
            expectedFields = setOf(TrackingField.PROGRESS),
        )
        val remote = before.copy(progress = 1.5, score = 9.0)
        assertEquals(remote.copy(progress = 10.5), TrackingQueueReconciliation.desired(inverse, remote))
        assertThrows(TrackingQueueException::class.java) {
            TrackingQueueReconciliation.desired(inverse, remote.copy(progress = 20.5))
        }
    }

    @Test fun `partial acknowledgement cannot hide guarded fields still waiting to send`() {
        val pending = edit(
            TrackingPatch(
                before.copy(progress = 3.5, score = 8.0),
                setOf(TrackingField.PROGRESS, TrackingField.SCORE),
            ),
        ).copy(
            expectedRemote = before,
            expectedFields = setOf(TrackingField.PROGRESS),
            automaticProgress = true,
        )
        val confirmed = pending.copy(
            patch = pending.patch.copy(fields = setOf(TrackingField.PROGRESS)),
            expectedFields = setOf(TrackingField.PROGRESS),
        )
        val remaining = pending.acknowledge(confirmed)!!
        assertEquals(setOf(TrackingField.SCORE), remaining.patch.fields)
        assertTrue(remaining.expectedFields.isEmpty())
        assertFalse(remaining.automaticProgress)
        assertTrue(remaining.valid())
    }

    @Test fun `newer remote start date cannot combine with an older finish correction`() {
        val correction = edit(TrackingPatch(before.copy(finishDate = 1500), setOf(TrackingField.FINISH)))
        assertThrows(TrackingQueueException::class.java) {
            TrackingQueueReconciliation.desired(correction, before.copy(startDate = 2000, finishDate = 3000))
        }
        assertEquals(
            0L,
            TrackingQueueReconciliation.desired(
                correction.copy(
                    patch = correction.patch.copy(values = before.copy(finishDate = 0)),
                ),
                before.copy(startDate = 2000, finishDate = 3000),
            ).finishDate,
        )
    }
    private fun edit(patch: TrackingPatch = TrackingPatch(before.copy(progress = 3.5), setOf(TrackingField.PROGRESS))) =
        PendingTrackingEdit(
            TrackingSubject(TrackingMedium.MANGA, 61, "/fictional/title"), 10, 21, 55, 800,
            "fictional-account", patch, 100, accountEpoch = "session-one", base = before,
        )

    @Test fun `offline manual edit holds a conflicting newer remote change`() {
        val request = TrackingQueuePolicy.prepare(edit(), before, null)
        assertEquals(request.patch.fields, request.expectedFields)
        assertEquals(before, request.expectedRemote)
        assertThrows(TrackingQueueException::class.java) {
            TrackingQueueReconciliation.desired(request, before.copy(progress = 15.5))
        }
        assertEquals(
            before.copy(progress = 3.5, score = 9.0),
            TrackingQueueReconciliation.desired(request, before.copy(score = 9.0)),
        )
    }

    @Test fun `a newer local edit accepts the immediately preceding in flight write`() {
        val flight = edit().copy(patch = TrackingPatch(before.copy(progress = 12.5), setOf(TrackingField.PROGRESS)))
        val request = TrackingQueuePolicy.prepare(edit(), before, flight)
        assertEquals(
            before.copy(progress = 3.5),
            TrackingQueueReconciliation.desired(request, before.copy(progress = 12.5)),
        )
        assertThrows(TrackingQueueException::class.java) {
            TrackingQueueReconciliation.desired(request, before.copy(progress = 20.5))
        }
        val other = flight.copy(account = "unrelated-account")
        assertEquals(null, TrackingQueuePolicy.prepare(edit(), before, other).alternativeExpectedRemote)
    }

    @Test fun `automatic writes retain monotonic reconciliation instead of manual conflict guards`() {
        val automatic = edit().copy(origin = TrackingEditOrigin.AUTOMATIC, automaticProgress = true)
        assertEquals(automatic, TrackingQueuePolicy.prepare(automatic, before, null))
    }

    @Test fun `manual correction may move backward without touching unrelated remote fields`() {
        val remote = before.copy(score = 9.0, finishDate = 1234)
        val result = TrackingQueueReconciliation.desired(edit(), remote)
        assertEquals(remote.copy(progress = 3.5), result)
    }

    @Test fun `automatic progress cannot lower newer remote progress`() {
        val queued = edit().copy(automaticProgress = true, origin = TrackingEditOrigin.AUTOMATIC)
        assertEquals(before, TrackingQueueReconciliation.desired(queued, before))
        val remote = before.copy(progress = 2.5)
        assertEquals(remote.copy(progress = 3.5), TrackingQueueReconciliation.desired(queued, remote))
    }

    @Test fun `uncertain resend observes already accepted state as a no op`() {
        val queued = edit().copy(attempts = 2, delivery = TrackingDelivery.UNCERTAIN)
        val accepted = before.copy(progress = 3.5, score = 9.5)
        assertEquals(accepted, TrackingQueueReconciliation.desired(queued, accepted))
    }

    @Test fun `undo after sending preserves unrelated newer remote edits`() {
        val desired = before.copy(progress = 3.5)
        val inverse = edit(TrackingPatch(before, setOf(TrackingField.PROGRESS))).copy(
            origin = TrackingEditOrigin.UNDO,
            expectedRemote = desired,
            expectedFields = setOf(TrackingField.PROGRESS),
        )
        val remote = desired.copy(score = 9.0, private = true)
        assertEquals(remote.copy(progress = 8.5), TrackingQueueReconciliation.desired(inverse, remote))
        assertEquals(before.copy(score = 9.0), TrackingQueueReconciliation.desired(inverse, before.copy(score = 9.0)))
    }

    @Test fun `undo refuses to overwrite a later remote correction of the same field`() {
        val inverse = edit(TrackingPatch(before, setOf(TrackingField.PROGRESS))).copy(
            expectedRemote = before.copy(progress = 3.5),
            expectedFields = setOf(TrackingField.PROGRESS),
        )
        val error = assertThrows(TrackingQueueException::class.java) {
            TrackingQueueReconciliation.desired(inverse, before.copy(progress = 6.0))
        }
        assertEquals(TrackingQueueProblem.CONFLICT, error.problem)
    }

    @Test fun `revision ownership protects later equal value edits from ABA undo`() {
        val original = edit(TrackingPatch(before.copy(score = 8.0), setOf(TrackingField.SCORE, TrackingField.PROGRESS)))
        val receipt = TrackingEditReceipt("receipt-one", original, before, original.revision)
        val undo = TrackingQueuePolicy.undoPatch(
            receipt,
            mapOf(
                TrackingField.SCORE to 102,
                TrackingField.PROGRESS to 100,
            ),
        )
        assertEquals(setOf(TrackingField.PROGRESS), undo.fields)
        assertTrue(
            TrackingQueuePolicy.undoPatch(
                receipt.copy(undone = true),
                mapOf(TrackingField.PROGRESS to 100),
            ).fields.isEmpty(),
        )
    }

    @Test fun `already current requests cannot undo another devices earlier value`() {
        val request = edit()
        val receipt = TrackingEditReceipt("no-change", request, before, request.revision, sentAt = 200, noEffect = true)
        assertTrue(
            TrackingQueuePolicy.undoPatch(receipt, mapOf(TrackingField.PROGRESS to request.revision)).fields.isEmpty(),
        )
    }

    @Test fun `retries respect server delay and stop per item without deleting it`() {
        val first = TrackingQueuePolicy.failed(
            edit().copy(attempts = 1),
            TrackingQueueProblem.RATE_LIMIT,
            1000,
            120_000,
        )
        assertEquals(121_000L, first.notBefore)
        assertFalse(TrackingQueuePolicy.ready(first, 120_999))
        assertTrue(TrackingQueuePolicy.ready(first, 121_000))
        val last = TrackingQueuePolicy.failed(first.copy(attempts = 6), TrackingQueueProblem.NETWORK, 1000)
        assertEquals(TrackingQueueProblem.EXHAUSTED, last.problem)
        assertEquals(TrackingDelivery.BLOCKED, last.delivery)
        assertEquals(first.patch, last.patch)
    }

    @Test fun `long server cooldown is not shortened by the ordinary backoff cap`() {
        val delay = 12 * 60 * 60 * 1000L
        val held = TrackingQueuePolicy.failed(edit().copy(attempts = 1), TrackingQueueProblem.RATE_LIMIT, 1000, delay)
        assertEquals(1000 + delay, held.notBefore)
        assertFalse(TrackingQueuePolicy.ready(held, 1000 + TrackingQueuePolicy.MAX_DELAY))
    }

    @Test fun `authentication association and conflicts never spin automatically`() {
        for (problem in listOf(
            TrackingQueueProblem.AUTHENTICATION,
            TrackingQueueProblem.ACCOUNT,
            TrackingQueueProblem.ASSOCIATION,
            TrackingQueueProblem.CONFLICT,
            TrackingQueueProblem.REJECTED,
        )) {
            val failed = TrackingQueuePolicy.failed(edit().copy(attempts = 1), problem, 1000)
            assertFalse(TrackingQueuePolicy.ready(failed, Long.MAX_VALUE))
            assertEquals(problem, failed.problem)
        }
        assertFalse(TrackingQueuePolicy.ready(edit().copy(delivery = TrackingDelivery.SENDING), Long.MAX_VALUE))
    }

    @Test fun `http failure classification retains retry after and safe terminal causes`() {
        assertEquals(
            TrackingQueueProblem.RATE_LIMIT to 90_000L,
            TrackingQueueFailures.classify(java.io.IOException("wrapper", TrackingQueueHttpFailure(429, 90_000))),
        )
        assertEquals(
            TrackingQueueProblem.SERVICE,
            TrackingQueueFailures.classify(TrackingQueueHttpFailure(503, null)).first,
        )
        assertEquals(
            TrackingQueueProblem.AUTHENTICATION,
            TrackingQueueFailures.classify(TrackingQueueHttpFailure(401, null)).first,
        )
        assertEquals(
            TrackingQueueProblem.ASSOCIATION,
            TrackingQueueFailures.classify(TrackingQueueHttpFailure(404, null)).first,
        )
    }

    @Test fun `old pending records decode without inventing an original epoch or baseline`() {
        val json = Json { encodeDefaults = false }
        val original = edit().copy(accountEpoch = "", base = null)
        val restored = json.decodeFromString<PendingTrackingEdit>(json.encodeToString(original))
        assertEquals("", restored.accountEpoch)
        assertEquals(null, restored.base)
        assertTrue(restored.valid())
    }

    @Test fun `association identity separates medium account session and exact source title`() {
        val one = edit()
        assertFalse(one.sameIdentity(one.copy(subject = one.subject.copy(medium = TrackingMedium.ANIME))))
        assertFalse(one.sameIdentity(one.copy(account = "different-account")))
        assertFalse(one.sameIdentity(one.copy(accountEpoch = "session-two")))
        assertFalse(one.sameIdentity(one.copy(remoteId = 801)))
        assertFalse(one.sameIdentity(one.copy(remoteUrl = "/changed-reference")))
        assertNotEquals(one.identityKey(), one.copy(subject = one.subject.copy(url = "/other")).identityKey())
    }
}
