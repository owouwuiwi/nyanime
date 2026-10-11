package it.lorelore.nyanime.novel.source

import android.content.Context
import eu.kanade.domain.source.service.SourcePreferences
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.stateIn

/** Host-owned registries enforce their own activation, revision and execution consent. */
internal interface NovelSourceProvider {
    val id: String

    fun observe(): StateFlow<NovelSourceInventory>

    /** Declarations must be local-only: no APK constructors, source requests or engine startup. */
    fun declarations(): Flow<List<NovelSourceDeclaration>> = flowOf(emptyList())

    suspend fun resolve(id: Long): NovelSourceRegistration
}

internal object NovelSourceInventories {
    fun merge(
        snapshots: List<Pair<String, NovelSourceInventory>>,
        languages: Set<String>,
        adult: Boolean,
    ): NovelSourceInventory {
        require(
            snapshots.isNotEmpty() && snapshots.map { it.first }.distinct().size == snapshots.size,
        )
        val unavailable = snapshots.flatMapTo(mutableSetOf()) { it.second.unavailable }
        val registrations = snapshots.flatMap { (provider, snapshot) ->
            snapshot.sources.map { it.copy(providerId = provider) }
        }
        val collisions = registrations.groupBy { it.source.id }.filterValues { it.size > 1 }
        collisions.values.flatten().forEach { unavailable += it.packageName }
        return NovelSourceInventory(
            registrations.filter {
                it.source.id !in collisions &&
                    NovelSourceAvailability.visible(
                        it.source.lang,
                        it.source.isNsfw,
                        languages,
                        adult,
                    )
            },
            unavailable,
            snapshots.all { it.second.initialized },
        )
    }
}

/** APK, Studio and imported-format registries share the same ordinary reader/search pipeline. */
internal class NovelSources(
    context: Context,
    private val preferences: SourcePreferences,
    extraProviders: () -> List<NovelSourceProvider> = { emptyList() },
) {
    private val app = context.applicationContext
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val providers by lazy {
        (listOf(NovelPackageSources(app, preferences)) + extraProviders()).also { values ->
            require(values.map { it.id }.distinct().size == values.size)
            require(values.all { it.id.matches(Regex("[a-z][a-z0-9-]{0,63}")) })
        }
    }
    private val inventory by lazy {
        combine(
            combine(providers.map { provider -> provider.observe() }) { values ->
                providers.mapIndexed { index, provider -> provider.id to values[index] }
            },
            preferences.enabledLanguages().changes(),
            preferences.showNsfwSource().changes(),
        ) { snapshots, languages, adult ->
            NovelSourceInventories.merge(snapshots, languages, adult)
        }
            .stateIn(scope, SharingStarted.WhileSubscribed(5_000), NovelSourceInventory())
    }

    fun observe() = inventory

    private val availability by lazy {
        NovelSourceAvailability.observe(
            providers,
            preferences.enabledLanguages().changes(),
            preferences.showNsfwSource().changes(),
        )
            .stateIn(scope, SharingStarted.WhileSubscribed(5_000), false)
    }

    fun available() = availability

    suspend fun resolve(id: Long): NovelSourceRegistration {
        val previous =
            inventory.first { it.initialized }.sources.singleOrNull { it.source.id == id }
                ?: error("Novel source is unavailable")
        val provider = providers.single { it.id == previous.providerId }
        val current = provider.resolve(id)
        check(
            current.source.id == id &&
                current.packageName == previous.packageName &&
                current.revision == previous.revision,
        ) {
            "Novel source changed or is no longer enabled"
        }
        check(preferences.showNsfwSource().get() || !current.source.isNsfw)
        check(
            current.source.lang == "all" ||
                current.source.lang in preferences.enabledLanguages().get(),
        )
        return current.copy(providerId = provider.id)
    }
}
