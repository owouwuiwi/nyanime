package eu.kanade.presentation.more

import tachiyomi.domain.release.model.ReleaseHistoryEntry
import tachiyomi.domain.release.model.ReleaseVersion

internal enum class UpdateNoteCategory(val headings: Set<String>) {
    FEATURES(setOf("Novità", "Features")),
    FIXES(setOf("Correzioni", "Fixes")),
    PERFORMANCE(setOf("Prestazioni", "Performance")),
    INTERFACE(setOf("Interfaccia", "Interface")),
    COMPATIBILITY(setOf("Compatibilità", "Compatibility")),
    SECURITY(setOf("Sicurezza", "Security")),
    DISTRIBUTION(setOf("Distribuzione", "Distribution")),
    VERIFICATION(setOf("Verifiche", "Verification")),
    LIMITATIONS(setOf("Limitazioni", "Limitations")),
    ORIGINAL(setOf("Note originali", "Original notes")),
}

private fun noteCategory(heading: String) =
    UpdateNoteCategory.entries.firstOrNull { heading in it.headings }

internal data class UpdateNoteGroup(
    val category: UpdateNoteCategory,
    val markdown: String,
    // Only flat top-level list entries can be shortened without truncating Markdown blocks.
    val entries: List<String>? = null,
)

internal data class UpdateVersionNotes(
    val version: String,
    val title: String?,
    val groups: List<UpdateNoteGroup>,
    val prerelease: Boolean = false,
    val key: String = version,
)

internal const val UPDATE_NOTES_FORMAT = "<!-- nyanime-changelog:1 -->"

internal fun updateNotesDocument(version: String): String {
    val tag =
        version
            .takeIf { it.matches(Regex("v?\\d+\\.\\d+\\.\\d+\\.\\d+|r\\d+")) }
            ?.let { if (it.startsWith("r") || it.startsWith("v")) it else "v$it" } ?: "main"
    return "https://github.com/owouwuiwi/nyanime/blob/$tag/CHANGELOG.md"
}

/** Categories are authoritative only in the explicitly versioned format. Old notes stay intact. */
internal fun parseUpdateTimeline(version: String, content: String): List<UpdateVersionNotes> {
    if (content.lineSequence().none { it.trim() == UPDATE_NOTES_FORMAT }) {
        val versions = mutableListOf<UpdateVersionNotes>()
        var currentVersion = version.removePrefix("v")
        var title: String? = null
        val body = StringBuilder()
        fun flush() {
            if (body.isNotBlank()) {
                versions +=
                    UpdateVersionNotes(
                        currentVersion,
                        title,
                        listOf(
                            UpdateNoteGroup(UpdateNoteCategory.ORIGINAL, body.toString().trim()),
                        ),
                    )
            }
            body.clear()
        }
        updateReleaseNotes(content).forEach { section ->
            val numeric = section.title?.let(::versionInHeading)
            if (numeric != null) {
                flush()
                currentVersion = numeric
                title = section.title?.substringAfter(":", "")?.trim()?.takeIf(String::isNotBlank)
                body.appendLine(section.markdown)
            } else {
                section.title?.let { body.appendLine("### $it") }
                body.appendLine(section.markdown)
            }
            body.appendLine()
        }
        flush()
        return versions.ifEmpty { listOf(UpdateVersionNotes(currentVersion, null, emptyList())) }
    }
    val versions = mutableListOf<UpdateVersionNotes>()
    var currentVersion = version.removePrefix("v")
    var title: String? = null
    var category = UpdateNoteCategory.ORIGINAL
    var fence: String? = null
    val groups = mutableListOf<UpdateNoteGroup>()
    val body = StringBuilder()
    fun flushGroup() {
        val markdown = body.toString().trim()
        if (markdown.isNotEmpty()) {
            groups += UpdateNoteGroup(category, markdown, updateNoteEntries(markdown))
        }
        body.clear()
    }
    fun flushVersion() {
        flushGroup()
        if (groups.isNotEmpty()) {
            versions += UpdateVersionNotes(currentVersion, title, groups.toList())
        }
        groups.clear()
    }
    for (line in content.replace("\r\n", "\n").lines()) {
        val trimmed = line.trim()
        val marker = trimmed.take(3).takeIf { it == "```" || it == "~~~" }
        when {
            marker != null -> {
                fence = if (fence == marker) null else fence ?: marker
                body.appendLine(line)
            }
            fence != null -> body.appendLine(line)
            trimmed == UPDATE_NOTES_FORMAT || line.startsWith("## Novità di Nyanime ") -> Unit
            trimmed.matches(Regex("(?:#{1,6}\\s+)?Checksums?\\b.*", RegexOption.IGNORE_CASE)) ->
                break
            // Generated metadata is an action in the native page, not a changelog item.
            trimmed.startsWith("[Changelog completo](") -> Unit
            line.startsWith("### ") -> {
                val heading = line.removePrefix("### ")
                val numeric = versionInHeading(heading)
                if (numeric != null && ReleaseVersion.parse(numeric) != null) {
                    flushVersion()
                    currentVersion = numeric
                    title = heading.substringAfter(":", "").trim().takeIf(String::isNotBlank)
                    category = UpdateNoteCategory.ORIGINAL
                } else {
                    flushGroup()
                    category = UpdateNoteCategory.ORIGINAL
                    body.appendLine(line)
                }
            }
            line.startsWith("#### ") -> {
                val next = noteCategory(line.removePrefix("#### ").trim())
                flushGroup()
                category = next ?: UpdateNoteCategory.ORIGINAL
                if (next == null) {
                    body.appendLine(line)
                }
            }
            else -> body.appendLine(line)
        }
    }
    flushVersion()
    return versions.ifEmpty {
        listOf(UpdateVersionNotes(version.removePrefix("v"), null, emptyList()))
    }
}

