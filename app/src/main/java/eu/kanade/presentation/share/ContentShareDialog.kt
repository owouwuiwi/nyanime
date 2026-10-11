package eu.kanade.presentation.share

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.os.bundleOf
import androidx.fragment.app.FragmentActivity
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import eu.kanade.presentation.motion.ModernMotion
import eu.kanade.presentation.motion.modernMotionEnabled
import eu.kanade.tachiyomi.data.share.ContentLink
import eu.kanade.tachiyomi.data.share.ContentLinks
import eu.kanade.tachiyomi.data.share.SharedMedium
import eu.kanade.tachiyomi.util.system.toast
import eu.kanade.tachiyomi.util.view.setComposeContent
import tachiyomi.i18n.aniyomi.AYMR
import tachiyomi.presentation.core.i18n.stringResource
import java.util.Locale
import tachiyomi.core.common.i18n.stringResource as localized

/** A native sheet shared by details, player and reader; opening it does not change playback state. */
class ContentShareDialog : BottomSheetDialogFragment() {
    override fun onStart() {
        super.onStart()
        if (requireActivity().window.attributes.flags and WindowManager.LayoutParams.FLAG_SECURE != 0) {
            dialog?.window?.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        }
        (dialog as? BottomSheetDialog)?.behavior?.apply {
            skipCollapsed = true
            state = BottomSheetBehavior.STATE_EXPANDED
        }
        (dialog as? BottomSheetDialog)?.findViewById<View>(com.google.android.material.R.id.design_bottom_sheet)
            ?.setBackgroundColor(android.graphics.Color.TRANSPARENT)
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        val link = ContentLinks.decode(requireArguments().getString(ARG_LINK).orEmpty())
        return ComposeView(requireContext()).apply {
            setComposeContent {
                if (link != null) {
                    Surface(
                        color = MaterialTheme.colorScheme.surfaceContainerLow,
                        shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp),
                    ) {
                        ContentSharePanel(link) { selected, copy ->
                            val encoded = ContentLinks.encode(selected)
                            val context = requireContext()
                            if (copy) {
                                val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                                clipboard.setPrimaryClip(ClipData.newPlainText(selected.title, encoded))
                                context.toast(context.localized(AYMR.strings.content_link_copied))
                            } else {
                                val message = buildString {
                                    append(selected.title)
                                    selected.itemTitle?.let { append(" — ").append(it) }
                                    selected.positionMs?.takeIf { it > 0 }?.let { position ->
                                        append(" · ")
                                        append(formatLinkTime(position))
                                    }
                                    selected.page?.let {
                                        append(" · ")
                                            .append(context.localized(AYMR.strings.content_share_page, it))
                                    }
                                    append('\n').append(encoded)
                                }
                                try {
                                    context.startActivity(
                                        Intent.createChooser(
                                            Intent(Intent.ACTION_SEND).apply {
                                                type = "text/plain"
                                                putExtra(Intent.EXTRA_TEXT, message)
                                                putExtra(Intent.EXTRA_TITLE, selected.title)
                                            },
                                            context.localized(AYMR.strings.content_share_title),
                                        ),
                                    )
                                } catch (_: android.content.ActivityNotFoundException) {
                                    context.toast(context.localized(AYMR.strings.content_share_unavailable))
                                    return@ContentSharePanel
                                }
                            }
                            dismiss()
                        }
                    }
                }
            }
        }
    }

    companion object {
        private const val ARG_LINK = "content_link"
        private const val TAG = "nyanime.content.share"

        fun show(context: Context, link: ContentLink) {
            val encoded = runCatching { ContentLinks.encode(link) }.getOrNull()
            if (encoded == null) {
                context.toast(context.localized(AYMR.strings.content_share_unavailable))
                return
            }
            var current = context
            while (current is ContextWrapper && current !is FragmentActivity) current = current.baseContext
            val activity = current as? FragmentActivity ?: return
            val manager = activity.supportFragmentManager
            if (activity.isFinishing || manager.isStateSaved || manager.findFragmentByTag(TAG) != null) return
            ContentShareDialog().apply { arguments = bundleOf(ARG_LINK to encoded) }.show(manager, TAG)
        }
    }
}

