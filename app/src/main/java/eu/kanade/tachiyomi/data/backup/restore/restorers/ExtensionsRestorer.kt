package eu.kanade.tachiyomi.data.backup.restore.restorers

import android.content.Context
import android.content.Intent
import eu.kanade.tachiyomi.data.backup.models.BackupExtension
import eu.kanade.tachiyomi.data.rin.RinRepository
import eu.kanade.tachiyomi.data.rin.RinStorage
import eu.kanade.tachiyomi.extension.ExtensionPackageArchive
import eu.kanade.tachiyomi.extension.ExtensionPackageInspector
import eu.kanade.tachiyomi.util.storage.getUriCompat
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import java.io.File

class ExtensionsRestorer(
    private val context: Context,
    private val emergency: Boolean = false,
) {

    fun restoreExtensions(extensions: List<BackupExtension>): List<String> {
        val failures = mutableListOf<String>()
        extensions.forEach {
            if (it.format != "apk") return@forEach
            try {
                require(it.pkgName.matches(Regex("[A-Za-z][A-Za-z0-9_]*(\\.[A-Za-z][A-Za-z0-9_]*)+")))
                require(it.apk.size.toLong() in 1..ExtensionPackageArchive.MAX_BYTES)
                if (!it.privatelyInstalled &&
                    context.packageManager.getInstalledPackages(0).any { pkg -> pkg.packageName == it.pkgName }
                ) {
                    return@forEach
                }
                val file = File.createTempFile("extension-restore-", ".apk", context.cacheDir)
                var handedToAndroid = false
                try {
                    file.writeBytes(it.apk)
                    val inspected = ExtensionPackageArchive.inspect(context, file)
                    require(inspected.manifest.packageName == it.pkgName)
                    if (it.privatelyInstalled) {
                        val storage = RinStorage(context)
                        val signer = ExtensionPackageInspector.inspect(inspected.info) { 0 }.signers.first()
                        runBlocking {
                            val record = storage.stage(
                                file,
                                signer,
                                ExtensionPackageArchive.sha256(file),
                                it.rinRepository,
                            )
                            require(it.storagePublisher.isEmpty() || it.storagePublisher in record.publisherHistory)
                            val staged = record.copy(storagePublisher = it.storagePublisher.ifEmpty { record.signer })
                            val previous = storage.record(it.pkgName)
                            // A catalogue pin authenticates bytes; it is not consent to execute a new extension.
                            val enabled = previous?.enabled == true && previous.signer == record.signer && it.rinEnabled
                            val restored = staged.copy(
                                enabled = enabled,
                                trusted = previous?.trusted == true,
                                automaticUpdates = it.automaticUpdates,
                            )
                            if (emergency) {
                                storage.activate(restored)
                            } else {
                                RinRepository.get(context).restoreDormant(restored)
                            }
                        }
                        return@forEach
                    }
                    check(!emergency) { "An extension needs to be installed from the saved backup" }
                    val intent = Intent(Intent.ACTION_VIEW)
                        .setDataAndType(
                            file.getUriCompat(context),
                            "application/vnd.android.package-archive",
                        )
                        .setFlags(
                            Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION,
                        )
                    context.startActivity(intent)
                    handedToAndroid = true
                } finally {
                    if (!handedToAndroid) file.delete()
                }
            } catch (cancel: CancellationException) {
                throw cancel
            } catch (error: Exception) {
                failures += "${it.pkgName}: ${error.message ?: error.javaClass.simpleName}"
            }
        }
        return failures
    }
}
