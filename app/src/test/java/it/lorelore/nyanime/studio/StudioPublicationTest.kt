package it.lorelore.nyanime.studio

import eu.kanade.tachiyomi.data.backup.models.Backup
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.protobuf.ProtoBuf
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import javax.crypto.AEADBadTagException
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

class StudioPublicationTest {
    @Test fun deferredScriptApprovalKeepsThePreviouslyApprovedActiveVersionWorking() {
        val old = validSource().copy(flows = validSource().flows + (StudioFlow.SEARCH to scriptGraph()))
        val next = old.copy(revision = old.revision + 1, name = "Edited synthetic source")
        val record =
            StudioRecord(old, active = old, enabled = true, trustedScriptFingerprint = StudioDocuments.fingerprint(old))
        val pending = record.publishApproved(next, deferred = true)
        assertEquals(old, pending.active)
        assertTrue(pending.pending)
        assertTrue(pending.trusts(old))
        assertTrue(pending.trusts(next))
        assertFalse(pending.trusts(next.copy(name = "Unreviewed edit")))
        val restored = pending.afterRestore()
        assertFalse(restored.trusts(old))
        assertFalse(restored.trusts(next))
        assertNull(restored.activeScriptFingerprint)
    }

    @Test fun credentialOriginsIgnorePathsAndOrderButRejectNewDestinations() {
        val source = validSource().copy(
            baseUrl = "https://fixture.test/old",
            origins = linkedSetOf("https://media.fixture.test/path", "https://api.fixture.test"),
        )
        val changedPaths = source.copy(
            baseUrl = "https://FIXTURE.test/new",
            origins = linkedSetOf("https://api.fixture.test:443", "https://media.fixture.test/other"),
        )
        assertArrayEquals(studioCredentialAAD(source, "ACCESS"), studioCredentialAAD(changedPaths, "ACCESS"))
        val key = SecretKeySpec(ByteArray(16) { 7 }, "AES")
        val encryption = Cipher.getInstance("AES/GCM/NoPadding").apply {
            init(Cipher.ENCRYPT_MODE, key)
            updateAAD(studioCredentialAAD(source, "ACCESS"))
        }
        val ciphertext = encryption.doFinal("synthetic-value".toByteArray())
        val changed = source.copy(origins = source.origins + "https://other.fixture.test")
        val decryption = Cipher.getInstance("AES/GCM/NoPadding").apply {
            init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(128, encryption.iv))
            updateAAD(studioCredentialAAD(changed, "ACCESS"))
        }
        assertThrows(AEADBadTagException::class.java) { decryption.doFinal(ciphertext) }
    }

    @Test fun identicalAutosaveKeepsAnApprovedDeferredPublication() {
        val active = StudioBlueprints.source().copy(name = "Synthetic source", revision = 2)
        val draft = active.copy(name = "Edited source", revision = 3)
        val record = StudioRecord(draft, active = active, enabled = true, pending = true)
        val saved = record.withDraft(draft)
        assertSame(record, saved)
        assertTrue(saved.pending)
        assertEquals(active, saved.active)
    }

    @Test fun aRealEditCancelsDeferredActivationWithoutChangingTheActiveSource() {
        val active = StudioBlueprints.source().copy(name = "Synthetic source", revision = 2)
        val draft = active.copy(name = "Edited source", revision = 3)
        val record = StudioRecord(draft, active = active, enabled = true, pending = true)
        val edited = record.withDraft(draft.copy(name = "Another edit"))
        assertFalse(edited.pending)
        assertEquals(active, edited.active)
        assertEquals(active.sourceId, edited.draft.sourceId)
    }

    @Test fun restoredPreviousScriptsCannotGainPermissionThroughRollback() {
        val clean = validSource()
        val scripted = clean.copy(flows = clean.flows + (StudioFlow.SEARCH to scriptGraph()))
        assertTrue(StudioValidation.source(scripted).isEmpty())
        val restored = StudioRecord(
            clean,
            active = clean,
            previous = scripted,
            enabled = true,
            pending = true,
            trustedScriptFingerprint = "foreign-approval",
        ).afterRestore()
        assertTrue(restored.enabled)
        assertFalse(restored.pending)
        assertNull(restored.previous)
        assertNull(restored.trustedScriptFingerprint)
        assertEquals(clean, restored.active)
    }

    @Test fun restoredActiveScriptsWaitForApprovalOnThisInstallation() {
        val clean = validSource()
        val scripted = clean.copy(flows = clean.flows + (StudioFlow.SEARCH to scriptGraph()))
        assertTrue(StudioValidation.source(scripted).isEmpty())
        val restored = StudioRecord(
            scripted,
            active = scripted,
            enabled = true,
            trustedScriptFingerprint = "foreign-approval",
        ).afterRestore()
        assertFalse(restored.enabled)
        assertNull(restored.trustedScriptFingerprint)
    }

    @OptIn(kotlinx.serialization.ExperimentalSerializationApi::class)
    @Test
    fun normalBackupCodecRetainsTheStudioDraftAndItsSourceIdentity() {
        val draft = StudioBlueprints.source(StudioMedium.MANGA).copy(name = "Synthetic source")
        val serializer = ListSerializer(StudioRecord.serializer())
        val state = StudioDocuments.json.encodeToString(serializer, listOf(StudioRecord(draft)))
        val bytes = ProtoBuf.encodeToByteArray(Backup.serializer(), Backup(isLegacy = false, sourceStudioState = state))
        val restored = ProtoBuf.decodeFromByteArray(Backup.serializer(), bytes)
        val records = StudioDocuments.json.decodeFromString(serializer, requireNotNull(restored.sourceStudioState))
        assertEquals(draft, records.single().draft)
        assertEquals(draft.sourceId, records.single().draft.sourceId)
    }
    private fun validSource(): StudioSource {
        val graph = StudioGraph(
            nodes = listOf(
                StudioNode("input", StudioKind.CONSTANT, parameters = mapOf("value" to "[]")),
                StudioNode("json", StudioKind.JSON),
                StudioNode("result", StudioKind.OUTPUT, parameters = mapOf("structured" to "true")),
            ),
            wires = listOf(
                StudioWire("input", "value", "json", "text"),
                StudioWire("json", "value", "result", "value"),
            ),
        )
        return StudioSource(
            name = "Synthetic source",
            baseUrl = "https://fixture.test",
            flows = listOf(
                StudioFlow.SEARCH,
                StudioFlow.DETAILS,
                StudioFlow.ENTRIES,
                StudioFlow.CONTENT,
            ).associateWith {
                graph
            },
        )
    }

    private fun scriptGraph() = StudioGraph(
        nodes = listOf(
            StudioNode("script", StudioKind.SCRIPT, parameters = mapOf("code" to "return []")),
            StudioNode("result", StudioKind.OUTPUT, parameters = mapOf("structured" to "true")),
        ),
        wires = listOf(StudioWire("script", "value", "result", "value")),
    )
}
