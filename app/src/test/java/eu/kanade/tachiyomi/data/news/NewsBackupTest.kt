package eu.kanade.tachiyomi.data.news

import eu.kanade.tachiyomi.data.backup.models.Backup
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.decodeFromByteArray
import kotlinx.serialization.encodeToByteArray
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.protobuf.ProtoBuf
import nyanime.news.api.NewsCatalogId
import nyanime.news.api.NewsMedium
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

@OptIn(ExperimentalSerializationApi::class)
class NewsBackupTest {
    @Test fun `optional news field keeps older backup payloads readable`() {
        val bytes = ProtoBuf.encodeToByteArray(Backup(isLegacy = false))
        assertNull(ProtoBuf.decodeFromByteArray<Backup>(bytes).newsState)
    }

    @Test fun `news settings and mappings survive the existing protobuf envelope`() {
        val id = NewsCatalogId("catalog", "18", NewsMedium.MANGA)
        val state = NewsSnapshot(
            mappings = mapOf("topic" to setOf(id)),
            excluded = setOf(id),
            textScale = 1.2f,
            sources = mapOf("example" to NewsSourceSettings(alerts = NewsAlerts.PERSONAL)),
        )
        val bytes = ProtoBuf.encodeToByteArray(Backup(newsState = Json.encodeToString(state)))
        val decoded = ProtoBuf.decodeFromByteArray<Backup>(bytes)
        assertEquals(state, Json.decodeFromString<NewsSnapshot>(requireNotNull(decoded.newsState)))
    }

    @Test fun `excluding an interest excludes its related works as well`() {
        val root = NewsCatalogId("catalog", "1", NewsMedium.ANIME)
        val related = NewsCatalogId("catalog", "2", NewsMedium.MANGA)
        val snapshot = NewsSnapshot(relations = mapOf(NewsRules.catalogKey(root) to setOf(related)))
        assertEquals(setOf(root, related), NewsRules.personalIds(snapshot, setOf(root)))
        assertTrue(NewsRules.personalIds(snapshot.copy(excluded = setOf(root)), setOf(root)).isEmpty())
    }
}
