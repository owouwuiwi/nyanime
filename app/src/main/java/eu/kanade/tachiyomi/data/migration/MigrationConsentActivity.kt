package eu.kanade.tachiyomi.data.migration

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.ui.res.stringResource
import eu.kanade.tachiyomi.BuildConfig
import eu.kanade.tachiyomi.R

class MigrationConsentActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (packageName != BuildConfig.LEGACY_APPLICATION_ID ||
            callingPackage != BuildConfig.PRODUCTION_APPLICATION_ID ||
            !MigrationState.verified(this, BuildConfig.PRODUCTION_APPLICATION_ID, BuildConfig.PRODUCTION_CERT_SHA256)
        ) {
            finish()
            return
        }
        setContent {
            MigrationPage(
                0,
                stringResource(R.string.migration_authorize_title),
                stringResource(R.string.migration_authorize_body),
            ) {
                Button(onClick = {
                    // Revalidate after the user decision; a matching package name alone never grants access.
                    if (MigrationState.verified(
                            this@MigrationConsentActivity,
                            BuildConfig.PRODUCTION_APPLICATION_ID,
                            BuildConfig.PRODUCTION_CERT_SHA256,
                        )
                    ) {
                        MigrationKeepAliveService.start(this@MigrationConsentActivity)
                        setResult(
                            RESULT_OK,
                            Intent().putExtra("grant", MigrationBridgeProvider.grant(this@MigrationConsentActivity)),
                        )
                    }
                    finish()
                }) { Text(stringResource(R.string.migration_authorize)) }
                TextButton(onClick = { finish() }) { Text(stringResource(R.string.migration_not_now)) }
            }
        }
    }
}
