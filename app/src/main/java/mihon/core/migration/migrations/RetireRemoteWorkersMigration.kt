package mihon.core.migration.migrations

import android.app.Application
import android.app.NotificationManager
import eu.kanade.tachiyomi.util.system.workManager
import kotlinx.coroutines.guava.await
import mihon.core.migration.Migration
import mihon.core.migration.MigrationContext

internal class RetireRemoteWorkersMigration : Migration {
    override val version = 310f

    override suspend fun invoke(migrationContext: MigrationContext): Boolean {
        val context = migrationContext.get<Application>() ?: return false
        listOf(
            "eu.kanade.tachiyomi.data.cloud.CloudBackupWorker",
            "eu.kanade.tachiyomi.data.cloud.sync.CloudSyncWorker",
            "it.lorelore.nyanime.backup.remote.RemoteBackupWorker",
        ).forEach { worker ->
            context.workManager.cancelAllWorkByTag(worker).result.await()
        }
        context.getSystemService(NotificationManager::class.java)?.apply {
            deleteNotificationChannel("cloud-backups")
            deleteNotificationChannel("remote-backup")
        }
        return true
    }
}
