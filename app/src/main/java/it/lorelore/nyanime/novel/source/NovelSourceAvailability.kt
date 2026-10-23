package it.lorelore.nyanime.novel.source

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine

/** Local registry metadata, not a provider instance or permission to execute a source. */
internal data class NovelSourceDeclaration(val id: Long, val language: String, val adult: Boolean)

internal object NovelSourceAvailability {
    fun visible(language: String, nsfw: Boolean, languages: Set<String>, adult: Boolean) =
        (adult || !nsfw) && (language == "all" || language in languages)

    fun observe(
        providers: List<NovelSourceProvider>,
        languages: Flow<Set<String>>,
        adult: Flow<Boolean>,
    ): Flow<Boolean> =
        combine(
            combine(
                providers.map { provider ->
                    provider.declarations().catch { failure ->
                        if (failure is CancellationException) throw failure
                        emit(emptyList())
                    }
                },
            ) { values ->
                values.flatMap { it }
            },
            languages,
            adult,
        ) { declarations, enabledLanguages, showAdult ->
            val counts = declarations.groupingBy { it.id }.eachCount()
            declarations.any {
                counts[it.id] == 1 && visible(it.language, it.adult, enabledLanguages, showAdult)
            }
        }
}
