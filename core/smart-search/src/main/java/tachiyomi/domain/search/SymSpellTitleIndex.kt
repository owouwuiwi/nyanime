package tachiyomi.domain.search

import com.darkrockstudios.symspellkt.common.SpellCheckSettings
import com.darkrockstudios.symspellkt.common.Verbosity
import com.darkrockstudios.symspellkt.impl.SymSpell

/** Only names actually observed at runtime enter this bounded index. No language word lists. */
class SymSpellTitleIndex(private val capacity: Int = 5_000) {
    private var dictionary = spellChecker()
    private val records = LinkedHashMap<String, SearchTitle>()
    private val terms = mutableMapOf<String, MutableSet<String>>()
    private val prefixes = mutableMapOf<String, MutableSet<String>>()
    private val editions = mutableMapOf<List<String>, MutableSet<String>>()
    private var termAssociations = 0L

    @Synchronized
    fun replace(items: Collection<SearchTitle>) {
        records.clear()
        records.putAll(items.takeLastBounded().associateBy { it.key })
        rebuild()
    }

    @Synchronized
    fun add(items: Collection<SearchTitle>) {
        var changed = false
        var namesChanged = false
        items.forEach { item ->
            val previous = records[item.key]
            if (previous != item) {
                records.remove(item.key)
                records[item.key] = item
                if (previous != null && previous.names != item.names) namesChanged = true
                if (previous == null && !namesChanged) index(item)
                changed = true
            }
        }
        if (changed && (namesChanged || records.size > capacity || termAssociations > capacity.toLong() * 40)) {
            while (records.size > capacity) records.remove(records.keys.first())
            // SymSpell cannot remove individual terms. Rebuild once per batch so replaced names,
            // aliases and evicted identities disappear from every lookup without deleting shared terms.
            rebuild()
        }
    }

    @Synchronized
    fun candidates(query: String, medium: SearchMedium): List<SearchTitle> {
        val words = TitleNormalizer.words(query).split(' ').filter { it.isNotBlank() }
        val numbers = TitleNormalizer.numericParts(query)
        val baseNumbers = TitleNormalizer.trailingNumberBase(query)?.let(TitleNormalizer::numericParts)
        val eligible = if (numbers.isEmpty()) {
            null
        } else {
            editions.entries.filter {
                TitleNormalizer.preservesNumbers(numbers, it.key) || it.key == baseNumbers
            }
                .flatMapTo(mutableSetOf()) { it.value }
        }
        val evidence = mutableMapOf<String, Int>()
        fun record(matches: Collection<String>, weight: Int) {
            matches.forEach { key ->
                if (eligible == null || key in eligible) evidence[key] = (evidence[key] ?: 0) + weight
            }
        }
        eligible?.let { record(it, 2) }
        val compact = TitleNormalizer.compact(query)
        (words + compact + compact.take(7)).distinct().forEach { word ->
            terms[word]?.let { record(it, if (word == TitleNormalizer.compact(query)) 30 else 6) }
            if (word.length >= 3) prefixes[word.take(3)]?.let { record(it.take(600), 1) }
            if (word.length in 4..32 && word.none(Char::isDigit) && (word != compact || words.size == 1)) {
                val distance = if (word == compact.take(7) || word.length >= 8) 2.0 else 1.0
                dictionary.lookup(word, Verbosity.All, distance).take(20).forEach {
                    terms[it.term]?.let { matches -> record(matches, 3) }
                }
            }
        }
        return evidence.entries.sortedByDescending { it.value }.asSequence().mapNotNull { records[it.key] }
            .filter { item ->
                item.medium == medium &&
                    (
                        numbers.isEmpty() ||
                            item.names.any { name ->
                                val actual = TitleNormalizer.numericParts(name)
                                TitleNormalizer.preservesNumbers(numbers, actual) || actual == baseNumbers
                            }
                        )
            }.take(600).toList()
    }

    @Synchronized
    fun clear() {
        records.clear()
        rebuild()
    }

    private fun Collection<SearchTitle>.takeLastBounded() = toList().takeLast(capacity)

    private fun rebuild() {
        dictionary = spellChecker()
        terms.clear()
        prefixes.clear()
        editions.clear()
        termAssociations = 0L
        records.values.forEach(::index)
    }

    private fun index(item: SearchTitle) {
        item.names.forEach { name ->
            editions.getOrPut(TitleNormalizer.numericParts(name)) { linkedSetOf() }.add(item.key)
            val words = TitleNormalizer.words(name).split(' ')
            val compact = TitleNormalizer.compact(name)
            val values = words + compact.take(7) + compact
            values.filter { it.length in 1..128 }.distinct().forEach { term ->
                if (terms.getOrPut(term) { linkedSetOf() }.add(item.key)) {
                    termAssociations++
                    // Full joined titles remain exact keys. Short lexical prefixes avoid enormous
                    // SymSpell deletion buckets for titles sharing a long introduction.
                    if (term.length <= 32 && term.none(Char::isDigit) && (term in words || term == compact.take(7))) {
                        dictionary.createDictionaryEntry(term, 1)
                    }
                    if (term.length >= 3) prefixes.getOrPut(term.take(3)) { linkedSetOf() }.add(item.key)
                }
            }
        }
    }

    private fun spellChecker() = SymSpell(SpellCheckSettings(maxEditDistance = 2.0, prefixLength = 5, topK = 20))
}
