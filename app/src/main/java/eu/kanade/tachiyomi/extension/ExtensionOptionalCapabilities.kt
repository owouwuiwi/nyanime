package eu.kanade.tachiyomi.extension

import eu.kanade.tachiyomi.animesource.AnimeSource
import eu.kanade.tachiyomi.data.rin.RinLoading
import eu.kanade.tachiyomi.source.MangaSource
import nyanime.extension.api.SourceCapabilitiesFactory

/** Optional classes are linked only by a supporting host. Failure retains the usable base sources. */
object ExtensionOptionalCapabilities {
    fun attach(loader: ClassLoader, factories: List<String>, sources: List<Any>): List<Any> {
        fun identities(values: List<Any>) = values.map {
            when (it) {
                is AnimeSource -> "anime:${it.id}"
                is MangaSource -> "manga:${it.id}"
                else -> error("Unsupported base source")
            }
        }
        var active = sources
        for (name in factories) {
            active = try {
                RinLoading.guard {
                    val factory = Class.forName(
                        name,
                        false,
                        loader,
                    ).getDeclaredConstructor().newInstance() as SourceCapabilitiesFactory
                    factory.attach(active).also { require(identities(it) == identities(sources)) }
                }
            } catch (cancel: kotlinx.coroutines.CancellationException) {
                throw cancel
            } catch (_: Exception) {
                active
            }
        }
        return active
    }
}
