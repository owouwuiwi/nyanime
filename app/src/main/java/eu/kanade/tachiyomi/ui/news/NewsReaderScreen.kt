package eu.kanade.tachiyomi.ui.news

import android.content.Intent
import android.os.Bundle
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.Bookmark
import androidx.compose.material.icons.outlined.BookmarkBorder
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Link
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material.icons.outlined.TextFields
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.UriHandler
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import cafe.adriel.voyager.core.screen.Screen
import cafe.adriel.voyager.navigator.LocalNavigator
import cafe.adriel.voyager.navigator.currentOrThrow
import coil3.compose.AsyncImage
import com.halilibo.richtext.markdown.Markdown
import com.halilibo.richtext.ui.material3.RichText
import eu.kanade.domain.base.BasePreferences
import eu.kanade.tachiyomi.R
import eu.kanade.tachiyomi.data.news.NewsRepository
import eu.kanade.tachiyomi.data.news.NewsRules
import eu.kanade.tachiyomi.ui.base.activity.BaseActivity
import eu.kanade.tachiyomi.util.system.openInBrowser
import eu.kanade.tachiyomi.util.view.setComposeContent
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import nyanime.news.api.NewsBlock
import nyanime.news.api.NewsBlockKind
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get

data class NewsReaderScreen(val articleKey: String) : Screen {
    @Composable
    override fun Content() {
        val navigator = LocalNavigator.currentOrThrow
        NewsReaderContent(articleKey, onBack = { navigator.pop() })
    }
}

class NewsReaderActivity : BaseActivity() {
    init {
        registerSecureActivity(this)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val key = intent.getStringExtra(ARTICLE)?.takeIf { it.matches(Regex("[a-f0-9]{64}")) }
        if (key == null) {
            finish()
            return
        }
        setComposeContent { NewsReaderContent(key, onBack = ::finish) }
    }
    companion object {
        const val ARTICLE = "news_article"
    }
}

