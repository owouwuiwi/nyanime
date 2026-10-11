package tachiyomi.domain.release.service

import tachiyomi.domain.release.interactor.GetApplicationRelease
import tachiyomi.domain.release.model.Release
import tachiyomi.domain.release.model.ReleaseHistoryEntry

interface ReleaseService {

    suspend fun latest(arguments: GetApplicationRelease.Arguments): Release?

    suspend fun history(arguments: GetApplicationRelease.Arguments, throughVersion: String): List<ReleaseHistoryEntry> =
        emptyList()
}
