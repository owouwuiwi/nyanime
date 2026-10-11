package eu.kanade.tachiyomi.data.extensionstore

import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.net.Uri
import android.util.AtomicFile
import androidx.core.content.pm.PackageInfoCompat
import eu.kanade.domain.extension.ExtensionExecutionTrust
import eu.kanade.tachiyomi.data.addon.RinAddonRuntime
import eu.kanade.tachiyomi.data.rin.RinArchive
import eu.kanade.tachiyomi.data.rin.RinLoading
import eu.kanade.tachiyomi.data.rin.RinRecord
import eu.kanade.tachiyomi.data.rin.RinRepository
import eu.kanade.tachiyomi.data.rin.RinSessions
import eu.kanade.tachiyomi.extension.ExtensionPackageArchive
import eu.kanade.tachiyomi.extension.ExtensionPackageInspector
import eu.kanade.tachiyomi.extension.manga.MangaExtensionManager
import eu.kanade.tachiyomi.extension.manga.model.MangaExtension
import eu.kanade.tachiyomi.source.CatalogueSource
import eu.kanade.tachiyomi.source.SourceFactory
import eu.kanade.tachiyomi.util.storage.getUriCompat
import eu.kanade.tachiyomi.util.system.ChildFirstPathClassLoader
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import mihon.domain.extensionrepo.manga.repository.MangaExtensionRepoRepository
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import java.io.File
import java.io.InputStream

internal class SystemMangaMigration private constructor(private val context: Context) {
    private val root = File(context.filesDir, "system-manga-migrations")
    private val mutex = Mutex()
    private val rin = RinRepository.get(context)
    private val mutableSessions = MutableStateFlow<List<Session>>(emptyList())
    val sessions = mutableSessions.asStateFlow()

    @Serializable
    data class Apk(val versionCode: Long, val signers: Set<String>, val sha256: String)

    @Serializable
    data class Session(
        val packageName: String,
        val name: String,
        val sourceIds: Set<Long>,
        val original: Apk,
        val candidate: Apk,
        val candidateRepository: String? = null,
        val verified: Boolean = false,
        val awaitingInstall: Side? = null,
    )

    @Serializable
    enum class Side(val filename: String) { ORIGINAL("original.apk"), CANDIDATE("candidate.apk") }

    enum class Position { ORIGINAL, ABSENT, CANDIDATE, OTHER }

    data class Snapshot(val session: Session, val position: Position)

    class SplitPackage : IllegalStateException()

    fun canStart(entry: StoreEntry, layers: List<RinRecord>): Boolean {
        val base = entry.manga as? MangaExtension.Installed ?: return false
        return base.isShared && !entry.imported && entry.rinRecord == null && base.sources.isNotEmpty() &&
            layers.any { layer ->
                layer.manifest.mangaLayer?.targets.orEmpty().any { target ->
                    target.medium == "manga" && target.packageName == base.pkgName &&
                        target.signer !in (base.metadata.signers + base.metadata.signerHistory) &&
                        base.sources.any { it.id == target.sourceId }
                }
            }
    }

    suspend fun refreshSessions() = locked { publish() }

