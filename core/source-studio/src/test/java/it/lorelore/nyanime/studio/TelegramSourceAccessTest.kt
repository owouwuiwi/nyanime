package it.lorelore.nyanime.studio

import it.lorelore.nyanime.source.telegram.TelegramSourceDocument
import it.lorelore.nyanime.source.telegram.TelegramSourceMessage
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.supervisorScope
import kotlinx.coroutines.withTimeout
import org.junit.jupiter.api.DynamicTest
import org.junit.jupiter.api.TestFactory

private class MemoryGrants : TelegramSourceGrantStore {
    val granted = mutableMapOf<SourceAccessOwner, TelegramSourceGrant>()
    val joined = mutableSetOf<TelegramManagedMembership>()
    var afterSave: suspend (TelegramSourceGrant) -> Unit = {}

    override suspend fun grants() = granted.values.toList()

    override suspend fun save(grant: TelegramSourceGrant) {
        granted[grant.owner] = grant
        afterSave(grant)
    }

    override suspend fun remove(owner: SourceAccessOwner) {
        granted.remove(owner)
    }

    override suspend fun memberships() = joined.toSet()

    override suspend fun remember(membership: TelegramManagedMembership) {
        joined += membership
    }

    override suspend fun forget(membership: TelegramManagedMembership) {
        joined -= membership
    }
}

private class Backend : TelegramChannelBackend {
    val channels =
        mutableMapOf(
            "fixture_alpha" to
                TelegramPublicChannelState(
                    -101,
                    "fixture_alpha",
                    "The first fictional channel",
                    true,
                    false,
                ),
            "fixture_extra" to
                TelegramPublicChannelState(-102, "fixture_extra", "Fictional extras", true, false),
        )
    val joins = mutableListOf<Long>()
    val mutes = mutableListOf<Long>()
    val leaves = mutableListOf<Long>()
    var requested = 0
    var muteFails = false
    var joiningFails = false
    var beforeJoin: suspend () -> Unit = {}
    var response: suspend (Long) -> List<TelegramSourceMessage> = { id ->
        listOf(TelegramSourceMessage(id, 20, "A fictional post", 1000))
    }

    override suspend fun channel(username: String) = requireNotNull(channels[username])

    override suspend fun join(channel: Long) {
        joins += channel
        beforeJoin()
        if (joiningFails) error("Membership is not yet approved")
        channels.entries
            .first { it.value.id == channel }
            .setValue(channels.values.first { it.id == channel }.copy(member = true))
    }

    override suspend fun mute(channel: Long) {
        mutes += channel
        if (muteFails) error("Fictional network failure")
    }

    override suspend fun leave(channel: Long) {
        leaves += channel
    }

    override suspend fun history(
        channel: Long,
        before: Long,
        limit: Int,
    ): List<TelegramSourceMessage> {
        requested++
        return response(channel)
    }

    override suspend fun message(channel: Long, message: Long) =
        TelegramSourceMessage(channel, message, "Fictional body", null)

    override suspend fun document(channel: Long, message: Long, reference: String) =
        TelegramSourceDocument(
            reference,
            "fixture.txt",
            "text/plain",
            "Fictional text".toByteArray(),
        )
}

private class Fixture {
    val store = MemoryGrants()
    val backend = Backend()
    val owner = SourceAccessOwner("fiction.source", "publisher-a", 42)
    val second = SourceAccessOwner("fiction.other", "publisher-b", 43)
    var state = SourceAccessState(101, "revision-one", true)
    val access = TelegramSourceAccess(store, backend) { state }

    suspend fun activate(
        names: List<String> = listOf("fixture_alpha"),
        owner: SourceAccessOwner = this.owner,
    ) = access.activate(access.review(owner, names), true)

    fun reader(names: List<String> = listOf("fixture_alpha")) =
        access.reader(owner, "revision-one", names)
}

private suspend fun rejects(block: suspend () -> Any?) {
    var failed = false
    try {
        block()
    } catch (_: IllegalArgumentException) {
        failed = true
    } catch (_: IllegalStateException) {
        failed = true
    }
    check(failed) { "An unauthorized operation succeeded" }
}

