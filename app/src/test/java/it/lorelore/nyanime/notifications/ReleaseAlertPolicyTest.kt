package it.lorelore.nyanime.notifications

import eu.kanade.tachiyomi.data.releases.FollowMode
import eu.kanade.tachiyomi.data.releases.ReleaseSubscription
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ReleaseAlertPolicyTest {
    @Test fun `automatic alerts keep their existing default`() {
        assertTrue(ReleaseAlertPolicy.allows(ReleaseSubscription(), false, false))
        assertTrue(ReleaseAlertPolicy.allows(ReleaseSubscription(), false, true))
    }

    @Test fun `explicit mode sends nothing merely because a title was started`() {
        assertFalse(ReleaseAlertPolicy.allows(ReleaseSubscription(), true, false))
        assertTrue(ReleaseAlertPolicy.allows(ReleaseSubscription(FollowMode.FOLLOW), true, false))
    }

    @Test fun `muting alerts does not change following or library membership`() {
        val followed = ReleaseSubscription(FollowMode.FOLLOW, availability = false, reminder = false)
        assertFalse(ReleaseAlertPolicy.allows(followed, false, false))
        assertFalse(ReleaseAlertPolicy.allows(followed, false, true))
        assertEquals(FollowMode.FOLLOW, followed.mode)
    }

    @Test fun `scheduled and available alerts can be controlled separately`() {
        val selected = ReleaseSubscription(availability = false, reminder = true)
        assertFalse(ReleaseAlertPolicy.allows(selected, false, false))
        assertTrue(ReleaseAlertPolicy.allows(selected, false, true))
    }

    @Test fun `automatic seasons inherit parent follow and mute decisions`() {
        val child = ReleaseSubscription()
        assertTrue(ReleaseAlertPolicy.allows(child, true, true, ReleaseSubscription(FollowMode.FOLLOW)))
        assertFalse(ReleaseAlertPolicy.allows(child, false, true, ReleaseSubscription(FollowMode.IGNORE)))
        assertFalse(
            ReleaseAlertPolicy.allows(child, true, true, ReleaseSubscription(FollowMode.FOLLOW, reminder = false)),
        )
    }

    @Test fun `explicit child choices override parent choices while ignore always wins`() {
        assertTrue(
            ReleaseAlertPolicy.allows(
                ReleaseSubscription(FollowMode.FOLLOW),
                true,
                true,
                ReleaseSubscription(FollowMode.IGNORE),
            ),
        )
        assertFalse(
            ReleaseAlertPolicy.allows(
                ReleaseSubscription(FollowMode.IGNORE),
                false,
                false,
                ReleaseSubscription(FollowMode.FOLLOW),
            ),
        )
    }
}
