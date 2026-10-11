package eu.kanade.tachiyomi.extension.anime.installer

import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageInstaller
import android.os.Build
import androidx.core.content.ContextCompat
import eu.kanade.tachiyomi.extension.InstallStep
import eu.kanade.tachiyomi.util.lang.use
import eu.kanade.tachiyomi.util.system.getParcelableExtraCompat
import eu.kanade.tachiyomi.util.system.getUriSize
import logcat.LogPriority
import tachiyomi.core.common.util.system.logcat

class PackageInstallerInstallerAnime(private val service: Service) : InstallerAnime(service) {

    private val packageInstaller = service.packageManager.packageInstaller

    private val packageActionReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val sessionId = activeSession?.second ?: return
            if (intent.action != INSTALL_ACTION ||
                intent.getIntExtra(PackageInstaller.EXTRA_SESSION_ID, -1) != sessionId
            ) {
                return
            }
            when (intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)) {
                PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                    val userAction = intent.getParcelableExtraCompat<Intent>(Intent.EXTRA_INTENT)
                    if (userAction == null) {
                        logcat(LogPriority.ERROR) { "Fatal error for $intent" }
                        finishSession(InstallStep.Error)
                        return
                    }
                    userAction.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    try {
                        service.startActivity(userAction)
                    } catch (error: RuntimeException) {
                        logcat(LogPriority.ERROR, error) { "Unable to open the system installer confirmation" }
                        runCatching { packageInstaller.abandonSession(sessionId) }
                        finishSession(InstallStep.Error)
                    }
                }
                PackageInstaller.STATUS_FAILURE_ABORTED -> {
                    finishSession(InstallStep.Idle)
                }
                PackageInstaller.STATUS_SUCCESS -> finishSession(InstallStep.Installed)
                else -> finishSession(InstallStep.Error)
            }
        }
    }

    private var activeSession: Pair<Entry, Int>? = null

    private fun finishSession(result: InstallStep) {
        activeSession = null
        continueQueue(result)
    }

    // Always ready
    override var ready = true

    override fun processEntry(entry: Entry) {
        super.processEntry(entry)
        activeSession = null
        try {
            val installParams = PackageInstaller.SessionParams(
                PackageInstaller.SessionParams.MODE_FULL_INSTALL,
            )
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                installParams.setRequireUserAction(
                    PackageInstaller.SessionParams.USER_ACTION_REQUIRED,
                )
            }
            val fileSize = service.getUriSize(entry.uri) ?: throw IllegalStateException()
            installParams.setSize(fileSize)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                installParams.setPackageSource(PackageInstaller.PACKAGE_SOURCE_DOWNLOADED_FILE)
            }
            activeSession = entry to packageInstaller.createSession(installParams)

            val inputStream = service.contentResolver.openInputStream(entry.uri) ?: throw IllegalStateException()
            val session = packageInstaller.openSession(activeSession!!.second)
            val outputStream = session.openWrite(entry.downloadId.toString(), 0, fileSize)
            session.use {
                arrayOf(inputStream, outputStream).use {
                    inputStream.copyTo(outputStream)
                    session.fsync(outputStream)
                }

                val intentSender = PendingIntent.getBroadcast(
                    service,
                    activeSession!!.second,
                    Intent(INSTALL_ACTION).setPackage(service.packageName),
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) PendingIntent.FLAG_MUTABLE else 0,
                ).intentSender
                session.commit(intentSender)
            }
        } catch (e: Exception) {
            logcat(LogPriority.ERROR, e) { "Failed to install extension ${entry.downloadId} ${entry.uri}" }
            activeSession?.let { (_, sessionId) ->
                runCatching { packageInstaller.abandonSession(sessionId) }
            }
            finishSession(InstallStep.Error)
        }
    }

    override fun cancelEntry(entry: Entry): Boolean {
        activeSession?.let { (activeEntry, sessionId) ->
            if (activeEntry == entry) {
                packageInstaller.abandonSession(sessionId)
                return false
            }
        }
        return true
    }

    override fun onDestroy() {
        service.unregisterReceiver(packageActionReceiver)
        super.onDestroy()
    }

    init {
        ContextCompat.registerReceiver(
            service,
            packageActionReceiver,
            IntentFilter(INSTALL_ACTION),
            ContextCompat.RECEIVER_NOT_EXPORTED,
        )
    }
}

private const val INSTALL_ACTION = "PackageInstallerInstallerAnime.INSTALL_ACTION"
