package eu.kanade.presentation.more

internal data class UpdateNoteSection(val title: String?, val markdown: String)

/** Keep Markdown intact, including nested lists and code, while separating release sections. */
internal fun updateReleaseNotes(content: String): List<UpdateNoteSection> {
    val result = mutableListOf<UpdateNoteSection>()
    val body = StringBuilder()
    var title: String? = null
    var fence: String? = null
    fun flush() {
        val text = body.toString().trim()
        if (text.isNotEmpty()) result += UpdateNoteSection(title, text)
        body.clear()
    }
    for (line in content.replace("\r\n", "\n").lines()) {
        val trimmed = line.trimStart()
        val marker = trimmed.take(3).takeIf { it == "```" || it == "~~~" }
        if (marker != null) {
            fence = if (fence == marker) null else fence ?: marker
            body.appendLine(line)
        } else if (fence == null && trimmed.matches(Regex("(?:#{1,6}\\s+)?Checksums?\\b.*", RegexOption.IGNORE_CASE))) {
            break
        } else if (fence == null && line.startsWith("### ")) {
            flush()
            title = line.removePrefix("### ").trim()
        } else if (fence == null && line.matches(Regex("## Novità di Nyanime .+"))) {
            // The version already has its own visual hierarchy; keep all actual release content.
        } else {
            body.appendLine(line)
        }
    }
    flush()
    return result
}
