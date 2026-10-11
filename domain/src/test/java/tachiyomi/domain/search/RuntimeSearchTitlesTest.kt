package tachiyomi.domain.search

import eu.kanade.tachiyomi.source.model.SManga
import io.kotest.matchers.shouldBe
import kotlinx.serialization.json.JsonObject
import org.junit.jupiter.api.Test

class RuntimeSearchTitlesTest {
    @Test
    fun `legacy implementations without optional metadata retain ordinary title search`() {
        val manga = object : SManga by SManga.create() {
            override var memo: JsonObject
                get() = throw AbstractMethodError("Optional metadata absent")
                set(value) = Unit
        }
        manga.title = "Synthetic Garden"
        manga.url = "/opaque/reference"
        manga.searchTitle(123).title shouldBe "Synthetic Garden"
        manga.searchTitle(123).aliases shouldBe emptyList()
        manga.searchTitle(123).key.contains(manga.url) shouldBe false
    }
}
