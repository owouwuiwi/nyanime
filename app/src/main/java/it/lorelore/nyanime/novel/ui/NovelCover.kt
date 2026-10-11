package it.lorelore.nyanime.novel.ui

import android.net.Uri
import coil3.map.Mapper
import coil3.request.Options
import eu.kanade.tachiyomi.BuildConfig
import eu.kanade.tachiyomi.R
import tachiyomi.domain.entries.EntryCover
import java.io.File

internal data class NovelCover(val bookId: String, val file: File?, val revision: String) :
    EntryCover

internal class NovelCoverMapper : Mapper<NovelCover, Uri> {
    override fun map(data: NovelCover, options: Options): Uri =
        data.file?.let(Uri::fromFile)
            ?: Uri.parse(
                "android.resource://${BuildConfig.APPLICATION_ID}/${R.drawable.ic_novel_cover}",
            )
}
