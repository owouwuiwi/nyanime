package eu.kanade.tachiyomi.util.system

/** Shared ABI types are parent-first; extension implementations and private dependencies remain child-first. */
internal object ExtensionClassLoading {
    fun hostFirst(name: String?, addon: Boolean = false): Boolean = name != null &&
        (
            sharedPackages.any(name::startsWith) ||
                addon &&
                !(name.startsWith("androidx.") && ".compose." in name) &&
                addonPackages.any(name::startsWith)
            )

    private val addonPackages = listOf(
        "nyanime.addon.api.",
        "androidx.activity.",
        "androidx.lifecycle.",
        "androidx.savedstate.",
        "androidx.core.",
    )
    private val sharedPackages = listOf(
        "kotlin.",
        "kotlinx.coroutines.",
        "nyanime.tv.api.",
        "nyanime.news.api.",
        "nyanime.extension.api.",
        "it.lorelore.nyanime.source.novel.",
        "it.lorelore.nyanime.source.telegram.",
        "it.lorelore.nyanime.source.document.",
        "eu.kanade.tachiyomi.animesource.",
        "eu.kanade.tachiyomi.source.",
        "rx.",
        "okhttp3.",
        "okio.",
        "org.jsoup.",
    )
}
