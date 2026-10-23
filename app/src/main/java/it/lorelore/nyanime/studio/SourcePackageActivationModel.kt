package it.lorelore.nyanime.studio

import android.content.Context
import cafe.adriel.voyager.core.model.ScreenModel
import cafe.adriel.voyager.core.model.screenModelScope
import eu.kanade.tachiyomi.data.rin.RinLoader
import eu.kanade.tachiyomi.data.rin.RinRecord
import eu.kanade.tachiyomi.data.rin.RinRepository
import it.lorelore.nyanime.source.novel.NovelSource
import it.lorelore.nyanime.source.telegram.TelegramBackedSource
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** No switch changes until all reviews finish. A closed or superseded dialog cannot enable a package. */
internal class SourcePackageActivationModel(context: Context) : ScreenModel {
    private val app = context.applicationContext
    private val repository = RinRepository.get(app)

    @Volatile private var generation = 0L
    private var prepare: Job? = null
    private data class Plan(val token: Long, val record: RinRecord, val requests: List<SourceCapabilityRequest>)
    private var plan: Plan? = null
    private val mutablePending = MutableStateFlow<SourceCapabilityRequest?>(null)
    val pending = mutablePending.asStateFlow()
    val busy = MutableStateFlow(false)
    val failed = MutableStateFlow(false)

    fun begin(record: RinRecord) {
        if (busy.value || record.enabled) return
        cancel()
        val token = generation
        busy.value = true
        failed.value = false
        prepare = screenModelScope.launch {
            try {
                val sources = withContext(Dispatchers.IO) { RinLoader.instantiate(app, record) }
                currentCoroutineContext().ensureActive()
                check(token == generation)
                val requests = sources.filterIsInstance<NovelSource>().filterIsInstance<TelegramBackedSource>()
                    .map { sourcePackageAccessRequest(app, record, it as NovelSource) }
                if (requests.isEmpty()) {
                    repository.setEnabled(
                        record.manifest.packageName,
                        true,
                        expected = record,
                        stillRequested = { token == generation },
                    )
                    busy.value = false
                } else {
                    plan = Plan(token, record, requests)
                    mutablePending.value = requests.first()
                }
            } catch (cancel: CancellationException) {
                throw cancel
            } catch (_: Exception) {
                if (token == generation) {
                    failed.value = true
                    busy.value = false
                }
            }
        }
    }

    suspend fun approved(request: SourceCapabilityRequest) {
        val selected = requireNotNull(plan)
        check(selected.token == generation && mutablePending.value === request)
        request.validate()
        val remaining = selected.requests.drop(1)
        if (remaining.isNotEmpty()) {
            plan = selected.copy(requests = remaining)
            mutablePending.value = remaining.first()
        } else {
            try {
                repository.setEnabled(
                    selected.record.manifest.packageName,
                    true,
                    expected = selected.record,
                    stillRequested = { selected.token == generation },
                )
                currentCoroutineContext().ensureActive()
                check(selected.token == generation)
                mutablePending.value = null
                plan = null
                busy.value = false
            } catch (cancel: CancellationException) {
                throw cancel
            } catch (failure: Exception) {
                if (selected.token == generation) {
                    failed.value = true
                    busy.value = false
                }
                throw failure
            }
        }
    }

    fun cancel() {
        generation++
        prepare?.cancel()
        prepare = null
        plan = null
        mutablePending.value = null
        busy.value = false
    }

    override fun onDispose() {
        cancel()
    }
}
