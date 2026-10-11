package eu.kanade.tachiyomi.data.recovery

import android.app.Application
import dev.mihon.injekt.patchInjekt
import eu.kanade.domain.DomainModule
import eu.kanade.tachiyomi.data.discovery.EpisodeEndingCueStore
import eu.kanade.tachiyomi.data.discovery.SqlEpisodeEndingCueStore
import eu.kanade.tachiyomi.di.AppModule
import eu.kanade.tachiyomi.di.PreferenceModule
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.InjektModule
import uy.kohesive.injekt.api.InjektRegistrar
import uy.kohesive.injekt.api.addSingletonFactory
import uy.kohesive.injekt.api.get

internal object RecoveryRuntime {
    private var writeMode: Boolean? = null

    @Synchronized fun initialize(app: Application, restore: Boolean) {
        writeMode?.let {
            check(it || !restore) { "Reconnect before changing recovery mode" }
            return
        }
        patchInjekt()
        Injekt.importModule(PreferenceModule(app))
        Injekt.importModule(AppModule(app, recoveryOnly = true, recoveryWrites = restore))
        Injekt.importModule(DomainModule())
        Injekt.importModule(object : InjektModule {
            override fun InjektRegistrar.registerInjectables() {
                addSingletonFactory<EpisodeEndingCueStore> { SqlEpisodeEndingCueStore(get()) }
            }
        })
        writeMode = restore
    }
}
