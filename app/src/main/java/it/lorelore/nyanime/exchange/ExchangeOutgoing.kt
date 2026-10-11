package it.lorelore.nyanime.exchange

import android.content.Context
import android.net.Uri
import eu.kanade.tachiyomi.data.extensionstore.ExtensionStoreRepository
import eu.kanade.tachiyomi.data.extensionstore.StoreEntry
import eu.kanade.tachiyomi.util.storage.getUriCompat
import it.lorelore.nyanime.studio.SourceStudioRepository
import it.lorelore.nyanime.studio.StudioDocuments
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

internal data class ExchangeOutgoing(val uri: Uri, val name: String)

internal object ExchangeOutgoingFiles {
    suspend fun studio(context: Context, uuid: String): ExchangeOutgoing = withContext(Dispatchers.IO) {
        val repository = SourceStudioRepository.get(context)
        val draft = repository.draft(uuid)
        val record = repository.records.value.first { it.draft.uuid == uuid }
        val source = record.active?.takeIf { record.enabled } ?: draft
        val directory = ExchangeStorage.newSession(context)
        val file = File(directory, "definition.nyastudio")
        file.writeText(StudioDocuments.encode(source))
        check(file.setReadOnly())
        ExchangeOutgoing(
            file.getUriCompat(context),
            "${source.name.replace(Regex("[^\\p{L}\\p{N}._-]"), "-").take(70).ifBlank {
                "source"
            }}.nyastudio",
        )
    }

    suspend fun installed(context: Context, entry: StoreEntry): ExchangeOutgoing = withContext(Dispatchers.IO) {
        require(entry.installed)
        val repository = ExtensionStoreRepository.get(context)
        val record = repository.rin.storage.record(entry.packageName)
        val source = if (record != null && !record.isShared) {
            repository.rin.storage.file(record)
        } else {
            val info = context.packageManager.getApplicationInfo(entry.packageName, 0)
            require(info.splitSourceDirs.isNullOrEmpty()) { "split" }
            File(info.sourceDir)
        }
        require(source.isFile && source.length() <= ExchangeProtocol.LIMIT)
        val name = "${entry.name.replace(Regex("[^\\p{L}\\p{N}._-]"), "-").take(70).ifBlank {
            "source"
        }}.${record?.format ?: "apk"}"
        // Copy into FileProvider space, preserving the exact signed original bytes.
        val directory = ExchangeStorage.newSession(context)
        val destination = File(directory, "outgoing.${record?.format ?: "apk"}")
        source.inputStream().use { input ->
            destination.outputStream().use { output ->
                val buffer = ByteArray(32768)
                var total = 0L
                while (true) {
                    val count = input.read(buffer)
                    if (count < 0) break
                    total += count
                    require(total <= ExchangeProtocol.LIMIT)
                    output.write(buffer, 0, count)
                }
                output.flush()
                output.fd.sync()
            }
        }
        check(destination.setReadOnly())
        ExchangeOutgoing(destination.getUriCompat(context), name)
    }
}
