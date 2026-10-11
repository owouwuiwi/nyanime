package eu.kanade.presentation.util

import android.content.ActivityNotFoundException
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.UriHandler
import eu.kanade.tachiyomi.R
import eu.kanade.tachiyomi.util.system.toast

/** Keep unavailable Android link handlers from turning a user action into an app crash. */
internal class SafeUriHandler(
    private val delegate: UriHandler,
    private val onUnavailable: () -> Unit,
    private val documentUrl: String? = null,
) : UriHandler {
    override fun openUri(uri: String) {
        val destination =
            if (documentUrl == null) {
                uri.takeIf { it.isNotBlank() && it.none(Char::isISOControl) }
            } else {
                resolveDocumentLink(uri, documentUrl)
            }
        if (destination == null) {
            onUnavailable()
            return
        }
        try {
            delegate.openUri(destination)
        } catch (_: ActivityNotFoundException) {
            onUnavailable()
        } catch (_: IllegalArgumentException) {
            onUnavailable()
        } catch (_: SecurityException) {
            onUnavailable()
        }
    }
}

@Composable
internal fun rememberSafeUriHandler(documentUrl: String? = null): UriHandler {
    val context = LocalContext.current
    val delegate = LocalUriHandler.current
    return remember(context, delegate, documentUrl) {
        SafeUriHandler(delegate, { context.toast(context.getString(R.string.link_open_unavailable)) }, documentUrl)
    }
}

@Composable
internal fun ProvideDocumentLinks(documentUrl: String, content: @Composable () -> Unit) {
    CompositionLocalProvider(
        LocalUriHandler provides rememberSafeUriHandler(documentUrl),
        content = content,
    )
}
