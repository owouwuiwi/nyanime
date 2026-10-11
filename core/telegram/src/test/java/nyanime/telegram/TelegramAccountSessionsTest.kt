package nyanime.telegram

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class TelegramAccountSessionsTest {
    @Test fun `transport loss and ordinary engine shutdown do not mean logout`() {
        listOf(
            "disconnected",
            "connecting",
            "authorizationStateClosing",
            "authorizationStateClosed",
            "authorizationStateReady",
        )
            .forEach { assertFalse(TelegramAccountSessions.requiresLogin(it), it) }
    }

    @Test fun `human authentication challenges and logout invalidate evidence`() {
        listOf(
            "PhoneNumber",
            "Code",
            "Password",
            "EmailAddress",
            "EmailCode",
            "Registration",
            "OtherDeviceConfirmation",
        )
            .forEach { assertTrue(TelegramAccountSessions.requiresLogin("authorizationStateWait$it"), it) }
        assertTrue(TelegramAccountSessions.requiresLogin("authorizationStateLoggingOut"))
    }
}
