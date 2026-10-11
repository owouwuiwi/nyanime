package eu.kanade.tachiyomi.ui.privacy

import eu.kanade.domain.source.anime.interactor.GetAnimeIncognitoState
import eu.kanade.domain.source.manga.interactor.GetMangaIncognitoState
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class PrivacyIncognitoResolverTest {
    @Test fun identicalSourceIdsRemainSeparatedByMediaAndReuseExistingSemantics() = runTest {
        val anime = mockk<GetAnimeIncognitoState>()
        val manga = mockk<GetMangaIncognitoState>()
        val videoState = MutableStateFlow(true)
        val mangaState = MutableStateFlow(false)
        every { anime.await(41L) } returns true
        every { manga.await(41L) } returns false
        every { anime.subscribe(41L) } returns videoState
        every { manga.subscribe(41L) } returns mangaState
        val resolver = PrivacyIncognitoResolver(anime, manga)
        assertTrue(resolver.current(PrivacyContentReference(PrivacyMedia.VIDEO, 41L)))
        assertFalse(resolver.current(PrivacyContentReference(PrivacyMedia.MANGA, 41L)))
        assertTrue(resolver.subscribe(PrivacyMedia.VIDEO, 41L).first())
        assertFalse(resolver.subscribe(PrivacyMedia.MANGA, 41L).first())
        mangaState.value = true
        assertTrue(resolver.subscribe(PrivacyMedia.MANGA, 41L).first())
        verify(exactly = 1) { anime.await(41L) }
        verify(exactly = 1) { manga.await(41L) }
    }
}