@Composable
private fun ContentSharePanel(link: ContentLink, onSend: (ContentLink, Boolean) -> Unit) {
    val choices = buildList {
        add(link.entryOnly() to stringResource(AYMR.strings.content_share_entry))
        if (link.itemUrl != null) {
            add(
                link.itemFromStart() to stringResource(
                    if (link.medium ==
                        SharedMedium.ANIME
                    ) {
                        AYMR.strings.content_share_episode
                    } else {
                        AYMR.strings.content_share_chapter
                    },
                ),
            )
            if (link.positionMs != null && link.positionMs > 0) {
                add(link to stringResource(AYMR.strings.content_share_time, formatLinkTime(link.positionMs)))
            } else if (link.page != null && link.page > 1) {
                add(link to stringResource(AYMR.strings.content_share_page, link.page))
            }
        }
    }
    var selected by rememberSaveable { mutableIntStateOf(choices.lastIndex) }
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        val compact = maxWidth >= 480.dp && maxHeight <= 480.dp
        Column(
            modifier = Modifier.fillMaxWidth()
                .navigationBarsPadding().padding(horizontal = 24.dp, vertical = 20.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Box(
                Modifier.align(Alignment.CenterHorizontally).size(width = 36.dp, height = 4.dp)
                    .background(
                        MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f),
                        RoundedCornerShape(2.dp),
                    ),
            )
            if (compact) {
                Row(
                    Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(24.dp),
                ) {
                    ShareInformation(link, true, Modifier.weight(1f))
                    ShareChoices(choices, selected, { selected = it }, Modifier.weight(1f))
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    ShareAction(choices[selected].first, false, onSend, Modifier.weight(1f))
                    ShareAction(choices[selected].first, true, onSend, Modifier.weight(1f))
                }
            } else {
                Column(
                    Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    ShareInformation(link, false)
                    ShareChoices(choices, selected, { selected = it })
                }
                ShareAction(choices[selected].first, false, onSend, Modifier.fillMaxWidth())
                ShareAction(choices[selected].first, true, onSend, Modifier.fillMaxWidth())
            }
        }
    }
}

@Composable
private fun ShareInformation(link: ContentLink, compact: Boolean, modifier: Modifier = Modifier) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(
            stringResource(AYMR.strings.content_share_title),
            style = if (compact) MaterialTheme.typography.titleMedium else MaterialTheme.typography.headlineSmall,
        )
        Text(
            link.title,
            style = MaterialTheme.typography.titleLarge,
            maxLines = if (compact) 3 else 4,
            overflow = TextOverflow.Ellipsis,
        )
        link.itemTitle?.let {
            Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Text(
            stringResource(AYMR.strings.content_share_help),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun ShareChoices(
    choices: List<Pair<ContentLink, String>>,
    selected: Int,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    val motionEnabled = modernMotionEnabled()
    Column(modifier, verticalArrangement = Arrangement.spacedBy(12.dp)) {
        choices.forEachIndexed { index, (_, label) ->
            val color by animateColorAsState(
                if (index == selected) {
                    MaterialTheme.colorScheme.secondaryContainer
                } else {
                    MaterialTheme.colorScheme.surfaceContainer
                },
                animationSpec = if (motionEnabled) tween(ModernMotion.RESIZE_MILLIS) else snap(),
                label = "shareChoice",
            )
            Surface(shape = MaterialTheme.shapes.large, color = color) {
                Row(
                    Modifier.fillMaxWidth().selectable(index == selected, role = Role.RadioButton) { onSelect(index) }
                        .padding(horizontal = 12.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    RadioButton(index == selected, onClick = null)
                    Text(label, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                }
            }
        }
    }
}

@Composable
private fun ShareAction(
    link: ContentLink,
    copy: Boolean,
    onSend: (ContentLink, Boolean) -> Unit,
    modifier: Modifier,
) {
    if (copy) {
        OutlinedButton(onClick = { onSend(link, true) }, modifier = modifier) {
            Icon(Icons.Outlined.ContentCopy, contentDescription = null)
            Text(stringResource(AYMR.strings.content_share_copy), Modifier.padding(start = 8.dp))
        }
    } else {
        Button(onClick = { onSend(link, false) }, modifier = modifier) {
            Icon(Icons.Outlined.Share, contentDescription = null)
            Text(stringResource(AYMR.strings.content_share_send), Modifier.padding(start = 8.dp))
        }
    }
}

internal fun formatLinkTime(positionMs: Long): String {
    val seconds = positionMs / 1000
    return if (seconds >= 3600) {
        String.format(Locale.ROOT, "%d:%02d:%02d", seconds / 3600, seconds / 60 % 60, seconds % 60)
    } else {
        String.format(Locale.ROOT, "%d:%02d", seconds / 60, seconds % 60)
    }
}
