package it.lorelore.nyanime.studio

import android.content.Context
import eu.kanade.tachiyomi.data.rin.RinRecord
import eu.kanade.tachiyomi.data.rin.RinRepository
import eu.kanade.tachiyomi.extension.ExtensionPackageConsent
import it.lorelore.nyanime.source.document.SourceDocumentBackedSource
import it.lorelore.nyanime.source.novel.NovelSource
import it.lorelore.nyanime.source.telegram.TelegramBackedSource

/** One request builder serves initial activation and later permission changes. */
internal fun sourcePackageAccessRequest(
    context: Context,
    record: RinRecord,
    source: NovelSource,
): SourceCapabilityRequest {
    val app = context.applicationContext
    val owner = SourcePackageIdentity.owner(record, source.id)
    val documents = (source as? SourceDocumentBackedSource)?.documentResolverIds.orEmpty().toSet()
    require(documents.size <= 16 && documents.all { it.matches(Regex("[a-zA-Z][a-zA-Z0-9_.-]{0,159}")) })
    val telegram: (suspend () -> TelegramSourceReview)? = if (source is TelegramBackedSource) {
        TelegramPublicChannel.names(source.telegramChannels)
        val review: suspend () -> TelegramSourceReview = { StudioTelegramAccess.get(app).review(record, source) }
        review
    } else {
        null
    }
    return SourceCapabilityRequest(
        source.name,
        "${record.sha256}:${source.id}:${record.enabled}",
        "package:${owner.packageName}:${owner.publisher}:${owner.sourceId}",
        documents,
        telegram,
        validate = {
            check(
                RinRepository.get(app).installed.value.any {
                    it.manifest.packageName == record.manifest.packageName &&
                        it.sha256 == record.sha256 &&
                        it.publisherSigners == record.publisherSigners &&
                        it.enabled == record.enabled &&
                        it.trusted &&
                        ExtensionPackageConsent.trusted(it) &&
                        RinRepository.compatible(it.manifest, it.format)
                },
            ) { "Source changed while its access was being reviewed" }
        },
    )
}