    suspend fun prepare(packageName: String, uri: Uri) = locked {
        requireIdle()
        require(read(packageName) == null)
        val installed = requireNotNull(Injekt.get<MangaExtensionManager>().installedExtensionsFlow.value.singleOrNull {
            it.pkgName == packageName && it.isShared && it.sources.isNotEmpty()
        })
        require(rin.storage.record(packageName) == null)
        val expectedIds = installed.sources.map { it.id }
        require(expectedIds.none { it == 0L } && expectedIds.size == expectedIds.distinct().size)
        val info = requireNotNull(installedInfo(packageName))
        val installedApp = requireNotNull(info.applicationInfo)
        if (!installedApp.splitSourceDirs.isNullOrEmpty() || !info.splitNames.isNullOrEmpty()) throw SplitPackage()
        require((installedApp.flags and ApplicationInfo.FLAG_SYSTEM) == 0)
        val originalFile = File(requireNotNull(installedApp.sourceDir))
        val original = identity(info, originalFile)
        require(original.signers == installed.metadata.signers && original.versionCode == installed.versionCode)
        val folder = directory(packageName)
        require(folder.mkdirs() || folder.isDirectory)
        var saved = false
        try {
            val backup = staged(packageName, Side.ORIGINAL)
            originalFile.inputStream().use { copyBounded(it, backup) }
            val oldArchive = ExtensionPackageArchive.inspect(context, backup)
            require(checkedIdentity(oldArchive, backup, packageName) == original)

            val replacement = staged(packageName, Side.CANDIDATE)
            requireNotNull(context.contentResolver.openInputStream(uri)).use { copyBounded(it, replacement) }
            val archive = ExtensionPackageArchive.inspect(context, replacement)
            val candidate = checkedIdentity(archive, replacement, packageName)
            require(archive.manifest.sources.isEmpty() || archive.manifest.knownSourceIds == expectedIds.toSet())
            val history = installed.metadata.signers + installed.metadata.signerHistory
            require(candidate.signers.size == 1 && candidate.signers.none { it in history })
            require(checkTarget(packageName, candidate, expectedIds.toSet()))
            val session = Session(
                packageName, installed.name, expectedIds.toSet(), original, candidate,
                candidateRepository = matchingRepository(candidate),
            )
            require(position(session) == Position.ORIGINAL)
            requireIdle()
            write(session)
            saved = true
        } finally {
            if (!saved) folder.deleteRecursively()
        }
    }

    /** Verify the actual source objects only after the user explicitly approves preflight. */
    suspend fun verify(packageName: String) = locked {
        requireIdle()
        val session = requireNotNull(read(packageName))
        require(position(session) == Position.ORIGINAL)
        require(checkTarget(packageName, session.candidate, session.sourceIds))
        verifySavedRoutes(session)
        require(position(session) == Position.ORIGINAL && checkTarget(packageName, session.candidate, session.sourceIds))
        requireIdle()
        write(session.copy(verified = true))
    }

    suspend fun snapshot(packageName: String): Snapshot? = locked {
        val session = read(packageName) ?: return@locked null
        val installed = position(session)
        val matched = when (session.awaitingInstall) {
            Side.ORIGINAL -> installed == Position.ORIGINAL
            Side.CANDIDATE -> installed == Position.CANDIDATE &&
                checkTarget(packageName, session.candidate, session.sourceIds)
            null -> false
        }
        if (matched && !busy()) {
            val info = requireNotNull(installedInfo(packageName))
            val expected = if (installed == Position.ORIGINAL) session.original else session.candidate
            require(identity(info, File(requireNotNull(info.applicationInfo?.sourceDir))) == expected)
            val repository = if (installed == Position.CANDIDATE) {
                session.candidateRepository?.takeIf { it == matchingRepository(session.candidate) }
            } else {
                matchingRepository(session.original)
            }
            ExtensionExecutionTrust(Injekt.get()).approve(packageName, ExtensionPackageInspector.inspect(info) { 0 }, repository)
            rin.reloadInstalled()
            write(session.copy(awaitingInstall = null))
        }
        Snapshot(requireNotNull(read(packageName)), installed)
    }

    suspend fun removeIntent(packageName: String, side: Side): Intent = locked {
        requireIdle()
        val session = requireNotNull(read(packageName))
        checkStaged(session, Side.ORIGINAL)
        if (side == Side.ORIGINAL) {
            require(session.verified && position(session) == Position.ORIGINAL)
            require(checkTarget(packageName, session.candidate, session.sourceIds))
            verifySavedRoutes(session)
            require(position(session) == Position.ORIGINAL && checkTarget(packageName, session.candidate, session.sourceIds))
        } else {
            require(session.verified && position(session) == Position.CANDIDATE)
        }
        requireIdle()
        Intent(Intent.ACTION_DELETE, Uri.fromParts("package", packageName, null))
    }

