package eu.kanade.tachiyomi.data.backup.models

import kotlinx.serialization.Serializable
import kotlinx.serialization.protobuf.ProtoNumber

@Serializable
data class BackupReleaseSubscription(
    @ProtoNumber(1) val medium: String,
    @ProtoNumber(2) val source: Long,
    @ProtoNumber(3) val url: String,
    @ProtoNumber(4) val mode: String,
    @ProtoNumber(5) val availability: Boolean = true,
    @ProtoNumber(6) val reminder: Boolean = true,
)
