package it.lorelore.nyanime.backup.remote

internal data class RemoteBackupFailure(val at: Long, val reason: String)

/** A later automatic success also supersedes an older failed manual attempt. */
internal fun latestRemoteBackupError(completedAt: Long, failures: List<RemoteBackupFailure>): String? =
    failures.filter { it.at > completedAt }.maxByOrNull { it.at }?.reason
