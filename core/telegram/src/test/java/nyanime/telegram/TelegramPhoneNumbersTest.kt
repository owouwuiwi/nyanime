package nyanime.telegram

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test

class TelegramPhoneNumbersTest {
    @Test fun acceptsExplicitInternationalFormats() {
        assertEquals("+12025550123", TelegramPhoneNumbers.normalize(" +1 (202) 555-0123 "))
        assertEquals("+12025550123", TelegramPhoneNumbers.normalize("001 202 555 0123"))
    }

    @Test fun refusesCountryGuessingAndInvalidInput() {
        listOf("2025550123", "+012345678", "+1+2025550123", "+12345", "+1234567890123456", "number").forEach {
            val error = assertThrows(TelegramException::class.java) { TelegramPhoneNumbers.normalize(it) }
            assertEquals(TelegramFailure.PHONE_INVALID, error.failure)
        }
    }

    @Test fun usesTheSelectedCountryForNationalNumbers() {
        assertEquals("+393123456789", TelegramPhoneNumbers.normalize("312 345 6789", "IT"))
        assertEquals("+12025550123", TelegramPhoneNumbers.normalize("202 555 0123", "US"))
        assertEquals("+447400123456", TelegramPhoneNumbers.normalize("07400 123456", "GB"))
        assertEquals("+390212345678", TelegramPhoneNumbers.normalize("02 1234 5678", "IT"))
    }

    @Test fun internationalPastesOverrideTheSelectedCountryWithoutDoublingPrefixes() {
        assertEquals("+12025550123", TelegramPhoneNumbers.normalize("+1 (202) 555-0123", "IT"))
        assertEquals("+447400123456", TelegramPhoneNumbers.normalize("0044 7400 123456", "IT"))
        val paste = TelegramPhoneNumbers.splitInternational("+39 312 345 6789")!!
        assertEquals("IT", paste.region)
        assertEquals("+393123456789", TelegramPhoneNumbers.normalize(paste.nationalNumber, paste.region))
        val british = TelegramPhoneNumbers.splitInternational("0044 7400 123456")!!
        assertEquals("GB", british.region)
        assertEquals("+447400123456", TelegramPhoneNumbers.normalize(british.nationalNumber, british.region))
    }

    @Test fun acceptsPasteSpacingAndRejectsIncompleteOrMalformedNumbers() {
        assertEquals("+393123456789", TelegramPhoneNumbers.normalize("312\u00a0345\u202f6789", "IT"))
        listOf("", "312", "+39", "+0 3123456789", "+39+3123456789", "312 ext 1").forEach {
            assertThrows(TelegramException::class.java) { TelegramPhoneNumbers.normalize(it, "IT") }
            assertEquals(null, TelegramPhoneNumbers.splitInternational(it))
        }
        assertThrows(TelegramException::class.java) { TelegramPhoneNumbers.normalize("3123456789", "XX") }
    }

    @Test fun countryCatalogueAndExamplesAgree() {
        assertEquals(39, TelegramPhoneNumbers.countries.single { it.region == "IT" }.callingCode)
        assertEquals(44, TelegramPhoneNumbers.countries.single { it.region == "GB" }.callingCode)
        TelegramPhoneNumbers.countries.forEach {
            val example = TelegramPhoneNumbers.example(it.region)
            if (example.isNotEmpty()) normalizeExample(example, it)
        }
    }

    private fun normalizeExample(example: String, country: TelegramPhoneCountry) {
        val normalized = TelegramPhoneNumbers.normalize(example, country.region)
        assertEquals(true, normalized.startsWith("+${country.callingCode}"))
    }
}
