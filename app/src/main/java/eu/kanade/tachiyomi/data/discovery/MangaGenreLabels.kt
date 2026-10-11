package eu.kanade.tachiyomi.data.discovery

import java.text.Normalizer
import java.util.Locale

/** Presentation-only genre equivalences. Source requests always retain the original filter value. */
object MangaGenreLabels {
    private val equivalents = mapOf(
        "Azione" to listOf("Action"),
        "Adulti" to listOf("Adult"),
        "Avventura" to listOf("Adventure"),
        "Amore tra ragazzi" to listOf("Boys Love", "BL"),
        "Commedia" to listOf("Comedy"),
        "Crimine" to listOf("Crime"),
        "Drammatico" to listOf("Drama", "Dramma"),
        "Amore tra ragazze" to listOf("Girls Love", "GL", "Yuri"),
        "Storico" to listOf("Historical", "Storia"),
        "Orrore" to listOf("Horror"),
        "Ragazze magiche" to listOf("Magical Girls", "Magical Girl"),
        "Maturo" to listOf("Mature"),
        "Medicina" to listOf("Medical"),
        "Mistero" to listOf("Mystery"),
        "Filosofico" to listOf("Philosophical"),
        "Psicologico" to listOf("Psychological"),
        "Romantico" to listOf("Romance", "Romantic", "Sentimentale"),
        "Fantascienza" to listOf("Sci-Fi", "Science Fiction", "Sci fi"),
        "Vita quotidiana" to listOf("Slice of Life", "Slice-of-life"),
        "Sport" to listOf("Sports", "Sportivo"),
        "Supereroi" to listOf("Superhero", "Superheroes"),
        "Thriller" to listOf("Suspense"),
        "Tragedia" to listOf("Tragedy", "Tragico"),
    ).flatMap { (label, aliases) -> (aliases + label).map { normalized(it) to label } }.toMap()

    fun display(value: String): String = equivalents[normalized(value)] ?: value.trim()

    fun key(value: String): String = normalized(display(value))

    fun distinct(values: Iterable<String>): List<String> = values.filter { it.isNotBlank() }
        .map(::display).distinctBy(::key).sortedWith(String.CASE_INSENSITIVE_ORDER)

    private fun normalized(value: String): String = Normalizer.normalize(value.trim(), Normalizer.Form.NFD)
        .replace(Regex("\\p{M}+"), "")
        .lowercase(Locale.ROOT)
        .replace(Regex("[^a-z0-9]+"), " ")
        .trim()
}
