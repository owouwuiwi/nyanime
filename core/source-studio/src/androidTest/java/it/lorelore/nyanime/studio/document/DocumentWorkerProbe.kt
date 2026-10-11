package it.lorelore.nyanime.studio.document

import android.app.Activity
import android.app.Instrumentation
import android.os.Bundle
import android.os.Process
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.json.JSONArray
import java.io.File
import java.util.concurrent.atomic.AtomicInteger

/** Self-targeted checks: external DEX, synthetic HTTP, and this test APK's own private files only. */
class DocumentWorkerProbe : Instrumentation() {
    private var externalConfiguration = ""
    override fun onCreate(arguments: Bundle?) {
        super.onCreate(arguments)
        externalConfiguration = arguments?.getString("configuration").orEmpty()
        start()
    }

    override fun onStart() {
        if (externalConfiguration.isNotBlank()) {
            val result = ExternalDocumentProbe.run(targetContext, externalConfiguration)
            finish(if (result.containsKey("failure")) Activity.RESULT_CANCELED else Activity.RESULT_OK, result)
            return
        }
        val passed = mutableListOf<String>()
        val report = Bundle()
        var outcome = Activity.RESULT_OK
        val root = File(targetContext.cacheDir, "document-worker-probe")
        var network: OkHttpClient? = null
        try {
            check(targetContext.packageName == "it.lorelore.nyanime.studio.test") {
                "Probe must target its own package"
            }
            check(root.mkdirs() || root.isDirectory)
            val apk = File(root, "fixture.zip")
            targetContext.assets.open("document-fixture.zip").use { input ->
                apk.outputStream().use(input::copyTo)
            }
            val factory = "it.lorelore.nyanime.fixture.documents.FictionalDocumentFactory"
            check(runCatching { Class.forName(factory, false, javaClass.classLoader) }.isFailure)
            passed += "component_absent_from_host_classpath"
            val invocation = StudioDocumentInvocation(
                apk,
                DocumentWorkerWire.hash(apk.readBytes()),
                listOf(factory),
                800101L,
                "fixture.document",
                setOf("fixture.invalid"),
            )
            val requests = AtomicInteger()
            val payload = "Synthetic chapter 日本語".toByteArray()
            network = OkHttpClient.Builder().addInterceptor { chain ->
                check(chain.request().url.host == "fixture.invalid")
                requests.incrementAndGet()
                Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1)
                    .code(200).message("Synthetic response").body(payload.toResponseBody()).build()
            }.build()
            val client = StudioDocumentWorkerClient(targetContext, network, 15_000)
            runBlocking {
                val result = client.run(invocation, "document", "normal") {}
                check(result.bytes.contentEquals(payload) && result.isolatedUid != Process.myUid())
                check(requests.get() == 1)
                passed += "isolated_external_dex_and_brokered_bytes"

                val sentinel = File(root, "private.txt").apply { writeText("Private test sentinel") }
                check(sentinel.canRead())
                val isolation = client.run(invocation, "document", "private:${sentinel.absolutePath}") {}
                check(isolation.bytes?.toString(Charsets.UTF_8) == "true:true")
                check(requests.get() == 1)
                passed += "worker_cannot_read_host_files_or_open_sockets"

                check(runCatching { client.run(invocation, "document", "forbidden-origin") {} }.isFailure)
                check(requests.get() == 1)
                passed += "undeclared_origin_denied_before_http"

                check(
                    runCatching {
                        client.run(invocation.copy(sha256 = "0".repeat(64)), "document", "normal") {}
                    }.isFailure,
                )
                check(requests.get() == 1)
                passed += "component_hash_checked_before_execution"

                val validations = AtomicInteger()
                check(
                    runCatching {
                        client.run(invocation, "document", "normal") {
                            check(validations.incrementAndGet() < 3) { "Consent revoked" }
                        }
                    }.isFailure,
                )
                passed += "revocation_prevents_publication"

                val shortClient = StudioDocumentWorkerClient(targetContext, network, 1500)
                val failure = runCatching { shortClient.run(invocation, "document", "loop") {} }.exceptionOrNull()
                check(failure is TimeoutCancellationException)
                val recovered = client.run(invocation, "document", "normal") {}
                check(recovered.bytes.contentEquals(payload))
                passed += "noncooperative_component_cancelled_and_next_worker_recovers"
            }
        } catch (failure: Throwable) {
            outcome = Activity.RESULT_CANCELED
            report.putString("failure", failure.stackTraceToString())
        } finally {
            network?.dispatcher?.executorService?.shutdown()
            network?.connectionPool?.evictAll()
            if (root.canonicalFile.parentFile == targetContext.cacheDir.canonicalFile) root.deleteRecursively()
            report.putString("passed", JSONArray(passed).toString())
            report.putInt("checks", passed.size)
            finish(outcome, report)
        }
    }
}
