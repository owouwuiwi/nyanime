package it.lorelore.nyanime.source.document

/** An optional source capability which also supplies a reusable document block to Studio. */
interface SourceDocumentResolver {
    val documentResolverId: String
    val documentResolverName: String

    /** HTTPS host rules, including explicit *.suffix rules, displayed with the dependency. */
    val documentResolverHosts: Set<String>

    /** Runs in the isolated resolver worker. Only the explicitly brokered network is available. */
    suspend fun resolveDocument(
        reference: String,
        network: SourceDocumentNetwork,
    ): ResolvedSourceDocument
}

interface SourceDocumentNetwork {
    suspend fun request(request: SourceDocumentRequest): SourceDocumentResponse

    /** Hosts override this with a streaming HTTP body; the fallback remains strictly bounded. */
    suspend fun transfer(
        request: SourceDocumentRequest,
        consumer: SourceDocumentSink,
    ): SourceDocumentTransfer {
        val response = request(request)
        if (response.status in 200..299) {
            var offset = 0
            while (offset < response.body.size) {
                val end = minOf(response.body.size, offset + SourceDocumentSink.MAX_CHUNK_BYTES)
                consumer.write(response.body.copyOfRange(offset, end))
                offset = end
            }
        }
        return SourceDocumentTransfer(response.status, response.body.size.toLong())
    }
}

/** The host stages chunks privately and commits only after this operation returns successfully. */
interface SourceStreamingDocumentResolver {
    suspend fun describeDocument(
        reference: String,
        network: SourceDocumentNetwork,
    ): SourceDocumentDescriptor

    suspend fun transferDocument(
        descriptor: SourceDocumentDescriptor,
        network: SourceDocumentNetwork,
        output: SourceDocumentSink,
    )
}

data class SourceDocumentDescriptor(
    val reference: String,
    val title: String,
    val mimeType: String,
    val size: Long?,
    val sha256: String? = null,
) {
    init {
        require(reference.length in 1..8192 && title.length <= 2048 && mimeType.length <= 256)
        require(size == null || size in 0..MAX_TRANSFER_BYTES)
        require(sha256 == null || Regex("[a-fA-F0-9]{64}").matches(sha256))
    }

    companion object {
        const val MAX_TRANSFER_BYTES = 64L * 1024 * 1024
    }
}

interface SourceDocumentSink {
    suspend fun write(bytes: ByteArray)

    companion object {
        const val MAX_CHUNK_BYTES = 64 * 1024
    }
}

data class SourceDocumentTransfer(val status: Int, val transferred: Long) {
    init {
        require(status in 100..599 && transferred in 0..SourceDocumentDescriptor.MAX_TRANSFER_BYTES)
    }
}

/** Optional collection capability for public folders; not required by ordinary resolvers. */
interface SourceDocumentCollectionResolver {
    suspend fun listDocuments(
        reference: String,
        network: SourceDocumentNetwork,
    ): List<SourceDocumentEntry>
}

data class SourceDocumentEntry(val reference: String, val title: String, val mimeType: String) {
    init {
        require(reference.length in 1..8192 && title.length in 1..2048 && mimeType.length <= 256)
    }
}

data class SourceDocumentRequest(
    val url: String,
    val method: String = "GET",
    val headers: Map<String, String> = emptyMap(),
    val body: ByteArray = byteArrayOf(),
) {
    init {
        require(url.length in 1..8192 && method in setOf("GET", "POST"))
        require(body.size <= 256 * 1024 && headers.size <= 32)
        require(
            headers.all { (key, value) ->
                key.length in 1..128 &&
                    key.all { it.code in 33..126 } &&
                    value.length <= 8192 &&
                    value.none { it == '\r' || it == '\n' || it == '\u0000' }
            },
        )
    }
}

data class SourceDocumentResponse(val status: Int, val body: ByteArray) {
    init {
        require(status in 100..599 && body.size <= ResolvedSourceDocument.MAX_BYTES)
    }
}

data class ResolvedSourceDocument(
    val reference: String,
    val title: String,
    val mimeType: String,
    val bytes: ByteArray,
) {
    init {
        require(reference.length in 1..8192 && title.length <= 2048 && mimeType.length <= 256)
        require(bytes.size <= MAX_BYTES)
    }

    companion object {
        const val MAX_BYTES = 4 * 1024 * 1024
    }
}
