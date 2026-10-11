package eu.kanade.tachiyomi.ui.base.activity

import android.content.Context
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import eu.kanade.domain.base.BasePreferences
import eu.kanade.tachiyomi.ui.base.delegate.SecureActivityDelegate
import eu.kanade.tachiyomi.ui.base.delegate.SecureActivityDelegateImpl
import eu.kanade.tachiyomi.ui.base.delegate.ThemingDelegate
import eu.kanade.tachiyomi.ui.base.delegate.ThemingDelegateImpl
import eu.kanade.tachiyomi.ui.privacy.PrivacyDisplayController
import eu.kanade.tachiyomi.ui.privacy.PrivacyDisplayPreferences
import eu.kanade.tachiyomi.util.system.prepareTabletUiContext
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get

open class BaseActivity :
    AppCompatActivity(),
    SecureActivityDelegate by SecureActivityDelegateImpl(),
    ThemingDelegate by ThemingDelegateImpl() {

    var privacyDisplayController: PrivacyDisplayController? = null
        private set

    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(
            if (eu.kanade.tachiyomi.data.migration.MigrationState.deferredStartup(
                    newBase,
                )
            ) {
                newBase
            } else {
                newBase.prepareTabletUiContext()
            },
        )
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        if (eu.kanade.tachiyomi.data.migration.MigrationState.deferredStartup(this)) {
            setTheme(eu.kanade.tachiyomi.R.style.Theme_Tachiyomi)
            super.onCreate(savedInstanceState)
            return
        }
        applyAppTheme(this)
        super.onCreate(savedInstanceState)
        privacyDisplayController = PrivacyDisplayController(
            this,
            Injekt.get<PrivacyDisplayPreferences>(),
            Injekt.get<BasePreferences>().incognitoMode(),
        )
    }
}
