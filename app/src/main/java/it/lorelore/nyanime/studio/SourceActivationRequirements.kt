package it.lorelore.nyanime.studio

import android.content.Context
import eu.kanade.tachiyomi.data.rin.RinLoader
import eu.kanade.tachiyomi.data.rin.RinRecord
import eu.kanade.tachiyomi.data.telegram.TelegramClient
import it.lorelore.nyanime.source.novel.NovelSource
import it.lorelore.nyanime.source.telegram.TelegramBackedSource
import nyanime.telegram.TelegramAccountSessions

internal class SourceActivationRequired(val packageName: String) : IllegalStateException(
    "Source access must be configured",
)

internal data class SourceActivationProof(
    val requirements: List<TelegramActivationRequirement>,
    val sessionVersion: Long = -1,
    val grantVersion: Long = -1,
    val live: Boolean = false,
)

/** Local inspection only. A repository mutation never opens a login page or joins a channel. */
internal object SourceActivationRequirements {
    fun declared(source: StudioSource, preview: Boolean = false): List<TelegramActivationRequirement> =
        if (source.transport == StudioTransport.TELEGRAM) {
            listOf(
                TelegramActivationRequirement(
                    SourceAccessOwner(
                        "${if (preview) "studio-draft" else "studio"}.${source.uuid}",
                        "local-studio",
                        source.sourceId,
                    ),
                    source.name,
                    TelegramPublicChannel.names(source.telegramChannels),
                ),
            )
        } else {
            emptyList()
        }

    private val cache = object : LinkedHashMap<String, List<TelegramActivationRequirement>>(32, .75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, List<TelegramActivationRequirement>>) =
            size > 64
    }

    fun declared(record: RinRecord, sources: List<Any>): List<TelegramActivationRequirement> =
        sources.filterIsInstance<NovelSource>().mapNotNull { source ->
            (source as? TelegramBackedSource)?.let {
                TelegramActivationRequirement(
                    SourcePackageIdentity.owner(record, source.id),
                    source.name,
                    TelegramPublicChannel.names(it.telegramChannels),
                )
            }
        }

    fun capture(record: RinRecord, sources: List<Any>): List<TelegramActivationRequirement> = declared(
        record,
        sources,
    ).also {
        synchronized(cache) { cache[key(record)] = it }
    }

    fun inspect(context: Context, record: RinRecord): List<TelegramActivationRequirement> {
        if (record.manifest.medium != "novel") return emptyList()
        return synchronized(cache) { cache[key(record)] } ?: capture(record, RinLoader.instantiate(context, record))
    }

    suspend fun proof(
        context: Context,
        requirements: List<TelegramActivationRequirement>,
        live: Boolean,
    ): SourceActivationProof? {
        if (requirements.isEmpty()) return SourceActivationProof(emptyList())
        val sessions = TelegramAccountSessions.get(context)
        val store = StudioTelegramGrantStore.get(context)
        val session = sessions.state.value
        if (live && !liveAccount(context, session.account)) return null
        val saved = store.snapshot()
        if (!SourceActivationPolicy.approved(requirements, session.account, session.revision, saved.grants)) return null
        val proof = SourceActivationProof(requirements, session.version, saved.version, live)
        return proof.takeIf { current(context, it) }
    }

    fun current(context: Context, proof: SourceActivationProof): Boolean {
        if (proof.requirements.isEmpty()) return true
        return TelegramAccountSessions.get(context).state.value.version == proof.sessionVersion &&
            StudioTelegramGrantStore.get(context).changes.value == proof.grantVersion &&
            (!proof.live || liveAccount(context, TelegramAccountSessions.get(context).state.value.account))
    }

    suspend fun requireEnabled(context: Context, record: RinRecord, sources: List<Any>): SourceActivationProof =
        proof(context, capture(record, sources), live = true)
            ?: throw SourceActivationRequired(record.manifest.packageName)

    private fun key(record: RinRecord) =
        record.manifest.packageName + ":" + record.sha256 + ":" + SourcePackageIdentity.publisher(record)

    private fun liveAccount(context: Context, account: Long?): Boolean {
        val state = TelegramClient.get(context).state.value
        return account != null && state.ready && state.account?.id == account
    }
}
