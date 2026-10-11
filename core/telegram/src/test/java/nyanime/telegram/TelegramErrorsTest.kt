package nyanime.telegram

import kotlinx.serialization.json.JsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Test

class TelegramErrorsTest {
    @Test fun permanentProtocolErrorsDoNotRetryForever() {
        assertFalse(TelegramErrors.retryable(TelegramException(TelegramFailure.REQUEST, code = 400)))
        assertFalse(TelegramErrors.retryable(TelegramException(TelegramFailure.REQUEST, code = 401)))
        assertFalse(TelegramErrors.retryable(TelegramException(TelegramFailure.REQUEST)))
        assertEquals(true, TelegramErrors.retryable(TelegramException(TelegramFailure.REQUEST, code = 503)))
        assertEquals(true, TelegramErrors.retryable(TelegramException(TelegramFailure.DISCONNECTED)))
    }
    private fun error(message: String, code: Int = 400) = TelegramErrors.parse(
        tdRequest(
            "error",
            "code" to JsonPrimitive(code),
            "message" to JsonPrimitive(message),
        ),
    )

    @Test fun deletedArchivesAreDistinctFromNetworkFailures() {
        listOf(
            "CHANNEL_PRIVATE",
            "CHANNEL_INVALID",
            "Chat not found",
            "Supergroup not found",
            "Can't access the chat",
        ).forEach {
            assertEquals(TelegramFailure.ARCHIVE_MISSING, error(it).failure)
            assertFalse(TelegramErrors.retryable(error(it)))
        }
        assertEquals(TelegramFailure.REQUEST, error("MESSAGE_ID_INVALID").failure)
        assertEquals(TelegramFailure.REQUEST, error("Network unavailable", 503).failure)
        assertEquals(TelegramFailure.REQUEST, error("Chat not found", 500).failure)
    }

    @Test fun rateLimitsAlwaysYieldABoundedNonZeroDelay() {
        assertEquals(900L, error("FLOOD_WAIT_900").retryAfterSeconds)
        assertEquals(60L, error("Too many requests", 429).retryAfterSeconds)
        assertEquals(86_400L, error("retry after 9999999").retryAfterSeconds)
    }

    @Test fun authenticationErrorsRemainSpecificWithoutLeakingServerText() {
        assertEquals(TelegramFailure.PHONE_INVALID, error("PHONE_NUMBER_INVALID").failure)
        assertEquals(TelegramFailure.CODE_EXPIRED, error("PHONE_CODE_EXPIRED").failure)
        assertEquals(TelegramFailure.PASSWORD_INVALID, error("PASSWORD_HASH_INVALID").failure)
        val unknown = error("Private diagnostic value")
        assertFalse(unknown.message.orEmpty().contains("Private diagnostic"))
    }

    @Test fun readyRequiresTheStableAccountIdentity() {
        assertFalse(TelegramState(authorization = "authorizationStateReady").ready)
    }

    @Test fun cachedAuthorizationDoesNotPretendToBeAnOnlineConnection() {
        val authorized = TelegramState(
            authorization = "authorizationStateReady",
            account = TelegramAccount(1, "Device owner", ""),
        )
        assertEquals(true, authorized.ready)
        listOf("", "connectionStateWaitingForNetwork", "connectionStateConnecting", "connectionStateUpdating").forEach {
            assertFalse(authorized.copy(connection = it).online)
        }
        assertEquals(true, authorized.copy(connection = "connectionStateReady").online)
        assertFalse(TelegramState(connection = "connectionStateReady").online)
    }

    @Test fun serverRejectionsAreDistinctFromAnUnconfiguredBuild() {
        assertEquals(TelegramFailure.API_REJECTED, error("API_ID_INVALID").failure)
        assertEquals(TelegramFailure.API_REJECTED, error("API_ID_PUBLISHED_FLOOD").failure)
        assertEquals(TelegramFailure.CLIENT_OUTDATED, error("UPDATE_APP_TO_LOGIN").failure)
        assertEquals(TelegramFailure.PHONE_REJECTED, error("PHONE_NUMBER_BANNED").failure)
        assertEquals(TelegramFailure.AUTH_UNAVAILABLE, error("SMS_CODE_CREATE_FAILED").failure)
    }

    @Test fun diagnosticsNeverEchoUnrecognizedServerData() {
        fun diagnostic(message: String) = TelegramErrors.diagnostic(
            tdRequest("error", "message" to JsonPrimitive(message)),
        )
        assertEquals("API_ID_INVALID", diagnostic("API_ID_INVALID"))
        assertEquals("UNCLASSIFIED", diagnostic("Private diagnostic value"))
        assertEquals("UNCLASSIFIED", diagnostic("PRIVATE_AUTHENTICATION_TOKEN_123456"))
        assertEquals("RATE_LIMIT", diagnostic("FLOOD_WAIT_900"))
    }

    @Test fun callFailuresKeepOnlyKnownProtocolReasons() {
        assertEquals("GROUPCALL_JOIN_MISSING", error("GROUPCALL_JOIN_MISSING").diagnostic)
        assertEquals("CALL_PARTICIPANT_MISSING", error("Can't find group call participant").diagnostic)
        assertEquals("CALL_UNMUTE_FORBIDDEN", error("Can't unmute user").diagnostic)
        assertEquals("UNCLASSIFIED", error("Can't find group call participant 123456").diagnostic)
        assertEquals("UNCLASSIFIED", error("Private SDP or invitation").diagnostic)
    }
}
