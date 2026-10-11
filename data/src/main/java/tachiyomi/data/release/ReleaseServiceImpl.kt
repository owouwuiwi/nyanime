package tachiyomi.data.release

import android.os.Build
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.network.HttpException
import eu.kanade.tachiyomi.network.NetworkHelper
import eu.kanade.tachiyomi.network.awaitSuccess
import eu.kanade.tachiyomi.network.parseAs
import kotlinx.serialization.json.Json
import tachiyomi.domain.release.interactor.GetApplicationRelease
import tachiyomi.domain.release.model.Release
import tachiyomi.domain.release.model.ReleaseHistoryEntry
import tachiyomi.domain.release.model.ReleaseVersion
import tachiyomi.domain.release.model.UpdateChannel
import tachiyomi.domain.release.service.ReleaseService

class ReleaseServiceImpl(
    private val networkService: NetworkHelper,
    private val json: Json,
) : ReleaseService {

    override suspend fun latest(arguments: GetApplicationRelease.Arguments): Release? {
        return ReleaseRepositoryFallback.latest(arguments.repository, arguments.fallbackRepositories) { repository ->
            latestFromRepository(arguments.copy(repository = repository))
        }
    }

    override suspend fun history(
        arguments: GetApplicationRelease.Arguments,
        throughVersion: String,
    ): List<ReleaseHistoryEntry> {
        if (ReleaseVersion.installed(arguments.versionName) == null) return emptyList()
        val candidates = mutableListOf<GithubRelease>()
        // Notes are optional enrichment, never a prerequisite to downloading the selected APK.
        var page = 1
        while (true) {
            val batch = with(json) {
                networkService.client.newCall(
                    GET("https://api.github.com/repos/${arguments.repository}/releases?per_page=100&page=$page"),
                ).awaitSuccess().parseAs<List<GithubRelease>>()
            }
            candidates.addAll(batch)
            // GitHub sorts by publication date: an old release can be republished ahead of newer versions.
            if (batch.size < 100) break
            page++
        }
        return ApplicationReleaseHistory.select(
            candidates,
            arguments.versionName,
            throughVersion,
            arguments.channel,
            Build.SUPPORTED_ABIS.toList(),
            arguments.assetPrefix,
        )
    }

    private suspend fun latestFromRepository(arguments: GetApplicationRelease.Arguments): Release? {
        val candidates = mutableListOf<GithubRelease>()
        if (arguments.channel == UpdateChannel.RECOMMENDED) {
            // GitHub already excludes previews here, even after thousands of preview releases.
            try {
                val recommended = with(json) {
                    networkService.client.newCall(
                        GET("https://api.github.com/repos/${arguments.repository}/releases/latest"),
                    )
                        .awaitSuccess().parseAs<GithubRelease>()
                }
                candidates.add(recommended)
            } catch (e: HttpException) {
                if (e.code != 404) throw e
            }
        }
        // Preview publications can be frequent: paginate until a compatible recommended
        // release is found rather than losing it behind the first page of previews.
        for (page in 1..5) {
            if (ApplicationReleasePolicy.select(
                    candidates,
                    arguments.channel,
                    Build.SUPPORTED_ABIS.toList(),
                    arguments.assetPrefix,
                ) !=
                null
            ) {
                break
            }
            val batch = with(json) {
                networkService.client
                    .newCall(
                        GET("https://api.github.com/repos/${arguments.repository}/releases?per_page=100&page=$page"),
                    )
                    .awaitSuccess().parseAs<List<GithubRelease>>()
            }
            candidates.addAll(batch)
            if (ApplicationReleasePolicy.select(
                    candidates,
                    arguments.channel,
                    Build.SUPPORTED_ABIS.toList(),
                    arguments.assetPrefix,
                ) !=
                null ||
                batch.size < 100
            ) {
                break
            }
        }
        val release = ApplicationReleasePolicy.select(
            candidates,
            arguments.channel,
            Build.SUPPORTED_ABIS.toList(),
            arguments.assetPrefix,
        ) ?: return null

        val downloadLink =
            ApplicationReleaseAssets.select(release.assets, Build.SUPPORTED_ABIS.toList(), arguments.assetPrefix)
                ?: return null

        return Release(
            version = release.version.removePrefix("v"),
            info = release.info.replace(gitHubUsernameMentionRegex) { mention ->
                "[${mention.value}](https://github.com/${mention.value.substring(1)})"
            },
            releaseLink = release.releaseLink,
            downloadLink = downloadLink,
        )
    }

    companion object {
        /**
         * Regular expression that matches a mention to a valid GitHub username, like it's
         * done in GitHub Flavored Markdown. It follows these constraints:
         *
         * - Alphanumeric with single hyphens (no consecutive hyphens)
         * - Cannot begin or end with a hyphen
         * - Max length of 39 characters
         *
         * Reference: https://stackoverflow.com/a/30281147
         */
        private val gitHubUsernameMentionRegex = """\B@([a-z0-9](?:-(?=[a-z0-9])|[a-z0-9]){0,38}(?<=[a-z0-9]))"""
            .toRegex(RegexOption.IGNORE_CASE)
    }
}
