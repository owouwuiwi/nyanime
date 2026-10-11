package eu.kanade.presentation.util

import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

/** Markdown links belong to their document, never to an Android file or executable intent. */
internal fun resolveDocumentLink(link: String, documentUrl: String): String? {
    if (link.any(Char::isISOControl)) return null
    val reference = link.trim().takeIf(String::isNotEmpty) ?: return null
    val resolved =
        reference.toHttpUrlOrNull()
            ?: documentUrl.toHttpUrlOrNull()?.resolve(reference)
            ?: return null
    if (resolved.username.isNotEmpty() || resolved.password.isNotEmpty()) return null
    return resolved.toString()
}
