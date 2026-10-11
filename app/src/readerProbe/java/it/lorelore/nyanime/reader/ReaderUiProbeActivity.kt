package it.lorelore.nyanime.reader

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.lifecycleScope
import androidx.preference.PreferenceManager
import eu.kanade.presentation.reader.appbars.ReaderAppBars
import eu.kanade.presentation.theme.TachiyomiPreviewTheme
import eu.kanade.tachiyomi.data.database.models.manga.ChapterImpl
import eu.kanade.tachiyomi.ui.reader.ReaderViewModel
import eu.kanade.tachiyomi.ui.reader.model.ReaderChapter
import eu.kanade.tachiyomi.ui.reader.model.ReaderPage
import eu.kanade.tachiyomi.ui.reader.viewer.ReaderPageImageView
import it.lorelore.nyanime.reader.quality.QualityEngineId
import it.lorelore.nyanime.reader.quality.QualityReaderState
import it.lorelore.nyanime.reader.quality.QualityTitle
import it.lorelore.nyanime.reader.quality.ReaderQualityPreferences
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import tachiyomi.domain.entries.manga.model.Manga
import java.io.File

/** Test-only activity over generated artwork. No library, progress, source or tracking writes. */
class ReaderUiProbeActivity : ComponentActivity() {
    private val manga = Manga.create().copy(
        id = Long.MIN_VALUE + 8671,
        source = Long.MIN_VALUE + 8671,
        url = "/reader-ui-fixture-20261021",
        title = "The Paper Garden",
    )
    private val title = QualityTitle(manga.source, manga.url)
    private var imageView: ReaderPageImageView? = null
    private var panel by mutableStateOf<String?>(null)
    private var chromeVisible by mutableStateOf(true)
    private var pageIndex by mutableIntStateOf(0)
    private var chapterIndex by mutableIntStateOf(0)
    private var ready by mutableStateOf<File?>(null)
    private var bookmarked by mutableStateOf(false)
    private val preferences by lazy { ReaderQualityPreferences(PreferenceManager.getDefaultSharedPreferences(this)) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val dark = intent.getBooleanExtra("dark", true)
        val bars = SystemBarStyle.auto(Color.TRANSPARENT, Color.TRANSPARENT) { dark }
        enableEdgeToEdge(statusBarStyle = bars, navigationBarStyle = bars)
        check(eu.kanade.tachiyomi.BuildConfig.ISOLATED_CLOUD_PROBE)
        if (savedInstanceState == null) preferences.setOverride(title, QualityEngineId.OFF)
        pageIndex = savedInstanceState?.getInt("page") ?: 0
        chapterIndex = savedInstanceState?.getInt("chapter") ?: 0
        lifecycleScope.launch {
            ready = withContext(Dispatchers.IO) { artwork() }
        }
        setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(
                LocalDensity provides Density(density.density, intent.getFloatExtra("fontScale", 1f)),
            ) {
                TachiyomiPreviewTheme(darkTheme = intent.getBooleanExtra("dark", true)) {
                    Surface(color = MaterialTheme.colorScheme.background) {
                        Box(Modifier.fillMaxSize()) {
                            ready?.let { file ->
                                AndroidView(
                                    modifier = Modifier.fillMaxSize(),
                                    factory = { context ->
                                        ReaderPageImageView(context).also { view ->
                                            imageView = view
                                            val chapter = ReaderChapter(
                                                ChapterImpl().apply {
                                                    id = -8671
                                                    manga_id = manga.id
                                                    url = "/fixture/chapter"
                                                    name = "Chapter 01"
                                                },
                                            )
                                            val page = ReaderPage(0, stream = { file.inputStream() }).apply {
                                                this.chapter = chapter
                                            }
                                            chapter.state = ReaderChapter.State.Loaded(listOf(page))
                                            view.bindReadingPage(page, manga)
                                            view.onViewClicked = { chromeVisible = !chromeVisible }
                                            view.setImage(file, false, ReaderPageImageView.Config(220))
                                        }
                                    },
                                    onRelease = { it.recycle() },
                                )
                            }
                            ReaderAppBars(
                                chromeVisible, true, manga.title,
                                "Chapter ${chapterIndex + 1} · Generated fixture", ::finish, {},
                                bookmarked, { bookmarked = !bookmarked }, null,
                                { pageIndex = (pageIndex + 1).coerceAtMost(7) }, pageIndex < 7,
                                { pageIndex = (pageIndex - 1).coerceAtLeast(0) }, pageIndex > 0,
                                pageIndex + 1, 8, { pageIndex = it.coerceIn(0, 7) },
                                { panel = "chapters" }, { panel = "mode" }, { panel = "quality" }, { panel = "more" },
                            )
                            when (panel) {
                                "quality" -> ReaderQualityPanel(manga, { panel = null }, { panel = "more" })
                                "chapters" -> ReaderChaptersPanel(
                                    (0..19).map {
                                        ReaderViewModel.ChapterMenuItem(
                                            it.toLong(),
                                            "Chapter ${it + 1}",
                                            null,
                                            it < 2,
                                            it == 5,
                                        )
                                    },
                                    chapterIndex.toLong(),
                                    {
                                        chapterIndex = it.toInt()
                                        pageIndex = 0
                                        panel = null
                                    },
                                    { panel = null },
                                )
                                "mode" -> ReaderActionsPanel("Mode · Fixture", { panel = null }) {
                                    ReaderPanelAction(
                                        "Existing native image renderer",
                                        "Pinch to zoom and double tap the generated artwork.",
                                    ) { panel = null }
                                }
                                "more" -> ReaderActionsPanel("Reader verification", { panel = null }) {
                                    ReaderPanelAction(
                                        "Generated content only",
                                        "No personal library, history or tracker state is modified.",
                                    ) { panel = null }
                                }
                            }
                        }
                    }
                }
            }
            BackHandler(panel != null) { panel = null }
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putInt("page", pageIndex)
        outState.putInt("chapter", chapterIndex)
        super.onSaveInstanceState(outState)
    }

