package eu.kanade.tachiyomi.ui.more

import android.content.Context
import androidx.core.net.toUri
import com.hippo.unifile.UniFile
import eu.kanade.domain.base.BasePreferences
import eu.kanade.domain.ui.ThemeSettingsRepository
import eu.kanade.domain.ui.model.ThemeMode
import eu.kanade.tachiyomi.data.addon.AddonIntroduction
import eu.kanade.tachiyomi.data.cloud.CloudPreferences
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import tachiyomi.core.common.preference.Preference
import tachiyomi.core.common.preference.PreferenceStore
import tachiyomi.domain.release.service.AppUpdatePreferences
import tachiyomi.domain.storage.service.StoragePreferences
import java.io.File
import java.io.IOException
import java.util.UUID

/** A completed setup stays completed on upgrades; a restored backup cannot consume it. */
class WelcomeSetup(private val store: PreferenceStore) {
    val step = store.getInt(Preference.appStateKey("welcome_step_v1"), 0)

    fun currentStep(): Int = step.get().coerceIn(0, LAST_STEP)
    fun moveTo(index: Int) = step.set(index.coerceIn(0, LAST_STEP))

    suspend fun complete(base: BasePreferences, themes: ThemeSettingsRepository, mode: ThemeMode): Result<Unit> {
        val saved = themes.save(mode, completeInitialChoice = true)
        if (saved.isFailure) return saved
        AppUpdatePreferences(store).confirm(AppUpdatePreferences(store).channel())
        AddonIntroduction(store).confirm()
        CloudPreferences(store).introductionComplete().set(true)
        // Publish completion last: an interruption must leave a resumable welcome.
        base.shownOnboardingFlow().set(true)
        step.delete()
        return Result.success(Unit)
    }

    companion object {
        const val LAST_STEP = 4
    }
}

/** No broad storage permission is needed for a new installation. Never replace an existing choice. */
object WelcomeStorage {
    suspend fun prepare(
        context: Context,
        preferences: StoragePreferences,
    ): Result<Boolean> = withContext(Dispatchers.IO) {
        try {
            val selected = preferences.baseStorageDirectory()
            if (selected.isSet()) {
                val directory = UniFile.fromUri(context, selected.get().toUri())
                checkWritable(directory ?: throw IOException("Storage is unavailable"))
                val managed = listOfNotNull(context.getExternalFilesDir(null), context.filesDir)
                    .any { selected.get() == File(it, "Nyanime").toUri().toString() }
                Result.success(managed)
            } else {
                val parent = context.getExternalFilesDir(null) ?: context.filesDir
                val folder = createManagedFolder(parent)
                selected.set(folder.toUri().toString())
                Result.success(true)
            }
        } catch (cancel: CancellationException) {
            throw cancel
        } catch (failure: Exception) {
            Result.failure(failure)
        }
    }

    internal fun createManagedFolder(parent: File): File {
        val folder = File(parent, "Nyanime")
        if ((!folder.isDirectory && !folder.mkdirs()) || !folder.canWrite()) {
            throw IOException("Cannot create app storage")
        }
        val probe = File.createTempFile(".welcome-", ".tmp", folder)
        try {
            probe.outputStream().use { it.write(0) }
        } finally {
            if (!probe.delete()) throw IOException("Cannot verify app storage")
        }
        return folder
    }

    private fun checkWritable(directory: UniFile) {
        if (!directory.exists() || !directory.isDirectory || !directory.canWrite()) {
            throw IOException("Selected storage is not writable")
        }
        val probe = directory.createFile(".welcome-${UUID.randomUUID()}.tmp")
            ?: throw IOException("Selected storage is unavailable")
        try {
            probe.openOutputStream().use { it.write(0) }
        } finally {
            probe.delete()
        }
    }
}
