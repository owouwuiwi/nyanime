package eu.kanade.tachiyomi.ui.discovery

import cafe.adriel.voyager.core.model.StateScreenModel
import cafe.adriel.voyager.core.model.screenModelScope
import eu.kanade.tachiyomi.data.discovery.ExtensionHomeRegistry
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get

/** Observes installed source capabilities only; it never loads the optional Home or its remote feeds. */
class DiscoveryHomeScreenModel(
    registry: ExtensionHomeRegistry = Injekt.get(),
) : StateScreenModel<DiscoveryHomeAvailability>(DiscoveryHomeAvailability()) {
    init {
        screenModelScope.launch {
            registry.observe()
                .map { DiscoveryHomeAvailability.from(it) }
                .distinctUntilChanged()
                .collect { mutableState.value = it }
        }
    }
}
