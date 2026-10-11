package eu.kanade.tachiyomi.data.backup

import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import androidx.annotation.RequiresApi
import tachiyomi.domain.storage.service.StorageManager
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import java.io.File
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Public manual exports are separate from the configured, rotating automatic backups. */
object BackupExportStore {
    private const val FOLDER = "Nyanime"
    private const val MIME_TYPE = "application/octet-stream"

    data class Entry(
        val uri: Uri,
        val name: String,
        val size: Long,
        val modifiedAt: Long,
        val automatic: Boolean,
    )

    fun requiresLegacyStoragePermission(): Boolean = Build.VERSION.SDK_INT < Build.VERSION_CODES.Q

    fun createPending(context: Context): Uri {
        val filename = "Nyanime_${SimpleDateFormat("yyyy-MM-dd_HH-mm-ss-SSS", Locale.ROOT).format(Date())}.nyabk"
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            return createMediaStoreEntry(context, filename)
        }

        val directory = legacyDirectory()
        if (!directory.isDirectory && !directory.mkdirs()) {
            throw IOException("Impossibile creare Download/Nyanime. Controlla lo spazio disponibile.")
        }
        return Uri.fromFile(File(directory, filename))
    }

    fun publish(context: Context, uri: Uri) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && uri.scheme == "content") {
            val values = ContentValues().apply {
                put(MediaStore.MediaColumns.IS_PENDING, 0)
                putNull(MediaStore.MediaColumns.DATE_EXPIRES)
            }
            if (context.contentResolver.update(uri, values, null, null) != 1) {
                throw IOException("Il backup è stato scritto, ma non è visibile nei Download.")
            }
        }
    }

    fun delete(context: Context, uri: Uri) {
        if (uri.scheme == "file") {
            uri.path?.let { File(it).delete() }
        } else {
            context.contentResolver.delete(uri, null, null)
        }
    }

    fun list(context: Context): List<Entry> {
        val manual = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            listMediaStoreEntries(context)
        } else {
            legacyDirectory().listFiles().orEmpty().mapNotNull { file ->
                file.takeIf { it.isFile && BackupFileFormat.acceptsPath(it.name) }?.let {
                    Entry(Uri.fromFile(it), it.name, it.length(), it.lastModified(), false)
                }
            }
        }
        val automatic = runCatching {
            Injekt.get<StorageManager>().getAutomaticBackupsDirectory()
                ?.listFiles { _, name -> BackupFileFormat.acceptsPath(name) }
                .orEmpty()
                .mapNotNull { file ->
                    file.name?.let { Entry(file.uri, it, file.length(), file.lastModified(), true) }
                }
        }.getOrDefault(emptyList())
        return (manual + automatic).distinctBy { it.uri }.sortedByDescending { it.modifiedAt }
    }

    @RequiresApi(Build.VERSION_CODES.Q)
    private fun createMediaStoreEntry(context: Context, filename: String): Uri {
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, filename)
            put(MediaStore.MediaColumns.MIME_TYPE, MIME_TYPE)
            put(MediaStore.MediaColumns.RELATIVE_PATH, relativePath())
            put(MediaStore.MediaColumns.IS_PENDING, 1)
            put(MediaStore.MediaColumns.DATE_EXPIRES, System.currentTimeMillis() / 1000 + 24 * 60 * 60)
        }
        return context.contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
            ?: throw IOException("Impossibile salvare il backup in Download/Nyanime.")
    }

    @RequiresApi(Build.VERSION_CODES.Q)
    private fun listMediaStoreEntries(context: Context): List<Entry> {
        val projection = arrayOf(
            MediaStore.MediaColumns._ID,
            MediaStore.MediaColumns.DISPLAY_NAME,
            MediaStore.MediaColumns.SIZE,
            MediaStore.MediaColumns.DATE_MODIFIED,
        )
        val selection = "${MediaStore.MediaColumns.RELATIVE_PATH}=? AND ${MediaStore.MediaColumns.IS_PENDING}=0"
        return context.contentResolver.query(
            MediaStore.Downloads.EXTERNAL_CONTENT_URI,
            projection,
            selection,
            arrayOf(relativePath()),
            "${MediaStore.MediaColumns.DATE_MODIFIED} DESC",
        )?.use { cursor ->
            val idColumn = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns._ID)
            val nameColumn = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.DISPLAY_NAME)
            val sizeColumn = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.SIZE)
            val dateColumn = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.DATE_MODIFIED)
            buildList {
                while (cursor.moveToNext()) {
                    val name = cursor.getString(nameColumn) ?: continue
                    if (!BackupFileFormat.acceptsPath(name)) continue
                    add(
                        Entry(
                            ContentUris.withAppendedId(
                                MediaStore.Downloads.EXTERNAL_CONTENT_URI,
                                cursor.getLong(idColumn),
                            ),
                            name,
                            cursor.getLong(sizeColumn),
                            cursor.getLong(dateColumn) * 1000,
                            false,
                        ),
                    )
                }
            }
        }.orEmpty()
    }

    private fun relativePath() = "${Environment.DIRECTORY_DOWNLOADS}/$FOLDER/"

    private fun legacyDirectory() = File(
        Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS),
        FOLDER,
    )
}
