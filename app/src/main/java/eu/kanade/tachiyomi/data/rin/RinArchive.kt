package eu.kanade.tachiyomi.data.rin

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File
import java.net.URI
import java.security.MessageDigest
import java.security.Signature
import java.security.cert.CertificateFactory
import java.util.zip.ZipFile

@Serializable
data class RinSource(val id: Long, val name: String, val language: String)

@Serializable
data class RinManifest(
    val schema: Int,
    val packageName: String,
    val name: String,
    val versionCode: Long,
    val versionName: String,
    val medium: String,
    val extensionLib: Double,
    val entryPoints: List<String>,
    val sources: List<RinSource>,
    val nsfw: Boolean = false,
    val torrent: Boolean = false,
    val hashes: Map<String, String>,
    val dormantSources: List<RinSource> = emptyList(),
    val addon: RinAddonDeclaration? = null,
    val guide: RinGuideDeclaration? = null,
    val minHostVersionCode: Long? = null,
    val maxHostVersionCode: Long? = null,
    val minHostVersionName: String? = null,
    val maxHostVersionName: String? = null,
) {
    companion object {
        val supportedMedia: Set<String> = setOf("anime", "manga", "addon", "guide", "tv")
    }

    val hostVersions get() = eu.kanade.domain.extension.AddonHostVersions(
        minHostVersionCode,
        maxHostVersionCode,
        minHostVersionName,
        maxHostVersionName,
    )
    val knownSourceIds: Set<Long> get() = (sources + dormantSources).map { it.id }.toSet()

    fun validSourceIdentities(): Boolean {
        if (medium == "guide") return sources.isEmpty() && dormantSources.isEmpty() && guide?.valid() == true
        if (medium == "addon") return sources.isEmpty() && dormantSources.isEmpty() && addon?.valid() == true
        val all = sources + dormantSources
        return sources.isNotEmpty() &&
            all.size <= 128 &&
            all.all { it.id != 0L && it.name.length in 1..200 && it.language.length in 2..10 } &&
            knownSourceIds.size == all.size
    }

    fun preservesSourceIds(ids: Collection<Long>): Boolean =
        validSourceIdentities() &&
            (
                if (medium in
                    setOf("addon", "guide")
                ) {
                    ids.isEmpty()
                } else {
                    ids.isNotEmpty() && knownSourceIds.containsAll(ids)
                }
                )
}

@Serializable
data class RinAddonDeclaration(
    val api: Int,
    val activation: String,
    val actionLabel: Map<String, String>,
    val actionSummary: Map<String, String>,
    val descriptions: Map<String, String>,
    val sourceUrl: String? = null,
) {
    fun valid(): Boolean = api == 1 &&
        activation == "more_action" &&
        texts(actionLabel, 128) &&
        texts(actionSummary, 256) &&
        texts(descriptions, 4096) &&
        (sourceUrl == null || RinArchive.validBundleUrl(sourceUrl))
    private fun texts(values: Map<String, String>, max: Int) =
        values.isNotEmpty() &&
            values.size <= 50 &&
            "en" in values &&
            values.all { (locale, value) ->
                locale.matches(Regex("[A-Za-z0-9-]{1,35}")) &&
                    value.isNotBlank() &&
                    value.length <= max
            }
    fun text(values: Map<String, String>, locale: java.util.Locale): String =
        values[locale.toLanguageTag()] ?: values[locale.language] ?: requireNotNull(values["en"])
}

/** Authenticate every executable byte before creating a class loader. No Android package installation. */
object RinArchive {
    val json = Json { ignoreUnknownKeys = true }
    const val MAX_ARCHIVE_BYTES = 32 * 1024 * 1024
    private val packagePattern = Regex("[a-zA-Z][a-zA-Z0-9_]*(\\.[a-zA-Z][a-zA-Z0-9_]*)+")
    private val hashPattern = Regex("[a-f0-9]{64}")

    /** Bundle endpoints may be opaque CDN URLs; their contents are authenticated separately. */
    fun validBundleUrl(value: String): Boolean = runCatching {
        val url = URI(value)
        url.scheme == "https" &&
            !url.host.isNullOrBlank() &&
            url.rawUserInfo == null &&
            url.rawFragment == null &&
            !url.rawPath.isNullOrBlank()
    }.getOrDefault(false)

    fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256")
        .digest(bytes).joinToString("") { "%02x".format(it) }

    /** Reads identity only. It does not establish trust or authenticate the executable payload. */
    fun publisher(file: File): String {
        require(file.length() in 1..MAX_ARCHIVE_BYTES.toLong())
        return ZipFile(file).use { zip ->
            val certificates = zip.entries().asSequence().filter { it.name == "META-INF/rin.cert" }.toList()
            val certificate = certificates.single()
            require(certificate.size in 1..16_384)
            sha256(zip.getInputStream(certificate).use { RinRepository.boundedRead(it, 16_384) })
        }
    }

    fun verify(file: File, signer: String, expectedHash: String? = null): RinManifest {
        require(file.length() in 1..MAX_ARCHIVE_BYTES.toLong()) { "Invalid RIN size" }
        require(hashPattern.matches(signer)) { "Invalid publisher" }
        expectedHash?.let { require(hashPattern.matches(it) && sha256(file.readBytes()) == it) { "RIN hash mismatch" } }
        return ZipFile(file).use { zip ->
            val entries = zip.entries().asSequence().toList()
            require(entries.size in 4..512 && entries.map { it.name }.distinct().size == entries.size)
            require(
                entries.none {
                    it.isDirectory ||
                        it.name.startsWith('/') ||
                        '\\' in it.name ||
                        it.name.split('/').any { p -> p in setOf("", ".", "..") }
                },
            )
            var total = 0
            fun read(name: String, limit: Int): ByteArray {
                val entry = requireNotNull(zip.getEntry(name)) { "Missing RIN component" }
                require(entry.size in 0..limit.toLong())
                val bytes = zip.getInputStream(entry).use { RinRepository.boundedRead(it, limit) }
                require(bytes.size <= limit)
                total += bytes.size
                require(total <= MAX_ARCHIVE_BYTES)
                return bytes
            }
            val manifestBytes = read("manifest.json", 65_536)
            val certificateBytes = read("META-INF/rin.cert", 16_384)
            require(sha256(certificateBytes) == signer) { "RIN publisher mismatch" }
            val certificate = CertificateFactory.getInstance(
                "X.509",
            ).generateCertificate(certificateBytes.inputStream())
            val algorithm = when (certificate.publicKey.algorithm) {
                "RSA" -> "SHA256withRSA"
                "EC" -> "SHA256withECDSA"
                else -> error("Unsupported RIN signing key")
            }
            require(
                Signature.getInstance(algorithm).run {
                    initVerify(certificate.publicKey)
                    update(manifestBytes)
                    verify(read("META-INF/rin.sig", 8192))
                },
            ) { "Invalid RIN signature" }
            val manifest = json.decodeFromString<RinManifest>(manifestBytes.decodeToString())
            require(
                (
                    manifest.schema == 1 &&
                        manifest.medium in setOf("anime", "manga") ||
                        manifest.schema == 2 &&
                        manifest.medium in setOf("addon", "guide", "tv")
                    ) &&
                    packagePattern.matches(manifest.packageName),
            )
            require(manifest.versionCode > 0 && manifest.versionName.length in 1..80 && manifest.name.length in 1..200)
            require(manifest.medium in RinManifest.supportedMedia && manifest.hostVersions.valid)
            require(manifest.medium !in setOf("addon", "guide", "tv") || manifest.entryPoints.size == 1)
            require(manifest.entryPoints.isNotEmpty() && manifest.entryPoints.size <= 32)
            require(manifest.entryPoints.all { packagePattern.matches(it) })
            require(manifest.validSourceIdentities())
            require("classes.dex" in manifest.hashes)
            require(
                entries.map { it.name }.toSet() ==
                    manifest.hashes.keys + setOf("manifest.json", "META-INF/rin.cert", "META-INF/rin.sig"),
            )
            manifest.hashes.forEach { (path, hash) ->
                require(hashPattern.matches(hash))
                require(
                    path.matches(Regex("classes([2-9][0-9]*)?\\.dex")) ||
                        path.startsWith("assets/") ||
                        manifest.medium in setOf("addon", "guide") &&
                        (path in setOf("resources.arsc", "AndroidManifest.xml") || path.startsWith("res/")),
                )
                require(sha256(read(path, MAX_ARCHIVE_BYTES)) == hash) { "Damaged RIN component" }
            }
            manifest
        }
    }
}
