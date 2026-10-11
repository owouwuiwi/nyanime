package eu.kanade.tachiyomi.data.backup.models

import kotlinx.serialization.Serializable
import kotlinx.serialization.protobuf.ProtoNumber

@Serializable
data class BackupHiddenResume(
    @ProtoNumber(1) val source: Long,
    @ProtoNumber(2) val url: String,
)

/** Null in older backups; a present empty state explicitly clears hidden items. */
@Serializable
data class BackupHiddenResumeState(
    @ProtoNumber(1) val entries: List<BackupHiddenResume> = emptyList(),
)
