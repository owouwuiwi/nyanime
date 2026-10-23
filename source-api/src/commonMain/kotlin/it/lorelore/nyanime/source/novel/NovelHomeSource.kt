package it.lorelore.nyanime.source.novel

/** Optional discovery capability. Sources without a Home remain searchable through NovelSource. */
interface NovelHomeSource {
    val homeSections: List<NovelHomeSection>

    /** Returns title metadata only. Implementations must not prepare chapter indexes or bodies. */
    suspend fun home(request: NovelHomeRequest): NovelSearchPage
}

data class NovelHomeSection(val id: String, val title: String) {
    init {
        require(id.isNotBlank() && id.length <= 128)
        require(title.isNotBlank() && title.length <= 512)
    }
}

data class NovelHomeRequest(val sectionId: String, val cursor: String? = null) {
    init {
        require(sectionId.isNotBlank() && sectionId.length <= 128)
        require(cursor == null || cursor.length <= 8192)
    }
}
