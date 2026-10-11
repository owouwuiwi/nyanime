package eu.kanade.tachiyomi.data.addon

import android.content.Context
import android.util.AtomicFile
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.RandomAccessFile

/** Authorization must be fresh in both the normal app and isolated capability processes. */
internal class AddonStateStore(private val context: Context) {
    private val file = AtomicFile(File(context.filesDir, "addon-state-v1.json"))

    fun snapshot(): JSONObject = locked { read() }

    fun update(block: (JSONObject) -> Unit) = locked {
        val state = read().also(block)
        val output = file.startWrite()
        try {
            output.write(state.toString().toByteArray(Charsets.UTF_8))
            file.finishWrite(output)
        } catch (error: Throwable) {
            file.failWrite(output)
            throw error
        }
    }

    private fun read(): JSONObject {
        if (file.baseFile.exists() || File("${file.baseFile.path}.bak").exists()) {
            return JSONObject(file.openRead().use { it.readBytes().toString(Charsets.UTF_8) })
        }
        // Read existing addon choices once when the first mutation commits the new store.
        val legacy = context.getSharedPreferences("optional_addons", Context.MODE_PRIVATE).all
        return JSONObject().apply {
            legacy.forEach { (key, value) -> put(key, if (value is Set<*>) JSONArray(value.toList()) else value) }
        }
    }

    private fun <T> locked(block: () -> T): T = synchronized(LOCK) {
        RandomAccessFile(File(context.filesDir, "addon-state.lock"), "rw").use { handle ->
            handle.channel.lock().use { block() }
        }
    }

    companion object {
        private val LOCK = Any()
    }
}
