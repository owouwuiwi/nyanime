package eu.kanade.tachiyomi.ui.main

import android.app.Activity
import android.content.Intent
import android.os.Bundle

/** The color-changing aliases must never own the activity containing navigation state. */
class LauncherActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        startActivity(
            Intent(
                this,
                if (eu.kanade.tachiyomi.data.migration.MigrationState.shouldOffer(this) ||
                    eu.kanade.tachiyomi.data.migration.MigrationState.frozen(this)
                ) {
                    eu.kanade.tachiyomi.data.migration.MigrationActivity::class.java
                } else {
                    MainActivity::class.java
                },
            ).addFlags(
                Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT or
                    Intent.FLAG_ACTIVITY_SINGLE_TOP,
            ),
        )
        finish()
        overridePendingTransition(0, 0)
    }
}