@OptIn(ExperimentalMaterial3Api::class, kotlinx.coroutines.FlowPreview::class)
@Composable
private fun NewsReaderContent(key: String, onBack: () -> Unit) {
    val repository = remember { Injekt.get<NewsRepository>() }
    val snapshot by repository.store.state.collectAsState()
    val item = snapshot.articles[key]
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }
    var loading by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf(false) }
    var retry by remember { mutableStateOf(0) }
    var saving by remember { mutableStateOf(false) }
    var fontDialog by remember { mutableStateOf(false) }
    var image by remember { mutableStateOf<String?>(null) }
    val list = rememberLazyListState()
    var restored by remember { mutableStateOf(false) }
    val close: () -> Unit = {
        scope.launch {
            try {
                if (restored &&
                    !Injekt.get<BasePreferences>().incognitoMode().get()
                ) {
                    repository.position(key, list.firstVisibleItemIndex, list.firstVisibleItemScrollOffset)
                }
            } catch (cancel: CancellationException) {
                throw cancel
            } catch (_: Exception) {
                snackbar.showSnackbar(context.getString(R.string.news_storage_error))
            } finally {
                onBack()
            }
        }
    }
    androidx.activity.compose.BackHandler(onBack = close)
    LaunchedEffect(key, retry) {
        loading = true
        error = false
        try {
            repository.initialize()
            repository.detail(key)
        } catch (
            cancel: CancellationException,
        ) {
            throw cancel
        } catch (_: Exception) {
            error = true
        } finally {
            loading = false
        }
    }
    LaunchedEffect(key, loading) {
        if (!loading && item != null && !restored) {
            list.scrollToItem(item.position.coerceIn(0, (item.article.blocks.size + 1).coerceAtLeast(0)), item.offset)
            restored = true
        }
    }
    LaunchedEffect(key, restored) {
        if (!restored || Injekt.get<BasePreferences>().incognitoMode().get()) return@LaunchedEffect
        snapshotFlow {
            list.firstVisibleItemIndex to list.firstVisibleItemScrollOffset
        }.distinctUntilChanged().debounce(400).collect { (index, offset) ->
            try {
                repository.position(key, index, offset)
            } catch (
                cancel: CancellationException,
            ) {
                throw cancel
            } catch (
                _: Exception,
            ) {
                snackbar.showSnackbar(context.getString(R.string.news_storage_error))
            }
        }
    }
    Scaffold(
        topBar = {
            TopAppBar(title = {
            }, navigationIcon = {
                IconButton(close) {
                    Icon(Icons.AutoMirrored.Outlined.ArrowBack, stringResource(R.string.news_close))
                }
            }, actions = {
                IconButton(onClick = {
                    fontDialog = true
                }) { Icon(Icons.Outlined.TextFields, stringResource(R.string.news_text_size)) }
                IconButton(onClick = {
                    val article = item?.article ?: return@IconButton
                    context.startActivity(
                        Intent.createChooser(
                            Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(
                                Intent.EXTRA_TEXT,
                                article.title + "\n" + article.url,
                            ),
                            context.getString(R.string.news_share),
                        ),
                    )
                }, enabled = item != null) { Icon(Icons.Outlined.Share, stringResource(R.string.news_share)) }
                IconButton(onClick = {
                    saving = true
                    scope.launch {
                        try {
                            repository.save(key, item?.saved != true)
                        } catch (
                            cancel: CancellationException,
                        ) {
                            throw cancel
                        } catch (
                            _: Exception,
                        ) {
                            snackbar.showSnackbar(context.getString(R.string.news_error))
                        } finally {
                            saving = false
                        }
                    }
                }, enabled = item != null && !saving) {
                    Icon(
                        if (item?.saved ==
                            true
                        ) {
                            Icons.Outlined.Bookmark
                        } else {
                            Icons.Outlined.BookmarkBorder
                        },
                        stringResource(
                            if (item?.saved ==
                                true
                            ) {
                                R.string.news_unsave
                            } else {
                                R.string.news_save
                            },
                        ),
                    )
                }
            })
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        if (item == null) {
            Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
                if (loading) CircularProgressIndicator() else Text(stringResource(R.string.news_error))
            }
        } else {
            val typography = MaterialTheme.typography
            val scale = snapshot.textScale
            val uriHandler = remember(context) {
                object : UriHandler {
                    override fun openUri(uri: String) {
                        if (NewsRules.webUrl(uri)) context.openInBrowser(uri)
                    }
                }
            }
            CompositionLocalProvider(LocalUriHandler provides uriHandler) {
                MaterialTheme(
                    typography = typography.copy(
                        bodyLarge = typography.bodyLarge.copy(
                            fontSize = typography.bodyLarge.fontSize * scale,
                            lineHeight =
                            typography.bodyLarge.lineHeight * scale,
                        ),
                        bodyMedium = typography.bodyMedium.copy(
                            fontSize = typography.bodyMedium.fontSize * scale,
                            lineHeight =
                            typography.bodyMedium.lineHeight * scale,
                        ),
                    ),
                ) {
                    LazyColumn(
                        Modifier.fillMaxSize().padding(padding),
                        state = list,
                        contentPadding = PaddingValues(20.dp),
                        verticalArrangement = Arrangement.spacedBy(20.dp),
                    ) {
                        item(key = "headline") {
                            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                                Text(
                                    item.article.publisher,
                                    style = MaterialTheme.typography.labelLarge,
                                    color = MaterialTheme.colorScheme.primary,
                                )
                                Text(item.article.title, style = MaterialTheme.typography.headlineMedium)
                                Text(
                                    item.article.publishedAt?.let(::newsDate)
                                        ?: stringResource(R.string.news_date_unknown),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                                item.article.author?.let {
                                    Text(
                                        stringResource(R.string.news_author, it),
                                        style = MaterialTheme.typography.bodySmall,
                                    )
                                }
                                if (item.saved) {
                                    Text(
                                        stringResource(R.string.news_saved_offline),
                                        style = MaterialTheme.typography.bodySmall,
                                    )
                                }
                                item.article.imageUrl?.let { NewsImage(it, null) { image = it } }
                                if (loading) {
                                    Text(
                                        stringResource(R.string.news_title) + "…",
                                        style = MaterialTheme.typography.bodySmall,
                                    )
                                }
                                if (error) {
                                    Text(
                                        stringResource(R.string.news_error),
                                        style = MaterialTheme.typography.bodySmall,
                                    )
                                    TextButton(onClick = { retry++ }) { Text(stringResource(R.string.news_retry)) }
                                }
                            }
                        }
                        if (item.article.blocks.isEmpty()) {
                            item(key = "excerpt") {
                                Text(item.article.excerpt.orEmpty(), style = MaterialTheme.typography.bodyLarge)
                            }
                        }
                        itemsIndexed(item.article.blocks, key = { index, _ -> "block:$index" }) { _, block ->
                            NewsArticleBlock(block, onImage = { image = it })
                        }
                        item(key = "attribution") {
                            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                                if (!item.article.fullText &&
                                    !loading
                                ) {
                                    Text(
                                        stringResource(R.string.news_excerpt_only),
                                        style = MaterialTheme.typography.bodyMedium,
                                    )
                                }
                                item.article.attribution?.let {
                                    Text(
                                        it,
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                                TextButton(onClick = {
                                    context.openInBrowser(item.article.url)
                                }) { Text(stringResource(R.string.news_read_original)) }
                                if (item.article.topics.isNotEmpty()) NewsTopicLinks(item)
                            }
                        }
                    }
                }
            }
        }
    }
    if (fontDialog) {
        var scale by remember { mutableFloatStateOf(snapshot.textScale) }
        AlertDialog(onDismissRequest = {
            fontDialog = false
        }, title = { Text(stringResource(R.string.news_text_size)) }, text = {
            Column {
                Text(
                    "Aa",
                    style = MaterialTheme.typography.headlineMedium.copy(
                        fontSize =
                        MaterialTheme.typography.headlineMedium.fontSize * scale,
                    ),
                )
                Slider(
                    scale,
                    { scale = it },
                    valueRange =
                    0.85f..1.6f,
                )
            }
        }, confirmButton = {
            TextButton(onClick = {
                scope.launch {
                    try {
                        repository.store.update { it.copy(textScale = scale) }
                    } catch (cancel: CancellationException) {
                        throw cancel
                    } catch (_: Exception) {
                        snackbar.showSnackbar(context.getString(R.string.news_storage_error))
                    }
                }
                fontDialog = false
            }) { Text(stringResource(R.string.news_close)) }
        })
    }
    image?.let { url ->
        Dialog(onDismissRequest = { image = null }, properties = DialogProperties(usePlatformDefaultWidth = false)) {
            var scale by remember { mutableFloatStateOf(1f) }
            var pan by remember { mutableStateOf(Offset.Zero) }
            Box(
                Modifier.fillMaxSize().background(Color.Black).pointerInput(url) {
                    detectTransformGestures { _, move, zoom, _ ->
                        scale = (scale * zoom).coerceIn(1f, 5f)
                        val moved = pan + move
                        val limitX = size.width * (scale - 1) / 2
                        val limitY = size.height * (scale - 1) / 2
                        pan = Offset(moved.x.coerceIn(-limitX, limitX), moved.y.coerceIn(-limitY, limitY))
                    }
                },
            ) {
                AsyncImage(
                    url,
                    null,
                    Modifier.fillMaxSize().graphicsLayer {
                        scaleX = scale
                        scaleY = scale
                        translationX =
                            pan.x
                        translationY = pan.y
                    },
                    contentScale = ContentScale.Fit,
                )
                IconButton(onClick = {
                    image = null
                }, modifier = Modifier.align(Alignment.TopEnd)) {
                    Icon(Icons.Outlined.Close, stringResource(R.string.news_close), tint = Color.White)
                }
            }
        }
    }
}

