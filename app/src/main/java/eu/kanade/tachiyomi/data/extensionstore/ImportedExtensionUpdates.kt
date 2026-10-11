package eu.kanade.tachiyomi.data.extensionstore

import eu.kanade.tachiyomi.data.rin.RinRecord
import eu.kanade.tachiyomi.data.rin.RinUpdatePolicy
import eu.kanade.tachiyomi.extension.anime.model.AnimeExtension
import eu.kanade.tachiyomi.extension.manga.model.MangaExtension

/** Only an installed, opted-in APK can authorize unattended replacement. */
internal object ImportedExtensionUpdates {
    fun eligible(entry: StoreEntry): Boolean {
        val installed = entry.rinRecord ?: return false
        if (!updatesEnabled(entry) || entry.action != StoreAction.UPDATE || !entry.canImport || !entry.addonHostCompatible) {
            return false
        }
        if (!matchesInstalled(entry, installed)) return false
        val candidate = entry.rinCandidate
        return if (candidate != null) {
            !candidate.requiresAndroid && RinUpdatePolicy.accepts(candidate, installed)
        } else {
            !installed.manifest.isLayer && entry.candidateUrl != null
        }
    }

    fun updatesEnabled(entry: StoreEntry): Boolean = entry.installed && entry.rinRecord?.let { installed ->
        installed.format == "apk" && !installed.isShared && installed.trusted && installed.automaticUpdates &&
            installed.repository.isNotBlank() && entry.repository == installed.repository
    } == true && (entry.anime as? AnimeExtension.Installed)?.keepVersion != true &&
        (entry.manga as? MangaExtension.Installed)?.keepVersion != true

    fun matchesInstalled(entry: StoreEntry, installed: RinRecord): Boolean =
        installed.format == "apk" && !installed.isShared && installed.trusted && installed.automaticUpdates &&
            entry.packageName == installed.manifest.packageName &&
            entry.rinRecord?.sha256 == installed.sha256 &&
            installed.repository.isNotBlank() && entry.repository == installed.repository &&
            entry.candidateSigner?.lowercase() in installed.publisherSigners &&
            (entry.candidateVersion ?: 0L) > installed.manifest.versionCode
}
