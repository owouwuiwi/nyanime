package eu.kanade.tachiyomi.data.updater.delta

import android.content.Context
import androidx.core.content.pm.PackageInfoCompat
import eu.kanade.tachiyomi.data.updater.AppUpdateApkValidator
import eu.kanade.tachiyomi.data.updater.GITHUB_REPO
import eu.kanade.tachiyomi.data.updater.UpdateHttpException
import eu.kanade.tachiyomi.data.updater.UpdateScreenPhase
import eu.kanade.tachiyomi.data.updater.awaitUpdateResponse
import eu.kanade.tachiyomi.network.GET
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import okhttp3.CookieJar
import okhttp3.OkHttpClient
import okhttp3.Response
import java.io.File
import java.io.IOException
import java.net.URI
import java.util.concurrent.TimeUnit

internal data class DeltaReleaseLocation(val directory: String, val target: String) {
    val versionName get() = target.removePrefix("Nyanime-Production-").substringBefore('-')
    companion object {
        fun from(url: String): DeltaReleaseLocation? = runCatching {
            val uri = URI(url)
            if (uri.scheme != "https" ||
                uri.host != "github.com" ||
                uri.port != -1 ||
                uri.rawQuery != null ||
                uri.rawFragment != null ||
                uri.rawUserInfo != null
            ) {
                return null
            }
            val prefix = "/$GITHUB_REPO/releases/download/"
            if (!uri.rawPath.startsWith(prefix)) return null
            val parts = uri.rawPath.removePrefix(prefix).split('/')
            if (parts.size != 2 || !Regex("v[0-9]+\\.[0-9]+\\.[0-9]+\\.[0-9]+").matches(parts[0])) return null
            val target = parts[1]
            if (!Regex(
                    "Nyanime-Production-${Regex.escape(
                        parts[0].removePrefix("v"),
                    )}-(universal|arm64-v8a|armeabi-v7a|x86|x86_64)\\.apk",
                ).matches(target)
            ) {
                return null
            }
            DeltaReleaseLocation(url.substringBeforeLast('/') + "/", target)
        }.getOrNull()
    }
}

/** Android identity/storage boundary; tests provide files without mocking the package manager. */
internal interface DeltaUpdateEnvironment {
    val packageName: String
    val installedApk: File
    val installedVersionCode: Long
    val splitInstallation: Boolean
    fun availableSpace(directory: File): Long
    fun validate(file: File, versionCode: Long, versionName: String)
}

private class AndroidDeltaEnvironment(private val context: Context) : DeltaUpdateEnvironment {
    override val packageName get() = context.packageName
    override val installedApk get() = File(context.applicationInfo.sourceDir)
    override val installedVersionCode get() = PackageInfoCompat.getLongVersionCode(
        context.packageManager.getPackageInfo(packageName, 0),
    )
    override val splitInstallation get() = !context.applicationInfo.splitSourceDirs.isNullOrEmpty()
    override fun availableSpace(directory: File) = directory.usableSpace
    override fun validate(
        file: File,
        versionCode: Long,
        versionName: String,
    ) = AppUpdateApkValidator.validate(context, file, versionCode, versionName)
}

internal class DeltaUpdateClient(private val environment: DeltaUpdateEnvironment, client: OkHttpClient) {
    constructor(context: Context, client: OkHttpClient) : this(AndroidDeltaEnvironment(context), client)
    private val transport = client.newBuilder().cache(null).cookieJar(CookieJar.NO_COOKIES)
        .followRedirects(false).followSslRedirects(false)
        .connectTimeout(10, TimeUnit.SECONDS).readTimeout(20, TimeUnit.SECONDS)
        .callTimeout(90, TimeUnit.SECONDS).build()

