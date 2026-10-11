package it.lorelore.nyanime.studio.document

import android.content.Context
import android.os.Bundle
import android.os.Process
import it.lorelore.nyanime.studio.SourceBytes
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import java.io.File

/** Opt-in, self-targeted ABI/transport probe. All provider data arrives in a private test input. */
internal object ExternalDocumentProbe {
    fun run(targetContext: Context, configuration: String): Bundle {
        val report = Bundle()
        var stage = "configuration"
        try {
            check(targetContext.packageName == "it.lorelore.nyanime.studio.test")
            require(configuration.matches(Regex("[a-zA-Z0-9._-]{1,80}")))
            val root = targetContext.cacheDir.canonicalFile
            val configFile = File(root, configuration).canonicalFile
            check(configFile.parentFile == root)
            val config = JSONObject(
                configFile.inputStream().use {
                    SourceBytes.read(it, 16 * 1024).toString(Charsets.UTF_8)
                },
            )
            val archive = File(root, config.getString("archive")).canonicalFile
            check(archive.parentFile == root && archive.length() in 1..16L * 1024 * 1024)
            val factories = config.getJSONArray("factories").let { value ->
                (0 until value.length()).map { value.getString(it) }
            }
            check(factories.size in 1..32)
            factories.forEach { check(runCatching { Class.forName(it, false, javaClass.classLoader) }.isFailure) }
            val hosts = config.getJSONArray("hosts").let { value ->
                (0 until value.length()).map { value.getString(it) }.toSet()
            }
            val invocation = StudioDocumentInvocation(
                archive,
                config.getString("apkSha256"),
                factories,
                config.getLong("sourceId"),
                config.getString("resolverId"),
                hosts,
            )
            val client = StudioDocumentWorkerClient(targetContext)
            runBlocking {
                val validate: suspend () -> Unit = {
                    check(DocumentWorkerWire.hash(archive.readBytes()) == invocation.sha256) {
                        "probe_archive_checksum"
                    }
                }
                val reference = config.getString("reference")
                stage = "describe"
                val metadata = client.run(invocation, "describe", reference, validate)
                stage = "verify_description"
                check(metadata.bytes == null) { "probe_unexpected_descriptor_body" }
                check(metadata.isolatedUid != Process.myUid()) { "probe_shared_uid" }
                stage = "document"
                val content = client.run(invocation, "document", reference, validate)
                stage = "verify"
                val bytes = requireNotNull(content.bytes)
                check(content.isolatedUid != Process.myUid() && bytes.isNotEmpty())
                if (config.has("expectedSize")) check(bytes.size.toLong() == config.getLong("expectedSize"))
                val hash = DocumentWorkerWire.hash(bytes)
                if (config.has("expectedSha256")) check(hash == config.getString("expectedSha256"))
                report.putInt("checks", 3)
                report.putInt("bytes", bytes.size)
                report.putString("sha256", hash)
                report.putString("result", "external DEX; isolated UID; brokered public HTTP")
            }
        } catch (failure: Throwable) {
            // No provider response, URL, key, content or account data is included in diagnostics.
            report.putString("failure", failure.javaClass.simpleName)
            report.putString("stage", stage)
            val message = failure.message.orEmpty()
            val prefix = "The document component could not complete the operation ("
            val category = message.removePrefix(prefix).removeSuffix(")")
            if (message.startsWith(prefix) && category.matches(Regex("[a-z_:]{1,64}"))) {
                report.putString("diagnostic", category)
            } else if (message.matches(Regex("probe_[a-z_]{1,64}"))) {
                report.putString("diagnostic", message)
            }
        }
        return report
    }
}
