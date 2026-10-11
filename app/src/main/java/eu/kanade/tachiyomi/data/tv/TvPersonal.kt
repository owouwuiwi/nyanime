package eu.kanade.tachiyomi.data.tv

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import nyanime.tv.api.TvChannelRef
import nyanime.tv.api.TvRules
import tachiyomi.core.common.preference.PreferenceStore

/** Preferences contain identities only: stream URLs, guide caches and authentication stay out. */
@Serializable
data class TvPersonal(
    val version: Int = 1,
    val favourites: List<TvChannelRef> = emptyList(),
    val recent: List<TvChannelRef> = emptyList(),
    val sources: Map<String, TvSelection> = emptyMap(),
    val gridGuide: Boolean = false,
) {
    fun validated(): TvPersonal {
        require(version == 1 && favourites.size <= 5000 && recent.size <= 30 && sources.size <= 128)
        require((favourites + recent).all(TvRules::valid))
        require(sources.all { (id, value) -> id.toLongOrNull()?.let { it != 0L } == true && value.valid() })
        return copy(favourites = favourites.distinct(), recent = recent.distinct())
    }
}

@Serializable
data class TvSelection(val country: String? = null, val language: String? = null, val category: String? = null) {
    fun valid(): Boolean = listOfNotNull(country, language, category).all {
        it.length in 1..128 &&
            it.none(Char::isISOControl)
    }
}

/** Uses the ordinary settings backup and restore transaction. No separate export format. */
class TvPreferences(store: PreferenceStore) {
    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }
    private val preference = store.getString("tv_personal_v1", "")
    private val mutableFault = MutableStateFlow(false)
    val fault = mutableFault.asStateFlow()
    private var lastValid = TvPersonal()
    val changes = preference.changes()

    @Synchronized
    fun read(): TvPersonal = try {
        val raw = preference.get()
        require(raw.length <= 1_000_000)
        val value = if (raw.isBlank()) TvPersonal() else json.decodeFromString<TvPersonal>(raw).validated()
        lastValid = value
        mutableFault.value = false
        value
    } catch (_: Exception) {
        // Keep the original bytes for recovery; a corrupt/imported setting must never crash Home.
        mutableFault.value = true
        lastValid
    }

    @Synchronized
    fun update(transform: (TvPersonal) -> TvPersonal): TvPersonal {
        val previous = read()
        check(!fault.value) { "TV settings need recovery" }
        val next = transform(previous).validated()
        val encoded = json.encodeToString(next)
        require(encoded.length <= 1_000_000)
        preference.set(encoded)
        lastValid = next
        return next
    }

    fun favourite(ref: TvChannelRef, enabled: Boolean) = update {
        it.copy(favourites = if (enabled) (it.favourites + ref).distinct() else it.favourites - ref)
    }

    fun visited(ref: TvChannelRef, incognito: Boolean) {
        if (!incognito) update { it.copy(recent = (listOf(ref) + it.recent).distinct().take(30)) }
    }

    fun moveFavourite(ref: TvChannelRef, offset: Int) = update { personal ->
        require(offset == -1 || offset == 1)
        val index = personal.favourites.indexOf(ref)
        val target = index + offset
        if (index < 0 || target !in personal.favourites.indices) {
            personal
        } else {
            val ordered = personal.favourites.toMutableList()
            ordered[index] = ordered[target]
            ordered[target] = ref
            personal.copy(favourites = ordered)
        }
    }
}
