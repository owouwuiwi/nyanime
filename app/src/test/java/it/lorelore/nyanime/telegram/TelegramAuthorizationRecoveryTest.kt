package it.lorelore.nyanime.telegram

import nyanime.telegram.TelegramAccount
import nyanime.telegram.TelegramState
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class TelegramAuthorizationRecoveryTest {
    private val account = TelegramAccount(101, "Fictional account", "")
    private val ready = TelegramState(authorization = "authorizationStateReady", account = account)

    @Test fun `a refreshed Ready waits for getMe instead of recursively rereading authorization`() {
        assertTrue(TelegramAuthorizationRecovery.required(ready, null, false))
        val pending = TelegramAuthorizationRecovery.begin(ready)
        assertNull(pending.account)
        val authorized = pending.copy(authorization = "authorizationStateReady")
        assertFalse(authorized.ready)
        assertFalse(TelegramAuthorizationRecovery.required(authorized, null, false))
        val verified = authorized.copy(account = account)
        assertTrue(verified.ready)
        assertFalse(TelegramAuthorizationRecovery.required(verified, account.id, false))
    }

    @Test fun `transport recovery also discards stale identity without requesting a new login`() {
        val disconnected = ready.copy(authorization = "disconnected")
        assertTrue(TelegramAuthorizationRecovery.required(disconnected, account.id, false))
        val pending = TelegramAuthorizationRecovery.begin(disconnected)
        assertNull(pending.account)
        assertFalse(TelegramAuthorizationRecovery.required(pending, account.id, false))
    }

    @Test fun `logout cannot revalidate an identity it has just invalidated`() {
        assertFalse(TelegramAuthorizationRecovery.required(ready, null, true))
        assertTrue(TelegramAuthorizationRecovery.required(ready.copy(authorization = "disconnected"), null, true))
    }

    @Test fun `an OTP or password challenge is not restarted by ordinary requests`() {
        listOf("WaitPhoneNumber", "WaitCode", "WaitPassword", "WaitEmailAddress", "WaitEmailCode").forEach {
            assertFalse(
                TelegramAuthorizationRecovery.required(
                    TelegramState(authorization = "authorizationState$it"),
                    null,
                    false,
                ),
            )
        }
    }
}
