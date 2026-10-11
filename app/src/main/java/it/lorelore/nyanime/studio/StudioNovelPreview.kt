package it.lorelore.nyanime.studio

import android.content.Context
import it.lorelore.nyanime.novel.source.NovelPreparationProgress
import it.lorelore.nyanime.novel.source.NovelSourceRegistration
import it.lorelore.nyanime.novel.ui.NovelReaderPreview
import it.lorelore.nyanime.studio.document.StudioDocumentComponents
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch

/** A fixed, explicitly approved draft session; it never registers or favorites a real book. */
internal object StudioNovelPreview {
    suspend fun open(
        context: Context,
        source: StudioSource,
        reference: String,
        allowScripts: Boolean,
        onProgress: (NovelPreparationProgress) -> Unit = {},
    ) {
        require(source.medium == StudioMedium.NOVEL && reference.isNotBlank())
        require(StudioValidation.source(source).isEmpty())
        val repository = SourceStudioRepository.get(context)
        val fingerprint = StudioDocuments.fingerprint(source)
        val index = StudioNovelIndex.get(context, repository)
        val implementation = StudioNovelSource(source, repository, index, true, allowScripts)
        suspend fun resolve(): NovelSourceRegistration {
            repository.awaitReady()
            val record =
                requireNotNull(
                    repository.records.value.singleOrNull { it.draft.uuid == source.uuid },
                )
            check(
                record.draft == source && StudioDocuments.fingerprint(record.draft) == fingerprint,
            ) {
                "The source draft changed"
            }
            check(!StudioBlueprints.hasScripts(source) || allowScripts || record.trusts(source)) {
                "Review JavaScript before previewing the source"
            }
            if (source.transport == StudioTransport.TELEGRAM) {
                StudioTelegramAccess.get(context).checkAccess(source, preview = true)
            }
            if (source.documentResolvers.isNotEmpty()) {
                StudioDocumentComponents.get(context).checkAccess(source)
            }
            return NovelSourceRegistration(
                implementation,
                "studio.${source.uuid}",
                fingerprint,
                "studio",
            )
        }
        val expected = resolve()
        // A long, resumable metadata scan is separate from the bounded reader/session creation.
        coroutineScope {
            val observer = launch {
                implementation.observePreparation(reference).collect(onProgress)
            }
            try {
                implementation.chapters(reference)
            } finally {
                observer.cancelAndJoin()
            }
        }
        resolve()
        NovelReaderPreview.open(context, expected, reference, ::resolve)
    }

    suspend fun pause(
        context: Context,
        source: StudioSource,
        reference: String,
        allowScripts: Boolean,
    ) {
        val repository = SourceStudioRepository.get(context)
        StudioNovelIndex.get(context, repository)
            .pause(StudioIndexRequest(source, reference, preview = true, scripts = allowScripts))
    }
}
