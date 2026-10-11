package it.lorelore.nyanime.update

import android.content.Context
import android.os.Build
import eu.kanade.tachiyomi.data.updater.GITHUB_REPO
import eu.kanade.tachiyomi.data.updater.UpdateScreenPhase
import eu.kanade.tachiyomi.data.updater.delta.AndroidDeltaEnvironment
import eu.kanade.tachiyomi.data.updater.delta.DeltaPatch
import eu.kanade.tachiyomi.data.updater.delta.DeltaRecord
import eu.kanade.tachiyomi.data.updater.delta.DeltaUpdateEnvironment
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import okhttp3.OkHttpClient
import java.io.File
import java.io.IOException

/** Reconstructs only signed packages. Intermediate checkpoints are never installed or executed. */
internal class ReleaseGraphClient(
    private val environment: DeltaUpdateEnvironment,
    client: OkHttpClient,
    private val supportedAbis: List<String>,
) {
    constructor(context: Context, client: OkHttpClient) : this(
        AndroidDeltaEnvironment(context),
        client,
        Build.SUPPORTED_ABIS.toList(),
    )

    private val assets = ReleaseAssetClient(client)

    suspend fun prepare(
        url: String,
        directory: File,
        id: String,
        stage: suspend (UpdateScreenPhase) -> Unit,
        progress: (Int) -> Unit,
    ): File {
        val version = requireNotNull(ReleaseGraph.descriptorVersion(url, GITHUB_REPO))
        require(Regex("[a-zA-Z0-9-]{1,80}").matches(id))
        val job = File(directory, "route-$id").apply { check(mkdirs() || isDirectory) }
        listOf("base.tmp", "patch.tmp", "target.tmp").forEach { File(job, it).delete() }
        val output = File(directory, "update-$id.rebuilt")
        val context = currentCoroutineContext()
        var finished = false
        try {
            stage(UpdateScreenPhase.PREPARING)
            val graph = ReleaseGraph.parse(assets.text(url), GITHUB_REPO, environment.packageName, version)
            require(graph.versionCode > environment.installedVersionCode) { "Update is not newer" }
            val byHash = graph.packages.associateBy { it.sha256 }
            val available = mutableMapOf<String, File>()
            fun verify(file: File, item: ReleaseGraph.Package) {
                require(file.length() == item.size && DeltaPatch.hash(file) { context.ensureActive() } == item.sha256)
                environment.validateReference(file, item.versionCode, item.versionName)
            }
            // Revalidate after a process restart; filenames and previous work are not trust evidence.
            graph.packages.forEach { item ->
                val cached = File(job, item.sha256)
                if (cached.isFile) {
                    try {
                        verify(cached, item)
                        available[item.sha256] = cached
                    } catch (cancel: CancellationException) {
                        throw cancel
                    } catch (_: Exception) {
                        cached.delete()
                    }
                }
            }
            if (!environment.splitInstallation && environment.installedApk.isFile) {
                val installed = environment.installedApk
                if (graph.packages.any { it.size == installed.length() }) {
                    val hash = DeltaPatch.hash(installed) { context.ensureActive() }
                    byHash[hash]?.takeIf { it.versionCode == environment.installedVersionCode }?.let {
                        available[hash] = installed
                    }
                }
            }
            val failedPatches = mutableSetOf<String>()
            val failedBases = mutableSetOf<String>()
            var lastFailure: Exception? = null
            // Bound fallback attempts too: a hostile declaration cannot cause an unbounded download loop.
            repeat(3) {
                val route = graph.route(supportedAbis, available.keys, failedPatches, failedBases)
                    ?: throw IOException("No verified update route is available", lastFailure)
                var current = available[route.base.sha256]
                if (current == null) {
                    stage(UpdateScreenPhase.DOWNLOADING_FULL)
                    requireSpace(job, route.base.size)
                    val temporary = File(job, "base.tmp")
                    try {
                        assets.download(requireNotNull(route.base.url), temporary, route.base.size, progress)
                        verify(temporary, route.base)
                        current = File(job, route.base.sha256)
                        check(temporary.renameTo(current))
                        available[route.base.sha256] = current
                    } catch (cancel: CancellationException) {
                        throw cancel
                    } catch (error: Exception) {
                        temporary.delete()
                        context.ensureActive()
                        failedBases += route.base.sha256
                        lastFailure = error
                        return@repeat
                    }
                }
                var failed = false
                for (edge in route.steps) {
                    val from = byHash.getValue(edge.from)
                    val target = byHash.getValue(edge.to)
                    val patch = File(job, "patch.tmp")
                    val temporary = File(job, "target.tmp")
                    try {
                        requireSpace(job, target.size + edge.size)
                        stage(UpdateScreenPhase.DOWNLOADING_DELTA)
                        assets.download(edge.url, patch, edge.size, progress)
                        stage(UpdateScreenPhase.REBUILDING)
                        temporary.delete()
                        DeltaPatch.apply(
                            requireNotNull(current),
                            patch,
                            temporary,
                            DeltaRecord(
                                from.sha256,
                                from.size,
                                target.sha256,
                                target.size,
                                edge.sha256,
                                edge.size,
                                edge.url.substringAfterLast('/'),
                                ReleaseGraph.apkName(target.versionName, target.abi),
                            ),
                            { context.ensureActive() },
                        ) { written, size -> progress((written * 100 / size).toInt()) }
                        stage(UpdateScreenPhase.VERIFYING)
                        verify(temporary, target)
                        val ready = File(job, target.sha256)
                        ready.delete()
                        check(temporary.renameTo(ready))
                        // Keep the newest verified checkpoint for retries, not every reconstructed APK.
                        available.entries.removeAll { (_, file) ->
                            if (file.parentFile == job) {
                                file.delete()
                                true
                            } else {
                                false
                            }
                        }
                        available[target.sha256] = ready
                        current = ready
                    } catch (cancel: CancellationException) {
                        throw cancel
                    } catch (error: Exception) {
                        context.ensureActive()
                        failedPatches += edge.sha256
                        lastFailure = error
                        failed = true
                        break
                    } finally {
                        patch.delete()
                        temporary.delete()
                    }
                }
                if (!failed) {
                    stage(UpdateScreenPhase.VERIFYING)
                    val target = byHash.getValue(route.targetHash)
                    val ready = requireNotNull(current)
                    verify(ready, target)
                    environment.validate(ready, graph.versionCode, graph.versionName)
                    context.ensureActive()
                    require(ready != environment.installedApk)
                    output.delete()
                    if (!ready.renameTo(output)) throw IOException("Unable to finalize reconstructed update")
                    finished = true
                    return output
                }
            }
            throw IOException("Unable to reconstruct a verified update", lastFailure)
        } catch (cancel: CancellationException) {
            // Explicit cancellation drops only this job's files; other updates own different directories.
            job.listFiles()?.forEach { it.delete() }
            job.delete()
            throw cancel
        } finally {
            job.listFiles()?.filter { finished || it.name.endsWith(".tmp") }?.forEach { it.delete() }
            if (finished) job.delete()
        }
    }

    private fun requireSpace(directory: File, bytes: Long) {
        if (environment.availableSpace(directory) < bytes + 16 * 1024 * 1024) {
            throw IOException("Insufficient space for application update")
        }
    }

    companion object {
        fun discard(directory: File, id: String) {
            require(Regex("[a-zA-Z0-9-]{1,80}").matches(id))
            val job = File(directory, "route-$id")
            job.listFiles()?.filter { it.isFile }?.forEach { it.delete() }
            job.delete()
        }
    }
}
