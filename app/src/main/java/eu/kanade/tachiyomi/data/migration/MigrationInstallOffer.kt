package eu.kanade.tachiyomi.data.migration

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.core.content.pm.PackageInfoCompat
import eu.kanade.tachiyomi.BuildConfig
import eu.kanade.tachiyomi.R
import eu.kanade.tachiyomi.data.updater.GITHUB_REPO
import eu.kanade.tachiyomi.util.system.toast
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import tachiyomi.domain.release.interactor.GetApplicationRelease
import tachiyomi.domain.release.model.ReleaseVersion
import tachiyomi.domain.release.service.ReleaseService
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import java.util.concurrent.atomic.AtomicBoolean

internal object MigrationInstallPolicy {
    fun shouldOffer(legacy: Boolean, configured: Boolean, upgraded: Boolean, handled: Boolean, transferred: Boolean) =
        legacy && configured && upgraded && !handled && !transferred

    fun compatibleRelease(candidate: String?, installed: String): Boolean {
        val available = ReleaseVersion.parse(candidate) ?: return false
        val current = ReleaseVersion.installed(installed) ?: return false
        return available >= current
    }
}

internal object MigrationInstallOffer {
    private val opening = AtomicBoolean()
    fun shouldOffer(context: Context, configured: Boolean, upgraded: Boolean): Boolean {
        val state = MigrationState.state(context)
        return MigrationInstallPolicy.shouldOffer(
            context.packageName == BuildConfig.LEGACY_APPLICATION_ID,
            configured,
            upgraded,
            state.optBoolean("installOfferHandled"),
            state.optBoolean("sourceTransferComplete"),
        )
    }

    fun acknowledge(context: Context) = MigrationState.update(context) { it.put("installOfferHandled", true) }

    suspend fun open(context: Context, stillRequested: () -> Boolean = { true }): Boolean {
        if (!opening.compareAndSet(false, true)) return false
        try {
            return openAvailable(context, stillRequested)
        } catch (_: TimeoutCancellationException) {
            context.toast(context.getString(R.string.migration_install_check_failed))
            return false
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            context.toast(context.getString(R.string.migration_install_check_failed))
            return false
        } finally {
            opening.set(false)
        }
    }

    private suspend fun openAvailable(context: Context, stillRequested: () -> Boolean): Boolean {
        val target = BuildConfig.PRODUCTION_APPLICATION_ID
        val installed = MigrationState.verified(context, target, BuildConfig.PRODUCTION_CERT_SHA256) &&
            runCatching {
                PackageInfoCompat.getLongVersionCode(context.packageManager.getPackageInfo(target, 0)) >=
                    BuildConfig.VERSION_CODE
            }.getOrDefault(false)
        val intent = if (installed) {
            checkNotNull(context.packageManager.getLaunchIntentForPackage(target))
        } else {
            context.toast(context.getString(R.string.migration_install_checking))
            val release = withContext(Dispatchers.IO) {
                withTimeout(10_000) {
                    Injekt.get<ReleaseService>().latest(
                        GetApplicationRelease.Arguments(
                            isPreview = false,
                            commitCount = BuildConfig.COMMIT_COUNT.toInt(),
                            versionName = BuildConfig.VERSION_NAME,
                            repository = GITHUB_REPO,
                            forceCheck = true,
                            assetPrefix = "Nyanime-Production",
                        ),
                    )
                }
            }
            if (!stillRequested()) return false
            if (release == null ||
                !MigrationInstallPolicy.compatibleRelease(release.version, BuildConfig.VERSION_NAME)
            ) {
                context.toast(context.getString(R.string.migration_install_unavailable))
                return false
            }
            Intent(
                Intent.ACTION_VIEW,
                Uri.parse(release.downloadLink),
            )
        }
        if (!stillRequested()) return false
        context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        return true
    }
}

@Composable
internal fun MigrationInstallDialog(onLater: () -> Unit, onContinue: () -> Unit) {
    AlertDialog(
        onDismissRequest = onLater,
        title = { Text(stringResource(R.string.migration_install_title)) },
        text = {
            Text(
                stringResource(R.string.migration_install_body),
                Modifier.verticalScroll(rememberScrollState()),
            )
        },
        confirmButton = {
            Button(
                onClick = onContinue,
                colors = ButtonDefaults.buttonColors(contentColor = Color.White),
            ) { Text(stringResource(R.string.migration_install_open)) }
        },
        dismissButton = {
            TextButton(onClick = onLater) {
                Text(stringResource(R.string.migration_not_now), color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        },
    )
}
