package eu.kanade.tachiyomi.data.community

import android.content.Context
import java.io.File

/** Local-only references never pass public profile/post validation. */
internal class CommunityImageDrafts(context: Context) {
    private val directory = File(context.noBackupFilesDir, "community-image-drafts")

    fun save(bytes: ByteArray): String {
        require(bytes.size in 1..2_000_000)
        check(directory.isDirectory || directory.mkdirs())
        val key = PREFIX + sha256(bytes).hex()
        val destination = requireNotNull(file(key))
        if (!destination.exists()) {
            val temporary = File.createTempFile("image-", ".tmp", directory)
            try {
                temporary.outputStream().use { it.write(bytes) }
                check(temporary.renameTo(destination) || destination.isFile)
            } finally {
                temporary.delete()
            }
        }
        return key
    }

    fun file(value: String): File? = if (isDraft(value)) File(directory, value.removePrefix(PREFIX) + ".jpg") else null

    companion object {
        private const val PREFIX = "nyanime-image:"
        fun isDraft(value: String) = value.startsWith(PREFIX) &&
            value.removePrefix(PREFIX).matches(Regex("[a-f0-9]{64}"))
    }
}

internal fun CommunityProfile.validDraft() = copy(
    avatar = avatar.takeUnless(CommunityImageDrafts::isDraft).orEmpty(),
    banner = banner.takeUnless(CommunityImageDrafts::isDraft).orEmpty(),
).valid()

internal fun SocialPost.validDraft() = if (CommunityImageDrafts.isDraft(image)) {
    // A local image counts as content; retain all other limits, including the original text.
    copy(image = "", text = text.ifBlank { "image" }).valid()
} else {
    valid()
}
