package eu.kanade.tachiyomi.data.rin

import android.content.Context
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import java.io.File

@Serializable
data class RinMigrationState(
    val phase: String = "checking",
    val entries: List<RinEntry> = emptyList(),
    val prepared: List<RinRecord> = emptyList(),
    val error: Boolean = false,
    val working: Boolean = false,
)

/** Persist preparation before Android asks for removal. Cancelling or restarting cannot lose the handoff. */
class RinMigration(private val context: Context, private val repository: RinRepository) {
    private val preferences = context.getSharedPreferences("rin_migration", Context.MODE_PRIVATE)
    private val checkMutex = Mutex()
    private val mutable = MutableStateFlow(pending() ?: RinMigrationState(phase = "open"))
    val state = mutable.asStateFlow()

    private fun pending(): RinMigrationState? = runCatching {
        RinArchive.json.decodeFromString<RinMigrationState>(preferences.getString("pending", "")!!)
    }.getOrNull()?.takeIf { it.phase in setOf("intro", "prepare", "remove", "configure", "complete") }
        ?.copy(working = false, error = false)

    suspend fun check() = withContext(Dispatchers.IO) {
        checkMutex.withLock {
            if (state.value.phase != "open") return@withLock
            val pending = pending()
            if (pending != null) {
                mutable.value = pending
                return@withLock
            }
            // Cached migrations appear immediately; ordinary openings do not need an interstitial.
            val cached = repository.migrationEntries()
            if (cached.isNotEmpty()) {
                set(RinMigrationState(phase = "intro", entries = cached))
                return@withLock
            }
            withTimeoutOrNull(10_000) { repository.refresh() }
            val entries = repository.migrationEntries()
            if (entries.isNotEmpty()) set(RinMigrationState(phase = "intro", entries = entries))
        }
    }

    private fun set(value: RinMigrationState) {
        if (value.phase !in setOf("checking", "open")) {
            check(
                preferences.edit().putString(
                    "pending",
                    RinArchive.json.encodeToString(value.copy(working = false)),
                ).commit(),
            )
        }
        mutable.value = value
    }

    fun prepare() {
        if (state.value.working) return
        repository.scope.launch {
            set(state.value.copy(phase = "prepare", working = true, error = false))
            try {
                for (entry in state.value.entries) {
                    if (state.value.prepared.any { it.manifest.packageName == entry.manifest.packageName }) continue
                    val record = repository.prepare(entry)
                    withContext(Dispatchers.IO) {
                        val packageName = entry.manifest.packageName
                        val info = runCatching { context.packageManager.getApplicationInfo(packageName, 0) }.getOrNull()
                        val old = info?.sourceDir?.let(::File) ?: File(context.filesDir, "exts/$packageName.ext")
                        if (old.isFile) {
                            val recovery = File(context.filesDir, "rin/recovery").apply { mkdirs() }
                            old.copyTo(File(recovery, "$packageName.apk"), overwrite = true)
                        }
                    }
                    set(state.value.copy(prepared = state.value.prepared + record))
                }
                set(state.value.copy(phase = "remove", working = false))
            } catch (cancel: CancellationException) {
                throw cancel
            } catch (_: Exception) {
                set(state.value.copy(working = false, error = true))
            }
        }
    }

    fun configure() {
        if (state.value.working) return
        require(state.value.prepared.size == state.value.entries.size)
        if (state.value.entries.any { repository.legacyPresent(it.manifest.packageName) }) return
        repository.scope.launch {
            set(state.value.copy(phase = "configure", working = true, error = false))
            try {
                state.value.prepared.forEach { repository.activate(it) }
                set(state.value.copy(phase = "complete", working = false))
            } catch (cancel: CancellationException) {
                throw cancel
            } catch (_: Exception) {
                set(state.value.copy(working = false, error = true))
            }
        }
    }

    fun enter() {
        check(state.value.phase == "complete")
        check(preferences.edit().remove("pending").commit())
        mutable.value = state.value.copy(phase = "open")
    }

    companion object {
        @Volatile private var instance: RinMigration? = null
        fun get(context: Context, repository: RinRepository): RinMigration = instance ?: synchronized(this) {
            instance ?: RinMigration(context.applicationContext, repository).also { instance = it }
        }
    }
}
