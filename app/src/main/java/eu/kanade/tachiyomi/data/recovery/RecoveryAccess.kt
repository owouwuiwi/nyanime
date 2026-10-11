package eu.kanade.tachiyomi.data.recovery

import android.content.Context
import android.os.Binder
import android.util.AtomicFile
import eu.kanade.tachiyomi.data.addon.AddonExtension
import eu.kanade.tachiyomi.data.addon.AddonRegistry
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import java.io.File
import java.io.RandomAccessFile

/** A fresh cross-process grant is required on every private IPC call. */
class RecoveryAccess(private val context: Context) {
    private val file = AtomicFile(File(context.filesDir, "recovery-access.json"))
    private fun exists() = file.baseFile.isFile || File("${file.baseFile.path}.bak").isFile
    fun hasGrants(): Boolean = exists() && runCatching { grants().length() > 0 }.getOrDefault(false)
    private fun read() = if (!exists()) {
        JSONObject()
    } else {
        JSONObject(
            file.openRead().use {
                it.readBytes().toString(Charsets.UTF_8)
            },
        )
    }
    fun grants(): JSONObject = locked { read() }
    fun approve(addon: AddonExtension) = locked {
        require(addon.usable && addon.dataRecovery)
        write(
            read().put(
                addon.packageName,
                JSONObject().apply {
                    put("distribution", addon.metadata.distribution?.id)
                    put("signers", org.json.JSONArray(addon.metadata.signers.toList()))
                },
            ),
        )
    }
    fun revoke(packageName: String) = locked {
        if (exists()) write(read().apply { remove(packageName) })
    }
    suspend fun approved(): List<AddonExtension> {
        if (!hasGrants()) return emptyList()
        val registry = AddonRegistry(context)
        registry.reload()
        val state = grants()
        return registry.state.value.filter { addon ->
            val grant = state.optJSONObject(addon.packageName)
            val array = grant?.optJSONArray("signers")
            val pins = if (array == null) emptySet() else (0 until array.length()).map { array.getString(it) }.toSet()
            addon.usable &&
                addon.dataRecovery &&
                grant != null &&
                grant.optString("distribution") == addon.metadata.distribution?.id &&
                pins.any { it in addon.metadata.signers || it in addon.metadata.signerHistory }
        }
    }
    fun caller(): String {
        val packages = context.packageManager.getPackagesForUid(Binder.getCallingUid()).orEmpty().toSet()
        require(packages.size == 1) { "A recovery grant requires an isolated application UID" }
        return runBlocking { approved() }.singleOrNull { it.packageName in packages }?.packageName
            ?: throw SecurityException("Data recovery has not been authorized")
    }
    private fun <T> locked(block: () -> T): T = synchronized(LOCK) {
        RandomAccessFile(File(context.filesDir, "recovery-access.lock"), "rw").use { handle ->
            handle.channel.lock().use { block() }
        }
    }
    private fun write(state: JSONObject) {
        val output = file.startWrite()
        try {
            output.write(state.toString().toByteArray())
            file.finishWrite(output)
        } catch (
            error: Throwable,
        ) {
            file.failWrite(output)
            throw error
        }
    }
    companion object {
        private val LOCK = Any()
    }
}
