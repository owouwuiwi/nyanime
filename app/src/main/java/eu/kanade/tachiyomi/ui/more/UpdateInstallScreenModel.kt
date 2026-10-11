package eu.kanade.tachiyomi.ui.more

import cafe.adriel.voyager.core.model.ScreenModel
import eu.kanade.tachiyomi.data.updater.UpdateInstallGate
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/** Retained with its screen across rotation; removing the screen abandons the automatic intent. */
internal class UpdateInstallScreenModel : ScreenModel {
    private val gate = MutableStateFlow(UpdateInstallGate())
    val state = gate.asStateFlow()
    private val error = MutableStateFlow<String?>(null)
    val installError = error.asStateFlow()

    fun request(updateId: String? = null) {
        error.value = null
        gate.update { it.request(updateId) }
    }

    fun claim(ready: Boolean, resumed: Boolean, allowed: Boolean, updateId: String?): UpdateInstallGate.Action? {
        val (next, action) = gate.value.claim(ready, resumed, allowed, updateId)
        gate.value = next
        return action
    }

    fun lifecycle(resumed: Boolean) {
        gate.update { it.lifecycle(resumed) }
    }

    fun permissionReturned(granted: Boolean, explanation: String) {
        gate.update { it.permissionReturned(granted) }
        error.value = explanation.takeUnless { granted }
    }

    fun cancel() {
        gate.value = UpdateInstallGate()
    }

    fun failed(message: String?) {
        error.value = message
        cancel()
    }

    override fun onDispose() {
        cancel()
    }
}
