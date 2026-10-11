package eu.kanade.tachiyomi.data.backup.models

import kotlinx.serialization.Serializable
import kotlinx.serialization.protobuf.ProtoNumber

@Serializable
data class BackupExtension(
    @ProtoNumber(1) val pkgName: String,
    @ProtoNumber(2) val apk: ByteArray,
    @ProtoNumber(3) val format: String = "apk",
    @ProtoNumber(4) val rinSigner: String = "",
    @ProtoNumber(5) val rinRepository: String = "",
    @ProtoNumber(6) val rinEnabled: Boolean = true,
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false

        other as BackupExtension

        if (pkgName != other.pkgName ||
            format != other.format ||
            rinSigner != other.rinSigner ||
            rinRepository != other.rinRepository ||
            rinEnabled != other.rinEnabled
        ) {
            return false
        }
        if (!apk.contentEquals(other.apk)) return false

        return true
    }

    override fun hashCode(): Int {
        var result = 31 * pkgName.hashCode() + format.hashCode() + rinSigner.hashCode() + rinRepository.hashCode()
        result = 31 * result + apk.contentHashCode()
        result = 31 * result + rinEnabled.hashCode()
        return result
    }
}
