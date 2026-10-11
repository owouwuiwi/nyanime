package it.lorelore.nyanime.source.document

/** Optional consumer for a source which also exposes a document resolver capability. */
interface SourceDocumentBackedSource {
    /** Resolver identifiers declared by this source, including a component it supplies itself. */
    val documentResolverIds: Set<String>

    /** The host binds this reader to this exact package, publisher, source and resolver. */
    fun attachDocuments(reader: SourceDocumentReader)
}

/** All operations use the host's consent and isolated document worker, including native browsing. */
interface SourceDocumentReader {
    suspend fun describe(reference: String, resolverId: String? = null): SourceDocumentDescriptor
    suspend fun document(reference: String, resolverId: String? = null): ResolvedSourceDocument
    suspend fun list(reference: String, resolverId: String? = null): List<SourceDocumentEntry>
}
