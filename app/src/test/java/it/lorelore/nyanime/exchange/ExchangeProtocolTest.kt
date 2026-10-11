package it.lorelore.nyanime.exchange

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test

class ExchangeProtocolTest {
    @Test
    fun linksRemainInertCapabilitiesUntilExplicitReceive() {
        val ticket = "fd2:synthetic-capability"
        assertEquals(ticket, ExchangeProtocol.ticket(ExchangeProtocol.link(ticket)))
        assertEquals(ticket, ExchangeProtocol.ticket("  $ticket  "))
        for (value in listOf(
            "https://example.test/",
            "nyanime://sources/wrong#$ticket",
            "nyanime://open/v1#$ticket",
            "nyanime://user@sources/v1#$ticket",
            "nyanime://sources/v1?extra=1#$ticket",
            "fd2:contains whitespace",
            "x".repeat(66001),
        )) {
            assertThrows(IllegalArgumentException::class.java) { ExchangeProtocol.ticket(value) }
        }
    }

    @Test
    fun fileNamesNeverChoosePathsAndOnlySupportedContainersAreOffered() {
        for (name in listOf(
            "fiction.nyastudio",
            "fiction.APK",
            "fiction.rin",
            "fiction.json",
            "fiction.nyasource",
        )) {
            assertEquals(name, ExchangeProtocol.filename(name))
        }
        for (name in listOf(
            "../fiction.apk",
            "dir\\fiction.apk",
            "fiction.exe",
            "fiction.apk\n",
            "a".repeat(257) + ".apk",
            "",
            "..",
        )) {
            assertThrows(IllegalArgumentException::class.java) { ExchangeProtocol.filename(name) }
        }
    }
}
