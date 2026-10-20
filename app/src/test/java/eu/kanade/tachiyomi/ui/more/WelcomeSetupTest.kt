package eu.kanade.tachiyomi.ui.more

import eu.kanade.domain.base.BasePreferences
import eu.kanade.domain.ui.NyanimeIdentity
import eu.kanade.domain.ui.ThemeSettings
import eu.kanade.domain.ui.ThemeSettingsRepository
import eu.kanade.domain.ui.model.ThemeMode
import eu.kanade.tachiyomi.data.addon.AddonIntroduction
import eu.kanade.tachiyomi.data.backup.BackupPreferencePolicy
import eu.kanade.tachiyomi.data.cloud.CloudPreferences
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import tachiyomi.core.common.preference.InMemoryPreferenceStore.InMemoryPreference
import tachiyomi.core.common.preference.Preference
import tachiyomi.core.common.preference.PreferenceStore
import tachiyomi.domain.release.service.AppUpdatePreferences
import java.io.File
import java.io.IOException

class WelcomeSetupTest {
    @TempDir lateinit var root: File

    private fun store(): PreferenceStore {
        val values = mutableMapOf<String, Preference<*>>()
        return mockk {
            every { getInt(any(), any()) } answers
                {
                    values.getOrPut(firstArg()) {
                        InMemoryPreference(firstArg(), null, secondArg<Int>())
                    } as Preference<Int>
                }
            every { getBoolean(any(), any()) } answers
                {
                    values.getOrPut(firstArg()) {
                        InMemoryPreference(firstArg(), null, secondArg<Boolean>())
                    } as Preference<Boolean>
                }
            every { getString(any(), any()) } answers
                {
                    values.getOrPut(firstArg()) {
                        InMemoryPreference(firstArg(), null, secondArg<String>())
                    } as Preference<String>
                }
            every { getLong(any(), any()) } answers
                {
                    values.getOrPut(firstArg()) {
                        InMemoryPreference(firstArg(), null, secondArg<Long>())
                    } as Preference<Long>
                }
        }
    }

    @Test
    fun interruptedStepsResumeWithoutCompletingIntroductions() {
        val store = store()
        WelcomeSetup(store).moveTo(3)
        assertEquals(3, WelcomeSetup(store).currentStep())
        assertFalse(AddonIntroduction(store).complete.get())
        assertFalse(AppUpdatePreferences(store).choiceComplete.get())
        assertFalse(CloudPreferences(store).introductionComplete().get())
    }

    @Test
    fun corruptedStepIsBoundedAndNotPortable() {
        val store = store()
        val setup = WelcomeSetup(store)
        setup.step.set(200)
        assertEquals(4, setup.currentStep())
        setup.moveTo(-1)
        assertEquals(0, setup.currentStep())
        assertFalse(BackupPreferencePolicy.isPortable(setup.step.key()))
    }

    @Test
    fun completionAcknowledgesIntroductionsOnlyAfterThemeSave() = runTest {
        val store = store()
        val base = BasePreferences(mockk(), store)
        var observedIncomplete = false
        val themes = themes {
            observedIncomplete = !base.shownOnboardingFlow().get() && !AddonIntroduction(store).complete.get()
            Result.success(Unit)
        }
        assertTrue(WelcomeSetup(store).complete(base, themes, ThemeMode.DARK).isSuccess)
        assertTrue(observedIncomplete)
        assertTrue(base.shownOnboardingFlow().get())
        assertTrue(AddonIntroduction(store).complete.get())
        assertTrue(AppUpdatePreferences(store).choiceComplete.get())
        assertTrue(CloudPreferences(store).introductionComplete().get())
        assertFalse(CloudPreferences(store).enabled().get())
        assertFalse(WelcomeSetup(store).step.isSet())
        assertFalse(BackupPreferencePolicy.isPortable(base.shownOnboardingFlow().key()))
    }

    @Test
    fun failedSaveDoesNotFinishOrConsumeIntroductions() = runTest {
        val store = store()
        val base = BasePreferences(mockk(), store)
        WelcomeSetup(store).moveTo(4)
        val result = WelcomeSetup(store).complete(base, themes { Result.failure(IOException()) }, ThemeMode.LIGHT)
        assertTrue(result.isFailure)
        assertFalse(base.shownOnboardingFlow().get())
        assertFalse(AddonIntroduction(store).complete.get())
        assertEquals(4, WelcomeSetup(store).currentStep())
    }

    @Test
    fun managedFolderIsWritableAndExistingDownloadsArePreserved() {
        val folder = WelcomeStorage.createManagedFolder(root)
        val saved = File(folder, "download.bin").apply { writeText("existing content") }
        assertEquals(folder, WelcomeStorage.createManagedFolder(root))
        assertEquals("existing content", saved.readText())
        assertEquals(listOf("download.bin"), folder.listFiles()!!.map { it.name })
    }

    @Test
    fun aFileBlockingTheDirectoryIsNotRemovedOrReportedReady() {
        val blocker = File(root, "Nyanime").apply { writeText("keep me") }
        assertThrows(IOException::class.java) { WelcomeStorage.createManagedFolder(root) }
        assertEquals("keep me", blocker.readText())
    }

    private fun themes(save: () -> Result<Unit>) = object : ThemeSettingsRepository {
        override val settings = flowOf(current())
        override fun current() = ThemeSettings(ThemeMode.SYSTEM, false)
        override suspend fun save(mode: ThemeMode, completeInitialChoice: Boolean): Result<Unit> = save()
        override suspend fun saveIdentity(identity: NyanimeIdentity): Result<Unit> = error("Unexpected identity write")
        override suspend fun unlockClassic(): Result<Unit> = error("Unexpected unlock")
    }
}
