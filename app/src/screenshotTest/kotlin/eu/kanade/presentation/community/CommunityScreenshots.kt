package eu.kanade.presentation.community

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.android.tools.screenshot.PreviewTest
import eu.kanade.domain.ui.model.AppTheme
import eu.kanade.presentation.discovery.PreviewImages
import eu.kanade.presentation.theme.TachiyomiPreviewTheme
import eu.kanade.tachiyomi.data.community.CommunityInteractions
import eu.kanade.tachiyomi.data.community.CommunityItem
import eu.kanade.tachiyomi.data.community.CommunityProfile
import eu.kanade.tachiyomi.data.community.CommunityState
import eu.kanade.tachiyomi.data.community.PublicTitle
import eu.kanade.tachiyomi.data.community.ShelfStatus
import eu.kanade.tachiyomi.data.community.SocialActivity
import eu.kanade.tachiyomi.data.community.SocialPresence
import eu.kanade.tachiyomi.data.community.SocialWatchRequest
import eu.kanade.tachiyomi.ui.community.CommunityTitlePicker
import eu.kanade.tachiyomi.ui.community.FriendActivityCard
import eu.kanade.tachiyomi.ui.community.NyanimeSticker
import eu.kanade.tachiyomi.ui.community.ProfilePage
import eu.kanade.tachiyomi.ui.community.ProfileStudio
import eu.kanade.tachiyomi.ui.community.SocialWatchCard

private object PreviewActions : CommunityInteractions {
    override fun requestFriend(peer: String) = Unit
    override fun react(item: CommunityItem) = Unit
    override fun moderate(item: CommunityItem, pin: Boolean) = Unit
    override fun removeFriend(peer: String, block: Boolean) = Unit
}

@PreviewTest
@Preview(name = "CommunityProfile", widthDp = 393, heightDp = 852, locale = "it")
@Preview(name = "CommunityProfileLargeText", widthDp = 320, heightDp = 852, fontScale = 1.5f, locale = "it")
@Preview(name = "CommunityProfileTablet", widthDp = 800, heightDp = 1000, locale = "it")
@Composable
fun CommunityProfileScreenshot() {
    PreviewImages()
    val titles = listOf("Le stelle di domani", "L’ultimo viaggio", "Oltre la luna").mapIndexed { index, title ->
        PublicTitle(index.toString(), title, artwork = "preview://$index", status = ShelfStatus.Watching)
    }
    val profile = CommunityProfile(
        key = "a".repeat(64),
        name = "Viaggiatrice tra le stelle",
        bio = "Colleziono storie che restano. Un episodio, un capitolo e un posto sul divano per gli amici.",
        favorites = titles,
        shelves = titles,
        accent = 0xFFAA7DEB,
    )
    TachiyomiPreviewTheme(appTheme = AppTheme.NYANIME) {
        Surface(Modifier.fillMaxSize()) {
            ProfilePage(
                CommunityState(me = profile, profiles = mapOf(profile.key to profile)),
                profile,
                PreviewActions,
                {}, {}, {}, {}, {}, {},
            )
        }
    }
}

@PreviewTest
@Preview(name = "OriginalStickers", widthDp = 393, heightDp = 110)
@Composable
fun CommunityStickersScreenshot() {
    TachiyomiPreviewTheme(appTheme = AppTheme.NYANIME) {
        Surface {
            Row(horizontalArrangement = Arrangement.SpaceEvenly) {
                listOf("cat", "heart", "star", "popcorn").forEach { NyanimeSticker(it) }
            }
        }
    }
}

private val studioTitles = listOf(
    "Le stelle di domani",
    "L’ultimo viaggio",
    "Oltre la luna",
    "La città delle promesse",
    "Il giardino dei sogni",
    "Una stagione con te",
).mapIndexed {
        index,
        title,
    ->
    PublicTitle(
        index.toString(),
        title,
        manga = index > 2,
        artwork = "https://preview.example/poster-${index % 3}.jpg",
    )
}

@PreviewTest
@Preview(name = "TitlePicker", widthDp = 393, heightDp = 852, locale = "it")
@Preview(name = "TitlePickerLargeText", widthDp = 320, heightDp = 852, fontScale = 1.5f, locale = "it")
@Composable
fun CommunityTitlePickerScreenshot() {
    PreviewImages()
    TachiyomiPreviewTheme(appTheme = AppTheme.NYANIME) {
        Surface(Modifier.fillMaxSize()) {
            CommunityTitlePicker(studioTitles, setOf("0", "2"), 3, {}) {}
        }
    }
}

@PreviewTest
@Preview(name = "ProfileStudioTop3", widthDp = 393, heightDp = 852, locale = "it")
@Preview(name = "ProfileStudioTop3LargeText", widthDp = 320, heightDp = 852, fontScale = 1.5f, locale = "it")
@Composable
fun CommunityProfileStudioScreenshot() {
    PreviewImages()
    val profile = CommunityProfile("a".repeat(64), "Luna", favorites = studioTitles.take(3))
    TachiyomiPreviewTheme(appTheme = AppTheme.NYANIME) {
        Surface(Modifier.fillMaxSize()) {
            ProfileStudio(
                CommunityState(me = profile),
                profile,
                PreviewActions,
                initialSection = 1,
                change = {},
                publish = {},
                close = {},
                imageEditor = { _, _, _ -> },
            )
        }
    }
}

@PreviewTest
@Preview(name = "FriendsLive", widthDp = 393, heightDp = 900, locale = "it")
@Preview(name = "FriendsLiveLargeText", widthDp = 320, heightDp = 1250, fontScale = 1.5f, locale = "it")
@Composable
fun CommunityLiveScreenshot() {
    val profile = CommunityProfile("a".repeat(64), "Lorenzo", accent = 0xFFAA7DEB)
    val activity = SocialActivity("c".repeat(32), "Le stelle di domani", "Episodio 12 · Una promessa sotto la pioggia")
    val request = SocialWatchRequest(
        "d".repeat(32),
        profile.key,
        "b".repeat(64),
        activity,
        System.currentTimeMillis() + 180_000,
    )
    TachiyomiPreviewTheme(appTheme = AppTheme.NYANIME) {
        Surface(Modifier.fillMaxSize()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                FriendActivityCard(profile, SocialPresence("Sta guardando", request.expires, activity), false, {
                }, {}, {})
                SocialWatchCard(request, request.host, profile.name, {}, {})
            }
        }
    }
}
