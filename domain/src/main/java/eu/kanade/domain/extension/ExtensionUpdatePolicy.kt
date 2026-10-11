package eu.kanade.domain.extension

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** Optional APK declaration. It describes a distribution, never grants execution trust. */
@Serializable
data class ExtensionDistribution(
    val version: Int = 1,
    val id: String,
    val label: String,
    val updatePolicy: String = "manual",
    val repository: String? = null,
) {
    companion object {
        const val ASSET_PATH = "assets/nyanime/extension-v1.json"
        private val json = Json { ignoreUnknownKeys = true }
        fun parse(value: String): ExtensionDistribution? = runCatching {
            json.decodeFromString<ExtensionDistribution>(value).takeIf {
                it.version == 1 &&
                    it.id.matches(Regex("[a-z0-9][a-z0-9._-]{0,79}")) &&
                    it.label.isNotBlank() &&
                    it.label.length <= 100 &&
                    it.label.none(Char::isISOControl) &&
                    it.updatePolicy in setOf("manual", "repository") &&
                    (it.updatePolicy != "repository" || it.repository?.startsWith("https://") == true)
            }
        }.getOrNull()
    }
}

enum class ExtensionHomeSupport { UNKNOWN, NONE, READY, PARTIAL, INCOMPATIBLE }

/** Obtained from the selected APK, independent of source visibility and enabled languages. */
data class ExtensionPackageMetadata(
    val signers: Set<String> = emptySet(),
    val signerHistory: Set<String> = emptySet(),
    val distribution: ExtensionDistribution? = null,
    val home: ExtensionHomeSupport = ExtensionHomeSupport.UNKNOWN,
    val declaredHomes: Int = 0,
    val supportedHomes: Int = 0,
    val invalidDistribution: Boolean = false,
) {
    val hasHomeDeclaration: Boolean get() = home in setOf(
        ExtensionHomeSupport.READY,
        ExtensionHomeSupport.PARTIAL,
        ExtensionHomeSupport.INCOMPATIBLE,
    )
}

data class ExtensionUpdateCandidate(
    val packageName: String,
    val versionCode: Long,
    val repository: String,
    val signer: String?,
    val compatibleApi: Boolean,
    val distributionId: String? = null,
    val repositoryAliases: Set<String> = emptySet(),
)

enum class ExtensionUpdateStatus {
    CURRENT,
    AVAILABLE,
    MANUAL,
    PROTECTED,
    UNVERIFIED,
    DIFFERENT_DISTRIBUTION,
    AMBIGUOUS,
    REPOSITORY_UNAVAILABLE,
    NOT_IN_CATALOGUE,
}

data class ExtensionUpdateDecision(
    val status: ExtensionUpdateStatus,
    val candidate: ExtensionUpdateCandidate? = null,
)

data class ExtensionUpdateRepository(val name: String, val url: String)

/** UI, background checks and installers must resolve the same identity, not merely a package name. */
object ExtensionUpdatePolicy {
    fun resolve(
        packageName: String,
        versionCode: Long,
        metadata: ExtensionPackageMetadata,
        candidates: List<ExtensionUpdateCandidate>,
        keepVersion: Boolean = false,
        boundRepository: String? = null,
        unavailableRepositories: Set<String> = emptySet(),
    ): ExtensionUpdateDecision {
        fun status(value: ExtensionUpdateStatus) = ExtensionUpdateDecision(value)
        if (keepVersion) return status(ExtensionUpdateStatus.PROTECTED)
        val distribution = metadata.distribution
        if (distribution?.updatePolicy == "manual" && boundRepository == null) {
            return status(ExtensionUpdateStatus.MANUAL)
        }
        if (metadata.invalidDistribution) return status(ExtensionUpdateStatus.UNVERIFIED)
        if (distribution == null && metadata.hasHomeDeclaration && boundRepository == null) {
            return status(ExtensionUpdateStatus.MANUAL)
        }
        val repository = if (distribution?.updatePolicy == "manual") {
            boundRepository
        } else {
            distribution?.repository ?: boundRepository
        }
        val all = candidates.filter {
            it.packageName == packageName &&
                it.compatibleApi &&
                it.repository !in unavailableRepositories
        }
        val matching = all.filter {
            (repository == null || it.repository == repository || repository in it.repositoryAliases) &&
                it.signer?.lowercase() in metadata.signers.map(String::lowercase) &&
                (distribution == null || it.distributionId == distribution.id)
        }
        if (matching.isEmpty()) {
            return status(
                when {
                    repository in unavailableRepositories -> ExtensionUpdateStatus.REPOSITORY_UNAVAILABLE
                    all.isEmpty() && unavailableRepositories.isNotEmpty() -> {
                        ExtensionUpdateStatus.REPOSITORY_UNAVAILABLE
                    }
                    all.isEmpty() -> ExtensionUpdateStatus.NOT_IN_CATALOGUE
                    metadata.signers.isEmpty() ||
                        all.any {
                            it.signer.isNullOrBlank()
                        } -> ExtensionUpdateStatus.UNVERIFIED
                    else -> ExtensionUpdateStatus.DIFFERENT_DISTRIBUTION
                },
            )
        }
        if (matching.map { it.repository }.distinct().size > 1) return status(ExtensionUpdateStatus.AMBIGUOUS)
        val latest = matching.maxBy { it.versionCode }
        return ExtensionUpdateDecision(
            if (latest.versionCode > versionCode) ExtensionUpdateStatus.AVAILABLE else ExtensionUpdateStatus.CURRENT,
            latest,
        )
    }

    /** A manual APK may adopt updates only from the repository explicitly chosen by its user. */
    fun permitsManualTransition(
        old: ExtensionPackageMetadata,
        updated: ExtensionPackageMetadata,
        approvedRepository: String?,
    ): Boolean = approvedRepository?.startsWith("https://") == true &&
        old.distribution?.updatePolicy == "manual" &&
        updated.distribution?.updatePolicy == "repository" &&
        updated.distribution.repository == approvedRepository &&
        old.distribution.id == updated.distribution.id &&
        old.signers.isNotEmpty() &&
        old.signers == updated.signers &&
        (!old.hasHomeDeclaration || updated.hasHomeDeclaration)
}
