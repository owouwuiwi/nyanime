package it.lorelore.nyanime.novel

import org.jsoup.Jsoup
import org.jsoup.nodes.Document

/** A source response becomes a relative, inert chapter before it can enter the navigator. */
object NovelOnlineHtml {
    data class Prepared(val bytes: ByteArray, val resources: Map<String, String>) {
        val sha256: String
            get() = NovelOnlineIdentity.digest(bytes)
    }

    fun prepare(
        chapter: NovelOnlineChapter,
        response: NovelOnlineBody,
        chapters: List<NovelOnlineChapter>,
    ): Prepared {
        require(chapters.size in 1..10_000)
        require(chapters.map { it.reference }.distinct().size == chapters.size)
        require(response.body.toByteArray(Charsets.UTF_8).size <= NovelHtmlPolicy.MAX_HTML_BYTES)
        require(response.body.count { it == '<' } <= 32_000 || !response.html)
        val document =
            if (response.html) {
                Jsoup.parse(response.body)
            } else {
                Document.createShell("").apply {
                    body()
                        .appendElement("div")
                        .attr("class", "nyanime-plain-text")
                        .text(response.body)
                }
            }
        // Only the chapter body belongs to the source. Reader typography and resources are owned
        // by the host; third-party style sheets cannot trigger additional network requests.
        document.outputSettings().prettyPrint(false).charset(Charsets.UTF_8)
        document.head().empty()
        document.title(chapter.title)
        document.select("style,link,meta,source,video,audio,button,input,textarea,select").remove()
        val localChapters = chapters.associate { it.reference to it.href }
        val usedResources = linkedMapOf<String, String>()
        document.allElements.forEach { element ->
            element.removeAttr("style").removeAttr("srcset").removeAttr("poster")
            element.removeAttr("background").removeAttr("data")
            if (element.hasAttr("src")) {
                val reference = response.resources[element.attr("src")]
                if (element.normalName() == "img" && reference != null) {
                    val href = NovelOnlineIdentity.resourceHref(reference)
                    usedResources[href] = reference
                    element.attr("src", "../$href")
                } else {
                    element.removeAttr("src")
                }
            }
            listOf("href", "xlink:href").forEach { attribute ->
                if (element.hasAttr(attribute)) {
                    val target = element.attr(attribute)
                    val direct = localChapters[target]
                    val base = target.substringBefore('#')
                    val relative = localChapters[base]
                    when {
                        target.startsWith('#') && target.length <= 8192 -> Unit
                        direct != null -> element.attr(attribute, "../$direct")
                        relative != null && '#' in target ->
                            element.attr(attribute, "../$relative#${target.substringAfter('#')}")
                        else -> element.removeAttr(attribute)
                    }
                }
            }
        }
        document.head().appendElement("meta").attr("charset", "UTF-8")
        if (!response.html) {
            document.head().appendElement("style").text(".nyanime-plain-text{white-space:pre-wrap}")
        }
        val bytes =
            NovelHtmlPolicy.sanitize(document.outerHtml().toByteArray(Charsets.UTF_8), xml = false)
        require(
            Jsoup.parse(bytes.toString(Charsets.UTF_8)).body().text().isNotBlank() ||
                usedResources.isNotEmpty(),
        ) {
            "Source returned an empty chapter"
        }
        return Prepared(bytes, usedResources)
    }
}
