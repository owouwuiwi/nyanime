package eu.kanade.tachiyomi.data.updater

import eu.kanade.tachiyomi.data.updater.delta.DeltaReleaseLocation
import it.lorelore.nyanime.update.ReleaseGraph
import tachiyomi.domain.release.model.ReleaseVersion
import java.io.IOException
import java.net.ProtocolException
import javax.net.ssl.SSLException

class UpdateHttpException(val status: Int) : IOException("HTTP $status")

object UpdateDownloadPolicy {
    /** A persisted worker can restart after Android has already installed its target. */
    fun isAlreadyInstalled(url: String, installedName: String?): Boolean {
        val target = (DeltaReleaseLocation.from(url)?.versionName ?: ReleaseGraph.descriptorVersion(url, GITHUB_REPO))
            ?.let(ReleaseVersion::parse) ?: return false
        val installed = ReleaseVersion.installed(installedName) ?: return false
        return installed >= target
    }

    fun shouldRetry(error: Exception, attempt: Int): Boolean {
        if (attempt >= 2) return false
        return when (error) {
            is UpdateHttpException -> error.status == 408 || error.status == 429 || error.status in 500..599
            is SSLException, is ProtocolException -> false
            is IOException -> true
            else -> false
        }
    }

    fun progress(bytes: Long, total: Long): Int? =
        if (total > 0 && bytes >= 0) ((bytes.toDouble() / total) * 100).toInt().coerceIn(0, 100) else null
}
