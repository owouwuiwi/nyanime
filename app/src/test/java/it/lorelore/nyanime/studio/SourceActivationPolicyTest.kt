package it.lorelore.nyanime.studio

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class SourceActivationPolicyTest {
    @Test fun `Studio and preview permissions have separate stable owners`() {
        val source = StudioSource(
            schema = 2,
            uuid = "fictional-id",
            medium = StudioMedium.NOVEL,
            transport = StudioTransport.TELEGRAM,
            telegramChannels = listOf("fixture_channel"),
        )
        val active = SourceActivationRequirements.declared(source).single()
        val preview = SourceActivationRequirements.declared(source, preview = true).single()
        val grant = TelegramSourceGrant(
            active.owner,
            101,
            mapOf("fixture_channel" to -201L),
            1,
            accountRevision = "login-one",
        )
        assertTrue(SourceActivationPolicy.approved(listOf(active), 101, "login-one", listOf(grant)))
        assertFalse(SourceActivationPolicy.approved(listOf(preview), 101, "login-one", listOf(grant)))
        assertTrue(SourceActivationRequirements.declared(source.copy(transport = StudioTransport.WEB)).isEmpty())
    }

    private val owner = SourceAccessOwner("fixture.source", "publisher-one", 1)
    private val requirement = TelegramActivationRequirement(owner, "Fictional source", setOf("fixture_channel"))
    private val grant =
        TelegramSourceGrant(owner, 101, mapOf("fixture_channel" to -201L), 1, accountRevision = "login-one")

    private fun approved(
        account: Long? = 101,
        revision: String? = "login-one",
        grants: List<TelegramSourceGrant> = listOf(grant),
    ) =
        SourceActivationPolicy.approved(listOf(requirement), account, revision, grants)

    @Test fun `ordinary sources do not require Telegram`() {
        assertTrue(SourceActivationPolicy.approved(emptyList(), null, null, emptyList()))
    }

    @Test fun `missing login and cancelled or incomplete approval cannot activate`() {
        assertFalse(approved(account = null))
        assertFalse(approved(revision = null))
        assertFalse(approved(grants = emptyList()))
        assertFalse(approved(grants = listOf(grant.copy(approved = false))))
    }

    @Test fun `matching login and grants enable offline without a live engine`() {
        assertTrue(approved())
    }

    @Test fun `another account or relogin cannot reuse saved approval`() {
        assertFalse(approved(account = 102))
        assertFalse(approved(revision = "login-two"))
        assertFalse(approved(grants = listOf(grant.copy(accountRevision = ""))))
    }

    @Test fun `channel and publisher identity must match and ambiguous grants are rejected`() {
        assertFalse(approved(grants = listOf(grant.copy(channels = mapOf("fixture_other" to -202L)))))
        assertFalse(approved(grants = listOf(grant.copy(owner = owner.copy(publisher = "publisher-two")))))
        assertFalse(approved(grants = listOf(grant, grant)))
    }

    @Test fun `every Telegram consumer in one package must be approved`() {
        val second = requirement.copy(owner = owner.copy(sourceId = 2))
        assertFalse(SourceActivationPolicy.approved(listOf(requirement, second), 101, "login-one", listOf(grant)))
        assertTrue(
            SourceActivationPolicy.approved(
                listOf(requirement, second),
                101,
                "login-one",
                listOf(grant, grant.copy(owner = second.owner)),
            ),
        )
    }
}