    suspend fun installIntent(packageName: String, side: Side): Intent = locked {
        requireIdle()
        val session = requireNotNull(read(packageName))
        require(position(session) == Position.ABSENT)
        val inspected = checkStaged(session, side)
        if (side == Side.CANDIDATE) {
            require(session.verified && checkTarget(packageName, session.candidate, session.sourceIds))
        }
        val handOff = File.createTempFile("manga-migration-", ".apk", context.cacheDir)
        try {
            staged(packageName, side).inputStream().use { copyBounded(it, handOff) }
            val expected = if (side == Side.ORIGINAL) session.original else session.candidate
            require(identity(inspected.info, handOff) == expected)
            val intent = Intent(Intent.ACTION_VIEW).setDataAndType(
                handOff.getUriCompat(context),
                "application/vnd.android.package-archive",
            ).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            require(position(session) == Position.ABSENT)
            requireIdle()
            write(session.copy(awaitingInstall = side))
            intent
        } catch (e: Exception) {
            handOff.delete()
            throw e
        }
    }

    /** Keep the backup if the original is absent or any other package occupies its name. */
    suspend fun cancel(packageName: String) = locked {
        requireIdle()
        val session = requireNotNull(read(packageName))
        require(position(session) == Position.ORIGINAL)
        val journal = journal(packageName)
        journal.delete()
        check(read(packageName) == null)
        staged(packageName, Side.ORIGINAL).delete()
        staged(packageName, Side.CANDIDATE).delete()
        directory(packageName).delete()
        publish()
    }

    private fun checkTarget(packageName: String, apk: Apk, ids: Set<Long>): Boolean {
        val covered = rin.activeMangaLayers.value.asSequence().filter { rin.verifiedMangaLayerFile(it) != null }
            .flatMap { it.manifest.mangaLayer?.targets.orEmpty().asSequence() }
            .filter { it.medium == "manga" && it.packageName == packageName && it.signer in apk.signers && it.supports(apk.versionCode) }
            .map { it.sourceId }.toSet()
        return covered.containsAll(ids)
    }

    private suspend fun verifySavedRoutes(session: Session) {
        MangaMigrationRoutes.verify(
            stagedSources(session, Side.ORIGINAL),
            stagedSources(session, Side.CANDIDATE),
            session.sourceIds,
        )
    }

    private fun stagedSources(session: Session, side: Side): List<CatalogueSource> = RinLoading.guard {
        val archive = checkStaged(session, side)
        val loader = ChildFirstPathClassLoader(staged(session.packageName, side).absolutePath, null, context.classLoader)
        archive.manifest.entryPoints.flatMap { name ->
            when (val instance = Class.forName(name, false, loader).getDeclaredConstructor().newInstance()) {
                is CatalogueSource -> listOf(instance)
                is SourceFactory -> instance.createSources().map { it as CatalogueSource }
                else -> error("Invalid manga source factory")
            }
        }
    }

    private suspend fun matchingRepository(apk: Apk): String? = Injekt.get<MangaExtensionRepoRepository>()
        .getAll()
        .filter { it.signingKeyFingerprint.lowercase() in apk.signers }
        .map { it.baseUrl }.distinct().singleOrNull()

    private fun checkedIdentity(archive: ExtensionPackageArchive.Inspected, file: File, packageName: String): Apk {
        require(archive.manifest.packageName == packageName && archive.manifest.medium == "manga")
        require(RinRepository.compatible(archive.manifest, "apk"))
        require(archive.manifest.entryPoints.isNotEmpty())
        require(archive.info.splitNames.isNullOrEmpty())
        return identity(archive.info, file)
    }

