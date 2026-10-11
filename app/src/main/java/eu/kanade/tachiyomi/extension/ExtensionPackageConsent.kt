package eu.kanade.tachiyomi.extension

import eu.kanade.domain.extension.ExtensionCatalogueTrust
import eu.kanade.tachiyomi.data.rin.RinRecord
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get

object ExtensionPackageConsent {
    fun trusted(record: RinRecord): Boolean = record.trusted &&
        (
            record.catalogueConsent == null ||
                ExtensionCatalogueTrust(Injekt.get()).permits(record.repository, record.catalogueConsent)
            )
}