@Composable
private fun NewsArticleBlock(block: NewsBlock, onImage: (String) -> Unit) {
    val context = LocalContext.current
    when (block.kind) {
        NewsBlockKind.IMAGE -> block.url?.let { NewsImage(it, block.caption) { onImage(it) } }
        NewsBlockKind.MEDIA -> TextButton(onClick = {
            block.url?.takeIf(NewsRules::webUrl)?.let { context.openInBrowser(it) }
        }) {
            Icon(Icons.Outlined.Link, null)
            Text(block.text.ifBlank { stringResource(R.string.news_open_media) })
        }
        NewsBlockKind.HEADING -> Text(block.text, style = MaterialTheme.typography.titleLarge)
        NewsBlockKind.TEXT, NewsBlockKind.QUOTE -> RichText { Markdown(block.text) }
    }
}

@Composable
private fun NewsImage(url: String, caption: String?, onClick: () -> Unit) {
    var failed by remember(url) { mutableStateOf(false) }
    Column {
        Box(
            Modifier.fillMaxWidth().aspectRatio(
                1.6f,
            ).clip(
                RoundedCornerShape(16.dp),
            ).background(MaterialTheme.colorScheme.surfaceContainerLow).clickable(onClick = onClick),
            contentAlignment = Alignment.Center,
        ) {
            AsyncImage(url, caption, Modifier.fillMaxSize(), contentScale = ContentScale.Fit, onError = {
                failed = true
            }, onSuccess = {
                failed =
                    false
            })
            if (failed) {
                Text(
                    stringResource(R.string.news_image_missing),
                    Modifier.padding(16.dp),
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }
        caption?.let { Text(it, Modifier.padding(top = 6.dp), style = MaterialTheme.typography.bodySmall) }
    }
}
