package it.lorelore.nyanime.studio

import android.content.Context
import eu.kanade.tachiyomi.data.rin.RinRecord
import eu.kanade.tachiyomi.data.rin.RinRepository
import eu.kanade.tachiyomi.data.telegram.TelegramClient
import eu.kanade.tachiyomi.extension.ExtensionPackageConsent
import it.lorelore.nyanime.source.novel.NovelSource
import it.lorelore.nyanime.source.telegram.TelegramBackedSource
import it.lorelore.nyanime.source.telegram.TelegramSourceReader
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

/** One host broker is shared by user graphs and optional capabilities of trusted APK sources. */
internal class StudioTelegramAccess private constructor(context: Context) {
    private val app = context.applicationContext
    private val repository = SourceStudioRepository.get(app)
    private val client = TelegramClient.get(app)
    private val store = StudioTelegramGrantStore.get(app)
    val policy = TelegramSourceAccess(store, StudioTelegramBackend(app), ::current)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val previews = ConcurrentHashMap<String, AtomicInteger>()

    init {
        scope.launch {
            nyanime.telegram.TelegramAccountSessions.get(app).state.drop(1).collect {
                policy.stopAllReads()
            }
        }
        scope.launch {
            try {
                client.signedOut.collect { account -> policy.revokeAccount(account) }
            } catch (
                cancel: CancellationException,
            ) {
                throw cancel
            } catch (_: Exception) {
                policy.stopAllReads()
            }
        }
        scope.launch {
            try {
                val previous = mutableMapOf<SourceAccessOwner, String>()
                combine(repository.records, RinRepository.get(app).installed) { definitions, packages ->
                    definitions to
                        packages
                }
                    .collect { (definitions, packages) ->
                        store.grants().forEach { grant ->
                            val owner = grant.owner
                            val revision = if (owner.packageName.startsWith("studio.") ||
                                owner.packageName.startsWith("studio-draft.")
                            ) {
                                val preview = owner.packageName.startsWith("studio-draft.")
                                val record = definitions.singleOrNull {
                                    this@StudioTelegramAccess.owner(it.draft, preview) ==
                                        owner
                                }
                                val source = if (preview) record?.draft else record?.active?.takeIf { record.enabled }
                                source?.let(StudioDocuments::fingerprint).orEmpty()
                            } else {
                                packages.singleOrNull {
                                    it.manifest.packageName == owner.packageName &&
                                        it.enabled &&
                                        it.trusted &&
                                        ExtensionPackageConsent.trusted(it) &&
                                        digest(it.publisherSigners.sorted().joinToString(":")) == owner.publisher
                                }?.sha256.orEmpty()
                            }
                            if (revision.isBlank() ||
                                previous[owner]?.let { it != revision } == true
                            ) {
                                policy.stopReads(owner)
                            }
                            previous[owner] = revision
                        }
                    }
            } catch (cancel: CancellationException) {
                throw cancel
            } catch (_: Exception) {
                policy.stopAllReads()
            }
        }
    }

    fun owner(source: StudioSource, preview: Boolean = false) =
        SourceAccessOwner(
            "${if (preview) "studio-draft" else "studio"}.${source.uuid}",
            "local-studio",
            source.sourceId,
        )

    fun owner(record: RinRecord, source: NovelSource) =
        SourcePackageIdentity.owner(record, source.id)

    suspend fun review(source: StudioSource, preview: Boolean = false): TelegramSourceReview =
        policy.review(owner(source, preview), source.telegramChannels)

    suspend fun activate(review: TelegramSourceReview, mute: Boolean) =
        policy.activate(review, mute)

    suspend fun revoke(source: StudioSource) = policy.revoke(owner(source))

    suspend fun checkAccess(source: StudioSource, preview: Boolean = false) {
        val check: suspend () -> Unit = {
            policy.checkAccess(owner(source, preview), StudioDocuments.fingerprint(source), source.telegramChannels)
        }
        if (preview) this.preview(source, check) else check()
    }

    fun reader(source: StudioSource, preview: Boolean = false): TelegramSourceReader =
        policy.reader(
            owner(source, preview),
            StudioDocuments.fingerprint(source),
            source.telegramChannels.toList(),
        )

    suspend fun <T> preview(source: StudioSource, operation: suspend () -> T): T {
        val hash = StudioDocuments.fingerprint(source)
        previews.compute(hash) { _, count ->
            (count ?: AtomicInteger()).apply { incrementAndGet() }
        }
        return try {
            operation()
        } finally {
            previews.computeIfPresent(hash) { _, count ->
                if (count.decrementAndGet() == 0) null else count
            }
        }
    }

