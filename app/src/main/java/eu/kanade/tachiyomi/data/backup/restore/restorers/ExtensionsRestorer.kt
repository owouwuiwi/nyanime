package eu.kanade.tachiyomi.data.backup.restore.restorers

import android.content.Context
import android.content.Intent
import eu.kanade.tachiyomi.data.backup.models.BackupExtension
import eu.kanade.tachiyomi.data.rin.RinArchive
import eu.kanade.tachiyomi.util.storage.getUriCompat
import mihon.domain.extension.anime.repository.AnimeExtensionStoreRepository
import mihon.domain.extensionrepo.manga.repository.MangaExtensionRepoRepository
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import java.io.File

class ExtensionsRestorer(
    private val context: Context,
    private val emergency: Boolean = false,
) {

    fun restoreExtensions(extensions: List<BackupExtension>): List<String> {
        val failures = mutableListOf<String>()
        extensions.forEach {
            try {
                if (it.format == "rin") {
                    val storage = eu.kanade.tachiyomi.data.rin.RinStorage(context)
                    kotlinx.coroutines.runBlocking {
                        val approved = Injekt.get<AnimeExtensionStoreRepository>().getAll().any { store ->
                            store.indexUrl == it.rinRepository && store.signingKey.lowercase() == it.rinSigner
                        } ||
                            Injekt.get<MangaExtensionRepoRepository>().getAll().any { store ->
                                store.baseUrl == it.rinRepository &&
                                    store.signingKeyFingerprint.lowercase() == it.rinSigner
                            }
                        require(approved) {
                            "Restore the trusted extension catalogue before RIN packages"
                        }
                        val file = File.createTempFile("rin-restore-", ".tmp", context.cacheDir)
                        try {
                            file.writeBytes(it.apk)
                            val record = storage.stage(
                                file,
                                it.rinSigner,
                                RinArchive.sha256(it.apk),
                                it.rinRepository,
                            )
                            require(record.manifest.packageName == it.pkgName)
                            val previous = storage.record(it.pkgName)
                            // A catalogue pin authenticates bytes; it is not consent to execute a new extension.
                            val enabled = previous?.enabled == true && previous.signer == record.signer && it.rinEnabled
                            if (emergency) {
                                storage.activate(record.copy(enabled = enabled))
                            } else {
                                eu.kanade.tachiyomi.data.rin.RinRepository.get(context)
                                    .activate(record.copy(enabled = enabled), preserveEnabled = false)
                            }
                        } finally {
                            file.delete()
                        }
                    }
                    return@forEach
                }
                require(it.format == "apk")
                if (context.packageManager.getInstalledPackages(0).none { pkg -> pkg.packageName == it.pkgName }) {
                    check(!emergency) { "An extension needs to be installed from the saved backup" }
                    // save apk in files dir and open installer dialog
                    val file = File(context.cacheDir, "${it.pkgName}.apk")
                    file.writeBytes(it.apk)
                    val intent = Intent(Intent.ACTION_VIEW)
                        .setDataAndType(
                            file.getUriCompat(context),
                            "application/vnd.android.package-archive",
                        )
                        .setFlags(
                            Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION,
                        )
                    context.startActivity(intent)
                }
            } catch (cancel: kotlinx.coroutines.CancellationException) {
                throw cancel
            } catch (error: Exception) {
                failures += "${it.pkgName}: ${error.message ?: error.javaClass.simpleName}"
            }
        }
        return failures
    }
}