    override fun onDestroy() {
        imageView?.recycle()
        if (isFinishing) {
            preferences.setOverride(title, null)
            QualityReaderState.showOriginal(manga.id, false)
            ready?.delete()
        }
        super.onDestroy()
    }

    private fun artwork(): File {
        val file = File(cacheDir, "reader-ui-fixture.png")
        if (file.exists()) return file
        val bitmap = Bitmap.createBitmap(1024, 1536, Bitmap.Config.ARGB_8888)
        try {
            val canvas = Canvas(bitmap)
            canvas.drawColor(Color.rgb(249, 246, 237))
            val paint = Paint(Paint.ANTI_ALIAS_FLAG)
            val panels = listOf(
                android.graphics.RectF(32f, 32f, 992f, 450f),
                android.graphics.RectF(32f, 474f, 504f, 1040f),
                android.graphics.RectF(528f, 474f, 992f, 1040f),
                android.graphics.RectF(32f, 1064f, 992f, 1504f),
            )
            for ((index, rect) in panels.withIndex()) {
                paint.color = listOf(0xff354557, 0xffa8b7b0, 0xffd2bb9f, 0xff596876)[index].toInt()
                canvas.drawRect(rect, paint)
                paint.color = Color.rgb(30, 31, 35)
                paint.style = Paint.Style.STROKE
                paint.strokeWidth = 4f
                canvas.drawRect(rect, paint)
                paint.strokeWidth = 1f
                for (line in 0..28) {
                    val y = rect.top + 8 + line * (rect.height() - 16) / 28
                    canvas.drawLine(rect.left + 4, y, rect.right - 4, y + 7, paint)
                }
                paint.style = Paint.Style.FILL
                paint.color = Color.WHITE
                canvas.drawOval(rect.left + 42, rect.top + 42, rect.right - 42, rect.top + 176, paint)
                paint.color = Color.BLACK
                paint.textSize = 26f
                canvas.drawText("A page made only for verification.", rect.left + 56, rect.top + 118, paint)
            }
            file.outputStream().use { check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) }
        } finally {
            bitmap.recycle()
        }
        return file
    }
}