    suspend fun prepare(
        fullUrl: String,
        directory: File,
        id: String,
        stage: suspend (UpdateScreenPhase) -> Unit,
        progress: (Int) -> Unit,
    ): File? {
        val location = DeltaReleaseLocation.from(fullUrl) ?: return null
        val patch = File(directory, "update-$id.nyadelta")
        val output = File(directory, "update-$id.rebuilt")
        try {
            if (environment.splitInstallation) return null
            val base = environment.installedApk
            if (!base.isFile || base.length() !in 1..DeltaPatch.MAX_APK_SIZE) return null
            stage(UpdateScreenPhase.PREPARING)
            val manifest = response(location.directory + DeltaManifest.FILE_NAME, manifest = true).use { response ->
                if (!response.isSuccessful) return null
                val bytes = response.body.byteStream().use { stream ->
                    stream.readNBytesBounded(DeltaManifest.MAX_BYTES.toInt())
                }
                DeltaManifest.parse(bytes.toString(Charsets.UTF_8), environment.packageName, location.target)
            }
            if (manifest.versionCode <= environment.installedVersionCode) return null
            if (manifest.patches.none { it.target == location.target && it.baseSize == base.length() }) return null
            val coroutine = currentCoroutineContext()
            val record =
                manifest.select(location.target, base.length(), DeltaPatch.hash(base) { coroutine.ensureActive() })
                    ?: return null
            // Reserve room for both files and a little metadata. The base APK is never copied or modified.
            if (environment.availableSpace(directory) <
                record.targetSize + record.patchSize + 16 * 1024 * 1024
            ) {
                return null
            }
            stage(UpdateScreenPhase.DOWNLOADING_DELTA)
            response(location.directory + record.patch).use { response ->
                if (!response.isSuccessful) throw UpdateHttpException(response.code)
                val length = response.body.contentLength()
                require(length == -1L || length == record.patchSize) { "Unexpected delta length" }
                response.body.byteStream().use { input ->
                    patch.outputStream().use { destination ->
                        val buffer = ByteArray(64 * 1024)
                        var total = 0L
                        while (true) {
                            coroutine.ensureActive()
                            val count = input.read(buffer)
                            if (count == -1) break
                            total += count
                            require(total <= record.patchSize) { "Delta exceeds declared size" }
                            destination.write(buffer, 0, count)
                            progress((total * 100 / record.patchSize).toInt())
                        }
                        require(total == record.patchSize) { "Incomplete delta" }
                    }
                }
            }
            stage(UpdateScreenPhase.REBUILDING)
            output.delete()
            DeltaPatch.apply(base, patch, output, record, { coroutine.ensureActive() }) { written, size ->
                progress((written * 100 / size).toInt())
            }
            stage(UpdateScreenPhase.VERIFYING)
            environment.validate(output, manifest.versionCode, manifest.versionName)
            coroutine.ensureActive()
            return output
        } catch (cancel: CancellationException) {
            output.delete()
            throw cancel
        } catch (_: Exception) {
            output.delete()
            currentCoroutineContext().ensureActive()
            // Missing, hostile, incompatible, inefficient or interrupted patches all use the original APK.
            return null
        } finally {
            patch.delete()
        }
    }

    private suspend fun response(url: String, manifest: Boolean = false): Response {
        var next = url
        repeat(5) {
            currentCoroutineContext().ensureActive()
            val client = if (manifest) transport.newBuilder().callTimeout(12, TimeUnit.SECONDS).build() else transport
            val response = client.newCall(GET(next)).awaitUpdateResponse()
            if (response.code !in setOf(301, 302, 303, 307, 308)) return response
            val redirect = response.use { it.header("Location") } ?: throw IOException("Missing release redirect")
            val resolved = URI(next).resolve(redirect)
            require(resolved.scheme == "https" && resolved.rawUserInfo == null && resolved.port == -1)
            require(resolved.host in setOf("release-assets.githubusercontent.com", "objects.githubusercontent.com"))
            next = resolved.toASCIIString()
        }
        throw IOException("Too many release redirects")
    }

    private suspend fun java.io.InputStream.readNBytesBounded(limit: Int): ByteArray {
        val output = java.io.ByteArrayOutputStream()
        val buffer = ByteArray(8192)
        while (true) {
            currentCoroutineContext().ensureActive()
            val count = read(buffer)
            if (count == -1) break
            require(output.size() + count <= limit) { "Delta manifest exceeds limit" }
            output.write(buffer, 0, count)
        }
        return output.toByteArray()
    }
}
