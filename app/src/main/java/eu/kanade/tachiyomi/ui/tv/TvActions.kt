package eu.kanade.tachiyomi.ui.tv

import android.content.Context
import android.content.Intent
import eu.kanade.tachiyomi.data.share.ContentLink
import eu.kanade.tachiyomi.data.share.ContentLinks
import eu.kanade.tachiyomi.data.share.SharedMedium
import nyanime.tv.api.TvChannel

object TvActions {
    fun play(context: Context, channel: TvChannel) {
        val manager = eu.kanade.tachiyomi.data.watch.WatchTogetherManager.get(context)
        if (manager.controller.active) {
            if (!manager.selectTv(channel)) {
                android.widget.Toast.makeText(
                    context,
                    context.getString(eu.kanade.tachiyomi.R.string.tv_room_host_only),
                    android.widget.Toast.LENGTH_LONG,
                ).show()
            }
        } else {
            context.startActivity(TvPlayerActivity.intent(context, channel.ref))
        }
    }

    fun room(context: Context, channel: TvChannel) {
        eu.kanade.tachiyomi.data.watch.WatchTogetherManager.get(context).roomForTv(channel, context)
    }

    /** Links contain the opaque reference, never the resolved stream or request headers. */
    fun share(context: Context, channel: TvChannel) {
        val link = ContentLinks.encode(
            ContentLink(
                medium = SharedMedium.TV,
                sourceId = channel.ref.sourceId,
                entryUrl = channel.ref.id,
                title = channel.name,
            ),
        )
        context.startActivity(
            Intent.createChooser(
                Intent(Intent.ACTION_SEND).apply {
                    type = "text/plain"
                    putExtra(Intent.EXTRA_TEXT, "${channel.name}\n$link")
                },
                null,
            ),
        )
    }
}
