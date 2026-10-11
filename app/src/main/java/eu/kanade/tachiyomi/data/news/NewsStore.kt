package eu.kanade.tachiyomi.data.news

import android.content.Context
import android.util.AtomicFile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File

/** A separate, atomically replaced store. No disk or network work on the rendering/player thread. */
class NewsStore(context: Context) {
    private val file = AtomicFile(File(context.filesDir, "news-v1.json"))
    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }
    private val mutex = Mutex()
    private var loaded = false
    private val mutable = MutableStateFlow(NewsSnapshot())
    val state = mutable.asStateFlow()

    suspend fun load() = withContext(Dispatchers.IO) {
        mutex.withLock { readLocked() }
    }

    private fun readLocked() {
        if (loaded) return
        if (file.baseFile.exists() || File(file.baseFile.path + ".bak").exists()) {
            val bytes = file.openRead().use { it.readBytes() }
            // Corruption is surfaced, never silently replaced with an empty library of saved articles.
            mutable.value = json.decodeFromString<NewsSnapshot>(bytes.toString(Charsets.UTF_8)).also {
                require(it.version == 1)
            }
        }
        loaded = true
    }

    suspend fun update(transform: (NewsSnapshot) -> NewsSnapshot) = withContext(Dispatchers.IO) {
        mutex.withLock {
            readLocked()
            val next = transform(mutable.value)
            if (next == mutable.value) return@withLock
            val output = file.startWrite()
            try {
                output.write(json.encodeToString(next).toByteArray())
                file.finishWrite(output)
                mutable.value = next
            } catch (error: Throwable) {
                file.failWrite(output)
                throw error
            }
        }
    }

    suspend fun backup(): String {
        load()
        val snapshot = state.value
        return json.encodeToString(
            snapshot.copy(
                articles = snapshot.articles.filterValues { it.saved || it.read || it.position > 0 }
                    .mapValues { (_, item) ->
                        if (item.saved) item else item.copy(article = item.article.copy(blocks = emptyList()))
                    },
                // Never export a trust decision to a different installation.
                sources = snapshot.sources.mapValues { (_, value) -> value.copy(signers = emptySet()) },
            ),
        ).also { require(it.length <= 64 * 1024 * 1024) { "Saved news exceeds the backup limit" } }
    }

    suspend fun restore(value: String) {
        require(value.length <= 64 * 1024 * 1024)
        val restored = json.decodeFromString<NewsSnapshot>(value)
        require(restored.version == 1)
        restored.articles.forEach { (key, item) ->
            require(key == item.key)
            NewsRules.validate(item.article)
        }
        update { current ->
            current.copy(
                sources = current.sources +
                    restored.sources.mapValues { (id, settings) ->
                        val local = current.sources[id]?.takeIf { it.distribution == settings.distribution }
                        settings.copy(signers = local?.signers.orEmpty())
                    },
                articles = restored.articles + current.articles,
                mappings = restored.mappings + current.mappings,
                excluded = restored.excluded + current.excluded,
                excludedTitles = restored.excludedTitles + current.excludedTitles,
                works = restored.works + current.works,
                receipts = restored.receipts + current.receipts + restored.articles.keys,
                pending = emptySet(),
                pendingClassification = emptySet(),
                // The first post-restore fetch seeds a fresh baseline, including newly discovered sources.
                checks = emptyMap(),
                textScale = restored.textScale.coerceIn(0.85f, 1.6f),
            )
        }
    }
}