    /** Call only after the host has validated the package, signer and declared source identity. */
    fun attach(record: RinRecord, source: NovelSource) {
        if (source is TelegramBackedSource) {
            val channels = source.telegramChannels.toList()
            TelegramPublicChannel.names(channels)
            source.attachTelegram(policy.reader(owner(record, source), record.sha256, channels))
        }
    }

    suspend fun review(record: RinRecord, source: NovelSource): TelegramSourceReview {
        require(source is TelegramBackedSource)
        return policy.review(owner(record, source), source.telegramChannels.toList())
    }

    private suspend fun revokePackage(record: RinRecord): Map<SourceAccessOwner, Set<TelegramManagedMembership>> {
        val publisher = digest(record.publisherSigners.sorted().joinToString(":"))
        val owners = store.grants().map { it.owner }.filter {
            it.packageName == record.manifest.packageName && it.publisher == publisher
        }
        return owners.associateWith { policy.revoke(it) }.filterValues { it.isNotEmpty() }
    }

    private suspend fun current(owner: SourceAccessOwner): SourceAccessState {
        client.connect()
        val telegram =
            withTimeout(15_000) {
                client.state.first {
                    it.ready || it.authorization !in setOf("connecting", "disconnected")
                }
            }
        val session = nyanime.telegram.TelegramAccountSessions.get(app).state.value
        val account = telegram.account?.id?.takeIf { telegram.ready && it == session.account }
        if (
            owner.packageName.startsWith("studio.") || owner.packageName.startsWith("studio-draft.")
        ) {
            repository.awaitReady()
            val preview = owner.packageName.startsWith("studio-draft.")
            val record =
                repository.records.value.singleOrNull {
                    "${if (preview) "studio-draft" else "studio"}.${it.draft.uuid}" ==
                        owner.packageName &&
                        it.draft.sourceId == owner.sourceId
                } ?: return SourceAccessState(account, "unavailable", false, configurable = false)
            val draft = record.draft
            val hash = StudioDocuments.fingerprint(draft)
            val active =
                if (preview) {
                    draft.takeIf { (previews[hash]?.get() ?: 0) > 0 }
                } else {
                    record.active?.takeIf {
                        record.enabled && (!StudioBlueprints.hasScripts(it) || record.trusts(it))
                    }
                }
            return SourceAccessState(
                account,
                StudioDocuments.fingerprint(draft),
                active != null,
                configurable =
                owner.publisher == "local-studio" &&
                    draft.transport == StudioTransport.TELEGRAM,
                readableRevisions =
                active?.let { setOf(StudioDocuments.fingerprint(it)) }.orEmpty(),
                accountRevision = session.revision.orEmpty(),
            )
        }
        val record =
            RinRepository.get(app).installed.value.singleOrNull {
                it.manifest.packageName == owner.packageName
            }
        val trusted =
            record != null &&
                record.trusted &&
                ExtensionPackageConsent.trusted(record) &&
                digest(record.publisherSigners.sorted().joinToString(":")) == owner.publisher &&
                RinRepository.compatible(record.manifest, record.format)
        return SourceAccessState(
            account,
            record?.sha256.orEmpty(),
            trusted && record?.enabled == true,
            configurable = trusted,
            accountRevision = session.revision.orEmpty(),
        )
    }

    private fun digest(value: String) =
        MessageDigest.getInstance("SHA-256").digest(value.toByteArray()).joinToString("") {
            "%02x".format(it)
        }

    companion object {
        @Volatile private var instance: StudioTelegramAccess? = null

        suspend fun revokePackageAccess(
            context: Context,
            record: RinRecord,
        ): Map<SourceAccessOwner, Set<TelegramManagedMembership>> {
            val file = java.io.File(context.noBackupFilesDir, "source-telegram-access-v1.json")
            if (instance == null && !file.exists() && !java.io.File(file.path + ".bak").exists()) return emptyMap()
            return get(context).revokePackage(record)
        }

        fun get(context: Context) =
            instance
                ?: synchronized(this) {
                    instance ?: StudioTelegramAccess(context).also { instance = it }
                }
    }
}

/** Brokers are acquired only when a graph actually executes an optional block. */
internal class StudioHostServices(
    private val context: Context,
    private val preview: Boolean = false,
) : StudioServices {
    override suspend fun telegram(source: StudioSource) =
        StudioTelegramAccess.get(context).reader(source, preview)

    override suspend fun document(source: StudioSource, resolver: String, reference: String): StudioTextDocument {
        val document = it.lorelore.nyanime.studio.document.StudioDocumentComponents.get(context)
            .reader(source, resolver, preview).document(reference)
        return StudioTextDocument(document.reference, document.title, document.mimeType, document.bytes)
    }

    override suspend fun documents(source: StudioSource, resolver: String, reference: String) =
        it.lorelore.nyanime.studio.document.StudioDocumentComponents.get(context)
            .reader(source, resolver, preview).list(reference)
}