class TelegramSourceAccessTest {
    @TestFactory
    fun scopedRules(): List<DynamicTest> {
        val cases = mutableListOf<DynamicTest>()
        fun test(name: String, run: suspend () -> Unit) {
            cases += DynamicTest.dynamicTest(name) { runBlocking { withTimeout(5000) { run() } } }
        }
        test("public input normalization") {
            check(TelegramPublicChannel.name("@Fixture_Alpha") == "fixture_alpha")
            check(TelegramPublicChannel.name("https://t.me/fixture_alpha") == "fixture_alpha")
            check(TelegramPublicChannel.name("https://t.me/s/fixture_alpha/") == "fixture_alpha")
            check(TelegramPublicChannel.names(listOf("@fixture_alpha", "fixture_alpha")).size == 1)
        }
        for (
        input in
        listOf(
            "https://t.me/+private",
            "https://t.me/joinchat/private",
            "https://t.me/c/12",
            "http://t.me/fixture_alpha",
            "https://evil.invalid/fixture_alpha",
            "https://user@t.me/fixture_alpha",
            "https://t.me/fixture_alpha?x=1",
            "https://t.me/fixture_alpha#key",
            "fixture alpha",
            "../fixture_alpha",
        )
        ) {
            test("reject private or malformed input $input") {
                rejects { TelegramPublicChannel.name(input) }
            }
        }
        test("no implicit consent") {
            val f = Fixture()
            rejects { f.reader().history("fixture_alpha") }
            check(f.backend.requested == 0 && f.backend.joins.isEmpty())
        }
        test("review never joins") {
            val f = Fixture()
            f.access.review(f.owner, listOf("fixture_alpha"))
            check(f.backend.joins.isEmpty() && f.store.granted.isEmpty())
        }
        test("login is required before channel review and never joins implicitly") {
            val f = Fixture()
            f.state = f.state.copy(account = null)
            rejects { f.access.review(f.owner, listOf("fixture_alpha")) }
            check(f.backend.joins.isEmpty() && f.store.granted.isEmpty())
        }
        test("same account with a new authentication revision invalidates an old review") {
            val f = Fixture()
            f.state = f.state.copy(accountRevision = "login-one")
            val review = f.access.review(f.owner, listOf("fixture_alpha"))
            f.state = f.state.copy(accountRevision = "login-two")
            rejects { f.access.activate(review, true) }
            check(f.backend.joins.isEmpty() && f.store.granted.isEmpty())
        }
        test("same account relogin never revives an old read grant") {
            val f = Fixture()
            f.state = f.state.copy(accountRevision = "login-one")
            f.activate()
            check(f.store.granted.getValue(f.owner).accountRevision == "login-one")
            f.state = f.state.copy(accountRevision = "login-two")
            rejects { f.reader().history("fixture_alpha") }
            check(f.backend.requested == 0)
        }
        test("new membership is joined and muted exactly once") {
            val f = Fixture()
            f.activate()
            f.activate()
            check(f.backend.joins == listOf(-101L) && f.backend.mutes == listOf(-101L))
            check(f.reader().history("fixture_alpha").messages.single().messageId == 20L)
        }
        test("existing subscriptions retain notification settings") {
            val f = Fixture()
            f.backend.channels["fixture_alpha"] =
                f.backend.channels.getValue("fixture_alpha").copy(member = true)
            f.activate()
            check(
                f.backend.joins.isEmpty() && f.backend.mutes.isEmpty() && f.store.joined.isEmpty(),
            )
        }
        test("renewing an active source never exposes an unapproved intermediate grant to reconciliation") {
            val f = Fixture()
            f.activate()
            val old = f.store.granted.getValue(f.owner)
            f.state = f.state.copy(revision = "revision-two", readableRevisions = setOf("revision-one"))
            f.store.afterSave = { grant ->
                if (!grant.approved) f.state = f.state.copy(enabled = false)
            }
            val replacement = f.activate().grant
            check(replacement.approved && replacement.generation == old.generation + 1)
            check(f.state.enabled)
        }
        test("a failed or cancelled renewal keeps the previously approved grant and no additional access") {
            for (cancel in listOf(false, true)) {
                val f = Fixture()
                f.activate()
                val old = f.store.granted.getValue(f.owner)
                f.state = f.state.copy(revision = "revision-two", readableRevisions = setOf("revision-one"))
                f.backend.beforeJoin = { if (cancel) throw CancellationException("User cancelled") }
                f.backend.joiningFails = !cancel
                try {
                    f.activate(listOf("fixture_alpha", "fixture_extra"))
                    error("Renewal should fail")
                } catch (_: CancellationException) {
                    check(cancel)
                } catch (_: IllegalStateException) {
                    check(!cancel)
                }
                check(f.store.granted.getValue(f.owner) == old)
                check(f.reader().history("fixture_alpha").messages.isNotEmpty())
                rejects { f.reader(listOf("fixture_alpha", "fixture_extra")).history("fixture_extra") }
            }
        }
        test("new channel permissions replace the old grant only after successful approval") {
            val f = Fixture()
            f.activate()
            val old = f.store.granted.getValue(f.owner)
            f.state = f.state.copy(revision = "revision-two", readableRevisions = setOf("revision-one"))
            f.backend.beforeJoin = {
                check(f.store.granted.getValue(f.owner) == old)
                check(f.reader().history("fixture_alpha").messages.isNotEmpty())
                rejects { f.reader(listOf("fixture_alpha", "fixture_extra")).history("fixture_extra") }
            }
            f.store.afterSave = { grant ->
                // The host's old active declaration cannot use a changed channel set.
                if (!grant.approved || grant.channels.keys != old.channels.keys) {
                    f.state = f.state.copy(enabled = false)
                }
            }
            val replacement = f.activate(listOf("fixture_alpha", "fixture_extra")).grant
            check(replacement.approved && replacement.channels.size == 2)
            // The repository can now atomically publish the reviewed definition.
            f.state = f.state.copy(enabled = true, readableRevisions = setOf("revision-two"))
            val reader = f.access.reader(f.owner, "revision-two", listOf("fixture_alpha", "fixture_extra"))
            check(reader.history("fixture_extra").messages.isNotEmpty())
        }
        test("concurrent disable account or definition changes cannot be overwritten by renewal") {
            for (change in listOf("disable", "account", "definition")) {
                val f = Fixture()
                f.activate()
                val old = f.store.granted.getValue(f.owner)
                f.state = f.state.copy(revision = "revision-two", readableRevisions = setOf("revision-one"))
                f.backend.beforeJoin = {
                    f.state = when (change) {
                        "disable" -> f.state.copy(enabled = false)
                        "account" -> f.state.copy(account = 202, accountRevision = "another-login")
                        else -> f.state.copy(revision = "another-definition")
                    }
                }
                rejects { f.activate(listOf("fixture_alpha", "fixture_extra")) }
                check(f.store.granted.getValue(f.owner) == old)
                if (change != "definition") rejects { f.reader().history("fixture_alpha") }
            }
        }
        test("a revocation queued during renewal wins without restoring the previous grant") {
            supervisorScope {
                val f = Fixture()
                f.activate()
                val started = CompletableDeferred<Unit>()
                val finish = CompletableDeferred<Unit>()
                f.backend.beforeJoin = {
                    started.complete(Unit)
                    finish.await()
                }
                val renewal = async { f.activate(listOf("fixture_alpha", "fixture_extra")) }
                started.await()
                val revoke = async { f.access.revoke(f.owner) }
                finish.complete(Unit)
                renewal.await()
                revoke.await()
                check(f.store.granted.isEmpty())
                rejects { f.reader().history("fixture_alpha") }
            }
        }
        test("mute failure is explicit and does not fake successful muting") {
            val f = Fixture()
            f.backend.muteFails = true
            check(f.activate().muteWarnings == setOf(-101L))
        }
        test("pending membership never grants source access") {
            val f = Fixture()
            f.backend.joiningFails = true
            rejects { f.activate() }
            check(f.store.granted.values.none { it.approved })
            rejects { f.reader().history("fixture_alpha") }
        }
        test("partial multi-channel activation keeps cleanup ownership without allowing reads") {
            val f = Fixture()
            f.backend.channels["fixture_extra"] =
                f.backend.channels.getValue("fixture_extra").copy(pending = true)
            rejects { f.activate(listOf("fixture_alpha", "fixture_extra")) }
            rejects { f.reader(listOf("fixture_alpha", "fixture_extra")).history("fixture_alpha") }
            check(f.access.revoke(f.owner) == setOf(TelegramManagedMembership(101, -101)))
            check(f.backend.leaves.isEmpty())
        }
        test("logout revokes grants without leaving channels") {
            val f = Fixture()
            f.activate()
            f.access.revokeAccount(101)
            rejects { f.reader().history("fixture_alpha") }
            check(f.backend.leaves.isEmpty() && f.store.granted.isEmpty())
        }
        test("private channels cannot be granted") {
            val f = Fixture()
            f.backend.channels["fixture_alpha"] =
                f.backend.channels.getValue("fixture_alpha").copy(publicChannel = false)
            rejects { f.activate() }
            check(f.backend.joins.isEmpty())
        }
        test("channel identity changes invalidate review") {
            val f = Fixture()
            val review = f.access.review(f.owner, listOf("fixture_alpha"))
            f.backend.channels["fixture_alpha"] =
                f.backend.channels.getValue("fixture_alpha").copy(id = -999)
            rejects { f.access.activate(review, true) }
            check(f.backend.joins.isEmpty())
        }
        test("account changes invalidate review") {
            val f = Fixture()
            val review = f.access.review(f.owner, listOf("fixture_alpha"))
            f.state = f.state.copy(account = 202)
            rejects { f.access.activate(review, true) }
            check(f.backend.joins.isEmpty())
        }
        test("source changes invalidate review") {
            val f = Fixture()
            val review = f.access.review(f.owner, listOf("fixture_alpha"))
            f.state = f.state.copy(revision = "revision-two")
            rejects { f.access.activate(review, true) }
        }
        test("extra requested channels cannot inherit a previous grant") {
            val f = Fixture()
            f.activate()
            rejects { f.reader(listOf("fixture_alpha", "fixture_extra")).history("fixture_extra") }
            check(f.backend.requested == 0)
        }
        test("read is limited to the reviewed channel") {
            val f = Fixture()
            f.activate()
            rejects { f.reader().history("fixture_extra") }
            check(f.backend.requested == 0)
        }
        test("attachment references cannot select another channel") {
            val f = Fixture()
            f.activate()
            rejects { f.reader().document("document:-102:20:" + "a".repeat(64)) }
        }
        test("document is scoped to the reviewed source") {
            val f = Fixture()
            f.activate()
            val ref = "document:-101:20:" + "a".repeat(64)
            check(f.reader().document(ref).reference == ref)
        }
        test("malformed cursors and cursor crossovers are rejected") {
            val f = Fixture()
            f.activate()
            rejects { f.reader().history("fixture_alpha", "history:-102:20") }
            rejects { f.reader().history("fixture_alpha", "history:-101:-5") }
            rejects { f.reader().history("fixture_alpha", "arbitrary-json") }
            check(f.backend.requested == 0)
        }
        test("underfilled history page does not claim end of archive") {
            val f = Fixture()
            f.activate()
            check(f.reader().history("fixture_alpha", limit = 100).nextCursor == "history:-101:20")
        }
        test("empty history ends without inventing a cursor") {
            val f = Fixture()
            f.activate()
            f.backend.response = { emptyList() }
            check(f.reader().history("fixture_alpha").nextCursor == null)
        }
        test("response from a different channel is rejected") {
            val f = Fixture()
            f.activate()
            f.backend.response = { listOf(TelegramSourceMessage(-102, 10, "", null)) }
            rejects { f.reader().history("fixture_alpha") }
        }
        test("revoke immediately blocks future reads") {
            val f = Fixture()
            f.activate()
            f.access.revoke(f.owner)
            rejects { f.reader().history("fixture_alpha") }
            check(f.backend.requested == 0)
        }
        test("account and revision are checked on every read") {
            val f = Fixture()
            f.activate()
            f.state = f.state.copy(account = 202)
            rejects { f.reader().history("fixture_alpha") }
            f.state = f.state.copy(account = 101, revision = "revision-two")
            rejects { f.reader().history("fixture_alpha") }
            check(f.backend.requested == 0)
        }
        test("disabled source cannot read and does not delete its stored grant") {
            val f = Fixture()
            f.activate()
            f.state = f.state.copy(enabled = false)
            rejects { f.reader().history("fixture_alpha") }
            check(f.store.granted.isNotEmpty())
        }
        test("a disabled but trusted source can be explicitly configured") {
            val f = Fixture()
            f.state = f.state.copy(enabled = false)
            f.activate()
            rejects { f.reader().history("fixture_alpha") }
            f.state = f.state.copy(enabled = true)
            check(f.reader().history("fixture_alpha").messages.isNotEmpty())
        }
        test("untrusted sources cannot be configured") {
            val f = Fixture()
            f.state = f.state.copy(configurable = false)
            rejects { f.access.review(f.owner, listOf("fixture_alpha")) }
            check(f.backend.joins.isEmpty())
        }
        test("leaving a channel is respected without rejoining") {
            val f = Fixture()
            f.activate()
            f.backend.channels["fixture_alpha"] =
                f.backend.channels.getValue("fixture_alpha").copy(member = false)
            rejects { f.reader().history("fixture_alpha") }
            check(f.backend.joins.size == 1 && f.backend.requested == 0)
        }
        test("a channel becoming private stops remote reads") {
            val f = Fixture()
            f.activate()
            f.backend.channels["fixture_alpha"] =
                f.backend.channels.getValue("fixture_alpha").copy(publicChannel = false)
            rejects { f.reader().history("fixture_alpha") }
            check(f.backend.requested == 0)
        }
        test("a reassigned username cannot redirect a grant") {
            val f = Fixture()
            f.activate()
            f.backend.channels["fixture_alpha"] =
                f.backend.channels.getValue("fixture_alpha").copy(id = -999)
            rejects { f.reader().message("fixture_alpha", 20) }
        }
        test("other publishers do not inherit grants") {
            val f = Fixture()
            f.activate()
            rejects {
                f.access
                    .reader(
                        f.owner.copy(publisher = "different"),
                        "revision-one",
                        listOf("fixture_alpha"),
                    )
                    .history("fixture_alpha")
            }
        }
        test("no automatic leave and shared membership ownership survives") {
            val f = Fixture()
            f.activate()
            f.activate(owner = f.second)
            check(f.access.revoke(f.owner).isEmpty() && f.backend.leaves.isEmpty())
            val candidates = f.access.revoke(f.second)
            check(candidates == setOf(TelegramManagedMembership(101, -101)))
            f.access.leave(f.second, candidates.single())
            check(f.backend.leaves == listOf(-101L))
        }
        test("preexisting membership cannot be offered for removal") {
            val f = Fixture()
            f.backend.channels["fixture_alpha"] =
                f.backend.channels.getValue("fixture_alpha").copy(member = true)
            f.activate()
            check(f.access.revoke(f.owner).isEmpty())
            rejects { f.access.leave(f.owner, TelegramManagedMembership(101, -101)) }
        }
        test("revocation cancels an in-flight read") {
            supervisorScope {
                val f = Fixture()
                f.activate()
                val entered = CompletableDeferred<Unit>()
                f.backend.response = {
                    entered.complete(Unit)
                    awaitCancellation()
                }
                val read = async { f.reader().history("fixture_alpha") }
                entered.await()
                f.access.revoke(f.owner)
                check(runCatching { read.await() }.exceptionOrNull() is CancellationException)
            }
        }
        test("a late result cannot escape after account change") {
            val f = Fixture()
            f.activate()
            f.backend.response = { id ->
                f.state = f.state.copy(account = 202)
                listOf(TelegramSourceMessage(id, 20, "Stale result", null))
            }
            rejects { f.reader().history("fixture_alpha") }
        }
        test("draft editing does not revoke an unchanged active revision") {
            val f = Fixture()
            f.activate()
            f.state =
                f.state.copy(revision = "draft-two", readableRevisions = setOf("revision-one"))
            check(f.reader().history("fixture_alpha").messages.isNotEmpty())
        }
        test("explicit service cursor keeps an empty intermediate page incomplete") {
            val f = Fixture()
            val backend = object : TelegramChannelPagedBackend, TelegramChannelBackend by f.backend {
                override suspend fun historyPage(channel: Long, before: Long, limit: Int) =
                    TelegramChannelHistoryPage(emptyList(), 10)
            }
            val access = TelegramSourceAccess(f.store, backend) { f.state }
            access.activate(access.review(f.owner, listOf("fixture_alpha")), true)
            val result = access.reader(f.owner, "revision-one", listOf("fixture_alpha")).history("fixture_alpha")
            check(result.messages.isEmpty() && result.nextCursor == "history:-101:10")
        }
        test("explicit end token completes even when the last page is full") {
            val f = Fixture()
            val backend = object : TelegramChannelPagedBackend, TelegramChannelBackend by f.backend {
                override suspend fun historyPage(channel: Long, before: Long, limit: Int) =
                    TelegramChannelHistoryPage(listOf(TelegramSourceMessage(channel, 20, "Fiction", null)), null)
            }
            val access = TelegramSourceAccess(f.store, backend) { f.state }
            access.activate(access.review(f.owner, listOf("fixture_alpha")), true)
            check(
                access.reader(
                    f.owner,
                    "revision-one",
                    listOf("fixture_alpha"),
                ).history("fixture_alpha", limit = 1).nextCursor ==
                    null,
            )
        }
        test("a stalled explicit cursor cannot silently complete the archive") {
            val f = Fixture()
            val backend = object : TelegramChannelPagedBackend, TelegramChannelBackend by f.backend {
                override suspend fun historyPage(
                    channel: Long,
                    before: Long,
                    limit: Int,
                ) = TelegramChannelHistoryPage(emptyList(), 10)
            }
            val access = TelegramSourceAccess(f.store, backend) { f.state }
            access.activate(access.review(f.owner, listOf("fixture_alpha")), true)
            rejects {
                access.reader(
                    f.owner,
                    "revision-one",
                    listOf("fixture_alpha"),
                ).history("fixture_alpha", "history:-101:10")
            }
        }
        return cases
    }
}
