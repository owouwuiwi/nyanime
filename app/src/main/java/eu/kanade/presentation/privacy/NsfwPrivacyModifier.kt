package eu.kanade.presentation.privacy

import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.platform.LocalView
import eu.kanade.tachiyomi.ui.privacy.PrivacyArea
import eu.kanade.tachiyomi.ui.privacy.PrivacyContentIndex
import eu.kanade.tachiyomi.ui.privacy.PrivacyContentReference
import eu.kanade.tachiyomi.ui.privacy.PrivacyMedia
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get

fun Modifier.contentPrivacyRegion(area: PrivacyArea, data: Any?): Modifier =
    privacyRegion(area, reference = PrivacyContentReference.from(data))

fun Modifier.nsfwPrivacy(data: Any?, labels: List<String>? = null): Modifier =
    contentPrivacy(PrivacyContentReference.from(data)) { index, ratings ->
        index.isNsfw(data, ratings) || eu.kanade.tachiyomi.ui.privacy.NsfwContentPolicy.isNsfw(false, labels)
    }

fun Modifier.nsfwSourcePrivacy(media: PrivacyMedia, sourceIds: Set<Long>): Modifier =
    contentPrivacy(sourceIds.singleOrNull()?.let { PrivacyContentReference(media, it) }) { _, ratings ->
        sourceIds.any { it in if (media == PrivacyMedia.VIDEO) ratings.video else ratings.manga }
    }

private fun Modifier.contentPrivacy(
    reference: PrivacyContentReference?,
    classified: (PrivacyContentIndex, eu.kanade.tachiyomi.ui.privacy.PrivacySourceRatings) -> Boolean,
): Modifier = composed {
    val view = LocalView.current
    val controller = remember(view) { view.context.baseActivity()?.privacyDisplayController }
    if (controller == null) return@composed this
    val eligibleAreas by controller.eligibleAreas.collectAsState()
    if (PrivacyArea.NSFW !in eligibleAreas) return@composed this
    val index = remember { Injekt.get<PrivacyContentIndex>() }
    val ratings by index.ratings.collectAsState()
    this.privacyRegion(PrivacyArea.NSFW, enabled = classified(index, ratings), reference = reference)
}