    private fun checkStaged(session: Session, side: Side): ExtensionPackageArchive.Inspected {
        val file = staged(session.packageName, side)
        val inspected = ExtensionPackageArchive.inspect(context, file)
        val expected = if (side == Side.ORIGINAL) session.original else session.candidate
        require(checkedIdentity(inspected, file, session.packageName) == expected)
        return inspected
    }

    private fun identity(info: PackageInfo, file: File): Apk {
        val signers = ExtensionPackageInspector.inspect(info) { 0 }.signers
        require(signers.isNotEmpty())
        return Apk(PackageInfoCompat.getLongVersionCode(info), signers, ExtensionPackageArchive.sha256(file))
    }

    private fun position(session: Session): Position {
        val info = installedInfo(session.packageName) ?: return Position.ABSENT
        val app = requireNotNull(info.applicationInfo)
        if (!app.splitSourceDirs.isNullOrEmpty() || !info.splitNames.isNullOrEmpty()) return Position.OTHER
        return when (identity(info, File(requireNotNull(app.sourceDir)))) {
            session.original -> Position.ORIGINAL
            session.candidate -> Position.CANDIDATE
            else -> Position.OTHER
        }
    }

    private fun installedInfo(packageName: String): PackageInfo? = try {
        context.packageManager.getPackageInfo(packageName, ExtensionPackageArchive.FLAGS)
    } catch (_: PackageManager.NameNotFoundException) {
        null
    }

    private suspend fun copyBounded(input: InputStream, file: File) {
        if (file.exists()) check(file.delete())
        file.outputStream().use { output ->
            val buffer = ByteArray(32 * 1024)
            var count = 0L
            while (true) {
                currentCoroutineContext().ensureActive()
                val size = input.read(buffer)
                if (size < 0) break
                count += size
                require(count <= ExtensionPackageArchive.MAX_BYTES)
                output.write(buffer, 0, size)
            }
            require(count > 0)
            output.fd.sync()
        }
        check(file.setReadOnly())
    }

    private fun directory(packageName: String): File {
        require(packageName.matches(PACKAGE))
        return File(root, packageName)
    }

    private fun staged(packageName: String, side: Side) = File(directory(packageName), side.filename)

    private fun journal(packageName: String) = AtomicFile(File(directory(packageName), "session.json"))

    private fun read(packageName: String): Session? {
        val record = journal(packageName)
        if (!record.baseFile.exists()) return null
        val raw = record.readFully()
        require(raw.size <= 64 * 1024)
        return RinArchive.json.decodeFromString<Session>(raw.decodeToString()).also { require(it.packageName == packageName) }
    }

    private fun write(session: Session) {
        val record = journal(session.packageName)
        val output = record.startWrite()
        try {
            output.write(RinArchive.json.encodeToString(Session.serializer(), session).encodeToByteArray())
            record.finishWrite(output)
        } catch (e: Exception) {
            record.failWrite(output)
            throw e
        }
        check(read(session.packageName) == session)
        publish()
    }

    private fun publish() {
        mutableSessions.value = root.listFiles().orEmpty().filter { it.isDirectory && it.name.matches(PACKAGE) }
            .mapNotNull { runCatching { read(it.name) }.getOrNull() }
            .sortedBy { it.name }
    }

    private fun requireIdle() = check(!busy())

    private fun busy() = RinSessions.busy.value || RinAddonRuntime.busy.value

    private suspend fun <T> locked(block: suspend () -> T): T = withContext(Dispatchers.IO) {
        mutex.withLock { block() }
    }

    companion object {
        private val PACKAGE = Regex("[A-Za-z][A-Za-z0-9_]*(\\.[A-Za-z][A-Za-z0-9_]*)+")
        @Volatile private var instance: SystemMangaMigration? = null

        fun get(context: Context): SystemMangaMigration = instance ?: synchronized(this) {
            instance ?: SystemMangaMigration(context.applicationContext).also { instance = it }
        }
    }
}
