package eu.kanade.tachiyomi.ui.deeplink.content

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.os.Bundle
import eu.kanade.tachiyomi.data.cast.CastController
import eu.kanade.tachiyomi.data.share.ContentLinks
import eu.kanade.tachiyomi.data.watch.WatchTogetherManager
import eu.kanade.tachiyomi.ui.main.MainActivity
import eu.kanade.tachiyomi.util.system.toast
import tachiyomi.i18n.aniyomi.AYMR

/** Reuse the existing main task so a received link does not create a second navigation stack. */
class ContentLinkActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (blockActiveSession(this, intent.data.toString())) {
            finish()
            return
        }
        startActivity(
            Intent(this, MainActivity::class.java).apply {
                action = Intent.ACTION_VIEW
                data = intent.data
                addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_NEW_TASK)
            },
        )
        finish()
    }

    companion object {
        /** Check before CLEAR_TOP can destroy an active player, including Android share targets. */
        fun blockActiveSession(context: Context, text: String?): Boolean {
            val link = text?.let(ContentLinks::decode) ?: return false
            if (link.itemUrl == null && link.medium != eu.kanade.tachiyomi.data.share.SharedMedium.TV) return false
            val cast = CastController.get(context).state.value
            if (!cast.active && !cast.connecting && !WatchTogetherManager.get(context).controller.active) return false
            context.toast(AYMR.strings.content_open_busy)
            return true
        }
    }
}