private fun versionInHeading(heading: String) =
    Regex("(?<![\\d.])\\d+\\.\\d+\\.\\d+\\.\\d+(?![\\d.])").find(heading)?.value?.takeIf {
        ReleaseVersion.parse(it) != null
    }

/** Nested lists, code fences and paragraphs are preserved inside their parent entry. */
internal fun updateNoteEntries(markdown: String): List<String>? {
    val entries = mutableListOf<String>()
    val current = StringBuilder()
    var fence: String? = null
    for (line in markdown.lines()) {
        if (line.startsWith("- ") && fence == null) {
            if (current.isNotEmpty()) entries += current.toString().trimEnd()
            current.clear()
        } else if (current.isEmpty() && line.isNotBlank()) {
            return null
        } else if (line.isNotBlank() && !line.startsWith(" ") && fence == null) {
            return null
        }
        val marker = line.trimStart().take(3).takeIf { it == "```" || it == "~~~" }
        if (marker != null) fence = if (fence == marker) null else fence ?: marker
        current.appendLine(line)
    }
    if (current.isNotEmpty()) entries += current.toString().trimEnd()
    return entries.takeIf(List<String>::isNotEmpty)
}

/**
 * Seed notes render immediately. Optional history adds older versions without replacing the seed.
 */
internal fun mergeUpdateTimeline(
    installedVersion: String,
    targetVersion: String,
    seed: String,
    history: List<ReleaseHistoryEntry>,
): List<UpdateVersionNotes> {
    val installed = ReleaseVersion.installed(installedVersion)
    val target = ReleaseVersion.parse(targetVersion)
    val metadata = history.associateBy { it.version.removePrefix("v") }
    val entries =
        parseUpdateTimeline(targetVersion, seed) +
            history.flatMap { parseUpdateTimeline(it.version, it.info) }
    return entries
        .filter { entry ->
            val numeric = ReleaseVersion.parse(entry.version)
            installed == null ||
                target == null ||
                numeric == null ||
                (numeric > installed && numeric <= target)
        }
        .distinctBy { it.version }
        .map {
            it.copy(prerelease = metadata[it.version]?.prerelease ?: false)
        }
        .sortedWith(compareByDescending { ReleaseVersion.parse(it.version) })
}

/** All documented changes, including historical revisions whose APK is no longer published. */
internal fun parseUpdateArchive(markdown: String): List<UpdateVersionNotes> {
    val blocks = Regex("(?m)^## (.+)$").findAll(markdown).toList()
    return blocks.mapIndexedNotNull { index, match ->
        val heading = match.groupValues[1].trim()
        val body =
            markdown.substring(
                match.range.last + 1,
                blocks.getOrNull(index + 1)?.range?.first ?: markdown.length,
            )
        if (
            !heading.any(Char::isDigit) &&
            body.lineSequence().none {
                it.startsWith("### ") && noteCategory(it.removePrefix("### ").trim()) != null
            }
        ) {
            return@mapIndexedNotNull null
        }
        val numeric = versionInHeading(heading)
        val label = numeric ?: heading.substringBefore(":")
        val formatted = body.replace(Regex("(?m)^### "), "#### ")
        val parsed =
            parseUpdateTimeline(
                numeric ?: "0.0.0.0",
                "$UPDATE_NOTES_FORMAT\n### ${numeric ?: "0.0.0.0"}: $heading\n$formatted",
            )
                .singleOrNull() ?: return@mapIndexedNotNull null
        parsed.copy(
            version = label,
            title =
            if (numeric != null) {
                heading.substringAfter(":", "").trim().takeIf(String::isNotBlank)
            } else {
                null
            },
            key = "archive-$index-$heading",
        )
    }
}
