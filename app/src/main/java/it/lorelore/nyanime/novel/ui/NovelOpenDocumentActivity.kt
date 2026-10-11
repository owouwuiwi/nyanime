package it.lorelore.nyanime.novel.ui

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import eu.kanade.tachiyomi.BuildConfig

/** Untrusted Android entry point: forwards only a read-granted document, never caller extras. */
class NovelOpenDocumentActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val uri = intent.data
        if (
            intent.action == Intent.ACTION_VIEW &&
            uri?.scheme == "content" &&
            !uri.authority.orEmpty().startsWith(BuildConfig.APPLICATION_ID)
        ) {
            startActivity(NovelReaderActivity.importIntent(this, uri))
        }
        finish()
    }
}
