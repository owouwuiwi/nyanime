package it.lorelore.nyanime.novel.ui

import android.text.format.DateFormat
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import eu.kanade.presentation.discovery.HomeResumeCard
import eu.kanade.presentation.motion.appMotionEnabled
import eu.kanade.presentation.privacy.privacyRegion
import eu.kanade.tachiyomi.R
import eu.kanade.tachiyomi.ui.privacy.PrivacyArea
import java.util.Date

@Composable
internal fun NovelHistoryContent(contentPadding: PaddingValues) {
    val context = LocalContext.current
    var query by rememberSaveable { mutableStateOf("") }
    var requested by rememberSaveable(query) { mutableIntStateOf(40) }
    var retry by remember { mutableIntStateOf(0) }
    val snapshot = rememberNovelShelf(query, requested, retry, kind = NovelShelfKind.HISTORY)
    val motion = appMotionEnabled()
    LazyColumn(
        Modifier.fillMaxSize().privacyRegion(PrivacyArea.HISTORY),
        contentPadding =
        PaddingValues(
            start = 20.dp,
            end = 20.dp,
            top = 12.dp,
            bottom = contentPadding.calculateBottomPadding() + 20.dp,
        ),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item("search") {
            OutlinedTextField(
                query,
                { query = it.take(256) },
                Modifier.fillMaxWidth(),
                singleLine = true,
                shape = RoundedCornerShape(18.dp),
                placeholder = { Text(stringResource(R.string.novel_library_search)) },
            )
        }
        if (snapshot.loading && snapshot.entries.isEmpty()) {
            item("loading") { CircularProgressIndicator(Modifier.padding(24.dp)) }
        }
        if (!snapshot.loading && !snapshot.error && snapshot.entries.isEmpty()) {
            item("empty") {
                Text(
                    stringResource(R.string.novel_history_empty),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        items(snapshot.entries, key = { it.id }, contentType = { "novel-history" }) { entry ->
            val point = entry.item.position
            val date =
                point
                    ?.updatedAt
                    ?.let { DateFormat.getDateFormat(context).format(Date(it)) }
                    .orEmpty()
            NovelReadingCard(
                entry,
                date,
                Modifier.then(if (motion) Modifier.animateItem() else Modifier),
            )
        }
        if (snapshot.more) {
            item("more") {
                TextButton({ requested += 40 }, Modifier.fillMaxWidth()) {
                    Text(stringResource(R.string.novel_load_more))
                }
            }
        }
        if (snapshot.error) {
            item("retry") {
                TextButton({ retry++ }, Modifier.fillMaxWidth()) {
                    Text(stringResource(R.string.novel_retry))
                }
            }
        }
    }
}

@Composable
internal fun NovelReadingCard(
    entry: NovelShelfEntry,
    detail: String = "",
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val progress =
        entry.fraction?.let { stringResource(R.string.novel_percent_read, (it * 100).toInt()) }
    val subtitle =
        listOfNotNull(progress, detail.takeIf(String::isNotBlank)).joinToString(" · ").ifBlank {
            stringResource(R.string.novel_saved_position)
        }
    HomeResumeCard(
        title = entry.item.book.title,
        subtitle = subtitle,
        onResume = { context.startActivity(NovelReaderActivity.intent(context, entry.id)) },
        progress = entry.fraction?.toFloat(),
        manga = true,
        modifier = modifier,
    ) { imageModifier ->
        AsyncImage(
            model = entry.cover,
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = imageModifier,
            placeholder = painterResource(R.drawable.ic_novel_cover),
            error = painterResource(R.drawable.ic_novel_cover),
        )
    }
}
